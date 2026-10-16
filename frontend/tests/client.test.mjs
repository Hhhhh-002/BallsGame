import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, extname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';
import ts from 'typescript';

// 原 TS 模块在内存转译，替换浏览器 WS/时钟，不连接真实后端，不产生源文件生成物。
const project = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const requireDependency = createRequire(resolve(project, 'package.json'));
function fixture() {
  let now = 0, ids = 0;
  const timers = new Map();
  const keyListeners = new Map();
  const clock = {
    now: () => now,
    advance(ms) {
      const end = now + ms;
      for (;;) {
        const next = [...timers.entries()].filter(([, task]) => task.at <= end).sort((a, b) => a[1].at - b[1].at)[0];
        if (!next) break;
        now = next[1].at; timers.delete(next[0]); next[1].run();
      }
      now = end;
    },
  };
  class Socket {
    static OPEN = 1;
    static instances = [];
    readyState = 0; bufferedAmount = 0; sent = [];
    constructor() { Socket.instances.push(this); }
    send(bytes) { this.sent.push(bytes.slice()); }
    close(code = 1000, reason = '') { this.readyState = 3; this.onclose?.({ wasClean: code === 1000, reason }); }
    open() { this.readyState = 1; this.onopen(); }
    receive(bytes) { this.onmessage({ data: bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) }); }
  }
  const context = vm.createContext({
    console, Error, String, Number, URL, ArrayBuffer, Uint8Array, WebSocket: Socket, location: { protocol: 'http:' },
    performance: { now: clock.now }, AbortController,
    HTMLInputElement: class {}, HTMLTextAreaElement: class {},
    window: { addEventListener: (name, listener) => keyListeners.set(name, listener) },
    document: { hidden: false, addEventListener() {}, querySelector: () => null },
    setTimeout: (run, delay) => { const id = ++ids; timers.set(id, { at: now + delay, run }); return id; },
    clearTimeout: id => timers.delete(id), setInterval: () => ++ids, clearInterval: () => {},
  });
  const modules = new Map();
  function load(file) {
    if (modules.has(file)) return modules.get(file).exports;
    const module = { exports: {} }; modules.set(file, module);
    const source = ts.transpileModule(readFileSync(file, 'utf8'), {
      compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS, esModuleInterop: true },
    }).outputText;
    const require = name => {
      if (!name.startsWith('.')) return requireDependency(name);
      const path = resolve(dirname(file), name);
      if (extname(path) === '.json') return JSON.parse(readFileSync(path, 'utf8'));
      return load(`${path}.ts`);
    };
    vm.runInContext(`(function(require,module,exports){${source}\n})`, context, { filename: file })(require, module, module.exports);
    return module.exports;
  }
  const { Gateway } = load(resolve(project, 'src/network/gateway.ts'));
  const protocol = load(resolve(project, 'src/protocol/codec.ts'));
  const { World } = load(resolve(project, 'src/game/world.ts'));
  const { GameCommandRegistry } = load(resolve(project, 'src/game/registry.ts'));
  const { registerGameHandlers } = load(resolve(project, 'src/game/handlers.ts'));
  const { Input } = load(resolve(project, 'src/game/input.ts'));
  const gateway = new Gateway();
  return {
    gateway, protocol, World, clock, GameCommandRegistry, registerGameHandlers, Input,
    key(name, key) { keyListeners.get(name)({ key, target: null, repeat: false, preventDefault() {} }); },
    async connect() { const connected = gateway.connect('ws://127.0.0.1:9001/ws'); const socket = Socket.instances.at(-1); socket.open(); await connected; return socket; },
    reply(socket, ticket, code = 0) { socket.receive(protocol.encode('ServerEnvelope', { cmd: ticket.cmd, requestId: ticket.id, code })); },
  };
}

test('超时重试沿用同一 requestId 和完整字节，成功响应只完成一次', async () => {
  const f = fixture(), socket = await f.connect();
  const ticket = f.gateway.prepare(f.protocol.CMD.START_GAME, 'StartGameRequest', { nickname: '测试' });
  const result = f.gateway.execute(ticket, 100, 2);
  f.clock.advance(100); assert.equal(socket.sent.length, 1);
  f.clock.advance(250); assert.equal(socket.sent.length, 2); assert.deepEqual(socket.sent[0], socket.sent[1]);
  const decoded = f.protocol.decode('ClientEnvelope', socket.sent[1]);
  assert.equal(decoded.requestId, ticket.id); assert.ok(BigInt(decoded.requestId) > BigInt(Number.MAX_SAFE_INTEGER));
  f.reply(socket, ticket); assert.equal((await result).requestId, ticket.id);
  f.clock.advance(1000); assert.equal(socket.sent.length, 2);
});

