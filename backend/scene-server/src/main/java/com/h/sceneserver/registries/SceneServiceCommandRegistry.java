package com.h.sceneserver.registries;

import com.h.sceneserver.dispatch.handlers.SceneServiceHandler;
import com.h.gamecommon.annotation.ServiceCommand;
import com.h.gamecommon.command.ServiceCommands;
import com.h.gamecommon.dispatch.AnnotatedCommandRegistry;
import org.springframework.stereotype.Component;
import java.util.List;

/** 自动收集 Spring Handler Bean；缺注解、重复 cmd 均在启动时拒绝。 */
@Component
public final class SceneServiceCommandRegistry extends AnnotatedCommandRegistry<ServiceCommands, SceneServiceHandler> {
    public SceneServiceCommandRegistry(List<SceneServiceHandler> handlers) {
        super(handlers, ServiceCommand.class, ServiceCommand::value);
    }
}
