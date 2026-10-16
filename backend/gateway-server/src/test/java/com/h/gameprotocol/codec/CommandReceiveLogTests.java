package com.h.gameprotocol.codec;

import com.google.protobuf.ByteString;
import com.h.gamecommon.command.*;
import com.h.gameprotocol.proto.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import static org.junit.jupiter.api.Assertions.*;

/** 仅检查接收日志，不发网络请求，也不记录任何 body/密码。 */
@ExtendWith(OutputCaptureExtension.class)
class CommandReceiveLogTests {
    // JDK System.Logger 默认走 JUL；每个测试重建 ConsoleHandler，避免它继续写向上一轮捕获流。
    @BeforeEach @AfterEach void resetConsoleHandler() throws java.io.IOException {
        java.util.logging.LogManager.getLogManager().readConfiguration();
    }
    @Test void logsClientHeaderWithoutCredentials(CapturedOutput output) {
        var credentials=LoginRequest.newBuilder().setAccount("private-account@example.com").setPassword("private-password").build();
        var request=ClientEnvelope.newBuilder().setCmd(GameCommands.LOGIN.code()).setRequestId(99)
                .setBody(credentials.toByteString()).build();
        CommandReceiveLog.game("gateway-server","Client WS",request);
        assertTrue(output.getAll().contains("cmd=LOGIN(1001), requestId=99"));
        assertFalse(output.getAll().contains("private-account"));assertFalse(output.getAll().contains("private-password"));
    }
    @Test void distinguishesServiceRequestResponseAndEvent(CapturedOutput output) {
        var request=ServiceEnvelope.newBuilder().setRequest(ServiceRequest.newBuilder()
                .setCmd(ServiceCommands.AUTH_LOGIN.code()).setRequestId(7).setBody(ByteString.copyFromUtf8("secret-body"))).build();
        var response=ServiceEnvelope.newBuilder().setResponse(ServiceResponse.newBuilder()
                .setCmd(ServiceCommands.AUTH_LOGIN.code()).setRequestId(7).setCode(40013).setMessage("secret-message")).build();
        var event=ServiceEnvelope.newBuilder().setEvent(ServiceEvent.newBuilder().setCmd(ServiceCommands.SCENE_TO_AGENT.code())).build();
        CommandReceiveLog.service("login-server","Gateway HTTP",request);
        CommandReceiveLog.service("gateway-server","Login HTTP",response);
        CommandReceiveLog.service("agent-server","Scene WS",event);
        var log=output.getAll();
        assertTrue(log.contains("type=服务请求, cmd=AUTH_LOGIN(10001)"));
        assertTrue(log.contains("type=服务响应, cmd=AUTH_LOGIN(10001), requestId=7, code=40013"));
        assertTrue(log.contains("type=服务事件, cmd=SCENE_TO_AGENT(10013), requestId=-"));
        assertFalse(log.contains("secret-body"));assertFalse(log.contains("secret-message"));
    }
    @Test void logsActualCommandsInsideForwardedPackets(CapturedOutput output) {
        var input=ClientEnvelope.newBuilder().setCmd(GameCommands.SHIFT.code()).setClientSequence(10).build();
        var move=ServerEnvelope.newBuilder().setCmd(GameCommands.MOVE.code()).build();
        CommandReceiveLog.nested("agent-server","Gateway WS",ForwardPlayerCommand.newBuilder().setClientMessage(input).build());
        CommandReceiveLog.nested("scene-server","Agent WS",ScenePlayerInput.newBuilder().setClientMessage(input).build());
        CommandReceiveLog.nested("agent-server","Scene WS",SceneToAgentEvent.newBuilder().setClientMessage(move).build());
        CommandReceiveLog.nested("gateway-server","Agent WS",ForwardToClientEvent.newBuilder().setClientMessage(move).build());
        var log=output.getAll();
        assertTrue(log.contains("service=agent-server, source=Gateway WS, type=游戏请求, cmd=SHIFT(2004)"));
        assertTrue(log.contains("service=scene-server, source=Agent WS, type=游戏请求, cmd=SHIFT(2004)"));
        assertTrue(log.contains("service=agent-server, source=Scene WS, type=游戏推送, cmd=MOVE(2001)"));
        assertTrue(log.contains("service=gateway-server, source=Agent WS, type=游戏推送, cmd=MOVE(2001)"));
        assertTrue(log.contains("clientSequence=10"));
    }
    @Test void unknownCommandsAndUnsignedIdsAreLoggedWithoutThrowing(CapturedOutput output) {
        var unknown=ClientEnvelope.newBuilder().setCmd(-1).setRequestId(-1).setClientSequence(-1)
                .setBody(ByteString.copyFromUtf8("do-not-print")).build();
        CommandReceiveLog.game("gateway-server","Client WS",unknown);
        assertTrue(output.getAll().contains("cmd=UNKNOWN(4294967295), requestId=18446744073709551615"));
        assertTrue(output.getAll().contains("clientSequence=18446744073709551615"));
        assertFalse(output.getAll().contains("do-not-print"));
    }
    @Test void gameResponseIsDifferentFromActivePush(CapturedOutput output) {
        CommandReceiveLog.game("agent-server","Scene WS",ServerEnvelope.newBuilder()
                .setCmd(GameCommands.SPLIT.code()).setRequestId(123).setCode(40107).build());
        assertTrue(output.getAll().contains("type=游戏响应, cmd=SPLIT(2002), requestId=123, code=40107"));
    }
}
