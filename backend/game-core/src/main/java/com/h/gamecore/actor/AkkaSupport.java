package com.h.gamecore.actor;

import akka.Done;
import akka.actor.*;
import akka.pattern.AskTimeoutException;
import akka.pattern.Patterns;
import com.h.gamecore.protocol.Messages;
import com.typesafe.config.ConfigFactory;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Akka 的应用接入工具：创建 ActorSystem、请求响应及异步回邮箱；没有自建队列或执行器。 */
public final class AkkaSupport {
    private AkkaSupport() {}
    public static ActorSystem system(String name,int threads) {
        if(threads<1)throw new IllegalArgumentException("Actor 线程数必须为正数");
        return ActorSystem.create(name,ConfigFactory.parseMap(Map.of(
                "akka.actor.default-dispatcher.executor","thread-pool-executor",
                "akka.actor.default-dispatcher.thread-pool-executor.fixed-pool-size",threads,
                "akka.actor.default-dispatcher.throughput",100,
                "akka.actor.allow-java-serialization","off",
                "akka.loglevel","WARNING")).withFallback(ConfigFactory.load()));
    }

    /** 外部只持有 ActorRef；默认业务等待上限与网关登录预算一致。 */
    public static <T> CompletableFuture<T> ask(ActorRef actor,Object message) {
        return ask(actor,message,Duration.ofSeconds(90));
    }
    @SuppressWarnings("unchecked")
    public static <T> CompletableFuture<T> ask(ActorRef actor,Object message,Duration timeout) {
        return Patterns.ask(actor,message,timeout).handle((value,failure)-> {
            if(failure!=null) {
                Throwable cause=Messages.unwrap(failure);
                if(cause instanceof AskTimeoutException) {
                    TimeoutException unavailable=new TimeoutException("等待 Actor 响应超时");
                    unavailable.initCause(cause);throw new CompletionException(unavailable);
                }
                throw new CompletionException(cause);
            }
            return value==Done.getInstance()?null:(T)value;
        }).toCompletableFuture();
    }

    /** 只用于 JVM 内的异步续接；状态逻辑作为消息投递回所属 Actor，不能直接在 IO 回调改状态。 */
    public record Continuation<T>(Supplier<T> action,CompletableFuture<T> result) {}
    public static <T> CompletableFuture<T> call(ActorRef self,Supplier<T> action) {
        CompletableFuture<T> result=new CompletableFuture<>();
        self.tell(new Continuation<>(action,result),self);
        return result;
    }
    public static CompletableFuture<Void> run(ActorRef self,Runnable action) {
        return call(self,()-> {action.run();return null;});
    }
    public static <T> void execute(Continuation<T> continuation) {
        if(continuation.result().isDone())return;
        try { continuation.result().complete(continuation.action().get()); }
        catch(Throwable failure) { continuation.result().completeExceptionally(failure); }
    }
    /** replyTo/self 必须在接收消息时捕获；回调中只发不可变响应，不访问 ActorContext。 */
    public static void reply(ActorRef replyTo,ActorRef self,Supplier<? extends CompletionStage<?>> operation) {
        try {
            operation.get().whenComplete((value,failure)->replyTo.tell(failure==null
                    ?(value==null?Done.getInstance():value):new Status.Failure(Messages.unwrap(failure)),self));
        } catch(Throwable failure) { replyTo.tell(new Status.Failure(Messages.unwrap(failure)),self); }
    }
    public static CompletableFuture<Void> stop(ActorRef actor,Object stopMessage) {
        return Patterns.gracefulStop(actor,Duration.ofSeconds(10),stopMessage).thenApply(stopped->(Void)null).toCompletableFuture();
    }
}
