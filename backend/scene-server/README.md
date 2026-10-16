# Scene 服务

存储已接入：统一建表脚本为 `../agent-server/src/main/resources/db/schema.sql`，比赛和个人战绩均为主键 + Protobuf data 两列；定义在 `game-protocol/src/main/proto/match.proto`。Scene 使用 MyBatis-Plus 保存和查询战绩，只记录结束时仍在场的玩家，离场玩家不入最终榜。

## Actor 与状态归属

一个 Scene 服务实例管理多场比赛；每场比赛一个 SceneActor，其邮箱串行处理入场、离场、玩家输入与 Tick。
使用 Akka Classic：SceneActor 继承 AbstractActorWithTimers，SceneDirectoryActor 管理子 ActorRef 和分配记录，不对外暴露可变 Actor 实例。
球、食物、成员与移动方向只能在此邮箱内修改；PlayerActor 不写入这些权威状态。
不同 SceneActor 可在共享工作线程之间并行，不为每个 Actor 单独创建线程。

SceneServiceDispatcher 按注解注册的 ServiceCommands 查 Handler，包含握手、入场/离场/输入和 MATCH_HISTORY。
INPUT 按 scene_id 投递到 SceneActor，再按 GameCommands 处理 SHIFT、SPLIT、EJECT。
同一 Agent—Scene 实例对共用一条 WS，场景广播按 Agent 实例分组，并携带 userId、playerActorId、joinOperationId。
PlayerActor 会重新核对原 Scene 实例和这次入场身份，拒绝上一轮登录/比赛的迟到广播。

## Nacos 全局规则

把 config/nacos-global-battle.yaml 合并到 Nacos 的 global-prod.yaml（DEFAULT_GROUP），不要覆盖原配置。
application.yaml 导入 global-prod.yaml 以及 scene-server-prod.yaml，使用当前项目的 Nacos namespace。
BattleRules 在创建比赛时从 Environment 读取并校验，保存本场不可变配置快照；全局配置热更新影响新比赛。
max-players / max-balls / max-foods 分别限制人数、球数和食物数。
initial-foods 默认 50（不超过 max-foods），创建房间时随机铺好，首个全量快照就包含食物；之后按 food-interval-ms 补充。
min-players-to-start 默认 2：单人先等候，达到最低人数后才开始计时和权威更新。开赛后有人离开，不暂停或重新计时。
目前安全范围：玩家 2～64，最低开赛人数 2～max-players，球数不小于人数且最多 256，食物 0～512，初始食物不能超过食物上限，单玩家球数 1～16。
tick-rate 1～100，broadcast-rate 必须为 tick-rate 的约数；地图宽高 20～1000。
容量并不代表压测能力，不承诺某个硬件一定能承载所有配置上限。

每个实例配置唯一 SCENE_INSTANCE_ID；Scene HTTP 注册端口与 WS 监听端口分开，scene-ws-port 元数据由实际监听配置登记。
所有 Agent 必须能访问该 WS 地址。监听端口改动需要重启，不是战斗规则热更新。

## 固定步长主循环

Akka startSingleTimer 每次 Tick 执行完后安排下一条 Tick（等待 10ms），场景最多存在一个待执行 Tick。
没有自建时钟线程或 Netty Actor 邮箱；Tick 在 createReceive 中执行，定时器随 Actor 停止自动取消。
SceneActor 使用 System.nanoTime 累积经过时间，以 1 / tick-rate 的固定 dt 推进逻辑。
一次最多补算 max-catch-up 次；超额积压丢弃并告警，逻辑 Tick 只随真实执行递增，不虚增或扩大 dt。
默认逻辑 25Hz、位置广播 5Hz；一次补帧合并位置广播，不逐球逐条广播。
持续过载时游戏会变慢，此方案不是无限追平墙钟时间的承诺。
目录记录每个子 Actor 的不可变可加入状态；真正 JOIN 仍由 SceneActor 校验容量，目录快照不是预约成功。
目录用 supervision/DeathWatch 移除异常停止的子 Actor，不用旧构造参数自动重启一场比赛并假装战斗已恢复。
当前使用 Akka 默认邮箱；沿用网关、协议、去重窗口、场景容量、网络发送缓冲和 IO 的现有上限，不宣称原自封装邮箱容量仍有效。

