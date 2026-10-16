package com.h.agentmgrserver.registries;

import com.h.agentmgrserver.annotation.AgentMgrCommand;
import com.h.agentmgrserver.dispatch.handlers.AgentMgrHandler;
import com.h.gamecommon.command.ServiceCommands;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Spring 管理对象和依赖，Map 只保存命令到同一 Bean 的引用，不为每次请求新建 Handler。 */
@Component
public class AgentMgrCommandRegistry {
    private final Map<ServiceCommands, AgentMgrHandler> handlers;

    public AgentMgrCommandRegistry(List<AgentMgrHandler> beans) {
        Map<ServiceCommands, AgentMgrHandler> registered = new EnumMap<>(ServiceCommands.class);
        for (AgentMgrHandler handler : beans) {
            Class<?> type = AopUtils.getTargetClass(handler);
            AgentMgrCommand annotation = AnnotationUtils.findAnnotation(type, AgentMgrCommand.class);
            if (annotation == null || annotation.value().length == 0) {
                throw new IllegalStateException(type.getName() + " 缺少 AgentMgr 命令注解");
            }
            for (ServiceCommands command : annotation.value()) {
                if (command == ServiceCommands.UNSPECIFIED || registered.putIfAbsent(command, handler) != null) {
                    throw new IllegalStateException("AgentMgr 命令无效或重复注册: " + command);
                }
            }
        }
        handlers = Map.copyOf(registered);
    }

    public AgentMgrHandler get(ServiceCommands command) { return handlers.get(command); }
}
