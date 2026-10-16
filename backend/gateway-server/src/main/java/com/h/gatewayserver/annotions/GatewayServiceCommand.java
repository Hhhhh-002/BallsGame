package com.h.gatewayserver.annotions;

import com.h.gamecommon.command.ServiceCommands;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 声明网关接收的服务间命令；与客户端使用的 @GatewayCommand 分开注册。 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface GatewayServiceCommand {
    /** 一个服务间 Handler 可以处理的命令编号。 */
    ServiceCommands[] value();
}
