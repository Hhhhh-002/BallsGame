package com.h.gatewayserver.dispatch;

import com.h.gamecommon.code.GameError;
import com.h.gamecommon.command.GameCommands;
import com.h.gameprotocol.codec.DecodedMessage;
import com.h.gameprotocol.proto.*;
import com.h.gatewayserver.context.GatewayContext;
import com.h.gatewayserver.dispatch.client.handlers.LoginGatewayHandler;
import com.h.gatewayserver.registries.GatewayChannelRegistry;
import com.h.gatewayserver.rpc.GatewayRpcClient;
import com.h.gatewayserver.rpc.agent.AgentLinkManager;
import com.h.gatewayserver.rpc.feign.AgentMgrDisconnectNotifierlCient;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LoginRequestIdTests {
    @Test void forwardsClientLoginIdInsteadOfGeneratingAnOperationUuid() throws Exception {
        var rpc=mock(GatewayRpcClient.class);
        when(rpc.login(any())).thenReturn(Mono.just(ServiceResponse.newBuilder().setCode(GameError.PASSWORD_INCORRECT.code()).build()));
        var channels=mock(GatewayChannelRegistry.class);
        var handler=new LoginGatewayHandler(rpc,mock(AgentLinkManager.class),channels,
                mock(AgentMgrDisconnectNotifierlCient.class),"gw",90000);
        var channel=new EmbeddedChannel();
        try {
            var credentials=LoginRequest.newBuilder().setAccount("a@test.com").setPassword("Test12345!").build();
            var request=ClientEnvelope.newBuilder().setCmd(GameCommands.LOGIN.code()).setRequestId(12345).setBody(credentials.toByteString()).build();
            handler.handle(new GatewayContext(channel),new DecodedMessage<>(request,GameCommands.LOGIN,credentials)).block();
            var captor=org.mockito.ArgumentCaptor.forClass(ServiceEnvelope.class);
            verify(rpc).login(captor.capture());
            var login=GatewayLoginRequest.parseFrom(captor.getValue().getRequest().getBody());
            assertEquals("12345",login.getLoginOperationId());
            verify(channels).beginLogin(eq("12345"),anyLong(),any());
        } finally {channel.finishAndReleaseAll();}
    }
}
