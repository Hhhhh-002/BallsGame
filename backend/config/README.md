# Nacos 连接配置与启动检查

Gateway、Login、AgentMgr、Agent、Scene 的本地配置统一使用 application.yaml，默认环境为 prod。
五个服务都必须导入同一命名空间内 DEFAULT_GROUP 的 global-prod.yaml；Login、AgentMgr、Scene 的专属配置是可选的，不需要为了启动再创建它们。
默认命名空间 ID 为 `eb7842a8-9b4e-4add-8516-83d7f1d01ee1`，新 Nacos 需要先创建它；或者为所有服务统一设置 `NACOS_NAMESPACE` 为实际命名空间 ID。只把配置放在 public 中无法供默认设置下的服务读取。
首次部署的完整配置、建表及逐服务启动步骤见[仓库首页](../../README.md#快速开始)。战斗参数示例位于[场景配置目录](../scene-server/config/nacos-global-battle.yaml)。

全局配置按下面的标准 Spring 属性填写，不需要新建手动读取配置的类：

- spring.datasource.url / username / password / driver-class-name：Login 和 Scene 自动创建 MySQL 数据源。
- spring.data.redis.host / port / password / database / timeout：AgentMgr、Agent 和 Scene 自动创建 Redis 连接。
- AgentMgr 的 StringRedisTemplate 复用 RedissonConnectionFactory，分布式锁和模板使用同一份 Redis 参数。
- Gateway 不访问 MySQL / Redis；它导入配置但不会仅因存在这些属性就创建数据库客户端。
- Agent、AgentMgr 不需要 MySQL；Login 不需要 Redis；nodemgr-server 是未使用的空模块，不在当前业务链路中。

MySQL 表应在 spring.datasource.url 指向的数据库创建，统一脚本是 ../agent-server/src/main/resources/db/schema.sql。
应用不自动建表，不自动迁移旧结构，不插入示例账号。
修改数据库/Redis 的地址或凭据后重启对应服务；refreshEnabled 不表示已创建的连接池一定能自动切换。

Nacos 启动地址可使用 NACOS_SERVER_ADDR（兼容 NACOS_ADDRESS），命名空间使用 NACOS_NAMESPACE，访问凭据使用 NACOS_USERNAME / NACOS_PASSWORD。
如果显式切换 SPRING_PROFILES_ACTIVE，需要在 Nacos 提供对应的 global-<环境>.yaml。
不要在全局配置中设置所有服务共用的 spring.application.name 或 server.port。

在仓库根目录 `BallsGame/`（包含 `backend/` 和 `frontend/`）执行普通测试：

```powershell
mvn -f backend/pom.xml -pl gateway-server,login-server,agentmgr-server,agent-server,scene-server -am clean test
```

开启真实环境检查（需要实际 Nacos、MySQL、Redis 和上述表；先停止正在运行的 Java 服务，避免测试监听相同 Netty 端口）：

```powershell
mvn -f backend/pom.xml -pl gateway-server,login-server,agentmgr-server,agent-server,scene-server -am clean test '-Dballs.infrastructure-test=true'
```

真实环境测试会启动各服务的 HTTP/Netty、确认 Nacos 配置源、执行 MySQL SELECT 1 / 表列检查和 Redis PING。
不向 Nacos 注册测试服务，不修改远程配置，不插入/删除数据库或 Redis 数据；测试进程退出后释放监听端口。

## 控制台接收指令日志

五个业务服务使用 INFO 级别的 CommandReceiveLog，在接收边界输出 `[收指令]`：服务名、接收通道、请求/响应/事件、cmd 名称与编号、无符号 requestId，移动请求额外打印 clientSequence，响应打印 code。
转发包同时输出外层服务命令和内层游戏命令，因此 Agent/Scene 能直接看到 SHIFT、SPLIT、EJECT，Agent/Gateway 能看到 MOVE、食物事件、快照和榜单等推送。
重试每收到一次就打印一次；转发包的内层命令只在该服务接收时打印，不按广播接收者或 Handler 执行次数重复打印。
不输出 body、完整 Protobuf 对象、邮箱、密码、密码哈希、响应原文或凭据。未知 cmd 也能打印；不可解析的客户端信封只说明无法确定 cmd。
日志不是业务成功证明：记录发生在校验/去重/Handler 执行之前。SHIFT/MOVE 全量打印会增加控制台开销，需要时可以在 Nacos 将 logging.level.com.h.gameprotocol.codec.CommandReceiveLog 改为 WARN。
nodemgr-server 当前是未使用的空模块，没有业务指令接收入口。上述日志不打印 Actor Tick、定时器任务或 WebSocket Ping/Pong 控制帧，它们不是 cmd 协议。
