package com.h.agentserver.registries;

import com.h.agentserver.dispatch.handlers.AgentGameHandler;
import com.h.gamecommon.annotation.GameCommand;
import com.h.gamecommon.command.GameCommands;
import com.h.gamecommon.dispatch.AnnotatedCommandRegistry;
import org.springframework.stereotype.Component;
import java.util.List;

/** 自动收集 Spring Handler Bean；缺注解、重复 cmd 均在启动时拒绝。 */
@Component
public final class AgentGameCommandRegistry extends AnnotatedCommandRegistry<GameCommands, AgentGameHandler> {
    public AgentGameCommandRegistry(List<AgentGameHandler> handlers) {
        super(handlers, GameCommand.class, GameCommand::value);
    }
}