test('结算只清理本地输入，不发送停止移动；下一局不会继续旧按键', () => {
  const f = fixture(), shifts = [], actions = [];
  const input = new f.Input({ addEventListener() {} }, {}, (x, y) => shifts.push([x, y]), (...args) => actions.push(args));
  input.enabled = true; f.key('keydown', 'd'); input.update();
  assert.deepEqual(shifts, [[1, 0]]);
  input.stop(false); input.enabled = false;
  f.clock.advance(11000); input.update(); f.key('keydown', 'e'); input.trigger('split');
  assert.deepEqual(shifts, [[1, 0]]); assert.equal(actions.length, 0);
  input.enabled = true; input.update(); // 旧按键已清空，只会按周期发零方向，不会继续向右。
  assert.deepEqual(shifts.at(-1), [0, 0]);
  f.key('keydown', 'w'); input.update(); assert.deepEqual(shifts.at(-1), [0, -1]);
  input.destroy();
});

test('普通比赛中停止移动仍发送零方向', () => {
  const f = fixture(), shifts = [];
  const input = new f.Input({ addEventListener() {} }, {}, (x, y) => shifts.push([x, y]), () => {});
  input.enabled = true; f.key('keydown', 'd'); input.update(); input.stop();
  assert.deepEqual(shifts, [[1, 0], [0, 0]]); input.destroy();
});

test('结算取消旧分裂和吐球重试，不影响非战斗请求及下一局输入', async () => {
  const f = fixture(), socket = await f.connect();
  const split = f.gateway.prepare(f.protocol.CMD.SPLIT, 'SplitRequest', {});
  const eject = f.gateway.prepare(f.protocol.CMD.EJECT, 'EjectRequest', {});
  const leave = f.gateway.prepare(f.protocol.CMD.LEAVE, 'LeaveRequest', {});
  const splitResult = assert.rejects(f.gateway.execute(split, 100, 2), /本局已结算/);
  const ejectResult = assert.rejects(f.gateway.execute(eject, 100, 2), /本局已结算/);
  const leaveResult = f.gateway.execute(leave, 1000);
  f.gateway.cancelRequests([f.protocol.CMD.SPLIT, f.protocol.CMD.EJECT], '本局已结算');
  await Promise.all([splitResult, ejectResult]); f.clock.advance(500);
  assert.equal(socket.sent.length, 3);
  f.reply(socket, split); f.reply(socket, eject); f.reply(socket, leave); await leaveResult;
  const next = f.gateway.prepare(f.protocol.CMD.SPLIT, 'SplitRequest', {});
  const nextResult = f.gateway.execute(next); f.reply(socket, next); await nextResult;
  assert.equal(socket.sent.length, 4);
});

