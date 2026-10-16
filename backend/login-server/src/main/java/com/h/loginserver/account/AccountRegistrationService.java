package com.h.loginserver.account;

import com.h.gamecommon.code.GameError;
import com.h.gamecommon.exception.LoginServiceException;
import com.h.gameprotocol.persistence.UserProto;
import com.h.gameprotocol.proto.RegisterRequest;
import com.h.loginserver.validator.LoginCredentialsValidator;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/** 注册账号：格式校验、重复邮箱检查、BCrypt 哈希、单次 INSERT；不参与登录仲裁。 */
@Service
public class AccountRegistrationService {
    private final AccountRepository accounts;
    private final PasswordEncoder passwords;
    private final LoginCredentialsValidator validator;

    public AccountRegistrationService(AccountRepository accounts, PasswordEncoder passwords,
                                      LoginCredentialsValidator validator) {
        this.accounts = accounts;
        this.passwords = passwords;
        this.validator = validator;
    }

    /** 禁用状态及时间均由服务端设置，客户端不能注册一个携带自定义状态的 UserProto。 */
    public String register(RegisterRequest request) {
        String account = request.getAccount();
        validator.validate(account, request.getPassword());
        if (accounts.existsByUserId(account)) {
            throw new LoginServiceException(GameError.ACCOUNT_ALREADY_EXISTS);
        }
        UserProto user = UserProto.newBuilder().setUserId(account)
                .setPassword(passwords.encode(request.getPassword()))
                .setDisabled(0).setCreateTime(System.currentTimeMillis()).setLastLoginTime(0).build();
        try {
            accounts.insert(user);
        } catch (DuplicateKeyException duplicate) {
            // 两个 Login 实例可能同时查到不存在，数据库主键负责最终互斥，失败者不能覆盖密码。
            throw new LoginServiceException(GameError.ACCOUNT_ALREADY_EXISTS);
        }
        return account;
    }
}
