package com.h.loginserver.rpc.internalHttp;

import com.h.loginserver.dispatch.LoginServiceDispatcher;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Gateway 的 LoginFeignClient 所调用的服务端 HTTP 入口。 */
@RestController
public class LoginInternalController {
    private final LoginServiceDispatcher dispatcher;

    /** Controller 只负责接收和响应，不执行账号校验或 Agent 分配。 */
    public LoginInternalController(LoginServiceDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    /** 复用网关约定的路径和 MIME；返回的是同一次 HTTP 调用的最终响应。 */
    @PostMapping(value = "/internal/game/dispatch",
            consumes = "application/x-protobuf", produces = "application/x-protobuf")
    public byte[] dispatch(@RequestBody byte[] request) {
        return dispatcher.dispatch(request);
    }
}
