package com.h.loginserver.account;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.h.gameprotocol.persistence.UserProto;
import com.h.loginserver.entity.UserEntity;
import com.h.loginserver.mapper.UserMapper;
import com.h.loginserver.validator.UserProtoCodecValidator;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** MyBatis-Plus 账号存取层；数据库两列映射与 Protobuf 用户实体在这里转换。 */
@Repository
public class AccountRepository {
    private final UserMapper mapper;
    private final UserProtoCodecValidator codec;

    /** Mapper 使用现有 MySQL 连接池，Codec 负责序列化及数据校验。 */
    public AccountRepository(UserMapper mapper, UserProtoCodecValidator codec) {
        this.mapper = mapper;
        this.codec = codec;
    }

    /**
     * 注册账号本身就是 userId，直接按字符串主键查询，不再额外使用 account 列。
     * MyBatis-Plus 对主键参数使用绑定，不将账号拼接进 SQL。
     */
    public Optional<UserProto> findByUserId(String userId) {
        UserEntity row = mapper.selectById(userId);
        if (row == null) {
            return Optional.empty();
        }
        return Optional.of(codec.decode(userId, row.getData()));
    }

    /** 注册只检查主键存在性，不读取或校验已有账号的密码数据。 */
    public boolean existsByUserId(String userId) {
        return mapper.selectCount(Wrappers.<UserEntity>lambdaQuery()
                .eq(UserEntity::getUserId, userId)) > 0;
    }

    /**
     * 注册流程使用：先由 PasswordEncoder 生成哈希，再构建 UserProto 传入。
     * 缺少创建时间时自动补当前时间；重复账号由主键拒绝，不覆盖已有用户。
     * 登录请求不会调用此方法；禁止用更新或 upsert 覆盖已有账号。
     */
    public void insert(UserProto user) {
        if (user == null) {
            throw new IllegalArgumentException("不能创建空的 UserProto");
        }
        UserProto created = user.getCreateTime() == 0
                ? user.toBuilder().setCreateTime(System.currentTimeMillis()).build() : user;
        if (mapper.insert(new UserEntity(created.getUserId(), codec.encode(created))) != 1) {
            throw new IllegalStateException("账号创建未完成");
        }
    }

    /**
     * AgentMgr 返回最终登录成功后调用，仅更新 UserProto.last_login_time。
     * BLOB 整体写回时使用旧 data 作比较条件，避免覆盖并发改密码或禁用账号的结果。
     */
    public void recordSuccessfulLogin(String userId, long loginTime) {
        if (loginTime <= 0) {
            throw new IllegalArgumentException("登录时间必须是正数 Unix 毫秒时间戳");
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            UserEntity row = mapper.selectById(userId);
            if (row == null) {
                throw new IllegalStateException("更新登录时间时账号已不存在");
            }
            UserProto current = codec.decode(userId, row.getData());
            if (current.getLastLoginTime() >= loginTime) {
                // 较早请求不能将最近登录时间回退，也无需重复写入相同值。
                return;
            }
            UserProto updated = current.toBuilder().setLastLoginTime(loginTime).build();
            int affected = mapper.update(null, Wrappers.<UserEntity>lambdaUpdate()
                    .eq(UserEntity::getUserId, userId)
                    //乐观锁
                    .eq(UserEntity::getData, row.getData())
                    .set(UserEntity::getData, codec.encode(updated)));
            if (affected == 1) {
                return;
            }
            // 只重读并更新本地数据库快照，不重发 BEGIN_LOGIN 或创建 Actor 请求。
        }
        throw new ConcurrencyFailureException("账号数据持续变化，最近登录时间未写入");
    }
}
