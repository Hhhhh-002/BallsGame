package com.h.gatewayserver.annotions;

import com.h.gamecommon.command.GameCommands;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Spring Handler 的 cmd 声明，启动时注册到只读 Map。 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface GatewayCommand {
    /** 一个 Handler 可以接收多个 cmd，例如移动、分裂和吐球都由 Agent 路由。 */
    GameCommands[] value();
}
