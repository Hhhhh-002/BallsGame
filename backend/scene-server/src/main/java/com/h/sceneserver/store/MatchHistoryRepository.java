package com.h.sceneserver.store;

import com.h.gameprotocol.persistence.*;
import com.h.gameprotocol.proto.MatchRanking;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** MyBatis-Plus + 原始 Protobuf 字节；方法由独立 I/O 线程调用，不能在 SceneActor 中阻塞执行。 */
@Repository
public class MatchHistoryRepository {
    public record Page(List<MatchResultProto> records,String nextCursor) {}
    private final MatchMapper matches;
    private final MatchResultMapper results;
    public MatchHistoryRepository(MatchMapper matches,MatchResultMapper results) {this.matches=matches;this.results=results;}

    private MatchProto decode(MatchEntity row) {
        try {
            MatchProto value=MatchProto.parseFrom(row.getData());
            if(!row.getMatchId().equals(value.getMatchId()))throw new IllegalStateException("比赛主键和 data 不一致");
            return value;
        } catch(com.google.protobuf.InvalidProtocolBufferException invalid) {throw new IllegalStateException("比赛数据损坏",invalid);}
    }
    private void ensureStart(MatchProto start) {
        MatchEntity existing=matches.selectById(start.getMatchId());
        if(existing==null) {
            try {
                if(matches.insert(new MatchEntity(start.getMatchId(),start.toByteArray()))!=1)throw new IllegalStateException("开赛记录写入失败");
                return;
            } catch(DuplicateKeyException concurrent) {existing=matches.selectById(start.getMatchId());}
        }
        if(existing==null || decode(existing).getStartTime()!=start.getStartTime())
            throw new IllegalStateException("同一比赛标识对应不同开赛时间");
    }
    private MatchProto start(MatchRanking ranking) {
        if(ranking.getStartedAtMs()<=0)throw new IllegalArgumentException("开赛时间不合法");
        return MatchProto.newBuilder().setMatchId(MatchRecordKeys.match(ranking.getSceneInstanceId(),ranking.getSceneId()))
                .setStartTime(ranking.getStartedAtMs()).build();
    }
    @Transactional public void started(MatchRanking ranking) {ensureStart(start(ranking));}

    /** 比赛与所有个人结果一起提交；失败整体回滚，重试核对同一份冻结结果，不覆盖另一份结果。 */
    @Transactional public void finish(MatchRanking ranking) {
        MatchProto begin=start(ranking);
        if(!ranking.getFrozen() || ranking.getEndedAtMs()<ranking.getStartedAtMs()
                || ranking.getEndReason()!=MatchRanking.EndReason.TIME_LIMIT && ranking.getEndReason()!=MatchRanking.EndReason.ALL_LEFT)
            throw new IllegalArgumentException("必须保存合法的冻结最终榜");
        MatchProto.Builder finalMatch=begin.toBuilder().setEndTime(ranking.getEndedAtMs())
                .setFinalPlayerCount(ranking.getPlayersCount())
                .setEndReason(ranking.getEndReason()==MatchRanking.EndReason.TIME_LIMIT?MatchProto.EndReason.TIME_LIMIT:MatchProto.EndReason.ALL_LEFT);
        Set<String> users=new HashSet<>();
        for(var player:ranking.getPlayersList()) {
            if(player.getLeft() || !users.add(player.getUserId()) || player.getRank()==0 || player.getRank()>ranking.getPlayersCount()
                    || !Double.isFinite(player.getArea()) || player.getArea()<0 || player.getNickname().codePointCount(0,player.getNickname().length())>32)
                throw new IllegalArgumentException("最终榜包含离场玩家或非法数据");
            MatchRecordKeys.prefix(player.getUserId());
            finalMatch.addPlayers(MatchPlayerProto.newBuilder().setUserId(player.getUserId()).setNickname(player.getNickname())
                    .setRank(player.getRank()).setArea(player.getArea()));
        }
        MatchProto frozen=finalMatch.build();
        ensureStart(begin);
        MatchEntity current=matches.lock(begin.getMatchId());
        if(current==null)throw new IllegalStateException("比赛记录缺失");
        MatchProto previous=decode(current);
        if(previous.getEndTime()!=0 && !previous.equals(frozen))throw new IllegalStateException("已保存最终战绩，不能覆盖");
        for(var player:frozen.getPlayersList()) {
            MatchResultProto result=MatchResultProto.newBuilder().setUserId(player.getUserId()).setMatchId(frozen.getMatchId())
                    .setNickname(player.getNickname()).setRank(player.getRank()).setArea(player.getArea())
                    .setFinalPlayerCount(frozen.getFinalPlayerCount()).setStartTime(frozen.getStartTime()).setEndTime(frozen.getEndTime()).build();
            String id=MatchRecordKeys.result(result.getUserId(),result.getMatchId());
            MatchResultEntity old=results.selectById(id);
            if(old==null) {
                if(results.insert(new MatchResultEntity(id,result.toByteArray()))!=1)throw new IllegalStateException("个人战绩写入失败");
            } else if(!Arrays.equals(old.getData(),result.toByteArray()))throw new IllegalStateException("已有个人战绩与本次结算不一致");
        }
        if(previous.getEndTime()==0 && matches.updateById(new MatchEntity(frozen.getMatchId(),frozen.toByteArray()))!=1)
            throw new IllegalStateException("比赛最终结果写入失败");
    }
    @Transactional(readOnly=true) public Page history(String user,int limit,String cursor) {
        String prefix=MatchRecordKeys.prefix(user),upper=MatchRecordKeys.upper(user);
        if(limit<1 || limit>100 || cursor==null || !cursor.isEmpty() && (!cursor.startsWith(prefix) || cursor.length()>384))
            throw new IllegalArgumentException("战绩分页参数不合法");
        var rows=results.history(cursor.isEmpty()?prefix:cursor,upper,limit+1);
        List<MatchResultProto> values=new ArrayList<>();
        for(var row:rows.subList(0,Math.min(limit,rows.size()))) {
            try {
                var value=MatchResultProto.parseFrom(row.getData());
                if(!user.equals(value.getUserId()) || !row.getResultId().equals(MatchRecordKeys.result(user,value.getMatchId())))
                    throw new IllegalStateException("个人战绩主键和 data 不一致");
                values.add(value);
            } catch(com.google.protobuf.InvalidProtocolBufferException invalid) {throw new IllegalStateException("个人战绩数据损坏",invalid);}
        }
        return new Page(List.copyOf(values),rows.size()>limit?rows.get(limit-1).getResultId():"");
    }
}
