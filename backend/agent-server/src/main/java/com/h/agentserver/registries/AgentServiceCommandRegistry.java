package com.h.agentserver.registries;

import com.h.agentserver.dispatch.handlers.AgentServiceHandler;
import com.h.gamecommon.annotation.ServiceCommand;
import com.h.gamecommon.command.ServiceCommands;
import com.h.gamecommon.dispatch.AnnotatedCommandRegistry;
import org.springframework.stereotype.Component;
import java.util.List;

/** 自动收集 Spring Handler Bean；缺注解、重复 cmd 均在启动时拒绝。 */
@Component
public final class AgentServiceCommandRegistry extends AnnotatedCommandRegistry<ServiceCommands, AgentServiceHandler> {
    public AgentServiceCommandRegistry(List<AgentServiceHandler> handlers) {
        super(handlers, ServiceCommand.class, ServiceCommand::value);
    }
}
