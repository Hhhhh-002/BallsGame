package com.h.gamecommon.annotation;

import com.h.gamecommon.command.ServiceCommands;
import java.lang.annotation.*;

/** 声明 Handler 接收的服务间 cmd，启动时注册，不逐条反射调用。 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ServiceCommand {
    ServiceCommands[] value();
}
