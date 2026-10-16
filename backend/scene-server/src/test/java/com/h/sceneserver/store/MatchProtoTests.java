package com.h.sceneserver.store;

import com.h.gameprotocol.persistence.MatchPlayerProto;
import com.h.gameprotocol.persistence.MatchProto;
import com.h.gameprotocol.persistence.MatchResultProto;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 只验证持久化协议的字节往返，不表示已接入 MySQL。 */
class MatchProtoTests {
    @Test
    void wholeMatchRoundTripsAsProtobufBytes() throws Exception {
        MatchProto match = MatchProto.newBuilder()
                .setMatchId("scene01:123").setStartTime(1000).setEndTime(601000)
                .setFinalPlayerCount(1).setEndReason(MatchProto.EndReason.TIME_LIMIT)
                .addPlayers(MatchPlayerProto.newBuilder().setUserId("a@example.com")
                        .setNickname("本局小球").setRank(1).setArea(12.5))
                .build();

        MatchProto decoded = MatchProto.parseFrom(match.toByteArray());
        assertEquals(match, decoded);
        assertEquals(decoded.getFinalPlayerCount(), decoded.getPlayersCount());
        assertEquals("本局小球", decoded.getPlayers(0).getNickname());
    }

    @Test
    void personalResultRoundTripsWithoutActorOrRequestIds() throws Exception {
        MatchResultProto result = MatchResultProto.newBuilder()
                .setUserId("a@example.com").setMatchId("scene01:123").setNickname("本局小球")
                .setRank(1).setFinalPlayerCount(1).setStartTime(1000).setEndTime(601000)
                .setArea(12.5).build();

        assertEquals(result, MatchResultProto.parseFrom(result.toByteArray()));
        assertEquals(8, result.getDescriptorForType().getFields().size());
    }

    @Test
    void allLeftMatchCanBeStoredWithoutPersonalResults() throws Exception {
        MatchProto match = MatchProto.newBuilder().setMatchId("scene01:124")
                .setStartTime(1000).setEndTime(2000).setEndReason(MatchProto.EndReason.ALL_LEFT).build();

        MatchProto decoded = MatchProto.parseFrom(match.toByteArray());
        assertEquals(0, decoded.getFinalPlayerCount());
        assertEquals(0, decoded.getPlayersCount());
        assertEquals(match, decoded);
    }
}
