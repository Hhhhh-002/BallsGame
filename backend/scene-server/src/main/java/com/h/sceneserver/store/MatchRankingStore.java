package com.h.sceneserver.store;

import com.h.gameprotocol.proto.MatchRanking;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.*;
import org.springframework.stereotype.Repository;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** 每局一个 ZSET 与一个完整榜单快照；同槽事务提交，不混入 AgentMgr 在线状态。 */
@Repository
public class MatchRankingStore {
    private final StringRedisTemplate redis;
    private final ExecutorService executor;
    private final String prefix;
    private final Duration retention;
    private final MatchHistoryRepository history;
    public MatchRankingStore(StringRedisTemplate redis, ExecutorService executor, Environment env,MatchHistoryRepository history) {
        this.history=history;
        this.redis=redis; this.executor=executor;
        prefix=env.getProperty("scene.ranking-key-prefix", "balls:match:");
        long days=env.getProperty("battle.ranking-retention-days",Long.class,7L);
        if (days<1 || days>365 || prefix.isBlank()) throw new IllegalArgumentException("排名存储配置不合法");
        retention=Duration.ofDays(days);
    }
    public CompletableFuture<Void> save(MatchRanking ranking) {
        try { return CompletableFuture.runAsync(() -> write(ranking), executor); }
        catch(RuntimeException rejected) { return CompletableFuture.failedFuture(rejected); }
    }
    private void write(MatchRanking ranking) {
        // 冻结榜先在同一 MySQL 事务保存比赛和个人战绩，再发布 Redis 快照；失败时重试同一份结果。
        if(ranking.getFrozen())history.finish(ranking);else history.started(ranking);
        String key=base(ranking.getSceneInstanceId(), ranking.getSceneId());
        String encoded=Base64.getEncoder().encodeToString(ranking.toByteArray());
        // Scene 对同一比赛始终只有一个在途写入，失败重试复用同一个不可变快照；没有并发写旧榜覆盖最终榜。
        MatchRanking previous=find(ranking.getSceneInstanceId(), ranking.getSceneId());
        if(previous!=null && previous.getFrozen()) {
            if(!previous.equals(ranking)) throw new IllegalStateException("最终排名已冻结，不能覆盖");
            return; // 事务已成功但响应丢失时，重复最终保存返回有依据的成功。
        }
        Set<ZSetOperations.TypedTuple<String>> scores=new HashSet<>();
        ranking.getPlayersList().forEach(p -> scores.add(new DefaultTypedTuple<>(p.getUserId(), p.getArea())));
        List<Object> replies=redis.execute(new SessionCallback<List<Object>>() {
            @Override @SuppressWarnings("unchecked") public <K,V> List<Object> execute(RedisOperations<K,V> operations) {
                RedisOperations<String,String> tx=(RedisOperations<String,String>)(RedisOperations<?,?>)operations;
                tx.multi();
                try {
                    // 榜单仅包含当前仍在场者；每次替换整个有界榜单，ZSET 和协议快照不会出现不同版本。
                    tx.delete(key+":scores");
                    if(!scores.isEmpty()) tx.opsForZSet().add(key+":scores",scores);
                    tx.opsForValue().set(key+":snapshot",encoded);
                    if(ranking.getFrozen()) {
                        tx.expire(key+":scores",retention);
                        tx.expire(key+":snapshot",retention);
                    }
                    return tx.exec();
                } catch(RuntimeException failure) {
                    try { tx.discard(); } catch(RuntimeException cleanup) { failure.addSuppressed(cleanup); }
                    throw failure;
                }
            }
        });
        int expected=2+(scores.isEmpty()?0:1)+(ranking.getFrozen()?2:0);
        if(replies==null || replies.size()!=expected || replies.stream().anyMatch(r -> r instanceof Exception))
            throw new IllegalStateException("Redis 未确认排名事务提交");
    }
    /** 供结束后的对局查询；一次 GET 读取完整一致快照，frozen=false 不能当成最终结算。 */
    public MatchRanking find(String instance,long sceneId) {
        String value=redis.opsForValue().get(base(instance,sceneId)+":snapshot");
        if(value==null)return null;
        try { return MatchRanking.parseFrom(Base64.getDecoder().decode(value)); }
        catch(Exception invalid) { throw new IllegalStateException("排名快照损坏",invalid); }
    }
    private String base(String instance,long sceneId) {
        if(instance==null || instance.isBlank() || instance.length()>256 || sceneId==0)
            throw new IllegalArgumentException("必须指定 Scene 实例和比赛 ID");
        String encoded=Base64.getUrlEncoder().withoutPadding().encodeToString(instance.getBytes(StandardCharsets.UTF_8));
        return prefix+"{"+encoded+":"+Long.toUnsignedString(sceneId)+"}";
    }
}
