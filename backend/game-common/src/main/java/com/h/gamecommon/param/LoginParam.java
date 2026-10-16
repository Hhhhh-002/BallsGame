package com.h.gamecommon.param;

public class LoginParam {
    /** 与 game_account.user_id 的 VARCHAR(64) 保持一致。 */
    public static final int MAX_ACCOUNT_CHARACTERS = 64;
    /** 产品要求密码长度大于 8 位，即至少 9 个字符。 */
    public static final int MIN_PASSWORD_CHARACTERS = 9;
    /** BCrypt 输入长度按 UTF-8 字节计算，不按 Java String.length() 计算。 */
    public static final int MAX_PASSWORD_BYTES = 72;

    /** 常见非引号 ASCII 邮箱的本地部分；点不能出现在首尾或连续出现。 */
    public static final String EMAIL_LOCAL_ATOM = "[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+";
    /** 域名标签不允许以连字符开头或结尾，单个标签最多 63 字符。 */
    public static final String EMAIL_DOMAIN_LABEL = "[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?";
    /** 与 MySQL BLOB 的最大字节数一致，禁止读取或写入超出账号存储范围的数据。 */
    public static final int MAX_DATA_BYTES = 65_535;


}
