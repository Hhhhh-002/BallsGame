package com.h.agentmgrserver;

import com.h.agentmgrserver.service.PlayerLifecycleService;
import com.h.gamecommon.exception.AgentMgrException;
import com.h.gameprotocol.proto.BeginLoginRequest;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RequestIdValidationTests {
    @Test void acceptsCanonicalClientIdsAndLegacyInflightUuidButRejectsInvalidIds() {
        var service=mock(PlayerLifecycleService.class,CALLS_REAL_METHODS);
        var builder=BeginLoginRequest.newBuilder().setUserId("a@test.com").setGatewayInstanceId("gw");
        for(String id:new String[]{"12345","18446744073709551615","e2c89c68-c5be-4a9e-b627-e9d05b223922"})
            assertDoesNotThrow(()->ReflectionTestUtils.invokeMethod(service,"validateLogin",builder.setLoginOperationId(id).build()));
        for(String id:new String[]{"0","001","-1","","not-an-id"})
            assertThrows(AgentMgrException.class,()->ReflectionTestUtils.invokeMethod(service,"validateLogin",builder.setLoginOperationId(id).build()));
    }
}
