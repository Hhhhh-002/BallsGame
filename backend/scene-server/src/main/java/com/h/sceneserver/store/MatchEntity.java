package com.h.sceneserver.store;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;

/** 一场比赛一行；data 为 MatchProto 字节，不再重复声明其业务字段。 */
@Getter @Setter @NoArgsConstructor
@TableName("game_match")
public class MatchEntity {
    @TableId(value="match_id",type=IdType.INPUT) private String matchId;
    @TableField("data") private byte[] data;
    public MatchEntity(String matchId,byte[] data) {this.matchId=matchId;this.data=data;}
}
