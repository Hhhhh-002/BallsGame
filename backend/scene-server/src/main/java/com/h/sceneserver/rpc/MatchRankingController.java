package com.h.sceneserver.rpc;

import com.h.sceneserver.store.MatchRankingStore;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** 内网查询入口：场景回收后仍可按实例/比赛 ID 查询 Redis 最终榜；不暴露到公网。 */
@RestController
public final class MatchRankingController {
    private final MatchRankingStore rankings;
    public MatchRankingController(MatchRankingStore rankings) {this.rankings=rankings;}
    @GetMapping(value="/internal/game/ranking",produces="application/x-protobuf")
    public byte[] find(@RequestParam String sceneInstanceId,@RequestParam long sceneId) {
        var result=rankings.find(sceneInstanceId,sceneId);
        if(result==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"排名不存在或已过保留期");
        return result.toByteArray();
    }
}
