package com.h.loginserver.account;

import com.h.gamecommon.code.GameError;
import com.h.gameprotocol.proto.LoginRequest;
import com.h.gameprotocol.persistence.UserProto;
import com.h.gamecommon.exception.LoginServiceException;
import com.h.loginserver.validator.LoginCredentialsValidator;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Optional;

/** 校验账号密码，成功后仅返回数据库确认的 userId。 */
@Service
public class AccountAuthenticationService {
    private final AccountRepository accounts;
    private final PasswordEncoder passwordsEncoder;
    /** 解析后的登录参数在查库前统一校验，不使用 MVC 拦截器重复读取请求体。 */
    private final LoginCredentialsValidator credentialsValidator;



    /** 注入账号存取、密码编码器和格式校验组件，不创建默认账号或占位哈希。 */
    public AccountAuthenticationService(AccountRepository accounts, PasswordEncoder passwordsEncoder,
                                        LoginCredentialsValidator credentialsValidator) {
        this.accounts = accounts;
        this.passwordsEncoder = passwordsEncoder;
        this.credentialsValidator = credentialsValidator;

    }

    /**
     * 按产品要求分别返回账号不存在、密码错误和账号封禁；客户端可按错误码区分。
     * 这种细分提示会暴露账号是否存在，不再使用统一鉴权失败掩盖账号状态。
     * 格式校验先于查库，禁用检查先于 BCrypt 比对；密码保持原值，不 trim。
     */
    public String authenticate(LoginRequest credentials) {
        String account = credentials.getAccount();
        String password = credentials.getPassword();
        credentialsValidator.validate(account, password);
        // LoginRequest.account 沿用客户端字段名，其内容现在就是注册时指定的字符串 userId。
        Optional<UserProto> found = accounts.findByUserId(account);
        // 按要求先检查禁用状态；禁用账号直接失败，不再执行后面的 BCrypt 密码比对。
        // 账号不存在，直接结束鉴权，由分发器返回失败响应给网关。
        if (found.isEmpty()) {
            throw new LoginServiceException(GameError.ACCOUNT_NOT_FOUND);
        }

        UserProto user = found.get();

        // 先检查禁用状态，封禁账号不继续执行 BCrypt。
        if (user.getDisabled() != 0) {
            throw new LoginServiceException(GameError.ACCOUNT_DISABLED);
        }

        // 再比对密码；格式合法不代表密码正确。
        if (!passwordsEncoder.matches(password, user.getPassword())) {
            throw new LoginServiceException(GameError.PASSWORD_INCORRECT);
        }

        return user.getUserId();
    }
}
