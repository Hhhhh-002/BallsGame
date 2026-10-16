# Gateway 协议与对接约定

客户端向 `/ws` 发送二进制 WebSocket 帧；每帧是一个 `ClientEnvelope`。网关先按 `cmd` 解析 `body`，再由 Spring 注册的 `GatewayCommandHandler` 分发。客户端响应和 Agent 推送均为 `ServerEnvelope`。

| 客户端 cmd | 请求 body | 网关处理 | 下游 |
| --- | --- | --- | --- |
| 1001 | `LoginRequest` | `LoginGatewayHandler` | Login Feign HTTP |
| 2002 | `SplitRequest` | `AgentGatewayHandler` | 指定 Agent 实例 WebSocket |
| 2003 | `EjectRequest` | `AgentGatewayHandler` | 指定 Agent 实例 WebSocket |
| 2004 | `ShiftRequest` | `AgentGatewayHandler` | 指定 Agent 实例 WebSocket |
| 2006 | `StartGameRequest` | `AgentGatewayHandler` | 指定 Agent 实例 WebSocket |

服务端推送使用 `ServerEnvelope`：`cmd=2001`（`MOVE`）时，`body` 为 `MoveBroadcast`。`MOVE` 不是客户端请求；客户端移动输入使用 `SHIFT=2004`。`LEAVE` 目前只有预留 cmd，没有请求消息和解码器，暂不接受。新增客户端命令时需同时定义 Protobuf 消息、`GameCommands`、解析器和 Handler 注册。

## 登录链路

Gateway 将 `GatewayLoginRequest` 放在 `ServiceEnvelope.request` 中，`cmd=AUTH_LOGIN`，经 Feign 调用 Nacos 服务 `login-server` 的 `POST /internal/game/dispatch`；请求和响应均使用 `application/x-protobuf`。Login 后续负责校验凭据并协调 AgentMgr/Agent，最终返回 `ServiceEnvelope.response`，成功时 `body` 为 `LoginBinding`。Gateway 不选择 Agent、不创建 Actor、不认证密码。

Gateway 校验并保存 `LoginBinding` 的 `user_id`、`player_actor_id`、`agent_host_service_name`、`agent_host_instance_id`。它按实例 ID 从 Nacos 找到指定 Agent 实例，建立内部 WebSocket，然后向客户端发送 `LoginResponse`。内部 Actor 和实例地址不会发送给客户端。

Gateway 为一次登录生成 `login_operation_id` 和 `login_deadline_ms`，Feign 业务重试复用原请求，Login 原样传给 AgentMgr；响应的操作 ID 必须匹配。登录默认总等待 90 秒、每跳有限重试，不叠加 Feign/LoadBalancer 隐式重试。AgentMgr 选定实例后不因创建超时重新分配，Actor ID 在创建前生成并保存。Agent 端幂等仍必须实现，不能仅靠 HTTP 请求关联 ID 防止重复创建。

同机优先分配依赖 Nacos `metadata.host-id`：Gateway 本地支持 `GAME_HOST_ID`，同机器的 Agent 配相同值，不同机器配不同值。未设置不猜测物理机器。

`user_id` 已统一为字符串注册账号，客户端 `LoginRequest.account` 的值就是该账号；网关按字符串 `userId` 索引连接，不再使用数字 0 判断未登录。所有服务间目标用户列表也使用字符串。此变更与旧 `uint64 user_id` 编码不兼容，客户端和各通信服务必须同步重新生成协议类、重新编译部署。请求 ID、场景 ID 和命令编号保持不变。

## Agent 长连接

一个 Gateway 进程到一个 Agent 实例复用一条双向 WebSocket，多个玩家的消息可共用它。Gateway 用 `ServiceEnvelope.request` 的 `FORWARD_PLAYER_COMMAND` 携带 `ForwardPlayerCommand`；Agent 应以 `player_actor_id` 找到 Actor，再用内层 `client_message.cmd` 解析玩家指令。此发送为单向操作，网关只保证帧写入，业务结果应由 Agent 以 `FORWARD_TO_CLIENT` 的 `ServiceEnvelope.event` 推回。Gateway 按 `target_user_ids` 查本机 `GatewayContext`，从中取 Channel 发给客户端，并核对推送来源是否是该玩家绑定的 Agent 实例。

每个 Agent 实例需要在 Nacos 注册 `instanceid` 和 `game.agent-ws-port` 元数据，并在该端口提供 `/internal/agent` WebSocket 入口。Gateway 建连时先发送 `AGENT_LINK_HELLO`，其中有本 Gateway 的实例 ID；Agent 必须回复相同 `cmd/request_id` 且 `code=0` 的 `ServiceEnvelope.response`，Gateway 才认为长连接可用。示例：

```yaml
spring:
  application:
    name: agent-server
  cloud:
    nacos:
      discovery:
        metadata:
          instanceid: agent-01
          game.agent-ws-port: 9101
```

当前已实现 Gateway、Login、AgentMgr 和共享协议；Login 通过 MyBatis-Plus 读取 `user_id + data` 账号表并解析 `UserProto` 校验密码，AgentMgr 通过 Redis/Redisson 仲裁生命周期并定向 Feign。Agent 的 HTTP/WebSocket 接收端与 Actor 加载、保存业务尚未实现，因此不能据此认定端到端登录或战斗已完成。部署及 Agent 幂等要求见 `../agentmgr-server/README.md`。生产部署时内部 WebSocket 还需限制网络访问并增加服务身份校验。
