package com.h.sceneserver.store;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface MatchResultMapper extends BaseMapper<MatchResultEntity> {
    /** 主键范围限定用户；分页参数绑定，不拼接账号，不尝试用 SQL 解析 Protobuf。 */
    @Select("SELECT result_id, data FROM game_match_result WHERE result_id > #{after} AND result_id < #{upper} ORDER BY result_id LIMIT #{limit}")
    List<MatchResultEntity> history(@Param("after") String after,@Param("upper") String upper,@Param("limit") int limit);
}
