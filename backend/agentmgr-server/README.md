# AgentMgr 登录与下线仲裁

只负责玩家在线状态、登录/下线仲裁、按 Nacos 权重选 Agent 和固定实例 Feign RPC；不鉴权、不保存战绩、不执行战斗。

## 状态和去重

- players：本机内存 Map，key 为 userId，value 为 ManagedPlayer，保存当前 LOGINING/PLAYING/LOGOUTING、Agent/Gateway/Actor 等信息。
- processing：本机 ConcurrentMap，保存同一原请求的共享 Future；重复请求等待同一任务。
- Redis 请求记录：保存 PROCESSING/DONE、原调用目标和最终业务响应。Future 不放 Redis，在线集合不写 Redis。

LOGIN 沿用客户端 requestId 的十进制字符串，按 userId 隔离，不生成新的登录请求 UUID。Actor ID 是本次登录的会话身份。Gateway 主动报告断线时没有客户端请求编号，按该 Actor 身份区分重复断线通知。

新请求通过 SET NX 占位；执行完成后一次 SET 缓存原结果，DONE 默认保留十分钟，PROCESSING 不自动过期。旧登录响应能否再次使用仍由本机当前用户/Actor/登录请求检查；旧回包不能覆盖新 Context。

普通超时重试复用原实例、Actor 和请求参数，不重新随机分配。如果结果 SET 已成功但确认丢失，读取 DONE 只完成本机原有的待处理 Context；本机没有 Context 就不恢复身份。

## 登录和下线

登录：Login 账号鉴权 → BEGIN_LOGIN → 用户锁检查状态并固定 Agent/Actor → 若顶号先 Feign 关闭旧客户端、确认旧 Actor 离场停止 → 创建新 PlayerActor/Context → 确认成功 → 缓存响应、更新内存为 PLAYING → 回 Login/Gateway。

下线：Gateway 客户端断连通知 → 核对 userId 与当前 Actor → 内存标为 LOGOUTING → 固定实例 Feign KICK_OLD_AGENT → 等实际 Actor 停止 → 缓存下线结果、删除内存在线记录 → Feign 回 Gateway。

顶号关闭连接触发的重复断线通知，等待原顶号任务，不启动第二套下线。找不到当前身份或 Actor，不从 Redis 补建；已有完成响应可正常重放。

## 锁、配置及明确不做的功能

保留既有 Redisson 用户粒度状态锁/执行锁及有限 RPC 退避重试，隐式 Feign/LoadBalancer 重试仍关闭。同机 Agent 权重为 Nacos weight × same-host-multiplier，选定后不因超时更换实例。

当前按单实例 AgentMgr 部署，在线集合不跨进程共享。不新增全局仲裁启动锁、不扫描 Redis、不恢复重启丢失的在线身份、不自动补建 Actor、不重放恢复任务、不保存阶段日志。去掉为恢复添加的 createdAt、retired、oldLoginRequestId。

使用现有 spring.data.redis.*、Redisson Starter 和 StringRedisTemplate，不自行实现 Redis 客户端。没有恢复你已删除的 RedisConfiguration 或自定义玩家心跳类。

默认使用 prod，必须加载 Nacos 的 global-prod.yaml（DEFAULT_GROUP）；agentmgr-server-prod.yaml 可选。Redis 地址、端口、密码、database、timeout 全部来自全局 spring.data.redis，不在本地重复填写。RedissonClient 和 StringRedisTemplate 使用同一份参数，修改连接地址或凭据后重启服务。

MySQL 只由 Login 保存账号、Scene 保存战绩，AgentMgr 不需要数据库表。标准 Netty 心跳属于连接保活，玩家下线仍通过原断连通知链路。

没有修改远程 Nacos 配置或删除 Redis/数据库数据。业务验证包含单元测试与离线替身；显式开启的 AgentmgrServerApplicationTests 还会加载真实 Nacos 配置、启动 HTTP、验证 StringRedisTemplate 复用 Redisson 连接并执行 Redis PING，不加锁、不写业务记录、不注册测试实例。执行方式见 ../config/README.md；不代替完整登录/战斗链路联调。
