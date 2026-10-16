package com.h.loginserver.annotation;

import com.h.gamecommon.command.ServiceCommands;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 将一个 Spring Handler Bean 绑定到 Login 支持的服务间命令。 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface LoginServiceCommand {
    /** 一个 Handler 可绑定多个命令；同一命令不能重复注册。 */
    ServiceCommands[] value();
}
