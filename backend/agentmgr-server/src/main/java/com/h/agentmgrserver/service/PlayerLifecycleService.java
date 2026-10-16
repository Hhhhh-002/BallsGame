package com.h.agentmgrserver.service;

import com.google.protobuf.InvalidProtocolBufferException;
import com.h.agentmgrserver.config.AgentMgrProperties;
import com.h.agentmgrserver.discovery.AgentInstanceSelector;
import com.h.gamecommon.exception.AgentMgrException;
import com.h.agentmgrserver.model.IdempotencyRecord;
import com.h.agentmgrserver.model.ManagedPlayer;
import com.h.agentmgrserver.rpc.feign.InstanceRpcClient;
import com.h.agentmgrserver.store.PlayerOnlineStore;
import com.h.gamecommon.Excutor.GameExecutorFactory;
import com.h.gamecommon.code.GameError;
import com.h.gamecommon.code.LoginStatus;
import com.h.gamecommon.services.ServiceNames;
import com.h.gamecommon.command.ServiceCommands;
import com.h.gameprotocol.proto.*;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * 参考 MessageContext 的“处理中/已有结果”机制：去重状态和结果存 Redis，Future 只放本进程。
 * 没有阶段日志、启动恢复或后台扫描；同操作请求，用原目标重新调用幂等的 Agent/Gateway 接口。
 * 用户状态锁只保护短读写；工作线程的执行锁避免两个进程并行处理同用户的远程流程。
 */
//AgentMgr 的核心职责：分配 Agent 实例、创建 PlayerActor、顶号踢旧连接、销毁 Actor、状态流转、幂等控制、分布式锁调度。
@Service
@Slf4j
public class PlayerLifecycleService {
    private final PlayerOnlineStore store;
    private final RedissonClient redission;
    private final AgentInstanceSelector selector;
    private final InstanceRpcClient rpc;
    private final AgentMgrProperties properties;
    private final ThreadPoolExecutor executor;
    /** 只合并本机正在处理同一请求的等待者，Future 不放 Redis。 */
    private final ConcurrentMap<String, CompletableFuture<byte[]>> processing = new ConcurrentHashMap<>();

    public PlayerLifecycleService(PlayerOnlineStore store, RedissonClient redission, AgentInstanceSelector selector,
                                  InstanceRpcClient rpc, AgentMgrProperties properties) {
        this.store = store;
        this.redission = redission;
        this.selector = selector;
        this.rpc = rpc;
        this.properties = properties;
        if (properties.getLockWaitMs() < 0 || properties.getOperationTimeoutMs() <= 0
                || properties.getResultWaitMs() <= 0 || properties.getIdempotencyResultTtlMs() <= 0
                || properties.getSameHostMultiplier() < 1 || !Double.isFinite(properties.getSameHostMultiplier())
                || properties.getRpcMaxAttempts() < 1 || properties.getRpcConnectTimeoutMs() <= 0
                || properties.getRpcReadTimeoutMs() <= 0 || properties.getInitialBackoffMs() <= 0
                || properties.getMaxBackoffMs() < properties.getInitialBackoffMs()) {
            throw new IllegalArgumentException("AgentMgr 超时、重试或结果缓存参数不合法");
        }
        executor = GameExecutorFactory.newFixedThreadPool("agentmgr-lifecycle",
                properties.getWorkerThreads(), properties.getWorkerQueue());
    }

    /** 同一登录 ID 查询 Redis 原结果，或等待本地同一任务；不同 ID 才是新的登录。 */
    public LoginBinding beginLogin(BeginLoginRequest request) {
        validateLogin(request);
        IdempotencyRecord record = locked(request.getUserId(), () -> prepareLogin(request));
        byte[] result = record.getStatus() == IdempotencyRecord.Status.DONE ? resultBody(record)
                : await(launch(record), Math.max(1, Math.min(properties.getResultWaitMs(),
                        request.getLoginDeadlineMs() - System.currentTimeMillis())));
        // HTTP 等待期间可能发生另一次顶号，旧成功缓存不能再让网关绑定已经失效的 Actor。
        locked(request.getUserId(), () -> {
            requireCurrentLogin(request.getUserId(), request.getLoginOperationId());
            return null;
        });
        try { return LoginBinding.parseFrom(result); }
        catch (InvalidProtocolBufferException failure) {
            throw new IllegalStateException("Redis 登录结果格式错误", failure);
        }
    }

