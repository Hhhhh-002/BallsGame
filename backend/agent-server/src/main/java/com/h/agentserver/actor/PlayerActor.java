package com.h.agentserver.actor;

import com.h.agentserver.rpc.SceneLinkManager;
import com.h.agentserver.store.*;
import com.h.agentserver.registries.AgentGameCommandRegistry;
import com.h.agentserver.registries.AgentSceneMessageRegistry;
import com.h.gameprotocol.codec.decode.ClientMessageDecoder;
import com.h.gamecommon.code.GameError;
import com.h.gamecommon.command.*;
import com.h.gamecore.actor.AkkaSupport;
import akka.actor.*;
import java.time.Duration;
import java.util.function.Supplier;
import com.h.gamecore.actor.MessageContext;
import com.h.gamecore.netty.PeerRegistry;
import com.h.gamecore.protocol.*;
import com.h.agentserver.actor.PlayerContext.Route;
import com.h.gameprotocol.proto.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import static com.h.gamecore.protocol.Messages.require;

/** 玩家生命周期及场景路由；战斗权威状态不在此 Actor 上计算。 */
public final class PlayerActor extends AbstractActorWithTimers {
    public record Forward(String gateway, ClientEnvelope request) {}
    public record SceneMessage(String userId,String instance,SceneToAgentEvent event,String join) {}
    public record Failure(ClientEnvelope request,Throwable cause) {}
    public enum Info { INSTANCE }
    public enum PrepareStop { INSTANCE }
    public enum Stop { INSTANCE }
    private record InputTimeout(ClientEnvelope request,MessageContext.Pending pending) {}
    private final ActorRef self=getSelf();
    private final PlayerContext player;
    private final SceneLinkManager scenes;
    private final PeerRegistry gateways;
    private final PlayerRequestStore requests;
    private final Executor db;
    private final AgentGameCommandRegistry gameHandlers;
    private final AgentSceneMessageRegistry sceneHandlers;
    private final ClientMessageDecoder decoder = new ClientMessageDecoder();
    public PlayerActor(PlayerContext player, SceneLinkManager scenes,
            PeerRegistry gateways, PlayerRequestStore requests, Executor db,
            AgentGameCommandRegistry gameHandlers, AgentSceneMessageRegistry sceneHandlers) {
        this.player=player; this.scenes=scenes; this.gateways=gateways; this.requests=requests; this.db=db;
        this.gameHandlers=gameHandlers; this.sceneHandlers=sceneHandlers;
    }
    public static Props props(PlayerContext player, SceneLinkManager scenes,PeerRegistry gateways,
            PlayerRequestStore requests, Executor db,AgentGameCommandRegistry games,AgentSceneMessageRegistry outputs) {
        return Props.create(PlayerActor.class,()->new PlayerActor(player,scenes,gateways,requests,db,games,outputs));
    }
    @Override public Receive createReceive() {
        return receiveBuilder()
                .match(Forward.class,m->AkkaSupport.reply(getSender(),self,()->forward(m.gateway(),m.request())))
                .match(SceneMessage.class,m->{if(player.userId().equals(m.userId()))sceneEvent(m.instance(),m.event(),m.join());})
                .match(Failure.class,m->error(m.request(),m.cause()))
                .matchEquals(Info.INSTANCE,m->AkkaSupport.reply(getSender(),self,()->CompletableFuture.completedFuture(publicInfo())))
                .matchEquals(PrepareStop.INSTANCE,m->AkkaSupport.reply(getSender(),self,this::prepareStop))
                .matchEquals(Stop.INSTANCE,m->{player.stopped=true;player.closing=true;getContext().stop(self);})
                .match(InputTimeout.class,m->{
                    if(player.messages.pending(m.request().getRequestId())==m.pending())player.messages.complete(m.pending(),
                            Messages.clientError(m.request(),new TimeoutException("等待 Scene 动作响应超时")),true);
                })
                .match(AkkaSupport.Continuation.class,AkkaSupport::execute)
                .build();
    }
    @Override public void postStop() { player.messages.abort(new TimeoutException("PlayerActor 已停止")); }
    private <T> CompletableFuture<T> call(Supplier<T> action) { return AkkaSupport.call(self,action); }
    private CompletableFuture<Void> tell(Runnable action) { return AkkaSupport.run(self,action); }
    public String user() { return player.userId(); }
    public PlayerPublicInfo publicInfo() { return PlayerPublicInfo.newBuilder().setNickname("玩家").build(); }
    public CompletableFuture<Void> forward(String sourceGateway, ClientEnvelope request) {
        try {
            require(sourceGateway.equals(player.gatewayId()) && !player.closing && !player.stopped,GameError.LOGIN_OPERATION_SUPERSEDED);
            var handler=gameHandlers.get(GameCommands.fromCode(request.getCmd()));
            require(handler!=null,GameError.UNKNOWN_COMMAND);
            final com.h.gameprotocol.codec.DecodedMessage<ClientEnvelope,GameCommands> message;
            try { message=decoder.decode(request.toByteArray()); }
            catch (com.google.protobuf.InvalidProtocolBufferException invalid) { throw new IllegalArgumentException(invalid); }
            if(player.roundEnding && handler.battleInput())return CompletableFuture.completedFuture(null);
            if(handler.forwardedResponse())return forwardSceneCommand(handler,message);
            if (!handler.cacheResponse()) return handler.handle(this,message).thenApply(v->(Void)null);
            var admission=player.messages.begin(request,true);
            var pending=admission.pending();
            pending.result().thenAccept(this::push); // 重复请求也订阅同一个最终结果，绝不重跑 Handler。
            if(!admission.owner()) {
                if(admission.completed())handler.replay(this,request,true);
                return CompletableFuture.<Void>completedFuture(null);
            }
            if(player.transition!=null && !player.transition.isDone()) {
                player.messages.complete(pending,Messages.clientError(request,new BattleException(GameError.LOGIN_IN_PROGRESS)),true);
                return CompletableFuture.<Void>completedFuture(null);
            }
            player.transition=pending.result();
            boolean replayOnly=player.messages.retired(request.getRequestId());
            final CompletableFuture<PlayerRequestStore.Stored> claimed;
            try { claimed=CompletableFuture.supplyAsync(()->replayOnly?requests.replay(player.actorId(),request):requests.claim(player.actorId(),request),db); }
            catch(RejectedExecutionException failure) {
                if(admission.uncommitted()!=null)player.messages.writeFailed(pending,admission.uncommitted(),Messages.clientError(request,failure));
                else player.messages.reject(pending,Messages.clientError(request,failure));
                return pending.result().thenApply(v->(Void)null);
            }
            CompletableFuture<ServerEnvelope> outcome=claimed
                    .thenCompose(stored->call(()-> {
                        if(stored.done()) {
                            handler.replay(this,request,true);
                            return CompletableFuture.completedFuture(stored.result());
                        }
                        if(admission.uncommitted()!=null)return CompletableFuture.completedFuture(admission.uncommitted());
                        require(!player.closing && !player.stopped,GameError.LOGOUT_IN_PROGRESS);
                        if(handler.retriesOriginalTarget() && player.route==null && !stored.instance().isBlank()) {
                            player.routeRequest=request;
                            player.route=new Route(stored.instance(),stored.scene(),stored.nickname(),request.getRequestId());
                            player.joined=player.route.scene()!=0;
                        }
                        return handler.handle(this,message);
                    }).thenCompose(Function.identity()))
                    .handle((response,failure)->failure==null?response:Messages.clientError(request,failure));
            outcome.thenCompose(response-> {
                        if(claimed.isCompletedExceptionally() || MessageContext.retryable(response))return CompletableFuture.completedFuture(response);
                        return CompletableFuture.supplyAsync(()-> {requests.complete(player.actorId(),request,response);return response;},db);
                    })
                    .whenComplete((response,failure)->tell(()-> {
                        ServerEnvelope finalResponse=failure==null?response:Messages.clientError(request,failure);
                        ServerEnvelope unsaved=admission.uncommitted()!=null?admission.uncommitted()
                                :outcome.getNow(null);
                        if((failure!=null || claimed.isCompletedExceptionally()) && unsaved!=null
                                && !MessageContext.retryable(unsaved) && !claimed.isCompletedExceptionally()) {
                            player.messages.writeFailed(pending,unsaved,finalResponse);
                        } else if(claimed.isCompletedExceptionally() && admission.uncommitted()!=null) {
                            player.messages.writeFailed(pending,admission.uncommitted(),finalResponse);
                        } else if(claimed.isCompletedExceptionally())player.messages.reject(pending,finalResponse);
                        else player.messages.complete(pending,finalResponse,MessageContext.retryable(finalResponse));
                    }).whenComplete((ignored,completionFailure)-> {
                        if(completionFailure!=null)pending.result().completeExceptionally(completionFailure);
                    }));
            return pending.result().thenApply(v->(Void)null);
        } catch(Exception failure) {return CompletableFuture.failedFuture(failure);}
    }
    private CompletableFuture<Void> forwardSceneCommand(com.h.agentserver.dispatch.handlers.AgentGameHandler handler,
            com.h.gameprotocol.codec.DecodedMessage<ClientEnvelope,GameCommands> message) {
        ClientEnvelope request=message.envelope();
        var admission=player.messages.begin(request,false,player.route);
        var pending=admission.pending();
        pending.result().thenAccept(this::push);
        if(!admission.owner())return CompletableFuture.completedFuture(null);
        try {
            require(pending.attachment()!=null,GameError.NOT_IN_SCENE);
            require(pending.attachment().equals(player.route),GameError.REQUEST_EXPIRED);
            require(player.joined && !player.roundEnding,GameError.NOT_IN_SCENE);
            getTimers().startSingleTimer(pending,new InputTimeout(request,pending),Duration.ofMillis(scenes.inputResponseTimeoutMs()));
            pending.result().whenComplete((v,e)->tell(()->getTimers().cancel(pending)));
            handler.handle(this,message).whenComplete((ignored,failure)-> {
                if(failure!=null)tell(()-> {
                    if(player.messages.pending(request.getRequestId())==pending) {
                        var response=Messages.clientError(request,failure);
                        player.messages.complete(pending,response,MessageContext.retryable(response));
                    }
                });
                // 成功写入 WS 只表示已发送，等待 sceneEvent 中的真实响应来完成 Future。
            });
        } catch(Exception failure) {
            var response=Messages.clientError(request,failure);
            player.messages.complete(pending,response,MessageContext.retryable(response));
        }
        return pending.result().thenApply(v->(Void)null);
    }
    /** 仅由已进入玩家邮箱的 Handler 调用，保持原有场景路由与恢复语义。 */
    public CompletableFuture<ServerEnvelope> startGame(ClientEnvelope request, StartGameRequest body) {
        try {
            String nickname=body.getNickname().isBlank()?"玩家":body.getNickname();
            require(nickname.codePointCount(0,nickname.length())<=32
                    && nickname.codePoints().noneMatch(Character::isISOControl),GameError.INVALID_INPUT);
            if(player.route!=null) require(player.route.requestId()==request.getRequestId(),GameError.ALREADY_IN_SCENE);
            else player.route=new Route("",0,nickname,request.getRequestId());
            player.routeRequest=request;
            CompletableFuture<ServerEnvelope> operation=ensureSelected()
                    .thenCompose(v->ensureAllocated())
                    .thenCompose(v->call(()-> {
                        require(!player.closing,GameError.LOGOUT_IN_PROGRESS);
                        return player.route;
                    }))
                    .thenCompose(r->scenes.request(r.instance(),ServiceCommands.SCENE_JOIN,SceneJoinRequest.newBuilder()
                            .setSceneId(r.scene()).setUserId(player.userId()).setPlayerActorId(player.actorId())
                            .setJoinOperationId(r.join()).setNickname(r.nickname()).build()))
                    .thenCompose(response->call(()-> {
                        try {
                            require(SceneJoinResult.parseFrom(response.getBody()).getSceneId()==player.route.scene(),GameError.OPERATION_CONFLICT);
                            require(!player.closing,GameError.LOGOUT_IN_PROGRESS); player.joined=true; player.roundEnding=false;
                            return Messages.client(request,StartGameResponse.newBuilder().setSceneId(player.route.scene()).build());
                        } catch(com.google.protobuf.InvalidProtocolBufferException e){throw new IllegalStateException("JOIN 响应错误",e);}
                    }));
            // 明确的满房/房间结束可以撤销入场；网络超时不能认定 JOIN 未执行，保留原路由供同请求重试。
            CompletableFuture<ServerEnvelope> guarded=operation.exceptionallyCompose(failure-> {
                Throwable cause=Messages.unwrap(failure);
                if(cause instanceof BattleException battle &&
                        (battle.error()==GameError.SCENE_FULL || battle.error()==GameError.SCENE_NOT_FOUND)) {
                    return leave().thenCompose(v->CompletableFuture.failedFuture(cause));
                }
                return CompletableFuture.failedFuture(cause);
            });
            return guarded;
        } catch(Exception e){return CompletableFuture.failedFuture(e);}
    }
    /** 转发不会被当作已执行业务的响应缓存，最终处理和去重属于 Scene。 */
    public CompletableFuture<Void> forwardSceneInput(ClientEnvelope request) {
        require(player.route!=null && player.route.scene()!=0 && player.joined && !player.roundEnding,GameError.NOT_IN_SCENE);
        return scenes.send(player.route.instance(),Messages.event(ServiceCommands.SCENE_PLAYER_INPUT,
                ScenePlayerInput.newBuilder().setSceneId(player.route.scene()).setUserId(player.userId()).setPlayerActorId(player.actorId())
                        .setJoinOperationId(player.route.join()).setClientMessage(request).build()));
    }
    public CompletableFuture<ServerEnvelope> leaveGame(ClientEnvelope request) {
        CompletableFuture<ServerEnvelope> operation=leave().thenApply(v->Messages.client(request,null));
        return operation;
    }
    /** 查询可发到任一 Scene 实例：各 Scene 必须连接同一个战绩库，用户身份只取自 Context。 */
    public CompletableFuture<ServerEnvelope> history(ClientEnvelope request,MatchHistoryRequest body) {
        require(request.getRequestId()!=0 && Integer.toUnsignedLong(body.getLimit())<=100,GameError.INVALID_INPUT);
        return scenes.select().thenCompose(instance->scenes.request(instance,ServiceCommands.MATCH_HISTORY,
                SceneMatchHistoryRequest.newBuilder().setUserId(player.userId()).setLimit(body.getLimit()).setCursor(body.getCursor()).build()))
                .thenApply(response->{
                    try {ServerEnvelope result=Messages.client(request,MatchHistoryResponse.parseFrom(response.getBody()));push(result);return result;}
                    catch(com.google.protobuf.InvalidProtocolBufferException invalid) {throw new IllegalStateException("战绩响应损坏",invalid);}
                });
    }
    /** 原 START_GAME 重发时补取快照，不重新入场；必须在玩家邮箱内调用。 */
    public void replayStartGame(ClientEnvelope request, boolean completed) {
        // 已离开该场时仍可重放原响应，但不能借重试重新入场/补取另一局快照。
        if(player.route==null || player.route.requestId()!=request.getRequestId() || !player.joined)return;
        Route current=player.route;
        if(completed)scenes.request(current.instance(),ServiceCommands.SCENE_JOIN,
                SceneJoinRequest.newBuilder().setSceneId(current.scene()).setUserId(player.userId()).setPlayerActorId(player.actorId())
                        .setJoinOperationId(current.join()).setNickname(current.nickname()).build())
                .exceptionally(failure->{error(request,failure);return null;});
    }
    private CompletableFuture<Void> ensureSelected() {
        return call(()->player.route).thenCompose(r-> {
            if(!r.instance().isBlank())return CompletableFuture.completedFuture(r);
            return scenes.select().thenCompose(instance->call(()-> {
                player.route=new Route(instance,player.route.scene(),player.route.nickname(),player.route.requestId()); return player.route;
            }));
        }).thenCompose(this::persist);
    }
    private CompletableFuture<Void> ensureAllocated() {
        return call(()->player.route).thenCompose(r-> {
            if(r.scene()!=0)return CompletableFuture.completedFuture(r);
            return scenes.request(r.instance(),ServiceCommands.FIND_SCENE,FindSceneRequest.newBuilder()
                            .setOperationId(r.join()).setPlayerActorId(player.actorId()).build())
                    .thenCompose(response->call(()-> {
                        try {
                            CreateSceneResult result=CreateSceneResult.parseFrom(response.getBody());
                            require(result.getSceneId()!=0 && r.instance().equals(result.getSceneHostInstanceId())
                                    && result.getSceneHostServiceName().equals("scene-server"),GameError.OPERATION_CONFLICT);
                            player.route=new Route(r.instance(),result.getSceneId(),r.nickname(),r.requestId()); return player.route;
                        } catch(com.google.protobuf.InvalidProtocolBufferException e){throw new IllegalStateException("FIND 响应错误",e);}
                    }));
        }).thenCompose(this::persist);
    }
    private CompletableFuture<Void> persist(Route r) {
        return call(()->player.routeRequest).thenCompose(original->CompletableFuture.runAsync(()-> {
            require(original!=null,GameError.OPERATION_CONFLICT);
            requests.binding(player.actorId(),original,r.instance(),r.scene(),r.nickname());
        },db));
    }
    private CompletableFuture<Void> leave() {
        return call(()->player.route).thenCompose(r-> {
            if(r==null)return CompletableFuture.completedFuture(null);
            // 即便 JOIN 超时或 同请求重试，也沿用原 START_GAME requestId 核实原场景，不能随机分配另一个。
            return ensureSelected().thenCompose(v->ensureAllocated()).thenCompose(v->call(()->player.route))
                    .thenCompose(current->scenes.request(current.instance(),ServiceCommands.SCENE_LEAVE,
                            SceneLeaveRequest.newBuilder().setSceneId(current.scene()).setUserId(player.userId()).setPlayerActorId(player.actorId())
                                    .setJoinOperationId(current.join()).build()))
                    .thenCompose(response-> {
                        try {
                            SceneLeaveResult.Outcome outcome=SceneLeaveResult.parseFrom(response.getBody()).getOutcome();
                            require(outcome==SceneLeaveResult.Outcome.LEFT || outcome==SceneLeaveResult.Outcome.ALREADY_LEFT,GameError.OPERATION_CONFLICT);
                        } catch(com.google.protobuf.InvalidProtocolBufferException e){return CompletableFuture.failedFuture(e);}
                        return CompletableFuture.<Void>completedFuture(null);
                    }).thenCompose(v->tell(()-> {player.route=null;player.routeRequest=null;player.joined=false;}));
        });
    }
    /** 先拒绝新输入，等入场/离场任务结束，再核实离场；不保存账号资料或当局战绩，战绩由 Scene 在结算时保存。 */
    public CompletableFuture<Void> prepareStop() {
        player.closing=true;
        CompletableFuture<?> pending=player.transition==null?CompletableFuture.completedFuture(null):player.transition.handle((v,e)->null);
        return pending.thenCompose(v->leave());
    }
    public void error(ClientEnvelope request, Throwable failure) { push(Messages.clientError(request,failure)); }
    public void sceneEvent(String instance, SceneToAgentEvent event, String join) {
        try {
            ServerEnvelope response=event.getClientMessage();
            var pending=player.messages.pending(response.getRequestId());
            if(pending!=null && pending.attachment() instanceof Route original
                    && original.instance().equals(instance) && original.scene()==event.getSceneId() && original.join().equals(join)
                    && pending.request().getCmd()==response.getCmd()) {
                // 原动作的迟到响应可以完成原 Future，但绝不改新场景状态。
                player.messages.complete(pending,response,MessageContext.retryable(response));
                return;
            }
            if(!player.closing && !player.stopped && player.route!=null && player.route.instance().equals(instance)
                    && player.route.scene()==event.getSceneId() && player.route.join().equals(join)) {
                ServerEnvelope message=event.getClientMessage();
                var handler=sceneHandlers.get(GameCommands.fromCode(message.getCmd()));
                require(handler!=null,GameError.UNKNOWN_COMMAND);
                handler.handle(this,instance,message);
            }
        } catch(Exception failure) {System.getLogger(PlayerActor.class.getName()).log(System.Logger.Level.WARNING,"Scene 消息处理失败",failure);}
    }
    /** 普通场景消息仅转发；所有 Handler 调用均在上面的邮箱任务内。 */
    public void emitSceneMessage(ServerEnvelope message) { emit(message); }
    public void matchEnded(String instance, ServerEnvelope message) {
        try {
            MatchRanking result=MatchRanking.parseFrom(message.getBody());
            require(result.getFrozen() && result.getSceneId()==player.route.scene()
                    && result.getSceneInstanceId().equals(instance),GameError.INVALID_MESSAGE);
        } catch(com.google.protobuf.InvalidProtocolBufferException invalid) {throw new IllegalStateException(invalid);}
        emit(message);
        // 最终结果已确认存库；正常 LEAVE 核对原入场请求并清理成员，不退出账号、不清除最终榜。
        // 入场/主动离场若正在完成，先等它结束；清理失败保留路由，后续最终榜推送会继续尝试。
        if(!player.roundEnding || player.transition==null || player.transition.isDone()) {
            player.roundEnding=true;
            CompletableFuture<?> previous=player.transition;
            CompletableFuture<Void> cleanup=(previous==null?CompletableFuture.completedFuture(null)
                    :previous.handle((v,e)->null)).thenCompose(v->leave());
            player.transition=cleanup;
        }
    }
    private void push(ServerEnvelope response) {
        // 异步业务回调可跨线程写 Channel；可变 Actor 状态校验仍回到邮箱。
        tell(()-> {
            if(player.stopped || player.closing)return;
            emit(response);
        });
    }
    private void emit(ServerEnvelope response) {
        gateways.send(player.gatewayId(),Messages.event(ServiceCommands.FORWARD_TO_CLIENT,ForwardToClientEvent.newBuilder()
                .addTargetUserIds(player.userId()).setClientMessage(response).build()));
    }
}
