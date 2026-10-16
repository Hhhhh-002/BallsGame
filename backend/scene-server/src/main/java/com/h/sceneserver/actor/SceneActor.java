package com.h.sceneserver.actor;

import com.h.gamecommon.code.GameError;
import com.h.gamecommon.command.*;
import akka.actor.*;
import java.time.Duration;
import java.util.function.Supplier;
import com.h.sceneserver.service.SceneDirectoryActor;
import com.h.gamecore.actor.MessageContext;
import com.h.gamecore.actor.AkkaSupport;
import com.h.gamecore.netty.PeerRegistry;
import com.h.gamecore.protocol.*;
import com.h.gameprotocol.proto.*;
import com.h.sceneserver.config.BattleRules;
import com.h.sceneserver.store.MatchRankingStore;
import com.h.sceneserver.store.SceneRequestStore;
import com.h.sceneserver.registries.SceneGameCommandRegistry;
import com.h.gameprotocol.codec.decode.ClientMessageDecoder;
import java.util.*;
import java.util.concurrent.*;
import static com.h.gamecore.protocol.Messages.require;

/** 一场比赛一个 Actor。球、食物、成员、Tick 均只能在本邮箱内修改。 */
public class SceneActor extends AbstractActorWithTimers {
    public record Join(String agent,SceneJoinRequest request) {}
    public record Leave(String agent,SceneLeaveRequest request) {}
    public record Input(String agent,ScenePlayerInput request) {}
    public record Resync(String agent) {}
    public record Expire(long idleNanos) {}
    public enum Tick { INSTANCE }
    public enum Snapshot { INSTANCE }
    private final ActorRef self=getSelf();
    private final ActorRef directory;
    private boolean announced,announcedJoinable;
    private static final System.Logger LOG = System.getLogger(SceneActor.class.getName());
    private static final class Member {
        final String user, actor, agent, join, nickname;
        float dx, dy; long sequence, splitTick = -1000, ejectTick = -1000;
        final MessageContext messages = new MessageContext(1024);
        CompletableFuture<Void> actions=CompletableFuture.completedFuture(null);
        Member(SceneJoinRequest request, String agent) {
            user = request.getUserId(); actor = request.getPlayerActorId(); join = request.getJoinOperationId();
            nickname = request.getNickname(); this.agent = agent;
        }
        // 同一登录再次进入同一比赛，也不能让旧成员的过期清理影响新成员。
        // join 来自原 START_GAME requestId，不生成新编号。
        String requestScope() { return actor+":"+join; }
    }
    private static final class Ball {
        long id, mergeAfter; Member owner; float x, y, radius;
    }
    private static final class Participant {
        String nickname; double area; boolean left;
        Participant(String nickname) { this.nickname=nickname; }
    }
    private final long id;
    private final BattleRules rules;
    private final PeerRegistry agents;
    private final String instanceId;
    private final MatchRankingStore rankings;
    private final SceneRequestStore requests;
    private final SceneGameCommandRegistry gameHandlers;
    private final ClientMessageDecoder decoder = new ClientMessageDecoder();
    private final Map<String,Participant> participants = new HashMap<>();
    private long startedNanos, startedAtMs, nextRankingNanos, nextFinalPushNanos;
    private boolean finishing;
    private boolean finalCommitted;
    private MatchRanking finalRanking, writingRanking;
    private CompletableFuture<Void> rankingWrite;
    private final Map<String, Member> members = new LinkedHashMap<>();
    private final Map<Long, Ball> balls = new TreeMap<>();
    private final Map<Long, FoodState> foods = new TreeMap<>();
    // 离场结果保留在本场生命周期内，重复 LEAVE 不会再操作新一轮入场。
    private final Map<String,Set<String>> leftJoins = new HashMap<>();
    private final SplittableRandom random = new SplittableRandom();
    private long ballIds, foodIds, tick, lastNanos = System.nanoTime(), accumulator, emptySince = System.nanoTime();
    private int playerCount;
    private int participantCount;
    private int joinHistorySize;
    private int pendingRequestIo;
    private boolean closed;
    private long droppedTicks;
    public SceneActor(long id, String instanceId, BattleRules rules, ActorRef directory, PeerRegistry agents,
            MatchRankingStore rankings, SceneRequestStore requests, SceneGameCommandRegistry gameHandlers) {
        this.id = id; this.rules = rules; this.agents = agents; this.directory=directory;
        this.instanceId=instanceId; this.rankings=rankings;
        this.gameHandlers=gameHandlers;
        this.requests=requests;
        // 房间创建时就随机铺好食物，首个入场快照直接包含整批食物。
        for(int i=0;i<rules.initialFoods();i++)createFood();
    }
    public static Props props(long id,String instance,BattleRules rules,ActorRef directory,PeerRegistry agents,
            MatchRankingStore rankings,SceneRequestStore requests,SceneGameCommandRegistry handlers) {
        return Props.create(SceneActor.class,()->new SceneActor(id,instance,rules,directory,agents,rankings,requests,handlers));
    }
    @Override public void preStart() {
        getTimers().startSingleTimer(Tick.INSTANCE,Tick.INSTANCE,Duration.ofMillis(10));
        publishAvailability();
    }
    @Override public Receive createReceive() {
        return receiveBuilder()
                .match(Join.class,m->respond(()->join(m.agent(),m.request())))
                .match(Leave.class,m->respond(()->leave(m.agent(),m.request())))
                .match(Input.class,m->AkkaSupport.reply(getSender(),self,()->input(m.agent(),m.request())))
                .match(Resync.class,m->resyncAgent(m.agent()))
                .match(Expire.class,m->respond(()->expireIfEmpty(m.idleNanos())))
                .matchEquals(Snapshot.INSTANCE,m->respond(this::snapshotMessage))
                .matchEquals(Tick.INSTANCE,m->{
                    try {advance();}
                    catch(Exception failure){LOG.log(System.Logger.Level.WARNING,"Scene Tick 失败: "+id,failure);}
                    finally {
                        publishAvailability();
                        // 本次 Tick 完成才安排下一次，最多一个待执行 Tick；调度与取消全部交给 Akka。
                        getTimers().startSingleTimer(Tick.INSTANCE,Tick.INSTANCE,Duration.ofMillis(10));
                    }
                })
                .match(AkkaSupport.Continuation.class,AkkaSupport::execute)
                .build();
    }
    @Override public void postStop() {
        members.values().forEach(member->member.messages.abort(new TimeoutException("比赛 Actor 已停止")));
    }
    private void respond(Supplier<Object> action) {
        ActorRef replyTo=getSender();
        try {replyTo.tell(action.get(),self);}
        catch(Exception failure) {replyTo.tell(new Status.Failure(failure),self);}
        finally {publishAvailability();}
    }
    private void publishAvailability() {
        boolean available=joinable();
        if(directory!=null && (!announced || available!=announcedJoinable || closed)) {
            announced=true;announcedJoinable=available;
            directory.tell(new SceneDirectoryActor.Availability(id,available,closed),self);
        }
    }
    public long id() { return id; }
    public boolean joinable() { return !closed && !finishing && playerCount < rules.maxPlayers()
            && participantCount < rules.maxParticipants() && joinHistorySize < 8192; }
    private SceneJoinResult join(String agent, SceneJoinRequest request) {

            checkMatchDeadline();
            require(!closed && !finishing, GameError.SCENE_NOT_FOUND);
            require(!request.getUserId().isBlank() && request.getUserId().length() <= 64
                    && !request.getPlayerActorId().isBlank() && request.getPlayerActorId().length()<=64
                    && !request.getJoinOperationId().isBlank() && request.getJoinOperationId().length()<=64
                    && request.getNickname().codePointCount(0, request.getNickname().length()) <= 32, GameError.INVALID_MESSAGE);
            Member previous = members.get(request.getUserId());
            if (previous != null) {
                require(matches(previous, agent, request.getPlayerActorId(), request.getJoinOperationId())
                        && previous.nickname.equals(request.getNickname()), GameError.ALREADY_IN_SCENE);
                snapshot(previous); // 原 JOIN 响应丢失后重试，重发全量快照，不重复创建球或广播入场。
                return SceneJoinResult.newBuilder().setSceneId(id).build();
            }
            require(!leftJoins.getOrDefault(request.getPlayerActorId(),Set.of()).contains(request.getJoinOperationId()), GameError.OPERATION_CONFLICT);
            require(members.size() < rules.maxPlayers() && balls.size() < rules.maxBalls()
                    && joinHistorySize<8192 && (participants.containsKey(request.getUserId())
                        || participants.size()<rules.maxParticipants()), GameError.SCENE_FULL);
            Member member = new Member(request, agent);
            Ball ball = new Ball(); ball.id = ++ballIds; ball.owner = member; ball.radius = 2;
            ball.x = coordinate(rules.width(), ball.radius); ball.y = coordinate(rules.height(), ball.radius);
            members.put(member.user, member); balls.put(ball.id, ball); playerCount = members.size();
            Participant participant=participants.computeIfAbsent(member.user,k -> new Participant(member.nickname));
            participant.nickname=member.nickname; participant.left=false;
            participantCount=participants.size();
            if(startedNanos==0 && members.size()>=rules.minPlayersToStart()) {
                startedNanos=System.nanoTime(); startedAtMs=System.currentTimeMillis();
                // 等候时间不补算成战斗 Tick，也不计入比赛时长。
                lastNanos=startedNanos; accumulator=0;
            }
            broadcast(Messages.push(GameCommands.PLAYER_ENTERED,
                    PlayerEnteredEvent.newBuilder().addBalls(state(ball)).build()), member);
            // 通知所有成员当前人数与是否开赛，第一名玩家也能立即得知第二名已到场。
            broadcast(snapshotMessage(),null);
            return SceneJoinResult.newBuilder().setSceneId(id).build();

    }
    private SceneLeaveResult leave(String agent, SceneLeaveRequest request) {

            checkMatchDeadline(); // 超过比赛截止时先冻结，迟到 LEAVE 不能把已到期的分数改成 0。
            require(!request.getJoinOperationId().isBlank() && request.getJoinOperationId().length()<=64
                    && !request.getPlayerActorId().isBlank() && request.getPlayerActorId().length()<=64, GameError.INVALID_MESSAGE);
            Member member = members.get(request.getUserId());
            if (member == null || !member.join.equals(request.getJoinOperationId()) || !member.actor.equals(request.getPlayerActorId())) {
                // 即使 LEAVE 先到，也登记此入场已取消，迟到 JOIN 不能重新加入。
                rememberLeft(request.getPlayerActorId(),request.getJoinOperationId());
                return SceneLeaveResult.newBuilder().setOutcome(SceneLeaveResult.Outcome.ALREADY_LEFT).build();
            }
            require(matches(member, agent, request.getPlayerActorId(), request.getJoinOperationId()), GameError.OPERATION_CONFLICT);
            members.remove(member.user); playerCount = members.size(); rememberLeft(member.actor,member.join);
            member.actions.handle((v,e)->null).thenCompose(v->requests.retired(instanceId,id,member.requestScope()))
                    .exceptionally(failure->{LOG.log(System.Logger.Level.WARNING,"离场去重记录过期设置失败",failure);return null;});
            if(!finishing) {
                Participant participant=participants.get(member.user);
                participant.left=true; participant.area=0; // 保留有限的入场统计，但排名/战绩按当前成员筛选，离场玩家不入榜。
            }
            PlayerLeftEvent.Builder event = PlayerLeftEvent.newBuilder().setUserId(member.user);
            balls.values().removeIf(b -> { if (b.owner != member) return false; event.addBallIds(b.id); return true; });
            broadcast(Messages.push(GameCommands.PLAYER_LEFT, event.build()), null);
            if (members.isEmpty()) emptySince = System.nanoTime();
            if(members.isEmpty() && !finishing && startedNanos!=0) finish(MatchRanking.EndReason.ALL_LEFT);
            if(!finishing)broadcast(snapshotMessage(),null);
            return SceneLeaveResult.newBuilder().setOutcome(SceneLeaveResult.Outcome.LEFT).build();

    }
    private CompletableFuture<Void> input(String agent, ScenePlayerInput input) {
            checkMatchDeadline();
            // 结算即冻结权威状态；本局之后到达的操作不登记去重、不执行 Handler、不回错误推送。
            if(closed || finishing)return CompletableFuture.completedFuture(null);
            Member member = members.get(input.getUserId());
            if (closed || member == null || !matches(member, agent, input.getPlayerActorId(), input.getJoinOperationId())) return CompletableFuture.completedFuture(null);
            ClientEnvelope request = input.getClientMessage();
            try {
                var handler = gameHandlers.get(GameCommands.fromCode(request.getCmd()));
                require(handler != null, GameError.UNKNOWN_COMMAND);
                var message = decoder.decode(request.toByteArray());
                if (!handler.cacheResponse()) {
                    if(!member.actions.isDone()) {
                        // SHIFT 不写 Redis，但不能越过同玩家先收到、仍等待 I/O 的动作。
                        member.actions=member.actions.handle((v,e)->null).thenCompose(v->AkkaSupport.run(self,()-> {
                            try {
                                checkMatchDeadline();
                                if(closed || finishing || members.get(member.user)!=member)return;
                                handler.handle(this,member.user,message);
                            } catch(Exception failure) {send(member,Messages.clientError(request,failure));}
                        }));
                        return member.actions;
                    }
                    checkMatchDeadline();
                    if(finishing)return CompletableFuture.completedFuture(null);
                    handler.handle(this, member.user, message);
                    return CompletableFuture.completedFuture(null);
                }
                var admission = member.messages.begin(request,true);
                var pending = admission.pending();
                pending.result().thenAccept(response -> send(member,response));
                if (!admission.owner()) return pending.result().thenApply(v->(Void)null);
                pendingRequestIo++;
                // 异步 Redis 不阻塞邮箱；同玩家的动作按接收顺序串接，再回本 Actor 执行 Handler。
                boolean replayOnly=member.messages.retired(request.getRequestId());
                member.actions=member.actions.handle((v,e)->null)
                        .thenCompose(v->requests.claim(instanceId,id,member.requestScope(),request,replayOnly))
                        .thenCompose(stored->AkkaSupport.call(self,()-> {
                            if(stored.stored().done())return stored.stored().result();
                            if(admission.uncommitted()!=null)return admission.uncommitted();
                            // 无本地执行者的 PROCESSING 无法证明动作未执行；绝不因重启盲目重放。
                            require(stored.owner(),GameError.SERVICE_UNAVAILABLE);
                            try {
                                checkMatchDeadline();
                                require(!closed && !finishing && members.get(member.user)==member,GameError.NOT_IN_SCENE);
                                handler.handle(this,member.user,message);
                                return Messages.client(request,null);
                            } catch(Exception failure) {return Messages.clientError(request,failure);}
                        }))
                        .thenCompose(response->requests.complete(instanceId,id,member.requestScope(),request,response)
                                .handle((ignored,failure)->new ActionResult(response,failure)))
                        .handle((result,failure)->failure==null?result:new ActionResult(null,failure))
                        .thenCompose(result->AkkaSupport.run(self,()-> {
                            pendingRequestIo--;
                            if(result.failure()==null)member.messages.complete(pending,result.response(),MessageContext.retryable(result.response()));
                            else {
                                ServerEnvelope error=Messages.clientError(request,result.failure());
                                ServerEnvelope original=result.response()!=null?result.response():admission.uncommitted();
                                if(original!=null)member.messages.writeFailed(pending,original,error);
                                else member.messages.reject(pending,error);
                            }
                        }));
                return pending.result().thenApply(v->(Void)null);
            } catch (Exception failure) { send(member, Messages.clientError(request, failure)); }
            return CompletableFuture.completedFuture(null);
    }
    private record ActionResult(ServerEnvelope response,Throwable failure) {}
    /** 以下输入操作仅供已进入场景邮箱的 Handler 调用。 */
    public void shiftInput(String userId, ShiftRequest request, long sequence) {
        Member member=members.get(userId);
        require(sequence!=0,GameError.INVALID_INPUT);
        if(Long.compareUnsigned(sequence,member.sequence)<=0)return;
        float[] d=direction(request.getDirectionX(),request.getDirectionY());
        member.dx=d[0];member.dy=d[1];member.sequence=sequence;
    }
    public void splitInput(String userId, SplitRequest request) {
        require(startedNanos!=0,GameError.MATCH_WAITING);
        Member member=members.get(userId);
        split(member,actionDirection(request.getDirectionX(),request.getDirectionY(),member));
    }
    public void ejectInput(String userId, EjectRequest request) {
        require(startedNanos!=0,GameError.MATCH_WAITING);
        Member member=members.get(userId);
        eject(member,actionDirection(request.getDirectionX(),request.getDirectionY(),member));
    }
    private void advance() {
        long now = System.nanoTime(), step = 1_000_000_000L / rules.tickRate();
        if(startedNanos==0) {
            // 单人只等候：不移动、不吞噬、不补食物、不写战绩，也不积压逻辑时间。
            lastNanos=now; accumulator=0; return;
        }
        // 比赛时长使用单调墙钟，不因补帧超限而无限延长；截止后立即停止改变权威状态。
        if(!finishing && startedNanos!=0 && now-startedNanos>=TimeUnit.SECONDS.toNanos(rules.matchDurationSeconds()))
            finish(MatchRanking.EndReason.TIME_LIMIT);
        if(finishing) { flushRanking(now); return; }
        accumulator += Math.max(0, now - lastNanos); lastNanos = now;
        int loops = 0;
        while (accumulator >= step && loops < rules.maxCatchUp()) {
            update(); accumulator -= step; loops++;
        }
        if (accumulator >= step) {
            // 过载时放弃超额墙钟积压，逻辑 tick 不伪增；避免追帧把其他房间拖死。
            droppedTicks += accumulator / step; accumulator %= step;
            LOG.log(System.Logger.Level.WARNING, "Scene " + id + " 补帧超限，累计放弃等待 tick=" + droppedTicks);
        }
        if (loops > 0 && tick % (rules.tickRate() / rules.broadcastRate()) < loops) moveBroadcast();
        flushRanking(now);
    }