    /** 持用户状态锁调用；先读去重记录，不能先按在线状态把同操作重试误判成另一轮登录。 */
    private IdempotencyRecord prepareLogin(BeginLoginRequest request) {
        String userId = request.getUserId();
        String id = request.getLoginOperationId();
        IdempotencyRecord previous = store.record(userId, IdempotencyRecord.Type.LOGIN, id);
        if (previous != null) {
            if (!request.getGatewayInstanceId().equals(previous.getGatewayInstanceId())) {
                throw new AgentMgrException(GameError.OPERATION_CONFLICT);
            }
            if (previous.getStatus() == IdempotencyRecord.Status.DONE) requireCurrentLogin(userId, id);
            else requireOwner(store.load(userId),previous);
            return previous;
        }
        ManagedPlayer old = store.load(userId);
        if (old != null && old.status() != LoginStatus.PLAYING) {
            // 旧版阶段日志不再读取；无法找到新去重记录时保留在线占位，不擅自创建第二个 Actor。
            throw new AgentMgrException(old.status() == LoginStatus.LOGINING
                    ? GameError.LOGIN_IN_PROGRESS : GameError.LOGOUT_IN_PROGRESS);
        }
        if (old != null && id.equals(old.loginOperationId())) {
            // 完成结果已过缓存窗口，不能把同一个登录 ID 当作新登录来顶掉自身。
            throw new AgentMgrException(GameError.OPERATION_CONFLICT);
        }
        long now = System.currentTimeMillis();
        if (request.getLoginDeadlineMs() <= now || request.getLoginDeadlineMs() > now + 900_000) {
            throw new AgentMgrException(GameError.SERVICE_UNAVAILABLE, true);
        }
        IdempotencyRecord record = new IdempotencyRecord();
        record.setId(id);
        record.setUserId(userId);
        record.setType(IdempotencyRecord.Type.LOGIN);
        record.setGatewayInstanceId(request.getGatewayInstanceId());
        // 在任何创建 RPC 前把实例和 Actor 固定下来；SET NX 后重试永远复用这两个标识。
        record.setAgentHostInstanceId(selector.id(selector.select(request.getGatewayInstanceId())));
        record.setPlayerActorId("player-" + UUID.randomUUID());
        record.setDeadline(Math.min(request.getLoginDeadlineMs(), now + properties.getOperationTimeoutMs()));
        if (old != null) {
            record.setOldGatewayInstanceId(old.gatewayInstanceId());
            record.setOldAgentHostInstanceId(old.agentHostInstanceId());
            record.setOldPlayerActorId(old.playerActorId());
        }
        if (!store.claim(record)) throw new AgentMgrException(GameError.SERVICE_UNAVAILABLE, true);
        reserveLogin(record);
        return record;
    }

    /**
     * SET NX 和内存 Context 登记 之间如果应用退出，重试可根据固定目标重新建立占位。
     * 已有别的操作/Actor 时拒绝覆盖；不因 PROCESSING 时间长就删除记录或重选目标。
     */
    private void reserveLogin(IdempotencyRecord record) {
        ManagedPlayer current = store.load(record.getUserId());
        if (current != null && record.getId().equals(current.pendingOperationId())) {
            boolean matching = current.playerActorId().equals(record.getPlayerActorId())
                    || current.playerActorId().equals(record.getOldPlayerActorId());
            if (!matching) throw new AgentMgrException(GameError.OPERATION_CONFLICT);
            return;
        }
        if (current == null) {
            store.save(creating(record));
        } else if (current.status() == LoginStatus.PLAYING
                && !record.getOldPlayerActorId().isBlank()
                && current.playerActorId().equals(record.getOldPlayerActorId())) {
            store.save(new ManagedPlayer(record.getUserId(), LoginStatus.LOGOUTING,
                    current.gatewayInstanceId(), current.agentHostInstanceId(), current.playerActorId(),
                    current.loginOperationId(), record.getId(), System.currentTimeMillis(), record.getDeadline()));
        } else {
            throw new AgentMgrException(GameError.OPERATION_CONFLICT);
        }
    }

