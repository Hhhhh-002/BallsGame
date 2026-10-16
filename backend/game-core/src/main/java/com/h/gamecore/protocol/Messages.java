package com.h.gamecore.protocol;
import com.google.protobuf.Message;
import com.google.protobuf.InvalidProtocolBufferException;
import com.h.gamecommon.exception.UnknownCommandException;
import com.h.gamecommon.exception.InvalidCommandBodyException;
import com.h.gamecommon.code.GameError;
import com.h.gamecommon.command.*;
import com.h.gameprotocol.proto.*;
import java.util.concurrent.*;

/** 统一生成信封；内部请求关联 ID 与客户端 request_id 不混用。 */
public final class Messages {
    private Messages() {}
    public static ServiceEnvelope response(ServiceRequest request, Message body) {
        ServiceResponse.Builder b = ServiceResponse.newBuilder().setCmd(request.getCmd()).setRequestId(request.getRequestId());
        if (body != null) b.setBody(body.toByteString());
        return ServiceEnvelope.newBuilder().setResponse(b).build();
    }
    public static ServiceEnvelope error(ServiceRequest request, Throwable failure) {
        Throwable cause = unwrap(failure);
        GameError error = cause instanceof BattleException b ? b.error()
                : cause instanceof UnknownCommandException ? GameError.UNKNOWN_COMMAND
                : cause instanceof IllegalArgumentException || cause instanceof InvalidProtocolBufferException
                    || cause instanceof InvalidCommandBodyException ? GameError.INVALID_MESSAGE
                : cause instanceof RejectedExecutionException || cause instanceof TimeoutException
                    || cause instanceof java.io.IOException
                    ? GameError.SERVICE_UNAVAILABLE : GameError.INTERNAL_ERROR;
        return ServiceEnvelope.newBuilder().setResponse(ServiceResponse.newBuilder()
                .setCmd(request.getCmd()).setRequestId(request.getRequestId())
                .setCode(error.code()).setMessage(error.message())).build();
    }
    public static ServerEnvelope client(ClientEnvelope request, Message body) {
        ServerEnvelope.Builder b = ServerEnvelope.newBuilder().setCmd(request.getCmd()).setRequestId(request.getRequestId());
        if (body != null) b.setBody(body.toByteString());
        return b.build();
    }
    public static ServerEnvelope clientError(ClientEnvelope request, Throwable failure) {
        ServiceResponse r = error(ServiceRequest.newBuilder().build(), failure).getResponse();
        return ServerEnvelope.newBuilder().setCmd(request.getCmd()).setRequestId(request.getRequestId())
                .setCode(r.getCode()).setMessage(r.getMessage()).build();
    }
    public static ServiceEnvelope event(ServiceCommands cmd, Message body) {
        return ServiceEnvelope.newBuilder().setEvent(ServiceEvent.newBuilder().setCmd(cmd.code()).setBody(body.toByteString())).build();
    }
    public static ServerEnvelope push(GameCommands cmd, Message body) {
        return ServerEnvelope.newBuilder().setCmd(cmd.code()).setBody(body.toByteString()).build();
    }
    public static Throwable unwrap(Throwable t) {
        while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) t = t.getCause();
        return t;
    }
    public static void require(boolean condition, GameError error) {
        if (!condition) throw new BattleException(error);
    }
}
