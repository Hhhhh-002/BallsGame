package com.h.loginserver.registries;

import com.h.gamecommon.command.ServiceCommands;
import com.h.loginserver.annotation.LoginServiceCommand;
import com.h.loginserver.dispatch.handlers.LoginServiceHandler;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** 启动时建立 cmd -> Spring Handler 实例的只读索引，代替逐个 if 分发。 */
@Component
public class LoginServiceCommandRegistry {
    /** Spring 创建 Handler 并注入依赖，Map 仅保存这些 Bean 的引用。 */
    private final Map<ServiceCommands, LoginServiceHandler> handlers;

    /** 自动收集实现接口的 Bean，配置遗漏或命令冲突时直接阻止启动。 */
    public LoginServiceCommandRegistry(List<LoginServiceHandler> beans) {
        Map<ServiceCommands, LoginServiceHandler> registered = new EnumMap<>(ServiceCommands.class);
        for (LoginServiceHandler bean : beans) {
            Class<?> type = AopUtils.getTargetClass(bean);
            LoginServiceCommand annotation = AnnotationUtils.findAnnotation(type, LoginServiceCommand.class);
            if (annotation == null || annotation.value().length == 0) {
                throw new IllegalStateException(type.getName() + " 缺少有效的 @LoginServiceCommand");
            }
            for (ServiceCommands command : annotation.value()) {
                if (command == ServiceCommands.UNSPECIFIED) {
                    throw new IllegalStateException(type.getName() + " 不能注册 UNSPECIFIED 命令");
                }
                LoginServiceHandler previous = registered.putIfAbsent(command, bean);
                if (previous != null) {
                    throw new IllegalStateException("Login cmd " + command.code() + " 重复注册: "
                            + AopUtils.getTargetClass(previous).getName() + " / " + type.getName());
                }
            }
        }
        handlers = Map.copyOf(registered);
    }

    /** 不属于本服务的命令返回 null，由分发器生成 UNKNOWN_COMMAND 响应。 */
    public LoginServiceHandler get(ServiceCommands command) {
        return handlers.get(command);
    }
}
