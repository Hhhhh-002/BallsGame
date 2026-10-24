# 球球 Web 客户端

独立的 TypeScript + Vite + Canvas 客户端，对接本仓库 `backend/` 中的 Gateway 二进制 WebSocket 协议。不依赖 Java 项目参与前端启动或构建，不直接访问 Agent、Scene、Redis 或数据库。完整环境初始化步骤见[仓库首页](../README.md#快速开始)。

## 在 VS Code 运行

需要 Node.js 18+ / npm。在 VS Code 打开本仓库的 `frontend/` 目录，在终端执行：

```powershell
npm ci
npm run dev
```

浏览器打开 **http://127.0.0.1:5173**。后续依赖和锁文件未变时，直接执行 `npm run dev` 即可。若端口已被占用，可访问已有页面，或在原 Vite 终端按 Ctrl+C 停止后再启动。

也可使用 VS Code 的浏览器调试配置连接开发服务。生产构建执行 `npm run build`，本地查看构建产物执行 `npm run preview`。

当前项目兼容本机 Node.js 18.19.0；Vite 使用支持 Node 18 的 6.x 分支。[Vite 官方说明](https://vite.dev/blog/announcing-vite6)

## 连接后端

网关地址在 **`public/client-config.js`** 中配置，默认连接本机网关。首次启动时，确认地址与实际部署一致：

```javascript
window.BALLS_CLIENT_CONFIG = {
  gatewayUrl: 'ws://127.0.0.1:9001/ws',
};
```

修改 IP、端口或路径并保存后，刷新网页即可生效，不需要重启开发服务。若浏览器缓存了旧文件，按 Ctrl+F5 强制刷新。生产构建会将此文件原样复制到 `dist/client-config.js`；部署后可直接修改该文件并刷新网页，无需重新构建。

每次加载页面时，地址优先级为：**配置文件非空地址 → 浏览器保存的地址 → `VITE_GATEWAY_URL` 环境变量 → `ws://127.0.0.1:9001/ws` 默认地址**。旧的浏览器设置不会覆盖配置文件。

右上角设置按钮仍可修改当前页面的连接地址；更换网关会断开旧连接，需要重新登录。配置文件地址非空时，刷新页面后会重新使用配置文件的地址。若希望长期使用页面设置，将配置文件中的 `gatewayUrl` 改为 `''`。

也可以复制 `.env.example` 为 `.env.local`，设置 `VITE_GATEWAY_URL` 后重启开发服务；该环境变量仅在配置文件地址和浏览器保存地址均为空时使用。

注意：9001 是 Gateway 的 WebSocket 端口，不是 Spring HTTP 的 8080 端口。实际地址以你 Nacos 中的 Gateway 配置为准。HTTPS 部署时需要 `wss://`；手机访问时应使用服务器局域网 IP，不能用手机自己的 `127.0.0.1`。

完整游戏需要启动 Gateway、Login、AgentMgr、Agent、Scene 及其依赖。注册只需要 Gateway、Login、Nacos 与 Login 的 MySQL 账号表，不依赖 AgentMgr 或战斗服务。已有账号可直接登录，新账号点击“还没有账号？注册邮箱”；不会创建演示账号或使用模拟服务器。

## 操作与功能

### 界面与战场显示

客户端界面使用浅蓝控制台与深蓝战场，包含凝胶球高光、自己的球轮廓与“我”标记、彩色能量颗粒、低对比度网格、小地图和清晰的 HUD。登录、注册、观战、结算和手机布局使用同一套视觉样式。

分裂 / 新球、增长 / 合并、球消失和吞食的粒子与光环继续读取已有服务端事件；新增食物有短暂闪光。所有特效仅属于 Renderer，不修改球的位置、半径、插值样本、碰撞或分数，也不会在收到服务端状态前假装操作成功。

球体纹理预绘制复用，视野外对象跳过绘制，网格仅绘制可见范围；大量细小食物按颜色合批。浏览器开启“减少动态效果”时，示意背景停止漂浮，粒子改为低强度固定轮廓。设计说明见 `UI_DESIGN.md`。

- 邮箱账号登录；输入服务端要求的密码。错误提示直接显示服务端响应，密码不写入本地存储或日志。
- 邮箱注册：输入密码及确认密码；账号与密码规则由 Login 校验，重复邮箱显示“该邮箱已被注册”。注册成功切回登录，保留邮箱但清空密码，不自动登录。
- 登录后设置本局昵称并开始比赛；留空昵称由服务端处理。
- 鼠标指向移动，或 WASD / 方向键移动；鼠标离开战场、页面失焦时发送停止方向。
- 空格分裂、E 吐球；也可以点击操作按钮。球的尺寸不足等限制由服务端判定。
- 触屏在战场内按住并拖动控制方向，使用按钮分裂、吐球。
- 离开比赛返回大厅，账号仍保持登录；断开连接后需要重新登录。
- 显示球、食物、进入/离开事件、分裂变化、吞噬变化、我的总面积、实时排行榜及最终结算。
- 球变化和食物吞食的短时视觉反馈、小地图及相机跟随均使用服务端状态，不参与碰撞或得分计算。
- 所有球被吞噬后进入观战界面，可选择仍有球的玩家跟随；“离场后再开一局”必须先收到 LEAVE 成功，再提交新的 START_GAME，不假装原地复活。
- 显示当前球数；分裂/吐球等待服务端确认期间暂停重复点击。当前服务端两种动作要求至少一个半径达到 3 的球，客户端提示该门槛，上限和冷却仍由服务端最终判定。
- 最终榜显示服务端返回的完整排名，而非只显示前五名，并显示本局昵称、个人名次、面积及结算时未离场人数。
- 支持缩放、全屏、连接日志、延迟显示和移动端布局。

未入场的背景是明确标注的画面示意，不代表实际玩家或战斗数据。客户端不执行碰撞、吞噬、加分或权威移动计算。

## 协议与可靠性

运行时使用 `src/protocol/schema.json` 中的客户端协议定义。64 位 Scene、Ball、Food、请求和 Tick ID 按字符串/BigInt 处理，避免 JavaScript Number 丢失精度。

服务端默认逻辑 25 Hz、位置广播 5 Hz；客户端使用 `requestAnimationFrame` 渲染，按服务端 Tick 在约 220 ms 的样本缓冲上插值。没有后续权威样本时保持最后位置，不无限预测。渲染频率不等于服务端逻辑频率。

房间创建时默认随机生成 50 个食物；单人入场显示“等待玩家 1/2”，不开始计时或发送战斗输入。第二名玩家到场后，服务端全量快照的 started=true 才启用移动/分裂/吐球；开赛后离场不会把比赛重新暂停。对应 Nacos 规则为 battle.initial-foods、battle.min-players-to-start。

START_GAME、LEAVE 每次等待 20 秒，SPLIT、EJECT 每次等待 10 秒（覆盖 Agent 默认 15 秒的动作确认等待），最多发送两次。等待超时或临时服务错误（5001/5002）时，间隔 250ms 重发原 request_id 和完整字节；明确业务失败不自动重试。
入场结果不确定时保留原请求，可重试本次入场或取消清理；离场结果不确定时也保留原 Ticket，再点击使用同一 requestId，期间禁止开始新比赛及发送战斗操作。不会把结果未知当作业务没执行。
迟到、重复且没有等待者的非零 requestId 回包不作为新推送处理；主动推送 requestId=0 才进入状态处理。切换连接后拒绝旧 Ticket，旧登录/动作回调不能覆盖新的页面会话。
SHIFT 使用递增 client_sequence，按方向变化限速发送；不为每条移动保存响应。

LOGIN 等待最多 100 秒，不自动重发：原 LOGIN requestId 已贯通后端，不再生成额外登录编号；但 Gateway 入口尚未提供完整登录响应缓存重放，因此客户端仍不能承诺登录成功回包丢失后可以安全重放该回包。登录超时断开连接；不会在后台自动重连并登录。

REGISTER（1002）通过 Gateway 的 Feign 调用 Login 的 REGISTER_ACCOUNT（10018）。Gateway 默认等待预算 10 秒（`gateway.rpc.register-timeout-ms`），网页等待 12 秒。注册没有业务操作 ID，不自动重试；数据库唯一主键保证不覆盖已有账号。网络失败可能发生在 INSERT 成功后，页面提示结果未确认，可返回登录尝试同一邮箱，不会把重复邮箱伪装成注册成功。邮箱大小写规则沿用 `utf8mb4_bin`，不自动 trim 或转换大小写。当前仅检查邮箱格式，没有邮件验证。

现有后端没有浏览器业务心跳命令，浏览器也不能主动发送 WebSocket Ping。当前兼容方案每 20 秒发送保留命令 `cmd=0`，网关明确返回 `4002` 未知命令，没有登录或战斗副作用；客户端只消费这条请求对应的错误回包，用于保活和 RTT。若下一次检测仍未收到回包，则断开连接。以后后端增加正式心跳协议时，应替换此兼容方案。

## 协议更新

后端协议修改后，在 `frontend/` 目录显式指定同仓库的 `../backend`，手动执行：

```powershell
npm run protocol:sync -- --backend ../backend
npm run protocol:check -- --backend ../backend
npm run build
```

生成脚本默认读取当前客户端目录旁的 `../backend`，也可直接执行 `npm run protocol:sync` / `npm run protocol:check`。脚本忽略 LF / CRLF 换行差异，Windows 检出不会仅因换行变化误报协议落后。同步仅更新本客户端的 schema，不修改后端文件。后端放在其他位置时可指定：

```powershell
npm run protocol:sync -- --backend "D:\其他目录\BallsGame"
```

生成器仅提取客户端所需消息和 GameCommands，不带内部 Feign 协议或账号密码哈希实体。后端增加新命令时还需要补充对应客户端处理；同步 schema 不会自动实现业务。

## 服务端权威与主循环位置

服务端主循环位于 [`backend/scene-server/src/main/java/com/h/sceneserver/actor/SceneActor.java`](../backend/scene-server/src/main/java/com/h/sceneserver/actor/SceneActor.java)：

- `preStart/createReceive`：Akka 单次定时器每约 10ms 唤醒 Tick，只是检查时间，不代表逻辑 100Hz。
- `advance`：单调时钟累计 elapsed，按 `1 / tickRate` 固定步长调用 `update`，默认 25Hz、每次最多补算 5 Tick，超过上限放弃超额积压，不伪增 tick。
- `update`：服务端计算移动、食物、吞噬等权威状态。
- `moveBroadcast`：默认 5Hz 广播位置；频率与数量上限从 Nacos 的 `battle.*` 创建比赛时加载。

浏览器 `src/game/renderer.ts` 使用 requestAnimationFrame 渲染，`src/game/world.ts` 只处理权威快照/事件及显示插值。客户端不运行权威物理循环、不上报自己判定的吃球或吃食物结果，也不连接内部 Agent/Scene。

## 离线回归测试

执行 `npm test` 验证原包重试、临时错误、迟到响应、跨连接隔离、离场 Ticket 复用、登录不自动重发、SHIFT 序号以及显示插值与权威状态分离。
测试在内存转译真实 TS 模块，替换 WebSocket 和时钟，不模拟服务端战斗规则、不连接外部服务。最终检查使用 `npm test`、`npm run protocol:check`、`npm run build`；通过不代表已完成真实后端联调。

游戏推送由 `src/game/registry.ts` 按 cmd 分发至 `src/game/handlers.ts` 注册的 Handler，覆盖全量场景、位置、玩家进入/离开、食物增删、球增删、实时榜、最终榜和被踢通知。没有客户端碰撞或吞食请求。

新增离线场景覆盖入场→移动→吞食消息→分裂→吐球消息→合并→被吞噬→排行榜/结束，全量校正、重复推送和非法最终榜；处理后的权威状态只取服务端消息，不由客户端物理推导。

浏览器页面回归入口：启动 `npm run dev` 后打开 `/tests/browser-smoke.html`，测试页面单独替换 WS 并操作真实 UI，检查观战、离场再开、完整结算及昵称安全展示；该测试页面不被正式 `index.html` 引用，也不打进生产包。它不是联调服务器或单机游戏模式。

当前后端没有开放历史战绩查询、好友、背包或房间列表协议，因此客户端不伪造这些功能。最终榜按现有后端返回展示；“提前离场不写战绩”的 MySQL 战绩结算仍需后端单独实现，本次不在网页过滤后冒充已经存库。

## 目录

```text
src/main.ts                 页面状态与业务链路
src/network/gateway.ts      WebSocket、请求匹配、超时与重试
src/protocol/               协议生成物、编码解码及类型
src/game/world.ts           权威快照、事件及插值样本
src/game/renderer.ts        Canvas 渲染与相机
src/game/input.ts           键鼠和触屏输入
src/game/registry.ts        cmd 到游戏 Handler 的注册表
src/game/handlers.ts        服务端状态、事件及榜单处理
src/ui/layout.ts            页面结构
src/styles.css              响应式样式
scripts/sync-protocol.mjs   只读后端的协议同步工具
```

已验证类型检查、生产构建、与现有后端的协议一致性，以及浏览器桌面/手机布局；通过浏览器临时模拟协议回包检查登录、快照先到、原包重试、重复响应、排名结算、离场和被踢。模拟仅用于开发检查，未加入应用。**尚未启动真实后端进行账号和完整对局联调。**
