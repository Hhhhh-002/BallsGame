# Agent 服务

一个 Agent 实例管理多个 Akka PlayerActor；一次玩家登录对应一个 Actor 和一份 PlayerContext。没有自建 Actor 框架，也不依赖 MySQL 玩家资料/Actor 表。

## 状态放在哪里

- PlayerContext：原登录身份、Gateway 路由、当前 Scene 绑定、本局昵称、正在进行的切场任务和消息上下文。可变字段只在所属 Actor 邮箱访问。
- MessageContext：processing 保存原请求和共享 Future；responses 保存原响应。key 直接是客户端 requestId，不同玩家会话各自隔离。
- Redis：只保存原请求、固定调用目标、最终响应和核实重试所需的标记，不保存 Future 或另一份在线列表。
- Scene：权威球/食物/比赛状态和最终战绩。Agent 不计算碰撞、不保存每局排名到用户资料。

## 调用链路

Gateway 内部 WS → AgentServiceDispatcher → 对应 PlayerActor 邮箱 → 去重/按 cmd 查注册 Handler → 执行业务。

AgentMgr 的 Feign 调用 `POST /internal/game/dispatch` 创建或停止固定 Actor。广播走 Scene → PlayerActor → Gateway → 客户端。Spring Handler 不持有玩家状态；异步 I/O 完成后的状态修改投递回 Actor 邮箱。

SceneLinkManager 按 Nacos 健康实例权重和同机倍率选择 Scene；FIND_SCENE/JOIN/LEAVE 及重试始终固定原实例。本局路由存在 Context，原入场请求的目标同时保存在 Redis 请求记录，供重试核对。

## 创建、下线与正常重试

创建请求原参数和最终响应保存在 `balls:agent:requests:lifecycle:<Base64URL ActorID>` 的 `_create` 字段。重复创建参数必须一致，复用原 Actor 和 Context。STOP 的原请求及真实停止结果在同一请求容器的 `_stopRequest/_stopResult`，不是账号资料。

下线顺序：拒绝新输入 → 等正在入场/离场的任务结束 → 向原 Scene 确认离场 → 等 Akka gracefulStop 确认 Actor 真正终止 → 把最终停止响应存 Redis → 回 AgentMgr。没有需要 Agent 保存的全局游戏资料；每局战绩由 Scene 结算保存。

内部 `SAVED_AND_STOPPED` 枚举名保留兼容，当前含义是离场完成、Actor 实际停止并已保存停止响应，不表示写了不存在的玩家资料表。重复下线重放原结果；在去重缓存窗口内，迟到的创建请求不能复活已停止的 Actor。

Actor 和 Context 只根据新的创建请求建立。转发或下线时找不到本机 Actor 就返回不存在；重复请求若已有最终结果，只重放原响应，不补建 Actor。当前场景绑定只在 Context，Redis 原入场请求只保存原调用目标，不另存 _scene、released 或恢复快照。

创建/停止请求只用于正常重试去重。Actor 真正停止且最终响应存 Redis 后，控制请求和游戏请求默认保留一天。不为重启永久保留记录，也不实现启动恢复。

## 注册的游戏 Handler

START_GAME、LEAVE、SHIFT、SPLIT、EJECT、MATCH_HISTORY 按 @GameCommand 注册。Scene 推送也按 cmd 查对应 Handler，业务入口不追加一堆命令 if。

START_GAME/LEAVE 的原请求及最终响应保存到 Redis Hash，field 是 requestId。提交失败时本地保留原响应，重试只补提交。SPLIT/EJECT 在 Scene 去重并执行，Agent 等待真实业务响应，不能把 WS 写入成功当作动作成功。SHIFT 用递增 client_sequence 丢弃过期方向，不为每条方向输入缓存响应。

MATCH_HISTORY（1003）查询当前 Context 用户自己的历史，不接受客户端指定其他 userId；Gateway → PlayerActor → 任一 Scene → 共享战绩库。查询没有副作用，不占切场任务，也不缓存过时结果。分页 request limit 默认 20、最大 100，cursor 原样使用上页返回值；按记录主键分页，不承诺按时间排序。网页战绩展示界面未在本次添加。

## Netty 心跳和断线

保留现有 Netty 心跳处理器，不恢复你删除的 PlayerConnectionMonitor，不新增定时玩家探测。

Netty 判断连接是否存活；玩家下线由 Gateway 的客户端断连通知进入 AgentMgr 流程，不能把一个承载多名玩家的内部 WS 断开当作所有玩家已保存退出。登录响应迟到且客户端已断开时，Gateway 已拿到 Actor ID 就提交原 Actor 的下线通知。若整个登录结果丢失、Gateway 根本没拿到 Actor ID，Netty 连接心跳本身不能补齐这个业务信息；本次没有重新引入自定义探测来补偿。

## 配置和升级

Agent 只需要 Redis、Nacos 和内部 WS，不需要 PLAYER_DATABASE_* 或 MySQL/MyBatis-Plus 依赖。Redis 配置从现有 spring.data.redis.* 读取；I/O 线程池为 agent.io-threads/io-queue。

默认环境 prod，必须导入 Nacos 的 global-prod.yaml（DEFAULT_GROUP）。Redis 连接参数只从该配置的 spring.data.redis 读取，不在本地重复填写；修改地址或凭据后重启服务。

同一个 AGENT_INSTANCE_ID 不得同时运行两个进程。旧 game_player/game_player_actor 已不再被代码使用；本次不实现重启身份恢复或状态迁移。不自动删旧表、旧 Redis 数据，也不执行 SQL。

统一建表脚本在 src/main/resources/db/schema.sql：账号表由 Login 使用，比赛/个人战绩表由 Scene 使用。

Scene 战斗仍为内存状态，未实现崩溃恢复/回放/跨实例迁移。内网 HTTP/WS 只部署在可信内网，实例 ID 不是鉴权凭据。未承诺端到端 exactly-once 或客户端推送 ACK。

编译：仓库根目录执行 `mvn -pl agent-server,scene-server,gateway-server,login-server,agentmgr-server -am -DskipTests compile`。单元/H2 测试不代替实际 MySQL、Redis、Nacos 联调。
