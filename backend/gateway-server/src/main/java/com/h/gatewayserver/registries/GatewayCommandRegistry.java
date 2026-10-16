package com.h.gatewayserver.registries;

import com.h.gamecommon.command.GameCommands;
import com.h.gatewayserver.annotions.GatewayCommand;
import com.h.gatewayserver.dispatch.client.handlers.GatewayCommandHandler;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 借鉴原项目 cmd -> HandlerExecutor 注册；直接保存 Spring Bean，避免每条消息反射调用。 */
@Component
public class GatewayCommandRegistry {
    private final Map<GameCommands, GatewayCommandHandler> handlers;

//    构造器注入  在程序启动后可以直接用
    /** 注册全部 Spring Handler Bean，并在启动时检查 cmd 重复。 */
    public GatewayCommandRegistry(List<GatewayCommandHandler> beans) {
        Map<GameCommands, GatewayCommandHandler> registered = new HashMap<>();
        // Spring 收集所有 Handler Bean；检查注解和重复 cmd，启动失败比运行时漏分发更容易发现。
        for (GatewayCommandHandler bean : beans) {
            Class<?> type = ClassUtils.getUserClass(bean);
            GatewayCommand annotation = AnnotationUtils.findAnnotation(type, GatewayCommand.class);
            if (annotation == null) {
                throw new IllegalStateException(type.getName() + " 缺少 @GatewayCommand");
            }
            // 一个Handler可以绑定多个cmd（annotation.value()是GameCommands数组）
            for (GameCommands cmd : annotation.value()) {
                GatewayCommandHandler previous = registered.putIfAbsent(cmd, bean);
                if (previous != null) {
                    // 同一个cmd已经被别的Handler注册过 → 冲突，启动直接崩溃
                    throw new IllegalStateException("cmd " + cmd.code() + " 重复注册: "
                            + previous.getClass().getName() + " / " + type.getName());
                }
            }
        }
        this.handlers = Map.copyOf(registered);
    }

    /** 根据客户端 cmd 常数时间取得处理器。 */
    public GatewayCommandHandler get(GameCommands cmd) {
        return handlers.get(cmd);
    }
}
