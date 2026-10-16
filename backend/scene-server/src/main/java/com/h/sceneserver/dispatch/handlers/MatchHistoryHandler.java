package com.h.sceneserver.dispatch.handlers;

import com.google.protobuf.Message;
import com.h.gamecommon.annotation.ServiceCommand;
import com.h.gamecommon.command.ServiceCommands;
import com.h.gamecore.protocol.Messages;
import com.h.gameprotocol.codec.DecodedMessage;
import com.h.gameprotocol.proto.*;
import com.h.sceneserver.store.MatchHistoryRepository;
import io.netty.channel.Channel;
import org.springframework.stereotype.Component;
import java.util.concurrent.*;

/** 查询不可变历史结果，不读写正在战斗的 SceneActor；数据库操作使用独立 I/O 线程。 */
@Component
@ServiceCommand(ServiceCommands.MATCH_HISTORY)
public final class MatchHistoryHandler implements SceneServiceHandler {
    private final MatchHistoryRepository history;
    private final ExecutorService executor;
    public MatchHistoryHandler(MatchHistoryRepository history,ExecutorService executor) {this.history=history;this.executor=executor;}
    @Override public CompletionStage<ServiceEnvelope> handle(Channel channel,DecodedMessage<? extends Message,ServiceCommands> message) {
        var request=(SceneMatchHistoryRequest)message.body();
        com.h.gamecore.protocol.Messages.require(Integer.toUnsignedLong(request.getLimit())<=100,
                com.h.gamecommon.code.GameError.INVALID_INPUT);
        return CompletableFuture.supplyAsync(()-> {
            int limit=request.getLimit()==0?20:request.getLimit();
            final MatchHistoryRepository.Page page;
            try {page=history.history(request.getUserId(),limit,request.getCursor());}
            catch(IllegalArgumentException invalid) {throw new com.h.gamecore.protocol.BattleException(com.h.gamecommon.code.GameError.INVALID_INPUT);}
            var result=MatchHistoryResponse.newBuilder().setNextCursor(page.nextCursor());
            for(var record:page.records())result.addRecords(MatchHistoryItem.newBuilder().setMatchId(record.getMatchId())
                    .setNickname(record.getNickname()).setRank(record.getRank()).setPlayerCount(record.getFinalPlayerCount())
                    .setStartTime(record.getStartTime()).setEndTime(record.getEndTime()).setArea(record.getArea()));
            return Messages.response((ServiceRequest)message.envelope(),result.build());
        },executor);
    }
}
