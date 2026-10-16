# 建表与存取

统一脚本是 schema.sql，仅定义三张表，都是“唯一标识 + Protobuf data”两列：

| 表 | 一行代表什么 | data 类型 | 负责服务 |
| --- | --- | --- | --- |
| game_account | 一个注册账号 | UserProto | Login |
| game_match | 一场比赛及完整最终排名 | MatchProto | Scene |
| game_match_result | 一个用户的一局战绩 | MatchResultProto | Scene |

协议定义在 game-protocol/src/main/proto/user.proto 和 match.proto。Mapper 写入的 byte[] 来自 toByteArray()，读取使用 parseFrom(data)，不把数据库 data 转为 JSON/Base64。序列化不是加密，账号密码仍先生成 BCrypt 哈希。

## 当前已接入的代码

Agent 已移除 PlayerRepository、两张旧表的实体/Mapper，以及 MySQL/MyBatis-Plus 依赖。每个 PlayerActor 使用自己的 PlayerContext；Redis 不再另存 _scene。原创建/停止请求和响应仍在 Redis，防止重复副作用。

AgentMgr 在线集合在本机内存，按单实例部署。Redis 保存 PROCESSING/DONE、原调用目标和最终响应，不写在线 Hash。DONE 使用配置的结果缓存期限，PROCESSING 不自动过期；不扫描 Redis，不重建在线身份，不添加重启恢复任务。原先为恢复加入的 createdAt、retired、oldLoginRequestId 已移除。

Scene 的 MatchEntity/MatchResultEntity 是两列 MyBatis-Plus 映射，MatchHistoryRepository 负责 Protobuf 读写。冻结结果先在同一 MySQL 事务保存比赛和所有个人结果，再更新 Redis 榜单快照；失败重试使用原冻结数据，不覆盖另一份最终战绩。数据库 I/O 不在 Actor 邮箱内阻塞执行。

按结束时成员集合保存结果：观战但未离场的玩家仍有战绩，提前离场的不写；全员离场时比赛人数为 0，个人结果为空。开赛记录在第一次排名保存时写入；本次不实现尚未保存比赛的崩溃补偿。

Redis 实时排行榜是此前单独要求的功能，继续保留，不是额外的玩家在线列表。最终 Redis 快照默认保留 7 天；MySQL 历史记录不会随它过期。

## 按用户查询

- 比赛主键：Scene实例ID:SceneID，例如 scene01:123。
- 个人战绩主键：userId字符数:userId:比赛主键，例如 13:a@example.com:scene01:123。
- 查询下界为 13:a@example.com:，上界为 13:a@example.com;，使用绑定参数按主键范围查询。不用 LIKE，账号中的 % 或 _ 不会被当作通配符。

固定主键只用于定位原用户/比赛结果，不是新生成的请求编号。数据库不能直接筛选 Protobuf 内的昵称、时间或排名，因此分页按主键，不承诺按时间排序。

客户端 MATCH_HISTORY（1003）经已登录 Gateway/PlayerActor 查询 Scene。userId 来自 PlayerContext，而非客户端参数；limit 默认 20、最大 100，后续使用 next_cursor。对应内部命令为 MATCH_HISTORY（10019）。所有 Scene 实例必须连接同一战绩库；本次未加网页历史战绩界面。

## 新库、旧库及升级

本脚本不自动执行，不包含 DROP/ALTER，不会删除现有数据。新库执行 schema.sql，再配置 Login/Scene 数据源即可；Agent、AgentMgr 不需要 MySQL 表。

旧版 game_match/game_match_result 若已存在，CREATE TABLE IF NOT EXISTS 不会变更结构。须备份并核实实际数据后单独迁移；不要直接删旧表，也不要把原多列记录当作 Protobuf 字节读取。

按当前要求，不实现重启后的会话恢复。Actor/在线 Context 只放内存；已有请求结果可以用于正常去重，但不会用它自动创建丢失的 Context 或 Actor。单实例是部署约定，不新增全局仲裁启动锁。

旧 game_player/game_player_actor 表和现有数据库数据未删除。本次移除的是不再需要的存取代码及 PlayerProto 定义。已删除自定义 PlayerConnectionMonitor，不恢复；连接保活继续使用 Netty 处理器。

验证包括真实 Akka、MyBatis-Plus/H2 事务和离线 Redis/Redisson 替身。没有实际执行 MySQL 建表、连接外部 Redis/Nacos或进行容量压测。