    private record DisconnectPlan(IdempotencyRecord record, PlayerDisconnectedResult.Outcome immediate,
                                  boolean joiningLogin, boolean joiningNewLogin) { }

    /** 断线用本轮 Actor ID 去重；旧连接不会清理新 Actor，顶号导致的断线等待原登录清理任务。 */
    public PlayerDisconnectedResult disconnect(PlayerDisconnectedRequest request) {
        String userId = request.getUserId();
        String actor = request.getPlayerActorId();
        if (userId.isBlank() || userId.length() > 64 || actor.isBlank() || actor.length() > 256) {
            throw new AgentMgrException(GameError.INVALID_MESSAGE);
        }
        DisconnectPlan plan = locked(userId, () -> prepareDisconnect(userId, actor));
        if (plan.immediate() != null) return disconnected(plan.immediate());
        IdempotencyRecord record = plan.record();
        byte[] result = record.getStatus() == IdempotencyRecord.Status.DONE ? resultBody(record)
                : await(launch(record), properties.getResultWaitMs());
        if (plan.joiningNewLogin()) {
            // 断线发生在 CREATE/Redis 登录结果提交之间：先等或恢复原登录任务，随后按同一 Actor 离场并停止。
            // 不提前删除 LOGINING，也不把创建成功当成下线成功；递归会重新核对当前路由，保护新登录。
            return disconnect(request);
        }
        if (plan.joiningLogin()) {
            // 顶号的原流程成功，表示旧 Actor 已离场并停止；不能把 LoginBinding 解析成断线结果。
            return disconnected(PlayerDisconnectedResult.Outcome.ALREADY_FINISHED);
        }
        try { return PlayerDisconnectedResult.parseFrom(result); }
        catch (InvalidProtocolBufferException failure) {
            throw new IllegalStateException("Redis 下线结果格式错误", failure);
        }
    }

    private DisconnectPlan prepareDisconnect(String userId, String actor) {
        IdempotencyRecord previous = store.record(userId, IdempotencyRecord.Type.DISCONNECT, actor);
        if (previous != null && previous.getStatus() == IdempotencyRecord.Status.DONE) {
            return new DisconnectPlan(previous, null, false, false);
        }
        ManagedPlayer current = store.load(userId);
        if (previous != null) {
            if (current != null && !actor.equals(current.playerActorId())) {
                return new DisconnectPlan(null, PlayerDisconnectedResult.Outcome.STALE_IGNORED, false, false);
            }
            // 原请求重试必须仍有本机 Context；缺失时不恢复身份或补建 Actor。
            if(current==null)return new DisconnectPlan(null,PlayerDisconnectedResult.Outcome.STALE_IGNORED,false,false);
            requireOwner(current,previous);
            return new DisconnectPlan(previous, null, false, false);
        }
        if (current == null || !actor.equals(current.playerActorId())) {
            // 找不到 Actor 不是“保存成功”的证据，不伪造 ALREADY_FINISHED。
            return new DisconnectPlan(null, PlayerDisconnectedResult.Outcome.STALE_IGNORED, false, false);
        }
        if (!current.pendingOperationId().isBlank()) {
            IdempotencyRecord login = store.record(userId, IdempotencyRecord.Type.LOGIN, current.pendingOperationId());
            if (login == null || !actor.equals(login.getOldPlayerActorId()) && !actor.equals(login.getPlayerActorId())) {
                throw new AgentMgrException(GameError.SERVICE_UNAVAILABLE, true);
            }
            boolean isNew = actor.equals(login.getPlayerActorId());
            return new DisconnectPlan(login, null, !isNew, isNew);
        }
        if (current.status() != LoginStatus.PLAYING) throw new AgentMgrException(GameError.LOGOUT_IN_PROGRESS);
        IdempotencyRecord record = new IdempotencyRecord();
        record.setId(actor);
        record.setUserId(userId);
        record.setType(IdempotencyRecord.Type.DISCONNECT);
        record.setOldGatewayInstanceId(current.gatewayInstanceId());
        record.setOldAgentHostInstanceId(current.agentHostInstanceId());
        record.setOldPlayerActorId(actor);
        record.setDeadline(System.currentTimeMillis() + properties.getOperationTimeoutMs());
        if (!store.claim(record)) throw new AgentMgrException(GameError.SERVICE_UNAVAILABLE, true);
        store.save(leaving(record));
        return new DisconnectPlan(record, null, false, false);
    }

