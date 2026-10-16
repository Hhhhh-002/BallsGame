package com.h.gatewayserver.registries;

import com.h.gamecommon.command.ServiceCommands;
import com.h.gatewayserver.annotions.GatewayServiceCommand;
import com.h.gatewayserver.dispatch.service.handlers.GatewayServiceHandler;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 只注册 Gateway 可接收的服务间命令，不影响客户端 GameCommands 的注册表。 */
@Component
public class GatewayServiceCommandRegistry {
    private final Map<ServiceCommands, GatewayServiceHandler> handlers;

    /** 启动时收集 Spring Handler Bean，并拒绝缺少注解或重复注册的 cmd。 */
    public GatewayServiceCommandRegistry(List<GatewayServiceHandler> beans) {
        Map<ServiceCommands, GatewayServiceHandler> registered = new HashMap<>();
        for (GatewayServiceHandler bean : beans) {
            Class<?> type = ClassUtils.getUserClass(bean);
            GatewayServiceCommand annotation = AnnotationUtils.findAnnotation(type, GatewayServiceCommand.class);
            if (annotation == null) {
                throw new IllegalStateException(type.getName() + " 缺少 @GatewayServiceCommand");
            }
            for (ServiceCommands command : annotation.value()) {
                GatewayServiceHandler previous = registered.putIfAbsent(command, bean);
                if (previous != null) {
                    throw new IllegalStateException("服务间 cmd " + command.code() + " 重复注册: "
                            + previous.getClass().getName() + " / " + type.getName());
                }
            }
        }
        handlers = Map.copyOf(registered);
    }

    /** 未注册的命令返回 null，由分发器返回未知命令错误。 */
    public GatewayServiceHandler get(ServiceCommands command) {
        return handlers.get(command);
    }
}
