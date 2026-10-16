# Login 服务

Login 是无状态的登录协调服务，不管理在线玩家、选 Agent 实例或操作客户端 Channel。

```text
Gateway --Feign AUTH_LOGIN--> Login HTTP Controller
  -> ServiceMessageDecoder -> cmd/Handler 注册表 -> AuthLoginHandler
  -> 校验邮箱和密码格式 -> MyBatis-Plus 按字符串 user_id 查询 game_account
  -> UserProto.parseFrom(data) -> 先检查禁用状态 -> BCrypt 校验密码
  -> Feign BEGIN_LOGIN -> AgentMgr（旧登录清理、选择 Agent、创建 Actor、加载玩家数据）
  <- ServiceResponse(code=0, body=LoginBinding)，或最终失败结果
  -> 成功时更新 UserProto.last_login_time 并序列化写回 data
Gateway <- ServiceResponse(cmd=AUTH_LOGIN, body=LoginBinding)
  -> 保存路由、确认 Agent 长连接就绪 -> 客户端 LoginResponse
```

## 对应 Lua 实现

- `s.client[cmd]`：Spring Handler Bean + `LoginServiceCommandRegistry` 只读 Map。
- `s.client.login`：`AuthLoginHandler`，请求命令为已有 `ServiceCommands.AUTH_LOGIN`。
- 密码判断：`AccountAuthenticationService`，不使用固定密码 123。
- `skynet.call(agentmgr, reqlogin, ...)`：`AgentMgrLoginRpcClient` 发 `BEGIN_LOGIN`。
- `sure_agent`：返回 `LoginBinding` 给原 Gateway HTTP 调用，由现有网关代码完成本地绑定；不另发回调。
- `send_by_fd`：不需要。HTTP 返回原调用方，Gateway 才负责向客户端 Channel 写消息。

## 文件职责

- `rpc/internalHttp/LoginInternalController`：接收 `/internal/game/dispatch` 的 Protobuf 请求。
- `dispatch/LoginServiceDispatcher`：复用公共解码器、查 Handler、统一错误响应。
- `annotation/LoginServiceCommand`、`registries/LoginServiceCommandRegistry`：启动时注册命令，重复编号阻止启动。
- `dispatch/handlers/AuthLoginHandler`：鉴权并等待 AgentMgr 最终结果。
- `entity/UserEntity`：只映射数据库 `user_id`、`data` 两列，不重复声明用户业务字段。
- `mapper/UserMapper`：MyBatis-Plus `BaseMapper`，提供账号表主键查询、插入和更新。
- `validator/UserProtoCodecValidator`：`byte[]` 与 `UserProto` 的转换、字段及主键一致性校验。
- `account/AccountRepository`：读取 Protobuf 账号实体、供注册流程调用的插入方法、成功登录时间更新。
- `validator/LoginCredentialsValidator`：查库前检查非空、邮箱格式及密码复杂度，不使用 MVC 拦截器。
- `account/AccountAuthenticationService`、`config/PasswordConfiguration`：BCrypt 密码验证。
- `rpc/feign/client/AgentMgrFeignClient`：按 Nacos 服务名调用单例 `agentmgr-server`。

本模块沿用 Spring MVC，不使用 Gateway 的 Netty EventLoop。数据库、BCrypt 和阻塞式 Feign 在 HTTP 工作线程执行；每个调用只返回最终结果，不额外增加线程池、Mono 订阅或轮询。

## 登录凭据校验

格式校验位于 `AccountAuthenticationService.authenticate()` 的查询之前，不重新读取或解析 HTTP/Protobuf 请求体：

- 账号和密码均不能为空或全部为空白。
- 账号必须是常见非引号 ASCII 邮箱格式，最多 64 字符，与现有 `user_id` 字段长度一致；本地部分不允许首尾点或连续点，域名标签不能以连字符开头或结尾。邮箱格式校验不证明邮箱存在或所有权。
- 密码长度大于 8 位，即至少 9 个字符，同时含英文字母、数字和特殊符号（标点或符号，例如 `Abcde123!`）；不强制大小写同时出现，不允许空白、控制字符或不可见类别。
- 密码 UTF-8 编码最多 72 字节，保留 BCrypt 边界；不 trim、不自动改大小写。
- 查到用户后，先检查 `disabled`，禁用账号不执行 BCrypt 比对；不存在的账号直接返回。封禁、账号不存在和密码不匹配分别返回具体错误，不再执行占位哈希比对。