    /** putIfAbsent 原子合并本机任务；Redis DONE 在工作线程里再次检查，覆盖检查/登记之间的竞态。 */
    private CompletableFuture<byte[]> launch(IdempotencyRecord record) {
        String key = store.taskKey(record);
        CompletableFuture<byte[]> fresh = new CompletableFuture<>();
        CompletableFuture<byte[]> existing = processing.putIfAbsent(key, fresh);
        if (existing != null) return existing;
        try {
            executor.execute(() -> {
                try {
                    byte[] body = withLock(store.executionLockName(record.getUserId()), () -> {
                        IdempotencyRecord current = store.record(record.getUserId(), record.getType(), record.getId());
                        if (current == null) throw new AgentMgrException(GameError.OPERATION_CONFLICT);
                        if (current.getStatus() == IdempotencyRecord.Status.DONE) return resultBody(current);
                        return current.getType() == IdempotencyRecord.Type.LOGIN ? runLogin(current) : runDisconnect(current);
                    });
                    // 业务方法已经确认 Redis 提交；此处只唤醒当前进程的等待者。
                    fresh.complete(body);
                } catch (Exception failure) {
                    // 超时可能发生在远端完成以后；保留 PROCESSING 和原目标，不能重新分配。
                    log.warn("操作结果未确认，保留原目标供同请求重试, userId={}, operationId={}, failureType={}",
                            record.getUserId(), record.getId(), failure.getClass().getSimpleName());
                    fresh.completeExceptionally(failure);
                } finally {
                    processing.remove(key, fresh);
                }
            });
        } catch (RuntimeException rejected) {
            processing.remove(key, fresh);
            fresh.completeExceptionally(new AgentMgrException(GameError.SERVICE_UNAVAILABLE, true));
        }
        return fresh;
    }

    /**
     * 不保存每一步日志。普通 HTTP 超时重试仍向原目标重发幂等控制调用，不做重启恢复。
     * Gateway/Agent 必须识别固定 Actor ID 的重复请求并返回原结果，不能重新产生副作用。
     */
    private byte[] runLogin(IdempotencyRecord record) {
        if (!record.getOldPlayerActorId().isBlank()) {
            checkExecution(record);
            // 顶号先关闭旧客户端，避免它继续向旧 Actor 输入；等待最终关闭结果而非“已受理”。
            ServiceResponse closed = rpc.call(ServiceNames.GATEWAY, record.getOldGatewayInstanceId(),
                    ServiceCommands.KICK_OLD_CLIENT, KickOldClientRequest.newBuilder().setUserId(record.getUserId())
                            .setPlayerActorId(record.getOldPlayerActorId()).setReason("账号在其他连接登录").build());
            verifyGatewayClosed(closed);
            stopAgent(record);
            locked(record.getUserId(), () -> {
                // 旧 Actor 已确认退出，当前在线状态改为登录中；这不是历史阶段日志。
                if (!redission.getLock(store.executionLockName(record.getUserId())).isHeldByCurrentThread()) {
                    throw new AgentMgrException(GameError.SERVICE_UNAVAILABLE, true);
                }
                requireOwner(store.load(record.getUserId()), record);
                store.save(creating(record));
                return null;
            });
        }
        checkExecution(record);
        ServiceResponse created = rpc.call(ServiceNames.AGENT, record.getAgentHostInstanceId(),
                ServiceCommands.CREATE_PLAYER_AGENT, CreatePlayerAgentRequest.newBuilder().setUserId(record.getUserId())
                        .setGatewayInstanceId(record.getGatewayInstanceId()).setPlayerActorId(record.getPlayerActorId())
                        .setLoginOperationId(record.getId()).build());
        LoginBinding binding = verifyCreated(record, created);
        ManagedPlayer online = new ManagedPlayer(record.getUserId(), LoginStatus.PLAYING,
                record.getGatewayInstanceId(), record.getAgentHostInstanceId(), record.getPlayerActorId(),
                record.getId(), "", System.currentTimeMillis(), 0);
        return finish(record, online, binding.toByteArray());
    }

