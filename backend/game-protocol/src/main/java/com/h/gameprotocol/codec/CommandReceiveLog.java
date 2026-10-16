package com.h.gameprotocol.codec;

import com.google.protobuf.Message;
import com.h.gamecommon.command.GameCommands;
import com.h.gamecommon.command.ServiceCommands;
import com.h.gameprotocol.proto.*;

/** 各服务接收入口共用的指令日志：只取信封字段，绝不打印 body 或整个 Protobuf 对象。 */
public final class CommandReceiveLog {
    private static final System.Logger LOG = System.getLogger(CommandReceiveLog.class.getName());
    private CommandReceiveLog() {}
    public static void game(String service, String source, ClientEnvelope request) { write(gameLine(service,source,request)); }
    public static void game(String service, String source, ServerEnvelope message) { write(gameLine(service,source,message)); }
    public static void service(String service, String source, ServiceEnvelope envelope) { write(serviceLine(service,source,envelope)); }

    /** 复用已解码的转发包，不为日志重新解析 body，不影响 Handler 注册或分发。 */
    public static void nested(String service, String source, Message body) {
        if (body instanceof ForwardPlayerCommand forward && forward.hasClientMessage()) game(service,source,forward.getClientMessage());
        else if (body instanceof ScenePlayerInput input && input.hasClientMessage()) game(service,source,input.getClientMessage());
        else if (body instanceof SceneToAgentEvent event && event.hasClientMessage()) game(service,source,event.getClientMessage());
        else if (body instanceof ForwardToClientEvent event && event.hasClientMessage()) game(service,source,event.getClientMessage());
    }
    public static void unreadable(String service, String source) {
        write("[收指令] service="+service+", source="+source+", 无法解析信封，不能确定 cmd/requestId");
    }
    private static void write(String line) { LOG.log(System.Logger.Level.INFO,line); }
    static String gameLine(String service,String source,ClientEnvelope request) {
        var command=GameCommands.fromCode(request.getCmd());
        return line(service,source,"游戏请求",command==null?"UNKNOWN":command.name(),request.getCmd(),
                Long.toUnsignedString(request.getRequestId()),"-")
                +", clientSequence="+Long.toUnsignedString(request.getClientSequence());
    }
    static String gameLine(String service,String source,ServerEnvelope message) {
        var command=GameCommands.fromCode(message.getCmd());
        return line(service,source,message.getRequestId()==0?"游戏推送":"游戏响应",
                command==null?"UNKNOWN":command.name(),message.getCmd(),Long.toUnsignedString(message.getRequestId()),
                Integer.toString(message.getCode()));
    }
    static String serviceLine(String service,String source,ServiceEnvelope envelope) {
        int cmd; String type,requestId,code="-";
        if(envelope.hasRequest()) {
            cmd=envelope.getRequest().getCmd();type="服务请求";requestId=Long.toUnsignedString(envelope.getRequest().getRequestId());
        } else if(envelope.hasResponse()) {
            cmd=envelope.getResponse().getCmd();type="服务响应";requestId=Long.toUnsignedString(envelope.getResponse().getRequestId());
            code=Integer.toString(envelope.getResponse().getCode());
        } else if(envelope.hasEvent()) {
            cmd=envelope.getEvent().getCmd();type="服务事件";requestId="-";
        } else return "[收指令] service="+service+", source="+source+", 空服务信封";
        var command=ServiceCommands.fromCode(cmd);
        return line(service,source,type,command==null?"UNKNOWN":command.name(),cmd,requestId,code);
    }
    private static String line(String service,String source,String type,String command,int cmd,String requestId,String code) {
        return "[收指令] service="+service+", source="+source+", type="+type+", cmd="+command
                +"("+Integer.toUnsignedString(cmd)+"), requestId="+requestId+", code="+code;
    }
}
