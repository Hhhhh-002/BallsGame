package com.h.loginserver.validator;

import com.h.gamecommon.code.GameError;
import com.h.gamecommon.exception.LoginServiceException;
import org.springframework.stereotype.Component;
import com.h.gamecommon.param.LoginParam;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;



/** 登录凭据格式校验；不读取 HTTP 请求体、不查询数据库、不执行密码哈希比对。 */
@Component
public class LoginCredentialsValidator {

    /**
     * 邮箱格式要求包含 @ 和完整域名，支持常用子域名、加号和点分账号。
     * 仅检查格式，不代表邮箱真实存在或用户拥有该邮箱；不 trim 或改变账号大小写。
     * Pattern 可以共享，每次校验单独创建 Matcher，不共享可变匹配状态。
     */
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "\\A" + LoginParam.EMAIL_LOCAL_ATOM + "(?:\\." + LoginParam.EMAIL_LOCAL_ATOM + ")*@"
                    + "(?:" + LoginParam.EMAIL_DOMAIN_LABEL + "\\.)+[A-Za-z]{2,63}\\z");

    /**
     * 长度大于 8 位，即至少 9 个字符，并同时包含英文字母、数字、特殊符号。
     * P/S 为 Unicode 标点/符号类别（如 !、@、#）；禁止空白、控制及其他不可见类别。
     * 不要求同时包含大写和小写，不悄悄删除密码中的空格或修改原始密码。
     */
    private static final Pattern PASSWORD_PATTERN = Pattern.compile(
            "\\A(?=.*[A-Za-z])(?=.*[0-9])(?=.*[\\p{P}\\p{S}])[^\\p{Z}\\p{C}]{"
                    + LoginParam.MIN_PASSWORD_CHARACTERS + ",}\\z");

    /** 查库前调用；各字段只在自己的校验方法检查一次，注册、修改密码也可复用。 */
    public void validate(String account, String password) {
        validateAccount(account);
        validatePassword(password);
    }

    /** 校验作为 userId 的邮箱账号；不执行 DNS 查询或发送邮件。 */
    public void validateAccount(String account) {
        // 方法可以独立供注册流程调用，因此自己处理空值，不依赖外层重复检查。
        if (account == null || account.isBlank()) {
            throw new LoginServiceException(GameError.ACCOUNT_EMPTY);
        }
        if (account.codePointCount(0, account.length()) > LoginParam.MAX_ACCOUNT_CHARACTERS) {
            throw new LoginServiceException(GameError.ACCOUNT_TOO_LONG);
        }
        if (!EMAIL_PATTERN.matcher(account).matches()) {
            throw new LoginServiceException(GameError.ACCOUNT_FORMAT_INVALID);
        }
    }

    /** 校验明文输入的复杂度及 BCrypt 长度边界，不对数据库中的哈希使用此规则。 */
    public void validatePassword(String password) {
        // 空密码、字节数超限、复杂度不满足分别返回可公开的具体提示。
        if (password == null || password.isBlank()) {
            throw new LoginServiceException(GameError.PASSWORD_EMPTY);
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > LoginParam.MAX_PASSWORD_BYTES) {
            throw new LoginServiceException(GameError.PASSWORD_TOO_LONG);
        }
        if (!PASSWORD_PATTERN.matcher(password).matches()) {
            throw new LoginServiceException(GameError.PASSWORD_FORMAT_INVALID);
        }
    }
}
