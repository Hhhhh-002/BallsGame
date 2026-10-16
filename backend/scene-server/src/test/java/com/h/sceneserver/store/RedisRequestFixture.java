package com.h.sceneserver.store;

import org.springframework.data.redis.core.*;
import org.springframework.mock.env.MockEnvironment;
import java.util.*;
import java.util.concurrent.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 仅替换 Redis 传输，执行真实 SceneRequestStore；不宣称外部 Redis 集成测试。 */
public final class RedisRequestFixture {
    public final StringRedisTemplate redis=mock(StringRedisTemplate.class);
    public final HashOperations<String,Object,Object> hashes=mock(HashOperations.class);
    public final Map<String,Map<Object,Object>> data=new ConcurrentHashMap<>();
    public final ExecutorService io=mock(ExecutorService.class);
    public RedisRequestFixture() {
        when(redis.opsForHash()).thenReturn(hashes);
        doAnswer(call->{((Runnable)call.getArgument(0)).run();return null;}).when(io).execute(any());
        when(hashes.putIfAbsent(anyString(),any(),any())).thenAnswer(call->data.computeIfAbsent(call.getArgument(0),key->new ConcurrentHashMap<>())
                .putIfAbsent(call.getArgument(1),call.getArgument(2))==null);
        when(hashes.get(anyString(),any())).thenAnswer(call->data.getOrDefault(call.getArgument(0),Map.of()).get(call.getArgument(1)));
        doAnswer(call->{data.computeIfAbsent(call.getArgument(0),key->new ConcurrentHashMap<>()).put(call.getArgument(1),call.getArgument(2));return null;})
                .when(hashes).put(anyString(),any(),any());
    }
    public SceneRequestStore store() {return new SceneRequestStore(redis,io,new MockEnvironment());}
}
