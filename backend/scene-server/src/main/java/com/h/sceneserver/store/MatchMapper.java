package com.h.sceneserver.store;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

@Mapper
public interface MatchMapper extends BaseMapper<MatchEntity> {
    @Select("SELECT match_id, data FROM game_match WHERE match_id=#{id} FOR UPDATE")
    MatchEntity lock(@Param("id") String id);
}
