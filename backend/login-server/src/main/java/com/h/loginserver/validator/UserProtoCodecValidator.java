package com.h.loginserver.validator;

import com.google.protobuf.InvalidProtocolBufferException;
import com.h.gamecommon.param.LoginParam;
import com.h.gameprotocol.persistence.UserProto;
import org.springframework.stereotype.Component;

/** 在数据库 byte[] 与 UserProto 之间转换，并拒绝损坏或身份不一致的账号数据。 */
@Component
public class UserProtoCodecValidator {


    /** 写库前校验字段，返回 Protobuf 二进制；不对密码进行二次编码或明文转换。 */
    public byte[] encode(UserProto user) {
        validate(user);
        if (user.getSerializedSize() > LoginParam.MAX_DATA_BYTES) {
            throw new IllegalArgumentException("UserProto 超出 BLOB 字段容量");
        }
        return user.toByteArray();
    }

    /** 读库时核对外层主键与内层 user_id，防止拿到另一账号的数据进行密码校验。 */
    public UserProto decode(String expectedUserId, byte[] data) {
        if (data == null || data.length == 0 ) {
            throw new IllegalStateException("账号 data 为空");
        }
        try {
            UserProto user = UserProto.parseFrom(data);
            validate(user);
            if (!user.getUserId().equals(expectedUserId)) {
                throw new IllegalStateException("账号主键与 UserProto.user_id 不一致");
            }
            return user;
        } catch (InvalidProtocolBufferException | IllegalArgumentException exception) {
            // 不包含二进制原文、密码或哈希内容，由上层返回服务内部错误。
            throw new IllegalStateException("账号 data 不是有效的 UserProto", exception);
        }
    }

    /** 数据库只有两列，所以密码格式、禁用状态和时间合法性在持久化边界统一检查。
     * 在存入数据库之前做序列化校验
     * */
    private void validate(UserProto user) {
        if (user == null || user.getUserId().isBlank()
//                长度不能超过 64 个 Unicode 字符，与数据库 VARCHAR(64) 对应。
                || user.getUserId().codePointCount(0, user.getUserId().length()) > 64) {
            throw new IllegalArgumentException("UserProto.user_id 必须是 1～64 字符的有效账号");
        }
//        它只检查哈希的格式
        if (!user.getPassword().matches("\\A\\$2[aby]\\$(0[4-9]|1[0-6])\\$[./A-Za-z0-9]{53}\\z")) {
            throw new IllegalArgumentException("UserProto.password 必须存完整 BCrypt 哈希");
        }
        if (user.getDisabled() != 0 && user.getDisabled() != 1) {
            throw new IllegalArgumentException("UserProto.disabled 只能为 0 或 1");
        }
    }
}
