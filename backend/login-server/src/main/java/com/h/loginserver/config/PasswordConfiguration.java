package com.h.loginserver.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** 只配置密码哈希组件，不在此处实现 Token、网关鉴权或玩家会话。 */
@Configuration(proxyBeanMethods = false)
public class PasswordConfiguration {
    /** 创建 BCrypt 编码器；数据库存哈希，校验使用 matches，不解密密码。 */
    @Bean
    public PasswordEncoder passwordEncoder(@Value("${login.password.bcrypt-strength:12}") int strength) {
        // 新写入哈希的成本限制在合理范围；已有哈希自带原工作因子。
        if (strength < 8 || strength > 16) {
            throw new IllegalArgumentException("login.password.bcrypt-strength 必须在 8～16 之间");
//            return
        }
        return new BCryptPasswordEncoder(strength);
    }
}