业务异常继续由 `LoginServiceDispatcher` 统一转换成 Protobuf `ServiceResponse`，不新增重复的 `@RestControllerAdvice` 业务异常处理。分发器保留本跳请求的 cmd/request_id；数据库故障返回“服务暂不可用”，数据损坏和其他内部异常返回“服务内部错误”，不公开异常详情。

| 错误码 | 含义 / 客户端提示 |
| --- | --- |
| 40010 | 请输入正确的邮箱 |
| 40011 | 密码必须包含数字、字母和特殊字符，长度必须大于8位，且不能包含空白 |
| 40012 | 账号不存在 |
| 40013 | 密码错误 |
| 40014 | 账号已被封禁 |
| 40015 | 请输入邮箱 |
| 40016 | 请输入密码 |
| 40017 | 邮箱长度不能超过64个字符 |
| 40018 | 密码UTF-8编码长度不能超过72字节 |

Gateway 的 `LoginGatewayHandler` 按白名单把上述错误码映射为本地公共提示，不直接透传远端 message。未知内部错误码仍返回“登录失败”；旧的账号密码合并错误码保留兼容。邮箱、密码校验统一放在 Login，不在 Gateway 重复做非空检查；Validator 的每个字段方法只检查自己的字段一次。

明确返回“账号不存在”与“密码错误”会暴露账号存在性，这是产品要求的取舍；后续可配合限流、验证码和登录失败次数限制。本次不增加这些机制，注册复用已有公共校验方法。

后续注册、修改密码流程应在生成哈希前复用 Validator。数据库哈希不能用于检查原始密码复杂度；不符合新规则的旧密码即使哈希匹配也会被当前登录校验拒绝，需要通过受控重置流程处理。

## 启动前准备

1. 启动 MySQL，建表脚本统一位于 `../agent-server/src/main/resources/db/schema.sql`，账号表是其中第 1 段。在 Nacos 的 `spring.datasource.url` 指向的数据库中执行；本地不再默认连接 `3306/balls_game`。已有账号表结构不一致时先备份并单独迁移，不直接修改生产数据。
2. 在 Nacos `global-prod.yaml`（DEFAULT_GROUP）配置 `spring.datasource.url/username/password/driver-class-name`；Login 通过 Boot / MyBatis-Plus 自动创建数据源，不需要手动读取配置或新建数据源。可选 `login-server-prod.yaml` 能覆盖全局同名项。Login 需要账号表查询、插入、更新权限，不需要建表或删除表权限。
3. 数据库仅有 `user_id VARCHAR(64)` 和 `data BLOB` 两列；`data` 是完整 `UserProto` 的二进制字节，不是 Base64、JSON 或普通字符串。`UserProto.password` 存完整 BCrypt 哈希（如 `$2a$...`），不加 `{bcrypt}` 前缀。注册调用 `PasswordEncoder.encode(rawPassword)`，登录用 `matches(rawPassword, storedHash)`；不自动写入测试账号。
4. 启动 Nacos，确认通信服务使用相同的 discovery namespace/group。全部业务服务默认 `prod`，导入 `global-prod.yaml`；如显式修改 `SPRING_PROFILES_ACTIVE`，必须提供对应环境的全局配置。全局配置必需，Login 专属配置可选，不要在全局配置中设置所有服务共用的 HTTP 端口。
5. 启动 AgentMgr 与 Agent：AgentMgr 仲裁玩家登录/下线，Redis 保存请求去重及原目标，在线状态在内存；Agent 创建/停止 Actor，没有额外玩家资料 MySQL 表。最终 `LoginBinding` 返回 Gateway，配置见各服务 README。

