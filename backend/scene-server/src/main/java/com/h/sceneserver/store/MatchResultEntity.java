package com.h.sceneserver.store;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;

/** 一个用户的一局战绩一行；data 为 MatchResultProto 字节。 */
@Getter @Setter @NoArgsConstructor
@TableName("game_match_result")
public class MatchResultEntity {
    @TableId(value="result_id",type=IdType.INPUT) private String resultId;
    @TableField("data") private byte[] data;
    public MatchResultEntity(String resultId,byte[] data) {this.resultId=resultId;this.data=data;}
}