test('注册 Handler 完整呈现服务端战斗消息，不在浏览器自行吞食或碰撞', () => {
  const f = fixture(), world = new f.World(), results = [], kicks = [];
  let active = true, changed = 0;
  const registry = f.registerGameHandlers(new f.GameCommandRegistry(), world, {
    accepts: scene => active && (!scene || !world.sceneId || world.sceneId === scene),
    changed: () => changed++, ranking: (result, ended) => { results.push(result); if (ended) active = false; },
    kicked: reason => kicks.push(reason),
  });
  const push = (cmd, type, body) => registry.dispatch({ cmd, requestId: '0', code: 0, message: '', body: f.protocol.encode(type, body) });
  const own = { ballId: '1', userId: 'u', nickname: '本局昵称', x: 10, y: 10, radius: 5 };
  const other = { ballId: '2', userId: 'v', nickname: '对手', x: 40, y: 40, radius: 10 };
  push(f.protocol.CMD.SCENE_SNAPSHOT, 'SceneSnapshot', { sceneId: '99', serverTick: '100', tickRate: 25, width: 100, height: 100,
    balls: [own, other], foods: [{ foodId: '25', x: 10, y: 10 }] });
  assert.equal(world.owned('u').length, 1); assert.equal(world.foods.size, 1); // 即使坐标重叠也不自行判定吃食物。
  push(f.protocol.CMD.MOVE, 'MoveBroadcast', { serverTick: '105', players: [{ ...own, x: 11 }] });
  assert.equal(world.owned('u')[0].x, 11); assert.equal(world.foods.size, 1);
  const eat = { ballId: '1', foodId: '25', radius: Math.sqrt(26) };
  push(f.protocol.CMD.FOOD_EATEN, 'FoodEatenEvent', eat);
  assert.equal(world.foods.size, 0); assert.ok(Math.abs(world.owned('u')[0].radius - Math.sqrt(26)) < 0.00001);
  const effects = world.effects.length; push(f.protocol.CMD.FOOD_EATEN, 'FoodEatenEvent', eat);
  assert.equal(world.effects.length, effects); // 重复推送不会重复吞食效果或自行加分。
  const split = { upserts: [{ ...own, radius: Math.sqrt(13) }, { ...own, ballId: '3', x: 20, radius: Math.sqrt(13) }], removedBallIds: [] };
  push(f.protocol.CMD.BALL_CHANGED, 'BallChangedEvent', split);
  assert.equal(world.owned('u').length, 2);
  assert.ok(Math.abs(world.players().find(player => player.userId === 'u').area - 26 * Math.PI) < .0001);
  const splitEffects = world.effects.length; push(f.protocol.CMD.BALL_CHANGED, 'BallChangedEvent', split);
  assert.equal(world.effects.length, splitEffects);
  push(f.protocol.CMD.FOOD_ADDED, 'FoodAddedEvent', { foods: [{ foodId: '26', x: 15, y: 10 }, { foodId: '27', x: 25, y: 10 }] });
  push(f.protocol.CMD.BALL_CHANGED, 'BallChangedEvent', { upserts: [{ ...own, radius: Math.sqrt(12) },
    { ...own, ballId: '3', x: 20, radius: Math.sqrt(12) }], removedBallIds: [] });
  assert.equal(world.foods.size, 2); assert.ok(Math.abs(world.players().find(player => player.userId === 'u').area - 24 * Math.PI) < .0001);
  push(f.protocol.CMD.BALL_CHANGED, 'BallChangedEvent', { upserts: [{ ...own, radius: Math.sqrt(24) }], removedBallIds: ['3'] });
  assert.equal(world.owned('u').length, 1); // 服务端确认合并。
  push(f.protocol.CMD.BALL_CHANGED, 'BallChangedEvent', { upserts: [{ ...other, radius: Math.sqrt(124) }], removedBallIds: ['1'] });
  assert.equal(world.owned('u').length, 0); assert.equal(world.players()[0].userId, 'v'); // 可跟随仍有球的对手观战。
  const ranking = { sceneId: '99', sceneInstanceId: 'scene', serverTick: '106', startedAtMs: '1000', endsAtMs: '601000',
    players: [{ userId: 'v', nickname: '对手', area: 124 * Math.PI, rank: 1, left: false },
      { userId: 'u', nickname: '本局昵称', area: 0, rank: 2, left: false }] };
  push(f.protocol.CMD.MATCH_RANKING, 'MatchRanking', ranking);
  push(f.protocol.CMD.MATCH_ENDED, 'MatchRanking', { ...ranking, frozen: true, endedAtMs: '601000', endReason: 1 });
  assert.equal(results.length, 2); assert.equal(results[1].players[1].nickname, '本局昵称'); assert.equal(results[1].frozen, true);
  push(f.protocol.CMD.MOVE, 'MoveBroadcast', { serverTick: '107', players: [{ ...other, x: 99 }] });
  assert.equal(world.balls.get('2').x, 40); // 结束后的推送不再推进已冻结画面。
  registry.dispatch({ cmd: f.protocol.CMD.KICKED_OFFLINE, requestId: '0', code: 40020, message: '顶号下线', body: new Uint8Array() });
  assert.deepEqual(kicks, ['顶号下线']); assert.ok(changed > 0);
});