Login 默认 HTTP 端口 8081；多实例通过 `LOGIN_HTTP_PORT` 分别设置端口，服务名仍为 `login-server`，实例标识通过 `LOGIN_INSTANCE_ID` 设置。Gateway 不需要手动保存 Login 地址。

本地启动配置统一为 `application.yaml`，旧 `application.properties` 已合并移除，避免它将环境重新覆盖为 dev。MySQL 连接参数只来自 Nacos（或显式的标准 Spring 启动参数）；连接池大小和 SQL 超时保留本地默认值。Nacos 配置变更可进入 Environment，但正在使用的数据库/Redis 连接池不保证热切换，修改地址或凭据后应重启对应服务。

## Protobuf 用户实体与两列存储

定义在 `game-protocol/src/main/proto/user.proto`，生成 `com.h.gameprotocol.persistence.UserProto`。

| UserProto 字段 | 含义 |
| --- | --- |
| `user_id` | 字符串注册账号，同时是用户 ID；必须与数据库外层主键完全一致 |
| `password` | BCrypt 密码哈希，不是客户端提交的明文密码 |
| `disabled` | 0 允许登录，1 禁止登录；其他值拒绝存取 |
| `last_login_time` | 最近成功登录的 Unix 毫秒时间戳，0 表示尚未成功登录 |
| `create_time` | 创建记录的 Unix 毫秒时间戳，插入时未填则由 Repository 补当前时间 |

`UserProto` 是持久化实体，`UserEntity` 仅是 MyBatis-Plus 的行映射。数据库 `user_id` 用于索引查询，`data` 内保留相同 ID 用于完整实体及读写一致性校验。账号主键采用 `IdType.INPUT`，不自增、不自动生成雪花 ID。Protobuf 序列化不是加密，禁止将包含密码哈希的 `UserProto` 发给客户端；客户端仍只收到公开的 `LoginResponse`。

注册 Handler 已调用此插入流程；以下示例仅说明内部实体构建，不会自动执行写库：

```java
String hash = passwordEncoder.encode(rawPassword);
UserProto user = UserProto.newBuilder()
        .setUserId(userId)
        .setPassword(hash)
        .setDisabled(0)
        .build();
accountRepository.insert(user); // 自动补 create_time，last_login_time 默认为 0。
```

最近登录时间仅在 AgentMgr 返回有效的最终成功路由后更新，不在账号校验刚通过时更新。更新从当前 `data` 构建新 Protobuf，再用 `WHERE user_id = ? AND data = ?` 比较旧快照后写回，避免覆盖并发改密码或禁用状态；冲突时最多重读三次，这不是登录 RPC 重试。若时间写入失败，Login 返回失败，但 AgentMgr 可能已经完成分配，不能据此认定 Actor 未创建；跨服务补偿仍需由登录生命周期方案处理。

新 SQL 只适用于新建两列表。`CREATE TABLE IF NOT EXISTS` 不会把已有三列结构变成两列，也不会转换旧数据。本次不连接数据库、不执行 ALTER/DROP、不自动迁移。已有账号数据需先备份，再由独立迁移流程把原字段构建成带创建时间的 `UserProto`。

## 字符串用户 ID 协议变更

Login、Gateway 与公共 `game.proto` 中所有 `user_id` / `target_user_ids` 现统一成字符串。`LoginRequest.account` 沿用字段名，但值就是注册账号，不再建立第二个数字用户 ID；网关索引改为 `Map<String, GatewayContext>`，未登录使用空字符串，不以 0 判断。

`uint64 -> string` 属于不兼容的 Protobuf 编码变更：所有客户端和通信服务都需使用新的协议生成类一起重新编译部署，旧编码消息不能与新编码混用。请求 ID、Scene ID、帧序号、服务命令编号没有修改。Agent/Scene 现已实现基础生命周期与战斗链路，仍需真实环境联调。

## 邮箱注册

```text
客户端 REGISTER(1002) / RegisterRequest
  -> RegisterGatewayHandler
  -> FeignGatewayRpcClient.register（工作池，不阻塞 Netty）
  -> 已有 LoginInternalController / LoginServiceDispatcher
  -> REGISTER_ACCOUNT(10018) / RegisterAccountHandler
  -> AccountRegistrationService：校验、查重、BCrypt、INSERT
  <- RegisterResponse(user_id)，Gateway 回到原客户端
```

