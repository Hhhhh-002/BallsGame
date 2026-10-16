package com.h.gamecommon.annotation;

import com.h.gamecommon.command.GameCommands;
import java.lang.annotation.*;

/** 声明 Handler 接收的游戏 cmd；请求和推送可以在各自注册器中使用同一注解。 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface GameCommand {
    GameCommands[] value();
}
