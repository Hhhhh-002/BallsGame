# BallsGame · 多人实时在线球球吞噬竞技游戏

> 本项目是本人在学习过程中从零独立开发的多人实时在线游戏，主要聚焦 Java 游戏服务端架构与核心游戏逻辑的设计和实现。Web 客户端在 AI 辅助下开发，用于联调与玩法展示。项目仍有不少不足，后续将持续完善功能、优化性能，并改进工程质量。欢迎交流与指正。

![Java](https://img.shields.io/badge/Java-17-orange?logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0-brightgreen?logo=springboot&logoColor=white)
![Spring Cloud](https://img.shields.io/badge/Spring%20Cloud-2025.1-6DB33F?logo=spring&logoColor=white)
![Netty](https://img.shields.io/badge/Netty-4.x-black?logo=netty&logoColor=white)
![Akka](https://img.shields.io/badge/Akka%20Classic-2.6-15A9CE)
![Protobuf](https://img.shields.io/badge/Protocol%20Buffers-proto3-4285F4)
![MySQL](https://img.shields.io/badge/MySQL-8.4-4479A1?logo=mysql&logoColor=white)
![Redis](https://img.shields.io/badge/Redis-7.4-DC382D?logo=redis&logoColor=white)
![Nacos](https://img.shields.io/badge/Nacos-2.5-1E90FF)
![TypeScript](https://img.shields.io/badge/TypeScript-5.9-3178C6?logo=typescript&logoColor=white)

> 从零开始，不使用任何现成游戏服务端框架，用 **Java 17** 打造的多人实时在线「球球吞噬」竞技游戏（Agar.io 类玩法）。
>
> 服务端由 5 个可独立部署的微服务组成，通过 **二进制 WebSocket + Protocol Buffers** 协议对外服务，以 **Akka Actor 模型** 承载服务端权威战斗模拟；客户端为 **TypeScript + Canvas** 的高性能 Web 实现，服务端逻辑 25Hz 固定步长推进，客户端按服务端 Tick 插值渲染。

---

## 目录

- [实机演示](#实机演示)
- [游戏功能](#游戏功能)
- [技术亮点](#技术亮点)
- [技术栈](#技术栈)
- [系统架构](#系统架构)
- [通信协议](#通信协议)
- [快速开始](#快速开始)
- [测试](#测试)
- [仓库结构](#仓库结构)
- [文档索引](#文档索引)
- [已知边界](#已知边界)

---

## 实机演示

[![点击前往 B 站观看 BallsGame 实机演示](docs/media/gameplay-cover.jpg)](https://www.bilibili.com/video/BV1QqHM6vEk5/)

[在 B 站观看实机演示](https://www.bilibili.com/video/BV1QqHM6vEk5/) · [视频展示说明](docs/demo.md)

点击封面即可前往 B 站观看。视频为当前项目的实机录制，不是效果预渲染；GitHub README 不内嵌 B 站播放器。

## 游戏功能

### 核心玩法

- **多人实时在线对战** —— 多名玩家进入同一张地图实时对抗，地图尺寸、人数上限与对局时长均由服务端统一配置，互相同屏可见移动、成长与吞噬全过程。
- **吞噬成长** —— 吞食地图上随机刷新的食物、吞噬体型小于自己的玩家球体，面积（质量）持续增长；体型越大力量越强，但也越笨拙。
- **分裂**（空格键）—— 一个球分裂为两个向前突进，用于快速捕猎；分裂保持总面积守恒，受球数上限（默认 16 个）与冷却限制。
- **吐球**（E 键）—— 吐出质量形成食物，可用于喂食队友、设置诱饵或快速减重；受冷却与场上食物容量约束。
- **合并** —— 同一玩家的分裂球经过合并等待时间后重新合体，串联起「分裂突进 → 吞噬 → 合体」的完整操作循环。
- **服务端权威判定** —— 移动位移、食物吞食、球体碰撞与吞噬关系全部由服务端计算，客户端仅上报操作意图。

### 对局系统

- **匹配与开赛** —— 一键开始对局，服务端自动匹配或创建比赛房间；等待室达到最低开赛人数（默认 2 人）才开始计时。
- **限时对局** —— 默认 10 分钟一局，截止时间全服统一，到时冻结面积榜并结算。
- **实时排行榜** —— 对局过程中持续推送按总面积排序的实时榜单。
- **最终结算榜** —— 输出完整排名（含并列名次），展示每名玩家的昵称、面积与名次。
- **观战模式** —— 球被全部吞噬后不会直接出局，可自由选择仍存活的玩家视角继续观战。
- **离场与重开** —— 随时可主动离开比赛（保留登录态），结算后可立即开始下一局。

### 账号与数据

- **邮箱注册 / 登录** —— BCrypt 密码哈希存储，凭据永不写入日志，重复邮箱拦截。
- **异地登录保护** —— 同一账号重复登录时旧会话被定向踢下线，杜绝双开。
- **历史战绩** —— 每局完整排名与个人战绩持久化至 MySQL，支持分页查询本人历史战绩。

### 客户端体验

- **多端操作适配** —— 桌面端鼠标指向 / WASD / 方向键，移动端触屏拖动 + 按钮，两套操作方案。
- **完整战斗界面** —— 球体光晕、吞噬粒子特效、新增食物闪光、小地图、HUD、相机跟随、缩放与全屏。
- **免构建切换服务器** —— 网关地址支持配置文件 / 浏览器保存 / 环境变量多级覆盖，修改后刷新即生效，无需重新构建。

## 技术亮点

> 除 Spring / Netty / Akka / Protobuf 等通用框架外，本项目从零自研了游戏协议栈、命令分发框架、服务间长连接、权威战斗循环与幂等投递等**全部游戏核心机制**。

1. **分布式微服务游戏架构** —— Gateway / Login / AgentMgr / Agent / Scene 五大服务独立部署、按需水平扩展；Nacos 负责服务注册与配置中心（战斗规则发布后新建对局采用新值，已有对局保留原规则）；Redis 承担在线路由、请求去重、排行榜与分布式锁；MySQL 持久化账号与战绩。
2. **自研二进制游戏协议栈** —— 不依赖 gRPC / JSON，自定义「信封 + 命令号 + Protobuf body」双层协议：客户端与服务间两套信封互不泄漏内部模型；Netty 编解码器、未知命令容错、带 requestId 的协议错误响应全部手写；64 位 ID 在客户端全程按 BigInt 处理，精度安全。
3. **注解式命令分发框架** —— `@GameCommand` / `@ServiceCommand` / `@GatewayCommand` 注解 + 注册表扫描：启动期校验命令冲突，运行期 O(1) 查表分发，五大服务共用同一套分发底座，新增命令只需实现 Handler。
4. **Netty 双向长连接复用** —— Gateway↔Agent、Agent↔Scene 按实例对复用单条双向 WebSocket：握手校验实例身份、广播按实例分组打包、心跳与断开由链路层统一处理——高频战斗指令不走 HTTP，复用长连接直发。
5. **Akka Actor 权威战斗模拟** —— 一局一 SceneActor，入场 / 离场 / 玩家输入 / Tick 全部在邮箱内串行处理，球与食物状态无锁修改；同一 Agent—Scene 连接承载多玩家广播，迟到广播按 playerActorId / joinOperationId 识别并拒绝。
6. **固定步长游戏主循环** —— 基于 Akka 定时器 + `System.nanoTime` 累积，按 `1 / tick-rate` 固定 dt 推进（默认逻辑 25Hz、位置广播 5Hz，参考 [Fix Your Timestep](https://gafferongames.com/post/fix_your_timestep/)）；补帧次数有上限、超额积压丢弃并告警，逻辑 Tick 只随真实执行递增，持续过载时宁可变慢也不拖垮服务。
7. **端到端幂等与防重放** —— `requestId` 从客户端贯穿五层服务；登录有端到端期限与业务操作 ID、入场 / 离场按轮次隔离、SPLIT / EJECT 缓存最近 1024 个最终响应原样重放（Redis 去重窗口）、SHIFT 以递增 `client_sequence` 丢弃过期方向——弱网重试不会多打一颗球。
8. **空间网格加速的吞噬判定** —— 移动、吞噬、分裂、合并、得分全部在服务端计算；食物判定走空间网格筛选，杜绝客户端参与任何物理与得分计算，从根上杜绝外挂。
9. **客户端插值渲染管线** —— 按服务端 Tick 建立约 220ms 样本缓冲插值显示，无后续样本时保持最后位置、不做无限预测；球体纹理预绘制复用、视野外对象裁剪、食物按色合批，保障大量对象下的流畅帧率。
10. **工程化质量保障** —— 后端以真实 Akka、H2、离线 Redis 替身运行离线测试，覆盖 Actor 生命周期、指令分发、请求去重与结算；前端 Node test runner 覆盖原包重试、迟到响应、跨连接隔离等场景；`sync-protocol.mjs` 从后端 proto 自动生成前端 schema 并支持一致性校验。

## 技术栈

| 层次 | 选型 |
| --- | --- |
| 语言 / 构建 | Java 17 · Maven 多模块 |
| 微服务框架 | Spring Boot 4.0 · Spring Cloud 2025.1 · Spring Cloud Alibaba（Nacos） |
| 接入层 | Spring Cloud Gateway（WebFlux）· **Netty**（二进制 WebSocket 服务） |
| 服务间通信 | OpenFeign（HTTP + Protobuf 载荷）· 自研 Netty 内部双向长连接 |
| 并发 / 模拟 | **Akka Classic Actor 2.6**（一局一 Actor，`AbstractActorWithTimers` 固定步长主循环） |
| 序列化协议 | Protocol Buffers 3（`proto3`，纯编解码，不依赖 gRPC 运行时） |
| 存储 | MySQL 8.4（MyBatis-Plus）· Redis 7.4（路由、去重、排行榜、分布式锁 / Redisson） |
| 注册与配置 | Nacos 2.5（服务发现 + 配置中心，战斗规则热更新） |
| 客户端 | TypeScript 5.9 · Vite 6 · Canvas 2D · protobufjs（64 位 ID 全程 BigInt 精度安全） |

## 系统架构

```mermaid
flowchart TD
    C["浏览器客户端<br/>TypeScript · Vite · Canvas 2D"]
    GW["Gateway 网关<br/>Netty 二进制 WebSocket · 协议分发"]
    LG["Login 账号服务<br/>注册 · 登录 · BCrypt 校验"]
    AM["AgentMgr 路由仲裁<br/>在线路由 · 生命周期 · 分布式锁"]
    AG["Agent 玩家会话<br/>PlayerActor · 指令转发 · 请求去重"]
    SC["Scene 战斗场景<br/>SceneActor · 权威模拟 · 排行榜"]
    DB[("MySQL<br/>账号表 · 战绩表")]
    RD[("Redis<br/>路由 · 去重 · 排行榜")]
    NC["Nacos<br/>服务注册 · 配置中心"]

    C <-->|"Protobuf / 二进制 WS"| GW
    GW -->|"Feign HTTP + Protobuf"| LG
    LG -->|"Feign HTTP + Protobuf"| AM
    GW <-->|"内部 WS 长连接（按实例复用）"| AG
    AG <-->|"内部 WS 长连接（按实例复用）"| SC
    LG --> DB
    SC --> DB
    AM --> RD
    AG --> RD
    SC --> RD
    GW -.-> NC
    LG -.-> NC
    AM -.-> NC
    AG -.-> NC
    SC -.-> NC

    classDef client fill:#e8f5e9,stroke:#43a047,color:#1b5e20
    classDef svc fill:#e3f2fd,stroke:#1976d2,color:#0d47a1
    classDef db fill:#fff3e0,stroke:#f57c00,color:#e65100
    classDef infra fill:#f3e5f5,stroke:#8e24aa,color:#4a148c
    class C client
    class GW,LG,AM,AG,SC svc
    class DB,RD db
    class NC infra
```

### 模块职责

| 模块 | 类型 | 职责 |
| --- | --- | --- |
| `game-protocol` | 协议库 | Protobuf 定义（客户端 / 服务间 / 持久化三套）与 Netty 编解码器、指令日志 |
| `game-common` | 公共库 | 客户端与服务端命令枚举、错误码、注解式命令注册表、有界 RPC 重试工具 |
| `game-core` | 基础库 | Akka 支持、内部 WebSocket 服务端 / 客户端、Peer 注册表 |
| `gateway-server` | 服务 | 客户端唯一入口：二进制 WS 接入、登录代理、指令分发、实例路由与内部长连接管理 |
| `login-server` | 服务 | 注册 / 登录 / 账号数据（MySQL），凭据校验后交棒 AgentMgr |
| `agentmgr-server` | 服务 | 登录生命周期仲裁：选定 Agent 实例、在线路由（Redis）、重复登录踢旧、断线清理 |
| `agent-server` | 服务 | 玩家会话层：PlayerActor 管理玩家上下文与请求去重，向 Scene 转发战斗指令 |
| `scene-server` | 服务 | 战斗权威：SceneActor 固定步长模拟（移动 / 吞噬 / 分裂 / 吐球）、榜单计算、战绩落库 |
| `nodemgr-server` | 服务 | 预留模块，当前未接入业务链路 |

### 登录链路（Gateway → Login → AgentMgr → Agent）

```mermaid
sequenceDiagram
    participant C as 浏览器客户端
    participant G as Gateway
    participant L as Login
    participant M as AgentMgr
    participant A as Agent 实例

    C->>G: LOGIN(1001) ClientEnvelope
    G->>L: AUTH_LOGIN · Feign HTTP
    L->>L: 读取账号表并校验 BCrypt 密码
    L->>M: BEGIN_LOGIN
    M->>A: CREATE_PLAYER_AGENT
    A->>A: 创建 PlayerActor · 加载玩家 Context
    A-->>M: CreatePlayerAgentResult
    M-->>L: LoginBinding（登记在线路由）
    L-->>G: LoginBinding
    G->>G: 保存路由 · 建立或复用 Agent 内部长连接
    G-->>C: LOGIN 响应
    Note over M,A: 同账号重复登录时，旧 Agent 与旧客户端连接被定向踢下线
```

### 战斗数据流（客户端 → Gateway → Agent → Scene → 回程广播）

```mermaid
sequenceDiagram
    participant C as 客户端
    participant G as Gateway
    participant A as Agent · PlayerActor
    participant S as Scene · SceneActor

    C->>G: SHIFT / SPLIT / EJECT(2002-2004)
    G->>A: FORWARD_PLAYER_COMMAND
    A->>S: SCENE_PLAYER_INPUT（按 scene_id 固定路由）
    S->>S: 25Hz 固定步长 Tick：移动 · 吞噬 · 分裂 · 合并
    S-->>A: SCENE_TO_AGENT：5Hz MOVE 广播 / 事件 / 榜单
    A-->>G: FORWARD_TO_CLIENT
    G-->>C: ServerEnvelope（2001 / 3002-3009）
```

## 通信协议

全链路使用 Protobuf `proto3` 二进制编码，两种「信封 + 命令号」结构，客户端与服务间互不暴露内部模型：

**客户端通道（Gateway 对外）**

```text
ClientEnvelope  { cmd, request_id, body, client_sequence }   ← 客户端请求
ServerEnvelope  { cmd, request_id, code, message, body }     ← 响应 & 主动推送（request_id=0）
```

**服务间通道（Feign HTTP / 内部 WS 共用）**

```text
ServiceEnvelope { oneof: request | response | event }
```

### 客户端命令

| cmd | 名称 | 方向 | 说明 |
| --- | --- | --- | --- |
| 1001 | `LOGIN` | C → S | 登录（经 Login 全链路） |
| 1002 | `REGISTER` | C → S | 邮箱注册（不自动登录） |
| 1003 | `MATCH_HISTORY` | C → S | 查询本人历史战绩 |
| 2001 | `MOVE` | S → C | 位置广播 `MoveBroadcast`（非请求） |
| 2002 | `SPLIT` | C → S | 分裂 |
| 2003 | `EJECT` | C → S | 吐球 |
| 2004 | `SHIFT` | C → S | 移动意图（服务端计算实际位移） |
| 2005 | `LEAVE` | C → S | 主动离开比赛（保留登录态） |
| 2006 | `START_GAME` | C → S | 开始对局（匹配 / 创建场景） |
| 3001 | `KICKED_OFFLINE` | S → C | 被异地登录踢下线通知 |
| 3002-3007 | `SCENE_SNAPSHOT` / `PLAYER_ENTERED` / `FOOD_ADDED` / `FOOD_EATEN` / `PLAYER_LEFT` / `BALL_CHANGED` | S → C | 场景快照与增量事件 |
| 3008 | `MATCH_RANKING` | S → C | 实时排行榜 |
| 3009 | `MATCH_ENDED` | S → C | 最终榜（frozen） |

### 服务间命令（节选）

`AUTH_LOGIN` → `BEGIN_LOGIN` → `CREATE_PLAYER_AGENT`（登录链路）；`AGENT_LINK_HELLO` / `FORWARD_PLAYER_COMMAND` / `FORWARD_TO_CLIENT`（Gateway↔Agent 通道）；`AGENT_SCENE_LINK_HELLO` / `SCENE_JOIN` / `SCENE_LEAVE` / `SCENE_PLAYER_INPUT` / `SCENE_TO_AGENT`（Agent↔Scene 通道）；`KICK_OLD_AGENT` / `KICK_OLD_CLIENT` / `PLAYER_DISCONNECTED`（生命周期仲裁）。

## 快速开始

以下以「MySQL / Redis / Nacos 运行在 Docker，五个 Java 服务和浏览器运行在同一台电脑」为例。应用暂未提供 Docker 镜像；不要把下面的 `127.0.0.1` 配置直接用于跨机器或应用容器部署。

### 1. 准备环境并获取代码

需要 Git、JDK 17、Maven（建议 3.9.x）、Node.js 18+ / npm，以及已启动的 Docker Engine / Docker Desktop（包含 Compose V2）。先确认 `java`、`mvn`、`node`、`npm`、`docker compose` 可在终端使用。

```bash
git clone https://github.com/Hhhhh-002/BallsGame.git
cd BallsGame
```

除明确写了 `cd frontend` 的步骤外，下面命令均在仓库根目录 `BallsGame/` 执行，可用于 Bash 或 Windows PowerShell。

### 2. 启动基础设施并建表

```bash
docker compose -f backend/docker-compose.yml up -d
docker compose -f backend/docker-compose.yml ps
```

等待 MySQL / Redis 为 `healthy`，且 Nacos 控制台可以打开后继续。`up -d` 返回不代表所有服务已经就绪。

| 依赖 | 本机地址 | 本地开发配置 |
| --- | --- | --- |
| MySQL | `127.0.0.1:3307` | 数据库 `ballsgame`；账号 `ballsgame`、密码 `H002`；root 密码也是 `H002` |
| Redis | `127.0.0.1:6379` | 密码 `H002`；数据库 `0` |
| Nacos | `http://127.0.0.1:8848/nacos` | 客户端还需要能够连接 `9848` 端口；首次使用按控制台要求初始化管理员 |

统一建表脚本不会由应用自动执行。下面通过容器内的 MySQL 客户端执行，无需在电脑安装 MySQL 客户端，也不依赖 PowerShell 不支持的 `<` 输入重定向：

```bash
docker cp backend/agent-server/src/main/resources/db/schema.sql ballsgame-mysql:/tmp/ballsgame-schema.sql
docker exec -it ballsgame-mysql mysql --default-character-set=utf8mb4 -uroot -p ballsgame
```

输入 root 密码，在出现的 `mysql>` 提示符中执行：

```sql
SOURCE /tmp/ballsgame-schema.sql;
SHOW TABLES;
exit;
```

确认存在 `game_account`、`game_match`、`game_match_result` 三张表。脚本不迁移已有旧表结构；此前运行过旧版本时，需先核对实际表结构。

### 3. 配置 Nacos（启动 Java 服务前必须完成）

五个服务默认使用同一命名空间 ID：`eb7842a8-9b4e-4add-8516-83d7f1d01ee1`。在控制台创建此 ID 的命名空间，再切换进去创建配置：

- Data ID：`global-prod.yaml`
- Group：`DEFAULT_GROUP`
- 格式：`YAML`

**不要只在默认的 public 命名空间创建配置**，否则默认设置下的服务读不到它。也可以使用其他命名空间，但必须为五个 Java 服务统一设置 `NACOS_NAMESPACE`，值填实际命名空间 ID，而不是显示名称。

把下面的本地开发配置粘贴到 `global-prod.yaml` 并发布：

```yaml
spring:
  datasource:
    url: jdbc:mysql://127.0.0.1:3307/ballsgame?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
    username: ballsgame
    password: "H002"
    driver-class-name: com.mysql.cj.jdbc.Driver
  data:
    redis:
      host: 127.0.0.1
      port: 6379
      password: "H002"
      database: 0
      timeout: 3s

netty:
  gateway:
    websocket:
      port: 9001
      path: /ws

battle:
  min-players-to-start: 2 # 至少两名玩家进入同一局才开赛。
  max-players: 32 # 每局同时在场人数上限，包括观战玩家。
  initial-foods: 50 # 创建场景时随机生成的食物数。
  max-foods: 50 # 场上食物上限，包含玩家吐球。
  food-interval-ms: 10000 # 开赛后每10秒模拟时间刷新一批。
  food-refresh-max-count: 5 # 每批随机1~5个，不超过剩余容量。
  width: 100 # 地图宽度，游戏坐标单位。
  height: 100 # 地图高度，游戏坐标单位。
  match-duration-seconds: 600 # 每局10分钟，从达到开赛人数开始计时。
  tick-rate: 25 # 战斗逻辑25Hz。
  broadcast-rate: 5 # 位置广播5Hz。
```

完整战斗参数、默认值与范围见 [`backend/scene-server/config/nacos-global-battle.yaml`](backend/scene-server/config/nacos-global-battle.yaml)。未填写的战斗参数使用代码默认值；不要把示例中的第二个 `battle:` 根节点直接追加到已有 `battle:` 后面，应合并字段。

服务默认连接 `127.0.0.1:8848`，Nacos 用户名 / 密码默认均为 `nacos`。如果管理员凭据或地址不同，**每个启动 Java 服务的终端**都要设置对应环境变量；IDE 启动则填入各自运行配置。例如 PowerShell：

```powershell
$env:NACOS_SERVER_ADDR = '127.0.0.1:8848'
$env:NACOS_NAMESPACE = 'eb7842a8-9b4e-4add-8516-83d7f1d01ee1'
$env:NACOS_USERNAME = '你的Nacos账号'
$env:NACOS_PASSWORD = '你的Nacos密码'
```

默认环境为 `prod`，Login、AgentMgr、Scene 的专属配置可不创建。不要在全局配置里统一设置 `spring.application.name` 或 `server.port`，否则会覆盖不同服务的身份或 HTTP 端口。更多说明见 [`backend/config/README.md`](backend/config/README.md)。

> 战斗规则更新发布后，新创建的对局读取新值；已经创建的对局（包括等待室）保留原规则。MySQL / Redis 连接配置变更需要重启使用该连接的服务；`ranking-retention-days` 也在 Scene 重启后应用。配置热更新不代表所有现有对象都会立即切换。
>
> 上述口令仅用于本地开发。生产环境必须更换默认口令和 Nacos 身份校验 / Token 配置，并限制数据库、Redis、Nacos 和内部 RPC / WebSocket 端口的访问。

### 4. 编译并启动五个后端服务

在仓库根目录编译全部模块：

```bash
mvn -f backend/pom.xml clean package -DskipTests
```

编译成功后，**分别打开五个终端，均进入仓库根目录，每个终端只执行下面的一条 Java 命令**。建议依次启动 Scene → Agent → AgentMgr → Login → Gateway，等待各自启动成功后再继续；不要把五条命令直接粘贴到同一前台终端等待自动执行。

```bash
# 终端1：Scene
java -jar backend/scene-server/target/scene-server-0.0.1-SNAPSHOT.jar
```

```bash
# 终端2：Agent
java -jar backend/agent-server/target/agent-server-0.0.1-SNAPSHOT.jar
```

```bash
# 终端3：AgentMgr
java -jar backend/agentmgr-server/target/agentmgr-server-0.0.1-SNAPSHOT.jar
```

```bash
# 终端4：Login
java -jar backend/login-server/target/login-server-0.0.1-SNAPSHOT.jar
```

```bash
# 终端5：Gateway
java -jar backend/gateway-server/target/gateway-server-0.0.1-SNAPSHOT.jar
```

JAR 文件名对应当前 POM 版本；以后改版本号时，请使用 `target/` 中实际生成的文件名，不要选择 `.jar.original`。当前链路不需要启动 `nodemgr-server`。

| 服务 | 默认 HTTP 端口 | 默认 WebSocket 端口 / 路径 |
| --- | --- | --- |
| Gateway | `8080` | `9001/ws`，浏览器入口 |
| Login | `8081` | — |
| AgentMgr | `8082` | — |
| Agent | `8083` | `9101/internal/agent`，内部通信 |
| Scene | `8084` | `9201/internal/scene`，内部通信 |

确认 Nacos 的同一命名空间、`DEFAULT_GROUP` 中出现这五个服务，且日志没有配置读取、数据库连接或端口占用错误，再启动客户端。端口以最终配置为准，浏览器应连接 `9001`，不是 `8080`。

### 5. 启动 Web 客户端并验证多人对局

先确认 [`frontend/public/client-config.js`](frontend/public/client-config.js) 中的地址与网关部署位置一致，默认连接本机：

```javascript
window.BALLS_CLIENT_CONFIG = { gatewayUrl: 'ws://127.0.0.1:9001/ws' };
```

该配置文件非空时优先于浏览器保存地址和环境变量，不能只改 `.env`。修改后刷新页面即可；有旧缓存时按 Ctrl+F5。

另开终端，从仓库根目录执行：

```bash
cd frontend
npm ci
npm run dev
```

浏览器打开 `http://127.0.0.1:5173`。首次使用先注册、再登录，不会自动生成演示账号。要验证多人对战，打开两个浏览器窗口，分别登录**两个不同邮箱账号**，开始比赛后确认进入同一局：第一人显示等待，达到默认两人后才开始计时和战斗。同一账号登录两次会踢掉旧连接，不能用来模拟两名玩家。本机验证建议只启动一个 Scene 实例，避免被分配到不同实例的房间。

若 `5173` 已被占用，先访问已有页面，或在原来的 Vite 终端按 Ctrl+C 后重新启动，不要反复开多个开发服务。手机或其他电脑访问时，网关地址不能写 `127.0.0.1`；还需开放 Vite 的局域网监听、放行对应端口，HTTPS 页面则需要可用的 `wss://` 网关。

## 测试

后端（真实 Akka、MyBatis-Plus/H2、离线 Redis 替身；覆盖 Actor 生命周期、指令分发、请求去重、结算与路由校验）。在仓库根目录执行：

```bash
mvn -f backend/pom.xml -pl gateway-server,login-server,agentmgr-server,agent-server,scene-server -am clean test
```

可选的「真实基础设施」检查（先按上述步骤配置 Nacos 并建表，只读校验、不写业务数据）。测试会启动 Netty 监听，请先停止正在运行的五个 Java 服务以免内部端口冲突，保留基础设施运行。在仓库根目录执行：

```bash
mvn -f backend/pom.xml -pl gateway-server,login-server,agentmgr-server,agent-server,scene-server -am clean test '-Dballs.infrastructure-test=true'
```

前端（离线回归：原包重试、临时错误、迟到响应、跨连接隔离、离场恢复、插值与权威状态分离）。另开终端，从仓库根目录进入 `frontend/`，安装依赖后执行：

```bash
cd frontend
npm ci
npm test # Node test runner 离线回归
npm run protocol:check -- --backend ../backend # 显式指定仓库内后端，避免依赖作者本机路径
npm run build # 类型检查 + 生产构建
```

## 仓库结构

```text
BallsGame/
├── backend/                          # Java 服务端（Maven 多模块）
│   ├── game-protocol/                #   Protobuf 协议定义与编解码
│   ├── game-common/                  #   命令枚举、错误码、注解注册表等公共设施
│   ├── game-core/                    #   Akka / Netty 基础库
│   ├── gateway-server/               #   接入网关（二进制 WS、协议分发）
│   ├── login-server/                 #   账号服务（注册 / 登录 / MySQL）
│   ├── agentmgr-server/              #   登录生命周期与在线路由仲裁
│   ├── agent-server/                 #   玩家会话 Actor 层
│   ├── scene-server/                 #   战斗场景权威模拟
│   ├── nodemgr-server/               #   预留模块（未接入链路）
│   ├── config/                       #   Nacos 配置说明与战斗规则示例
│   └── docker-compose.yml            #   MySQL / Redis / Nacos 本地环境
└── frontend/                         # TypeScript Web 客户端
    ├── src/network/gateway.ts        #   WebSocket 连接、请求匹配、超时重试
    ├── src/game/                     #   权威快照、渲染器、输入、消息处理
    ├── src/protocol/                 #   协议生成物与编解码
    ├── scripts/sync-protocol.mjs     #   后端 proto → 前端 schema 同步工具
    └── tests/                        #   离线回归测试与浏览器冒烟页
```

## 文档索引

| 文档 | 内容 |
| --- | --- |
| [`frontend/README.md`](frontend/README.md) | 客户端功能、操作说明、连接配置与可靠性细节 |
| [`frontend/UI_DESIGN.md`](frontend/UI_DESIGN.md) | 界面视觉与渲染设计说明 |
| [`backend/gateway-server/README-protocol.md`](backend/gateway-server/README-protocol.md) | 网关协议、登录链路与内部长连接约定 |
| [`backend/scene-server/README.md`](backend/scene-server/README.md) | 战斗规则、固定步长主循环、幂等与排名存储设计 |
| [`backend/config/README.md`](backend/config/README.md) | Nacos 配置结构、启动检查与指令日志说明 |

## 已知边界

本项目为**从零实践**性质的完整实现，以下边界如实声明：

- 战斗状态为内存态：未实现 Scene 进程崩溃后的对局恢复、战斗回放与跨实例迁移；
- 未实现奖励发放、好友、背包、房间列表等外围系统；
- 对局内球体碰撞当前为硬上限下的两两检查，尚未做全量空间索引优化；
- 默认口令与端口仅适用于本地开发，生产部署需要自行加固。