    /** 每次从权威球状态求总面积，不信任客户端上报分数；并列名次使用 1、1、3。 */
    private MatchRanking ranking(boolean frozen,MatchRanking.EndReason reason) {
        Map<String,Double> totals=new HashMap<>();
        for(Ball b:balls.values()) totals.merge(b.owner.user,Math.PI*b.radius*b.radius,Double::sum);
        participants.forEach((user,p)-> { if(!p.left)p.area=Math.round(totals.getOrDefault(user,0d)*1000)/1000d; });
        List<Map.Entry<String,Participant>> sorted=new ArrayList<>(participants.entrySet());
        sorted.removeIf(entry->!members.containsKey(entry.getKey())); // 只统计当前仍在场的玩家，观战玩家仍是成员。
        sorted.sort(Comparator.<Map.Entry<String,Participant>>comparingDouble(e->e.getValue().area).reversed()
                .thenComparing(Map.Entry::getKey));
        MatchRanking.Builder result=MatchRanking.newBuilder().setSceneId(id).setSceneInstanceId(instanceId)
                .setServerTick(tick).setFrozen(frozen).setStartedAtMs(startedAtMs)
                .setEndsAtMs(startedAtMs+rules.matchDurationSeconds()*1000L).setEndReason(reason);
        int rank=0,index=0; double last=Double.NaN;
        for(var entry:sorted) {
            Participant p=entry.getValue(); index++;
            if(Double.compare(last,p.area)!=0)rank=index;
            last=p.area;
            result.addPlayers(MatchRankingEntry.newBuilder().setUserId(entry.getKey()).setNickname(p.nickname)
                    .setArea(p.area).setRank(rank).setLeft(p.left));
        }
        return result.build();
    }
    private void finish(MatchRanking.EndReason reason) {
        finishing=true;
        finalRanking=ranking(true,reason).toBuilder().setEndedAtMs(System.currentTimeMillis()).build();
        // 冻结不可变榜单，后续离场、重试不能再改变它。
        nextRankingNanos=0;
    }
    private void checkMatchDeadline() {
        if(!finishing && startedNanos!=0 && System.nanoTime()-startedNanos>=TimeUnit.SECONDS.toNanos(rules.matchDurationSeconds()))
            finish(MatchRanking.EndReason.TIME_LIMIT);
    }
    /** 每场只有一个 Redis 写入在途；实时榜合并到最新状态，最终榜保留并定期重试直到确认。 */
    private void flushRanking(long now) {
        if(startedNanos==0 || participants.isEmpty())return;
        if(rankingWrite!=null && rankingWrite.isDone()) {
            try {
                rankingWrite.join(); // 已完成才读取结果，不在邮箱中等待 Redis。
                if(writingRanking.getFrozen()) {
                    finalCommitted=true;
                    broadcast(Messages.push(GameCommands.MATCH_ENDED,writingRanking),null);
                    nextFinalPushNanos=now+TimeUnit.SECONDS.toNanos(5);
                } else broadcast(Messages.push(GameCommands.MATCH_RANKING,writingRanking),null);
            } catch(CompletionException failure) {
                LOG.log(System.Logger.Level.WARNING,"排名保存失败，下轮重试: "+id,Messages.unwrap(failure));
                nextRankingNanos=now+TimeUnit.SECONDS.toNanos(1);
            }
            rankingWrite=null; writingRanking=null;
        }
        if(finalCommitted) {
            if(now>=nextFinalPushNanos) {
                broadcast(Messages.push(GameCommands.MATCH_ENDED,finalRanking),null);
                nextFinalPushNanos=now+TimeUnit.SECONDS.toNanos(5);
            }
            return;
        }
        if(rankingWrite==null && now>=nextRankingNanos) {
            writingRanking=finishing?finalRanking:ranking(false,MatchRanking.EndReason.NONE);
            rankingWrite=rankings.save(writingRanking);
            nextRankingNanos=now+TimeUnit.MILLISECONDS.toNanos(rules.rankingIntervalMs());
        }
    }
    private void update() {
        tick++;
        float dt = 1f / rules.tickRate();
        for (Ball b : balls.values()) {
            b.x = clamp(b.x + b.owner.dx * rules.speed() * dt, b.radius, rules.width() - b.radius);
            b.y = clamp(b.y + b.owner.dy * rules.speed() * dt, b.radius, rules.height() - b.radius);
        }
        if (tick % rules.foodIntervalTicks() == 0) refreshFoods();
        eatFood(); collideBalls();
        // 周期全量状态校正：临时推送失败后，即使连接未断开，客户端也能重新收敛；不是消息 ACK 保证。
        if(tick%(rules.tickRate()*10L)==0)broadcast(snapshotMessage(),null);
    }
    private FoodState createFood() {
        FoodState food=FoodState.newBuilder().setFoodId(++foodIds)
                .setX(coordinate(rules.width(),0)).setY(coordinate(rules.height(),0)).build();
        foods.put(food.getFoodId(),food);
        return food;
    }
    /** 每轮从 1 到配置上限取随机数量，只补到当前对局的食物容量；一批合并为一个广播。 */
    private void refreshFoods() {
        int remaining = rules.maxFoods() - foods.size();
        if (remaining <= 0) return;
        int count = Math.min(remaining, random.nextInt(1, rules.foodRefreshMaxCount() + 1));
        FoodAddedEvent.Builder event = FoodAddedEvent.newBuilder();
        for (int i = 0; i < count; i++) event.addFoods(createFood());
        broadcast(Messages.push(GameCommands.FOOD_ADDED, event.build()), null);
    }
    /** 食物空间网格，每个球只查询覆盖自己半径的网格；删除仍由同一个 Actor 完成。 */
    private void eatFood() {
        Map<Long, List<FoodState>> grid = new HashMap<>();
        for (FoodState f : foods.values()) grid.computeIfAbsent(cell((int)(f.getX()/10), (int)(f.getY()/10)), k -> new ArrayList<>()).add(f);
        for (Ball b : balls.values()) {
            int minX = (int)((b.x-b.radius)/10), maxX = (int)((b.x+b.radius)/10);
            int minY = (int)((b.y-b.radius)/10), maxY = (int)((b.y+b.radius)/10);
            for (int x = minX; x <= maxX; x++) for (int y = minY; y <= maxY; y++) {
                for (FoodState f : grid.getOrDefault(cell(x, y), List.of())) {
                    float dx = b.x-f.getX(), dy = b.y-f.getY();
                    if (dx*dx+dy*dy < b.radius*b.radius && foods.remove(f.getFoodId()) != null) {
                        b.radius = boundedRadius((float)Math.sqrt(b.radius*b.radius+1));
                        broadcast(Messages.push(GameCommands.FOOD_EATEN, FoodEatenEvent.newBuilder()
                                .setBallId(b.id).setFoodId(f.getFoodId()).setRadius(b.radius).build()), null);
                    }
                }
            }
        }
    }
    /** 球数量有硬上限；当前碰撞为有界两两检查，扩展超大单场时再改宽阶段空间索引。 */
    private void collideBalls() {
        List<Ball> ordered = new ArrayList<>(balls.values());
        BallChangedEvent.Builder event = BallChangedEvent.newBuilder();
        for (int i = 0; i < ordered.size(); i++) for (int j = i+1; j < ordered.size(); j++) {
            Ball a = ordered.get(i), b = ordered.get(j);
            if (!balls.containsKey(a.id) || !balls.containsKey(b.id)) continue;
            float dx = a.x-b.x, dy = a.y-b.y;
            if (a.owner == b.owner) {
                if (tick < a.mergeAfter || tick < b.mergeAfter || dx*dx+dy*dy >= Math.min(a.radius,b.radius)*Math.min(a.radius,b.radius)) continue;
            } else if (Math.max(a.radius,b.radius) < Math.min(a.radius,b.radius)*1.15f
                    || dx*dx+dy*dy >= Math.pow(Math.max(a.radius,b.radius)-Math.min(a.radius,b.radius)*0.5,2)) continue;
            Ball big = a.radius >= b.radius ? a : b, small = big == a ? b : a;
            big.radius = boundedRadius((float)Math.sqrt(big.radius*big.radius+small.radius*small.radius));
            balls.remove(small.id); event.addRemovedBallIds(small.id).addUpserts(state(big));
        }
        if (event.getRemovedBallIdsCount() > 0) broadcast(Messages.push(GameCommands.BALL_CHANGED, event.build()), null);
    }
    private void split(Member member, float[] d) {
        require(tick - member.splitTick >= rules.tickRate()/2, GameError.INVALID_INPUT);
        int count = (int)balls.values().stream().filter(b -> b.owner == member).count();
        BallChangedEvent.Builder event = BallChangedEvent.newBuilder();
        for (Ball b : new ArrayList<>(balls.values())) {
            if (b.owner != member || b.radius < 3 || count >= rules.maxBallsPerPlayer() || balls.size() >= rules.maxBalls()) continue;
            b.radius /= (float)Math.sqrt(2); b.mergeAfter = tick + rules.tickRate()*15L;
            Ball child = new Ball(); child.id = ++ballIds; child.owner = member; child.radius = b.radius; child.mergeAfter = b.mergeAfter;
            child.x = clamp(b.x + d[0]*b.radius*2, child.radius, rules.width()-child.radius);
            child.y = clamp(b.y + d[1]*b.radius*2, child.radius, rules.height()-child.radius);
            balls.put(child.id, child); count++; event.addUpserts(state(b)).addUpserts(state(child));
        }
        require(event.getUpsertsCount() > 0, GameError.INVALID_INPUT);
        member.splitTick = tick; broadcast(Messages.push(GameCommands.BALL_CHANGED, event.build()), null);
    }
    private void eject(Member member, float[] d) {
        require(tick - member.ejectTick >= rules.tickRate()/4, GameError.INVALID_INPUT);
        FoodAddedEvent.Builder foodEvent = FoodAddedEvent.newBuilder();
        BallChangedEvent.Builder changes = BallChangedEvent.newBuilder();
        for (Ball b : balls.values()) {
            if (b.owner != member || b.radius < 3 || foods.size() >= rules.maxFoods()) continue;
            b.radius = (float)Math.sqrt(b.radius*b.radius-1);
            FoodState f = FoodState.newBuilder().setFoodId(++foodIds)
                    .setX(clamp(b.x + d[0]*(b.radius+2),0,rules.width()))
                    .setY(clamp(b.y + d[1]*(b.radius+2),0,rules.height())).build();
            foods.put(f.getFoodId(), f); foodEvent.addFoods(f); changes.addUpserts(state(b));
        }
        require(foodEvent.getFoodsCount() > 0, GameError.INVALID_INPUT);
        member.ejectTick = tick;
        broadcast(Messages.push(GameCommands.FOOD_ADDED, foodEvent.build()), null);
        broadcast(Messages.push(GameCommands.BALL_CHANGED, changes.build()), null);
    }
    private boolean expireIfEmpty(long idleNanos) {

            // 最终榜未提交时不回收 Actor，避免 Redis 故障期间丢掉唯一的待结算快照。
            if(finishing && !finalCommitted)return false;
            // 已离场成员的请求仍可能等待 Redis；不能回收其异步续接所需的 Actor。
            if(pendingRequestIo>0)return false;
            if (!closed && members.isEmpty() && System.nanoTime()-emptySince >= idleNanos) closed = true;
            return closed;

    }
    private void snapshot(Member member) {
        send(member,snapshotMessage());
        if(finalCommitted)send(member,Messages.push(GameCommands.MATCH_ENDED,finalRanking));
    }
    /** 链路重连时按 Agent 分组恢复全量快照，补上断线期间丢失的食物/球增删事件。 */
    private void resyncAgent(String agent) {

            SceneToAgentEvent.Builder event=SceneToAgentEvent.newBuilder().setSceneId(id);
            for(Member m:members.values())if(m.agent.equals(agent))event.addTargetUserIds(m.user)
                    .addTargetPlayerActorIds(m.actor).addTargetJoinOperationIds(m.join);
            if(event.getTargetUserIdsCount()>0)emit(agent,event.setClientMessage(finalCommitted
                    ?Messages.push(GameCommands.MATCH_ENDED,finalRanking):snapshotMessage()).build());

    }
    private ServerEnvelope snapshotMessage() {
        SceneSnapshot.Builder snapshot = SceneSnapshot.newBuilder().setSceneId(id).setServerTick(tick)
                .setWidth(rules.width()).setHeight(rules.height()).setTickRate(rules.tickRate()).addAllFoods(foods.values())
                .setStarted(startedNanos!=0).setMinPlayersToStart(rules.minPlayersToStart()).setPlayerCount(members.size());
        balls.values().forEach(b -> snapshot.addBalls(state(b)));
        return Messages.push(GameCommands.SCENE_SNAPSHOT, snapshot.build());
    }
    private void moveBroadcast() {
        MoveBroadcast.Builder positions = MoveBroadcast.newBuilder().setServerTick(tick);
        balls.values().forEach(b -> positions.addPlayers(PlayerPosition.newBuilder().setBallId(b.id)
                .setUserId(b.owner.user).setX(b.x).setY(b.y).setRadius(b.radius)));
        broadcast(Messages.push(GameCommands.MOVE, positions.build()), null);
    }
    private void broadcast(ServerEnvelope message, Member excluded) {
        Map<String, SceneToAgentEvent.Builder> grouped = new HashMap<>();
        for (Member m : members.values()) {
            if (m == excluded) continue;
            grouped.computeIfAbsent(m.agent, ignored -> SceneToAgentEvent.newBuilder().setSceneId(id).setClientMessage(message))
                    .addTargetUserIds(m.user).addTargetPlayerActorIds(m.actor).addTargetJoinOperationIds(m.join);
        }
        grouped.forEach((agent, event) -> emit(agent, event.build()));
    }
    private void send(Member member, ServerEnvelope message) {
        emit(member.agent, SceneToAgentEvent.newBuilder().setSceneId(id).addTargetUserIds(member.user)
                .addTargetPlayerActorIds(member.actor).addTargetJoinOperationIds(member.join).setClientMessage(message).build());
    }
    private void emit(String agent, SceneToAgentEvent event) {
        agents.send(agent, Messages.event(ServiceCommands.SCENE_TO_AGENT, event)).whenComplete((v,e) -> {
            // 连接丢失不等于玩家已经保存下线；保留成员，由 Agent 的控制流程确认离场。
            if (e != null) LOG.log(System.Logger.Level.DEBUG, "Scene 推送暂时失败: " + id + "/" + agent);
        });
    }
    private BallState state(Ball b) {
        return BallState.newBuilder().setBallId(b.id).setUserId(b.owner.user).setX(b.x).setY(b.y)
                .setRadius(b.radius).setNickname(b.owner.nickname).build();
    }
    private boolean matches(Member m, String agent, String actor, String join) {
        return m.agent.equals(agent) && m.actor.equals(actor) && m.join.equals(join);
    }
    private void rememberLeft(String actor,String join) {
        // 达到历史容量后不再接收新入场，不靠淘汰旧标记来冒险接受迟到 JOIN。
        if(joinHistorySize<8192 && leftJoins.computeIfAbsent(actor,key->new HashSet<>()).add(join))joinHistorySize++;
    }
    private float coordinate(float max, float radius) { return (float)random.nextDouble(radius, max-radius); }
    private float boundedRadius(float radius) { return Math.min(radius, Math.min(rules.width(),rules.height())/2-0.01f); }
    private static long cell(int x, int y) { return ((long)x << 32) ^ (y & 0xffffffffL); }
    private static float clamp(float value, float min, float max) { return Math.max(min, Math.min(value,max)); }
    private static float[] direction(float x, float y) {
        require(Float.isFinite(x) && Float.isFinite(y), GameError.INVALID_INPUT);
        double magnitude = Math.hypot(x,y);
        if (magnitude > 1) return new float[]{(float)(x/magnitude),(float)(y/magnitude)};
        return new float[]{x,y};
    }
    private static float[] actionDirection(float x,float y,Member member) {
        float[] input=direction(x,y);
        if(input[0]==0 && input[1]==0)input=new float[]{member.dx,member.dy};
        double length=Math.hypot(input[0],input[1]);
        // 分裂/吐球使用单位方向；零方向沿当前移动方向，静止时默认朝右。
        return length==0?new float[]{1,0}:new float[]{(float)(input[0]/length),(float)(input[1]/length)};
    }
}
