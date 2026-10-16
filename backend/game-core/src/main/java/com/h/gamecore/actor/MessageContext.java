package com.h.gamecore.actor;

import com.h.gamecommon.code.GameError;
import com.h.gameprotocol.proto.ClientEnvelope;
import com.h.gameprotocol.proto.ServerEnvelope;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static com.h.gamecore.protocol.Messages.require;

/** 一份玩家消息上下文；只能在所属 Actor 的邮箱中访问，不保存 Channel 或 ThreadLocal。 */
public final class MessageContext {
    public record Pending(ClientEnvelope request, CompletableFuture<ServerEnvelope> result, Object attachment){}
    private record Completed(ClientEnvelope request, ServerEnvelope response, boolean retryable, ServerEnvelope uncommitted, Object attachment) {}
    public record Admission(Pending pending, boolean owner, boolean completed, ServerEnvelope uncommitted) {}

    // key 就是客户端 requestId。cmd/body 放在值中核对，不另造 RequestKey。
    private final Map<Long, Pending> processing = new HashMap<>();
    private final LinkedHashMap<Long, Completed> responses = new LinkedHashMap<>();
    private final int capacity;
    private long retiredThrough;
    private boolean retired;

    public MessageContext(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("响应窗口必须为正数");
        this.capacity = capacity;
    }

    /** replay 可来自 Redis；此时允许查询已移出本地窗口的请求。 */
    public Admission begin(ClientEnvelope request, boolean durable) {
        return begin(request,durable,null);
    }

    /** attachment 仅携带本地路由快照，不是 key；重试必须仍核对原目标。 */
    public Admission begin(ClientEnvelope request, boolean durable, Object attachment) {
        long requestId = request.getRequestId();
        require(requestId != 0, GameError.INVALID_INPUT);
        Pending pending = processing.get(requestId);
        if (pending != null) {
            require(pending.request().equals(request), GameError.OPERATION_CONFLICT);
            return new Admission(pending, false, false, null);
        }
        Completed completed = responses.get(requestId);
        if (completed != null) {
            require(completed.request().equals(request), GameError.OPERATION_CONFLICT);
            if (!completed.retryable()) return new Admission(new Pending(request,
                    CompletableFuture.completedFuture(completed.response()),completed.attachment()), false, true, null);
            responses.remove(requestId); // 暂时失败允许原请求恢复；不能换参数。
        } else if (!durable) {
            require(!retired || Long.compareUnsigned(requestId, retiredThrough) > 0, GameError.REQUEST_EXPIRED);
        }
        require(processing.size() < capacity, GameError.SERVICE_UNAVAILABLE);
        pending = new Pending(request, new CompletableFuture<>(),completed==null?attachment:completed.attachment());
        processing.put(requestId, pending);
        return new Admission(pending, true, false, completed == null ? null : completed.uncommitted());
    }

    public void complete(Pending pending, ServerEnvelope response, boolean retryable) {
        complete(pending, response, retryable, null);
    }

    /** 业务已得到最终响应、只是 Redis 提交失败：原响应保留，重试只补提交，不再跑 Handler。 */
    public void writeFailed(Pending pending, ServerEnvelope response, ServerEnvelope writeError) {
        complete(pending, writeError, true, response);
    }

    private void complete(Pending pending, ServerEnvelope response, boolean retryable, ServerEnvelope uncommitted) {
        require(response.getRequestId() == pending.request().getRequestId()
                && response.getCmd() == pending.request().getCmd(), GameError.OPERATION_CONFLICT);
        if (!processing.remove(pending.request().getRequestId(), pending)) return;
        responses.put(pending.request().getRequestId(), new Completed(pending.request(), response, retryable, uncommitted,pending.attachment()));
        while (responses.size() > capacity) {
            long oldest = responses.keySet().iterator().next();
            responses.remove(oldest);
            if (!retired || Long.compareUnsigned(oldest, retiredThrough) > 0) retiredThrough = oldest;
            retired = true; // 忘记旧响应后拒绝旧编号，而不是再执行一次动作。
        }
        pending.result().complete(response);
    }

    /** 持久占位失败或参数冲突不污染本地响应缓存，之后仍可查询真正的原请求。 */
    public void reject(Pending pending, ServerEnvelope response) {
        if (processing.remove(pending.request().getRequestId(), pending)) pending.result().complete(response);
    }
    public Pending pending(long requestId) { return processing.get(requestId); }
    /** Actor 停止时结束本地等待者；持久 PROCESSING 仍由重试核实，不伪造业务成功。 */
    public void abort(Throwable failure) {
        var waiting=java.util.List.copyOf(processing.values());
        processing.clear();
        waiting.forEach(pending->pending.result().completeExceptionally(failure));
    }
    public boolean retired(long requestId) { return retired && Long.compareUnsigned(requestId,retiredThrough)<=0; }

    /** 明确业务失败缓存原响应；临时不可用/忙碌保留恢复机会，不永久缓存为最终业务结果。 */
    public static boolean retryable(ServerEnvelope response) {
        int code = response.getCode();
        return code == GameError.SERVICE_UNAVAILABLE.code() || code == GameError.INTERNAL_ERROR.code()
                || code == GameError.LOGIN_IN_PROGRESS.code() || code == GameError.LOGOUT_IN_PROGRESS.code();
    }
}