    /** 客户端 Channel 已断，最终结果通过当前 Feign 响应返回 Gateway，不再向断开的 Channel 推送。 */
    private byte[] runDisconnect(IdempotencyRecord record) {
        stopAgent(record);
        return finish(record, null, disconnected(PlayerDisconnectedResult.Outcome.SAVED_AND_OFFLINE).toByteArray());
    }

    private void stopAgent(IdempotencyRecord record) {
        checkExecution(record);
        ServiceResponse response = rpc.call(ServiceNames.AGENT, record.getOldAgentHostInstanceId(),
                ServiceCommands.KICK_OLD_AGENT, KickOldAgentRequest.newBuilder().setUserId(record.getUserId())
                        .setPlayerActorId(record.getOldPlayerActorId()).build());
        try {
            KickOldAgentResult result = KickOldAgentResult.parseFrom(response.getBody());
            if (!record.getUserId().equals(result.getUserId()) || !record.getOldPlayerActorId().equals(result.getPlayerActorId())
                    || result.getOutcome() != KickOldAgentResult.Outcome.SAVED_AND_STOPPED
                    && result.getOutcome() != KickOldAgentResult.Outcome.ALREADY_STOPPED) {
                throw new IllegalStateException("Agent 未确认离场并停止原 Actor");
            }
        } catch (InvalidProtocolBufferException failure) {
            throw new IllegalStateException("Agent 下线结果格式错误", failure);
        }
    }

    /** 每次 RPC 前和最终提交前核对执行锁与在线占位，过期工作线程不能覆盖新操作。 */
    private void checkExecution(IdempotencyRecord record) {
        if (Thread.currentThread().isInterrupted()
                || !redission.getLock(store.executionLockName(record.getUserId())).isHeldByCurrentThread()) {
            throw new AgentMgrException(GameError.SERVICE_UNAVAILABLE, true);
        }
        locked(record.getUserId(), () -> {
            requireOwner(store.load(record.getUserId()), record);
            return null;
        });
    }

    private byte[] finish(IdempotencyRecord record, ManagedPlayer player, byte[] body) {
        return locked(record.getUserId(), () -> {
            if (!redission.getLock(store.executionLockName(record.getUserId())).isHeldByCurrentThread()) {
                throw new AgentMgrException(GameError.SERVICE_UNAVAILABLE, true);
            }
            IdempotencyRecord current = store.record(record.getUserId(), record.getType(), record.getId());
            if (current == null) throw new AgentMgrException(GameError.OPERATION_CONFLICT);
            if (current.getStatus() == IdempotencyRecord.Status.DONE) return resultBody(current);
            requireOwner(store.load(record.getUserId()), record);
            store.complete(current, player, body);
            return body;
        });
    }

    private ManagedPlayer creating(IdempotencyRecord record) {
        return new ManagedPlayer(record.getUserId(), LoginStatus.LOGINING, record.getGatewayInstanceId(),
                record.getAgentHostInstanceId(), record.getPlayerActorId(), record.getId(), record.getId(),
                System.currentTimeMillis(), record.getDeadline());
    }

    private ManagedPlayer leaving(IdempotencyRecord record) {
        return new ManagedPlayer(record.getUserId(), LoginStatus.LOGOUTING, record.getOldGatewayInstanceId(),
                record.getOldAgentHostInstanceId(), record.getOldPlayerActorId(), "", record.getId(),
                System.currentTimeMillis(), record.getDeadline());
    }

    private void requireOwner(ManagedPlayer player, IdempotencyRecord record) {
        if (player == null || !record.getId().equals(player.pendingOperationId())
                || !player.playerActorId().equals(record.getPlayerActorId())
                && !player.playerActorId().equals(record.getOldPlayerActorId())) {
            throw new AgentMgrException(GameError.OPERATION_CONFLICT);
        }
    }

    private void requireCurrentLogin(String userId, String operationId) {
        ManagedPlayer player = store.load(userId);
        if (player == null || player.status() != LoginStatus.PLAYING || !operationId.equals(player.loginOperationId())) {
            throw new AgentMgrException(GameError.LOGIN_OPERATION_SUPERSEDED);
        }
    }

