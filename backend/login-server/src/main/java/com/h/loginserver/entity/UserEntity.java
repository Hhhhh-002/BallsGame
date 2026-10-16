package com.h.loginserver.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** MyBatis-Plus 的两列存储映射；完整账号实体由 UserProto 定义，不再重复声明其业务字段。 */
@Getter
@Setter
@NoArgsConstructor
@TableName("game_account")
public class UserEntity {
    /** 注册流程指定的字符串主键；INPUT 禁止 MyBatis-Plus 自动生成雪花 ID 或依赖自增。 */
    @TableId(value = "user_id", type = IdType.INPUT)
    private String userId;

    /** UserProto.toByteArray() 的二进制结果，直接映射 BLOB，不转 Base64 或 JSON。 */
    @TableField("data")
    private byte[] data;

    /** 构造待插入记录；不生成包含 data 的 toString，避免误打印密码哈希。 */
    public UserEntity(String userId, byte[] data) {
        this.userId = userId;
        this.data = data;
    }
}
