package com.h.loginserver.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.h.loginserver.entity.UserEntity;
import org.apache.ibatis.annotations.Mapper;

/** MyBatis-Plus 提供按主键查询、插入和更新；SQL 字段仅包含 user_id、data。 */
@Mapper
public interface UserMapper extends BaseMapper<UserEntity> {
}