test('进入、离场和全量校正按服务端消息执行；未注册命令不改变世界', () => {
  const f = fixture(), world = new f.World();
  const registry = f.registerGameHandlers(new f.GameCommandRegistry(), world, {
    accepts: scene => !scene || !world.sceneId || scene === world.sceneId, changed() {}, ranking() {}, kicked() {},
  });
  const push = (cmd, type, body) => registry.dispatch({ cmd, requestId: '0', code: 0, message: '', body: f.protocol.encode(type, body) });
  const ball = { ballId: '1', userId: 'u', nickname: '用户', x: 10, y: 10, radius: 2 };
  push(f.protocol.CMD.SCENE_SNAPSHOT, 'SceneSnapshot', { sceneId: '99', serverTick: '10', tickRate: 25, width: 100, height: 100, balls: [ball] });
  push(f.protocol.CMD.PLAYER_ENTERED, 'PlayerEnteredEvent', { balls: [{ ...ball, ballId: '2', userId: 'v' }] });
  assert.equal(world.players().length, 2);
  push(f.protocol.CMD.PLAYER_LEFT, 'PlayerLeftEvent', { userId: 'v', ballIds: ['2'] });
  assert.equal(world.balls.has('2'), false);
  push(f.protocol.CMD.SCENE_SNAPSHOT, 'SceneSnapshot', { sceneId: '99', serverTick: '20', tickRate: 25, width: 100, height: 100,
    balls: [{ ...ball, x: 50 }], foods: [{ foodId: '5', x: 60, y: 60 }] });
  assert.equal(world.balls.get('1').x, 50); assert.equal(world.foods.size, 1);
  push(f.protocol.CMD.SCENE_SNAPSHOT, 'SceneSnapshot', { sceneId: '88', serverTick: '30', tickRate: 25, width: 100, height: 100, balls: [] });
  assert.equal(world.sceneId, '99'); assert.equal(world.balls.size, 1); // 不能用其他场景的快照覆盖当前局。
  assert.equal(registry.dispatch({ cmd: 99999, requestId: '0', code: 0, body: new Uint8Array(), message: '' }), false);
  assert.equal(world.balls.size, 1);
  assert.throws(() => registry.register({ cmd: f.protocol.CMD.MOVE, handle() {} }), /重复/);
});

test('错误推送不会执行游戏 Handler，最终榜必须冻结', () => {
  const f = fixture(), world = new f.World(); let rankCalls = 0;
  const registry = f.registerGameHandlers(new f.GameCommandRegistry(), world, { accepts: () => true, changed() {},
    ranking() { rankCalls++; }, kicked() {} });
  assert.equal(registry.dispatch({ cmd: f.protocol.CMD.BALL_CHANGED, code: 5001, requestId: '0', message: '', body: new Uint8Array() }), false);
  assert.equal(world.balls.size, 0);
  const body = f.protocol.encode('MatchRanking', { sceneId: '99', frozen: false });
  assert.throws(() => registry.dispatch({ cmd: f.protocol.CMD.MATCH_ENDED, code: 0, requestId: '0', message: '', body }), /没有冻结/);
  assert.equal(rankCalls, 0);
});

test('临时服务错误有限重试原包，不生成新请求', async () => {
  const f = fixture(), socket = await f.connect();
  const ticket = f.gateway.prepare(f.protocol.CMD.SPLIT, 'SplitRequest', { directionX: 1 });
  const result = f.gateway.execute(ticket, 1000, 2);
  f.reply(socket, ticket, 5002); f.clock.advance(250);
  assert.equal(socket.sent.length, 2); assert.deepEqual(socket.sent[0], socket.sent[1]);
  f.reply(socket, ticket); assert.equal((await result).code, 0);
});

test('明确业务失败不重试；迟到成功或错误不会再次变成主动推送', async () => {
  const f = fixture(), socket = await f.connect(); const pushes = [];
  f.gateway.onMessage = message => pushes.push(message);
  const ticket = f.gateway.prepare(f.protocol.CMD.EJECT, 'EjectRequest', { directionX: 1 });
  const result = assert.rejects(f.gateway.execute(ticket, 1000, 2), error => error.code === 40105 && !error.uncertain);
  f.reply(socket, ticket, 40105); await result; f.clock.advance(2000);
  assert.equal(socket.sent.length, 1);
  f.reply(socket, ticket); f.reply(socket, ticket, 5001); assert.equal(pushes.length, 0);
  socket.receive(f.protocol.encode('ServerEnvelope', { cmd: f.protocol.CMD.MOVE, requestId: '0' }));
  assert.equal(pushes.length, 1);
});

