package com.h.agentmgrserver.annotation;

import com.h.gamecommon.command.ServiceCommands;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** AgentMgr 的 cmd -> Spring Handler 声明；同一命令只能注册一个 Handler。 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface AgentMgrCommand { ServiceCommands[] value(); }
