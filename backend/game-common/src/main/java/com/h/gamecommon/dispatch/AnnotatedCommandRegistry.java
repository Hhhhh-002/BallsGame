package com.h.gamecommon.dispatch;

import java.lang.annotation.Annotation;
import java.util.*;
import java.util.function.Function;

/** 公共注册逻辑：发现注解、拒绝重复命令，运行时仅查只读 Map。 */
public abstract class AnnotatedCommandRegistry<C extends Enum<C>, H> {
    private final Map<C, H> handlers;

    protected <A extends Annotation> AnnotatedCommandRegistry(List<? extends H> beans,
            Class<A> annotationType, Function<A, C[]> commands) {
        Map<C, H> registered = new HashMap<>();
        for (H bean : beans) {
            A annotation = null;
            // 向父类查找，兼容带注解业务类的类代理。
            for (Class<?> type = bean.getClass(); type != null && annotation == null; type = type.getSuperclass()) {
                annotation = type.getDeclaredAnnotation(annotationType);
            }
            if (annotation == null) throw new IllegalStateException(bean.getClass().getName()
                    + " 缺少 @" + annotationType.getSimpleName());
            C[] declared = commands.apply(annotation);
            if (declared.length == 0) throw new IllegalStateException("Handler 未声明 cmd: " + bean.getClass().getName());
            for (C command : declared) {
                H previous = registered.putIfAbsent(command, bean);
                if (previous != null) throw new IllegalStateException("重复注册 cmd " + command
                        + ": " + previous.getClass().getName() + " / " + bean.getClass().getName());
            }
        }
        handlers = Map.copyOf(registered);
    }

    public final H get(C command) { return command == null ? null : handlers.get(command); }
}