服务器没有 display_game。客户端可按 server_tick 缓存两个快照并插值显示；速度外推是另一回事，不能当作服务端权威坐标。
固定步长与有限追帧设计参考：[Fix Your Timestep](https://gafferongames.com/post/fix_your_timestep/)。
本次没有复制用户提供的 C++ 主循环代码，也没有实现客户端渲染。

## 命令注册与分发

服务请求与事件通过 @ServiceCommand 注册到 SceneServiceCommandRegistry；分配、创建、入场、离场、握手、玩家输入各有独立 Handler。
玩家操作通过 @GameCommand 注册到 SceneGameCommandRegistry；SceneActor.input 统一解码、去重、查表，再在本场邮箱中调用 ShiftHandler / SplitHandler / EjectHandler。
各 Handler 是无比赛状态的 Spring Bean；球、食物、成员和 Tick 仍归 SceneActor 所有，不能在网络线程或 Handler 字段中修改共享场景状态。
缺少注解或重复 cmd 阻止启动；未知 cmd 返回协议错误。SHIFT 的序号过滤和动作成功请求缓存规则保持不变。
服务端生成的 MOVE、FOOD_ADDED、FOOD_EATEN、PLAYER_ENTERED、PLAYER_LEFT、BALL_CHANGED 等消息由 Agent 的游戏回传注册器按 cmd 处理；没有伪造客户端吃食物或吃球的请求协议。

## 基础游戏规则

- 入场创建半径 2 的球，随机合法坐标；推送入场、全量场景快照。
- SHIFT 提交有限值方向，长度大于 1 时归一化；位置根据服务端速度 × 固定 dt 更新并限制地图边界。
- 固定间隔生成食物，不超过总量上限；通过空间网格筛选球附近食物。
- 吞下食物只由场景判定，每份食物最多被一个球消费；半径按面积增加计算。
- 分裂保持面积，限制球数与冷却；同玩家的分裂球经过合并等待后可以合并。
- 吐球扣除面积并生成食物，限制冷却及食物数量。
- 不同玩家的球依据尺寸优势与覆盖关系吞噬；当前球碰撞使用硬上限下的两两检查，不声称已实现全场碰撞空间优化或连续碰撞检测。
- 最后一个球被吞噬后成员仍处于本场比赛，不能继续分裂/吐球；可主动 LEAVE 后重新开始。
- 离场删除该玩家所有球并广播；空比赛经过配置的空闲时间后回收。

## 幂等、重连与边界

FIND_SCENE/CREATE_SCENE 的分配结果缓存 10 分钟；JOIN 根据用户、Actor 与 joinOperationId 幂等，重复加入会重发快照而不创建第二个球。
LEAVE 的入场取消标记保留在该场生命周期内；容量达到 8192 后不再接受新成员，避免无限增长或淘汰后接收迟到 JOIN。
离场请求先到时也登记取消，防止迟到 JOIN 重新入场。
已不存在的比赛确实没有临时成员，可以返回 ALREADY_LEFT；这不代表 Agent 的资料保存成功。
每个场景成员使用 MessageContext，processing 和 responses 都直接按 requestId 索引；不使用 cmd+requestId 复合 key。
SPLIT/EJECT 缓存最近 1024 个最终响应，包括成功与明确业务失败；重复请求重放原 ServerEnvelope，不重新计算结果。
处理中任务不被响应淘汰影响；编号应递增且非零，窗口外只查 Redis 原记录，不存在则返回 REQUEST_EXPIRED，不能占位成新动作。不同参数复用 request_id 会被拒绝。
SceneRequestStore 使用 StringRedisTemplate：一场比赛内每个玩家的本次入场上下文一个 Hash（Actor ID 与原 START_GAME requestId 隔离上下文），field 直接是客户端 requestId；response=null 为 PROCESSING，否则为 DONE。
一局仍是一个 SceneActor，processing/responses 属于其中的各个 Member，不创建玩家级 SceneActor，也不使用锁。
Redis 在独立 I/O 线程池读写，完成后投递回原 SceneActor 执行 Handler；同玩家动作按收到顺序串接。所有玩家的状态修改和 Tick 仍在该场邮箱串行执行。
动作先确认 Redis 占位再执行；最终响应提交成功后才回复。响应提交失败，本地保留原结果，重试只补写，不再执行 Handler。
只有 Redis 里的 PROCESSING、没有本地执行者或原结果时，返回临时不可用，不自动重放无法核实的分裂/吐球；该机制防止重复，不保证崩溃后每个动作都完成。
在场请求不设 TTL，离场并等待已登记动作结束后才设置保留期（默认一天）；不能把 TTL 到期当作玩家已离场。
Redis 去重记录不等于战斗状态快照，不承诺 Scene 进程崩溃后的战斗恢复。
SHIFT 按 client_sequence 丢弃过期方向，不为每条方向输入返回成功包。
入场/分配沿用 START_GAME requestId；分配容器和离场取消记录按 PlayerActor 隔离，两个玩家使用相同编号不会互相去重或取消。
本次新增 FIND_SCENE 的 PlayerActor 字段，Gateway、AgentMgr、Agent、Scene 应一起升级内部协议；浏览器的客户端信封没有改变。

内部链路心跳与断开处理不会直接把玩家判为保存下线；生命周期由 AgentMgr/Agent 仲裁。
Agent—Scene 重连完成握手后按实例分组推送全量快照，并每 10 秒执行全量校正，帮助客户端恢复临时丢失的事件。
发送缓冲满时拒绝/丢弃推送，不无限排队；周期快照不是 ACK 或可靠消息日志。
单个场景缺失或邮箱满会给受影响用户返回错误，不关闭承载其他比赛的共享连接。
比赛、随机种子和 Tick 状态未持久化；没有对局崩溃恢复、战斗回放或跨实例迁移。

## Nacos 战斗配置与食物随机刷新

配置放在 Nacos `global-prod.yaml` / `DEFAULT_GROUP`，带默认值、单位和范围的完整示例见 `config/nacos-global-battle.yaml`。
不要用示例文件覆盖原有 MySQL、Redis 等全局配置；在原配置中合并唯一的 `battle:` 节点即可。

- `battle.initial-foods`：创建对局时的初始食物数，通常默认 50；未配置时取 `min(50,max-foods)`。
- `battle.max-players`：一局同时在场人数，默认 32，包含观战成员。
- `battle.max-foods`：一局同时在场食物数，默认 50；吐球和自动刷新共用该上限。
- `battle.food-interval-ms`：刷新间隔，默认 10000 毫秒（10 秒）；换算成逻辑 Tick 时向上取整（25Hz 下配置 500ms，实际为 13 Tick=520ms），单人等待时不刷新。
- `battle.food-refresh-max-count`：每批随机数量的上限，默认 5；每轮先取 1~上限（包含端点）的随机数，再受剩余食物容量限制。
- `battle.width` / `battle.height`：地图宽、高，默认均为 100，单位为游戏坐标而非像素。

例如上限 50、当前已有 48、随机抽到 5，本批只生成 2 个；满 50 后不会继续生成。一次刷新合并为一条包含多个食物的 FOOD_ADDED 广播，客户端已有批量处理，无需改协议。

`spring.config.import` 已启用 `refreshEnabled=true`。Nacos 发布新配置后，SceneDirectory 创建新的 SceneActor 时重新调用 `BattleRules.load(environment)`，无需重启即可使用新规则。
已创建对局（包括仍在等人的房间）持有创建时的规则快照，不会突然缩地图、删除食物或踢掉超出新上限的玩家；匹配进旧房间仍然使用旧规则，不保证点“再来一局”就一定创建新房间。
首次部署新增随机刷新代码时需重启 Scene，此后更新战斗规则不需要重启。`ranking-retention-days` 是存储组件启动时读取的配置，不在每局动态规则之内，修改它仍需重启。

真实 Nacos 热更新验证可在仓库根目录执行：`mvn -pl scene-server -am test -Dballs.nacos-refresh-test=true`。
该项测试只创建一个带 UUID 的临时测试配置，验证 Spring Environment 不重启即可读取新值，最后删除该临时配置；不修改正式全局配置、不向 Nacos 注册测试服务，也不启动游戏端口。

## 每局排名与固定时长

达到 battle.min-players-to-start 才启动比赛计时，默认两人、battle.match-duration-seconds=600（10 分钟）；所有玩家共享同一截止时间。
等待期间不移动、不吞噬、不补食物、不发布排名或保存未开始的比赛；分裂/吐球返回 MATCH_WAITING。等待室全员离场只回收房间，不写一场虚假的战绩。
SCENE_SNAPSHOT 新增 started、min_players_to_start、player_count，客户端据此显示等待人数和禁用战斗输入。重新部署 Scene 并刷新 Web 客户端后生效。
截止后停止入场及战斗输入，冻结比赛的总面积榜。全员提前离场时以 ALL_LEFT 提前结算。
按每个账号全部球的 π × radius² 相加，精度取 0.001 面积单位；分裂/合并按面积守恒，吞噬增加，吐球减少。
仍在场但没有球的观战玩家记 0 分；主动/断线离场的玩家不进入排名和战绩。并列使用 1、1、3 名次，账号字典序仅稳定同分展示。
每局最多 battle.max-ranking-participants=256 个不同账号（包括已离场者），与同时在线人数上限不同，限制历史参赛者及协议大小。

MatchRankingStore 使用 Spring Data Redis 的 StringRedisTemplate，不自写 Redis 驱动或 Lua：

- balls:match:{Base64URL实例ID:sceneID}:scores：ZSET，member=userId，score=总面积。
- 同前缀 :snapshot：Base64 编码的完整 MatchRanking Protobuf，包含昵称、并列名次和冻结标志。
- 同槽 MULTI/EXEC 提交整份榜单与快照，每个 SceneActor 最多一个写任务在途，实时榜合并最新状态。
- 默认每 1000ms 发布一次实时榜（MATCH_RANKING 3008）；最终榜（MATCH_ENDED 3009）在 MySQL 战绩事务及 Redis 快照均确认成功后才推送。
- Redis 临时失败不会阻塞 Tick；最终状态保留在 Actor 内，每秒重试，未确认提交前不回收该房间。
- 最终榜默认保留 7 天，已冻结的榜单不能被后续实时榜覆盖，重复保存核对完整原结果。
- 运行中榜单不设过期，不以 key 过期判定比赛已结算；Scene 崩溃可能留下 frozen=false 的未结算榜，不伪造最终结果。

内网查询：GET /internal/game/ranking?sceneInstanceId=scene01&sceneId=...，返回 MatchRanking Protobuf；不存在/过期返回 404。
此查询不是公网客户端接口；客户端通过原 Gateway—Agent—Scene 回程接收榜单。
长期战绩存 MySQL：game_match 保存整局完整排名，game_match_result 保存每个仍在场玩家的个人战绩，均使用 Protobuf 原始字节。MatchHistoryRepository 在同一事务提交；重复结算核对原结果，不覆盖已保存内容。奖励发放未实现。
结算等待期间仍可离场，但不再改变冻结分数；Agent 在收到最终榜后自动离场，账号保持在线，可以开始下一局。
比赛活状态及尚未写入 Redis 的最终快照仍在内存，不包含 Scene 崩溃恢复/奖励发放/客户端消息 ACK。

启动前在 Nacos `global-prod.yaml` 的 `spring.datasource.url` 对应数据库执行统一 schema.sql。MySQL 的 `spring.datasource.*` 和 Redis 的 `spring.data.redis.*` 均从该配置读取，所有 Scene 连接同一战绩库；不再使用本地 SCENE_DB_* / REDIS_* 连接默认值。Scene 专属配置可选，默认环境 prod，全局配置必需。合并 config/nacos-global-battle.yaml 规则时不要覆盖已有连接配置。修改数据库/Redis 地址或凭据后重启服务，不承诺已创建连接池自动热切换。

## 本次验证

业务测试包含真实 Akka、MyBatis-Plus/H2 事务与离线 Redis 替身。另有显式开启的 SceneServerApplicationTests：加载实际 Nacos 配置、启动 HTTP/Netty、验证真实 MySQL SELECT 1 / 两张战绩表所需列和 Redis PING。该启动检查不建表、不写业务数据、不向 Nacos 注册测试实例，不代替客户端完整对局联调或容量压测。执行方式见 ../config/README.md。