不新增重复 HTTP Controller，原 `/internal/game/dispatch` 按服务命令分发。注册先复用邮箱与密码规则，再检查邮箱是否存在；存在返回 `40022 / 该邮箱已被注册`。并发请求都查到不存在时，由 `game_account.user_id` 主键最终保证唯一，`DuplicateKeyException` 映射为同一错误，绝不覆盖原密码。邮箱大小写敏感，沿用现有账号表，不擅自更改旧账号匹配规则。

新账号 `disabled=0`、`create_time=当前 Unix 毫秒`、`last_login_time=0`；客户端不能传禁用状态或时间。只返回用户 ID，不返回密码哈希。注册不调用 AgentMgr、不创建 PlayerActor、不自动登录；玩家默认资料仍在第一次成功登录时由 Agent 初始化。

Gateway 的注册等待预算默认 10 秒，由 `gateway.rpc.register-timeout-ms` 配置，网页默认 12 秒。注册没有业务幂等操作 ID，不增加自动重放；网络失败可能发生在 INSERT 成功后，应提示结果未确认并允许尝试登录，而不是认定账号未创建。数据库主键保证重发不会创建第二个账号，但不会把已有邮箱伪装成注册成功。原登录、下线重试逻辑不变。

尚未提供邮箱所有权验证、验证码或注册频率限制。生产开放注册前应配合 WSS、内部受保护 HTTP 链路和入口限流。注册不需要 AgentMgr、Redis 或 Scene；账号表必须存在，Login 的数据库用户需要 INSERT 权限。

## 登录超时与故障边界

Login -> AgentMgr 默认连接超时 1 秒、单次读取上限 65 秒，业务封装最多三次尝试。Feign/LoadBalancer 隐式重试仍关闭，不代表业务重试关闭。相同操作的所有尝试复用 `login_operation_id`，AgentMgr 保留原实例和 Actor ID；Agent 端必须实现持久幂等，不能把网络超时当作未创建。

Gateway 的登录等待预算为 90 秒，Gateway -> Login 单次读取上限 85 秒。`login_deadline_ms` 从 Gateway 原样传到 AgentMgr，动态 Feign Options 不延长端到端等待期限。AgentMgr 等待超时不清除操作；Gateway 在绑定前断开时，迟到成功结果会触发下线，结果丢失由 Agent 的玩家级存活核对补偿。远程 Nacos 可能覆盖本地配置，部署前请核对最终生效值。

Login 返回细分鉴权错误或 AgentMgr 最终失败；Gateway 按公共错误白名单展示对应提示，未知内部码降级为 `LOGIN_FAILED`，不原样公开内部 message。

内部 HTTP 入口目前沿用项目的受信服务网络约定；`gateway_instance_id` 是路由信息，不是鉴权凭证。部署时需通过内网访问控制或服务身份校验保护入口；账号登录还需在入口实施限流。跨机器传递密码必须使用受保护的传输通道，不能把公网明文 HTTP 当作安全链路。

## 构建

在父工程目录执行 `mvn -pl agentmgr-server,login-server,gateway-server -am -DskipTests compile`。编译不连接数据库或 Nacos；真正登录还依赖 Redis、数据库账号记录和 Agent 接口实现。

Boot 版本与现有 Gateway 对齐到 4.0.0，Cloud 2025.1.0，Alibaba 2025.1.0.0；未修改其他服务的版本。

## 参考

- [Spring Cloud OpenFeign：超时、服务发现及重试配置](https://docs.spring.io/spring-cloud-openfeign/reference/spring-cloud-openfeign.html)
- [Spring Cloud Alibaba：Nacos 配置导入](https://sca.aliyun.com/en/docs/2025.x/user-guide/nacos/quick-start/)
- [Spring Security：密码哈希与校验](https://docs.spring.io/spring-security/reference/7.0/features/authentication/password-storage.html)
- [MyBatis-Plus：Boot 4 专用 Starter 与 BaseMapper](https://baomidou.com/en/getting-started/)