test('重试耗尽明确报告结果未知，手动重试同一离场 Ticket 仍用原编号', async () => {
  const f = fixture(), socket = await f.connect();
  const ticket = f.gateway.prepare(f.protocol.CMD.LEAVE, 'LeaveRequest', {});
  const failed = assert.rejects(f.gateway.execute(ticket, 100, 2), error => error.uncertain);
  f.clock.advance(100); f.clock.advance(250); f.clock.advance(100); await failed;
  const result = f.gateway.execute(ticket, 100, 2);
  assert.equal(socket.sent.length, 3); assert.deepEqual(socket.sent[0], socket.sent[2]);
  f.reply(socket, ticket); await result;
});

test('新连接不能发送旧会话 Ticket', async () => {
  const f = fixture(); await f.connect();
  const ticket = f.gateway.prepare(f.protocol.CMD.START_GAME, 'StartGameRequest', {});
  const socket = await f.connect();
  await assert.rejects(f.gateway.execute(ticket), /连接已变化/); assert.equal(socket.sent.length, 0);
});

test('LOGIN 不自动重发；超时断开，不生成第二次登录', async () => {
  const f = fixture(), socket = await f.connect();
  const ticket = f.gateway.prepare(f.protocol.CMD.LOGIN, 'LoginRequest', { account: 'a@example.com', password: 'Secret!123' });
  const failed = assert.rejects(f.gateway.execute(ticket, 100, 3), error => error.uncertain);
  f.clock.advance(100); await failed; f.clock.advance(1000);
  assert.equal(socket.sent.length, 1); assert.equal(f.gateway.open, false);
});

test('SHIFT 仅发送方向及递增操作序号，不伪造客户端坐标或吃球结果', async () => {
  const f = fixture(), socket = await f.connect();
  f.gateway.shift(1, 0); f.gateway.shift(0, 0);
  const first = f.protocol.decode('ClientEnvelope', socket.sent[0]);
  const second = f.protocol.decode('ClientEnvelope', socket.sent[1]);
  assert.equal(first.requestId, '0'); assert.equal(first.clientSequence, '1'); assert.equal(second.clientSequence, '2');
  const body = f.protocol.decode('ShiftRequest', first.body);
  assert.equal(body.directionX, 1); assert.equal(body.directionY, 0); assert.equal(body.x, undefined);
});

test('客户端仅插值渲染，不修改服务端权威位置；忽略旧 Tick，缺样本时停止外推', () => {
  const f = fixture(), world = new f.World();
  world.snapshot({ sceneId: '99', serverTick: '100', width: 100, height: 100, tickRate: 25,
    balls: [{ ballId: '1', userId: 'u', nickname: '本局', x: 10, y: 10, radius: 2 }], foods: [] });
  f.clock.advance(200);
  world.move('105', [{ ballId: '1', userId: 'u', x: 20, y: 10, radius: 2 }]);
  const ball = world.balls.get('1');
  assert.equal(world.position(ball, 320).x, 15); assert.equal(ball.x, 20);
  world.move('104', [{ ballId: '1', userId: 'u', x: 999, y: 999, radius: 2 }]);
  assert.equal(world.balls.get('1').x, 20); assert.equal(world.position(ball, 100000).x, 20);
  assert.equal(world.lastTick, 105n);
});

test('首个快照包含食物和等候状态；第二人入场后按服务端快照开赛', () => {
  const f = fixture(), world = new f.World();
  const snapshot = { sceneId: '99', serverTick: '0', width: 100, height: 100, tickRate: 25,
    balls: [{ ballId: '1', userId: 'a', nickname: 'a', x: 10, y: 10, radius: 2 }],
    foods: Array.from({ length: 50 }, (_, i) => ({ foodId: String(i + 1), x: i, y: i })),
    started: false, minPlayersToStart: 2, playerCount: 1 };
  const decoded = f.protocol.decode('SceneSnapshot', f.protocol.encode('SceneSnapshot', snapshot));
  world.snapshot(decoded);
  assert.equal(world.foods.size, 50); assert.equal(world.started, false);
  assert.equal(world.playerCount, 1); assert.equal(world.minPlayersToStart, 2);
  world.snapshot({ ...decoded, started: true, playerCount: 2 });
  assert.equal(world.started, true); assert.equal(world.playerCount, 2);
  world.reset(); assert.equal(world.started, false); assert.equal(world.playerCount, 0);
});