    private void verifyGatewayClosed(ServiceResponse response) {
        try {
            var result = KickOldClientResult.parseFrom(response.getBody());
            if (result.getOutcome() != KickOldClientResult.Outcome.CONNECTION_CLOSED
                    && result.getOutcome() != KickOldClientResult.Outcome.ALREADY_OFFLINE) {
                throw new AgentMgrException(GameError.OPERATION_CONFLICT);
            }
        } catch (InvalidProtocolBufferException failure) {
            throw new IllegalStateException("Gateway 踢人结果格式错误", failure);
        }
    }

    private LoginBinding verifyCreated(IdempotencyRecord record, ServiceResponse response) {
        try {
            CreatePlayerAgentResult result = CreatePlayerAgentResult.parseFrom(response.getBody());
            if (!record.getPlayerActorId().equals(result.getPlayerActorId())
                    || !record.getAgentHostInstanceId().equals(result.getAgentHostInstanceId())
                    || !ServiceNames.AGENT.equals(result.getAgentHostServiceName()) || !result.hasPlayer()) {
                throw new IllegalStateException("Agent 创建结果与原请求的 Actor 或实例不匹配");
            }
            return LoginBinding.newBuilder().setUserId(record.getUserId()).setLoginOperationId(record.getId())
                    .setPlayerActorId(result.getPlayerActorId()).setAgentHostInstanceId(result.getAgentHostInstanceId())
                    .setAgentHostServiceName(ServiceNames.AGENT).setPlayer(result.getPlayer()).build();
        } catch (InvalidProtocolBufferException failure) {
            throw new IllegalStateException("Agent 创建结果格式错误", failure);
        }
    }

    /** HTTP 等待超时不取消共享 Future，也不删除 Redis 中尚未确认的操作。 */
    private byte[] await(CompletableFuture<byte[]> future, long waitMs) {
        try { return future.get(waitMs, TimeUnit.MILLISECONDS); }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AgentMgrException(GameError.SERVICE_UNAVAILABLE, true);
        } catch (TimeoutException failure) {
            throw new AgentMgrException(GameError.SERVICE_UNAVAILABLE, true);
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("业务任务执行失败", failure.getCause());
        }
    }

    private <T> T locked(String userId, Supplier<T> work) { return withLock(store.lockName(userId), work); }

    /** 两种锁均使用 watchdog；执行锁在工作线程持有，HTTP 线程不等待它执行 RPC。 */
    private <T> T withLock(String name, Supplier<T> work) {
        RLock lock = redission.getLock(name);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(properties.getLockWaitMs(), TimeUnit.MILLISECONDS);
            if (!acquired) throw new AgentMgrException(GameError.SERVICE_UNAVAILABLE, true);
            return work.get();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AgentMgrException(GameError.SERVICE_UNAVAILABLE, true);
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) lock.unlock();
        }
    }

    private void validateLogin(BeginLoginRequest request) {
        if (request.getUserId().isBlank() || request.getUserId().length() > 64
                || request.getGatewayInstanceId().isBlank() || request.getGatewayInstanceId().length() > 256) {
            throw new AgentMgrException(GameError.INVALID_MESSAGE);
        }
        try {
            String id=request.getLoginOperationId();
            // 新链路直接传客户端 requestId 的十进制字符串，Redis 已按用户隔离。
            try {
                long value=Long.parseUnsignedLong(id);
                if(value==0 || !Long.toUnsignedString(value).equals(id))throw new IllegalArgumentException();
            } catch(NumberFormatException legacy) {
                // 仅兼容升级前已在途的 UUID；新 Gateway 不再生成它。
                if(!UUID.fromString(id).toString().equals(id))throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException failure) {
            throw new AgentMgrException(GameError.INVALID_MESSAGE);
        }
    }

    private byte[] resultBody(IdempotencyRecord record) { return Base64.getDecoder().decode(record.getResultBody()); }
    private PlayerDisconnectedResult disconnected(PlayerDisconnectedResult.Outcome outcome) {
        return PlayerDisconnectedResult.newBuilder().setOutcome(outcome).build();
    }

    /** 停机只停止工作线程；不登记启动恢复任务。 */
    @PreDestroy
    public void shutdown() { executor.shutdownNow(); }
}
