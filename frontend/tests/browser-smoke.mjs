// 仅供本地 Vite 下浏览器回归，不被 index.html 引用，也不打进生产应用。
// 替换 WS 传输并投递真实协议回包，不运行模拟战斗物理，不连接真实后端。
const { CMD, encode, decode } = await import('/src/protocol/codec.ts');
const user = 'a@example.com', sent = [];
let scene = 98, nickname = '本局玩家', socket;
const own = (id = '1', radius = 5, x = 20) => ({ ballId: id, userId: user, nickname, x, y: 25, radius });
const other = { ballId: '2', userId: 'other', nickname: '<svg/onload=alert(1)>', x: 60, y: 65, radius: 9 };
const food = { foodId: '1', x: 35, y: 25 };
function push(cmd, type, body) {
  socket.receive(encode('ServerEnvelope', { cmd, requestId: '0', body: encode(type, body) }));
}
function reply(request, type, body) {
  socket.receive(encode('ServerEnvelope', { cmd: request.cmd, requestId: request.requestId, body: type ? encode(type, body) : undefined }));
}
class FixtureSocket {
  static OPEN = 1;
  readyState = 0; bufferedAmount = 0;
  constructor() { socket = this; setTimeout(() => { this.readyState = 1; this.onopen(); }, 0); }
  receive(bytes) { this.onmessage({ data: bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) }); }
  close() { this.readyState = 3; this.onclose?.({ wasClean: true, reason: '回归测试关闭' }); }
  send(bytes) {
    const request = decode('ClientEnvelope', new Uint8Array(bytes)); sent.push(request);
    queueMicrotask(() => {
      if (request.cmd === CMD.LOGIN) reply(request, 'LoginResponse', { userId: user, player: { nickname: '玩家' } });
      if (request.cmd === CMD.START_GAME) {
        scene++; nickname = decode('StartGameRequest', request.body).nickname || '玩家';
        push(CMD.SCENE_SNAPSHOT, 'SceneSnapshot', { sceneId: String(scene), serverTick: '100', width: 100, height: 100, tickRate: 25,
          started: true, minPlayersToStart: 2, playerCount: 2,
          balls: [own(), other], foods: [food] });
        reply(request, 'StartGameResponse', { sceneId: String(scene) });
      }
      if (request.cmd === CMD.SPLIT) {
        push(CMD.BALL_CHANGED, 'BallChangedEvent', { upserts: [own('1', 5 / Math.sqrt(2)), own('3', 5 / Math.sqrt(2), 28)], removedBallIds: [] });
        reply(request);
      }
      if (request.cmd === CMD.EJECT) {
        push(CMD.FOOD_ADDED, 'FoodAddedEvent', { foods: [{ foodId: '2', x: 30, y: 25 }] }); reply(request);
      }
      if (request.cmd === CMD.LEAVE) reply(request);
    });
  }
}
window.WebSocket = FixtureSocket;
window.alert = () => { window.__unsafeNickname = true; };
const result = document.createElement('pre'); result.id = 'browser-test-result';
const check = (condition, message) => { if (!condition) throw new Error(message); };
const waitFor = async predicate => {
  for (let i = 0; i < 100; i++) { if (predicate()) return; await new Promise(resolve => setTimeout(resolve, 20)); }
  throw new Error('页面状态等待超时');
};
try {
  await import('/src/main.ts');
  const get = id => document.getElementById(id);
  get('account').value = user; get('password').value = 'Secret!123'; get('login-form').requestSubmit();
  await waitFor(() => !get('start-form').hidden);
  get('nickname').value = '测试本局昵称'; get('start-form').requestSubmit();
  await waitFor(() => get('arena-phase').textContent === '比赛进行中');
  check(!get('split').disabled && !get('eject').disabled, '操作按钮未就绪');
  get('split').click(); await waitFor(() => !get('split').disabled && sent.some(request => request.cmd === CMD.SPLIT));
  await waitFor(() => get('ball-count').textContent === '2');
  get('eject').click(); await waitFor(() => !get('eject').disabled && sent.some(request => request.cmd === CMD.EJECT));
  push(CMD.FOOD_EATEN, 'FoodEatenEvent', { ballId: '1', foodId: '1', radius: 4 });
  push(CMD.BALL_CHANGED, 'BallChangedEvent', { upserts: [{ ...other, radius: 12 }], removedBallIds: ['1', '3'] });
  await waitFor(() => !get('spectator-panel').hidden);
  check(get('split').disabled && get('eject').disabled, '被吞噬后仍允许战斗输入');
  check(get('spectate-target').value === 'other', '观战没有跟随场内玩家');
  check(!window.__unsafeNickname, '昵称被当成 HTML 执行');
  get('restart-game').click(); await waitFor(() => scene === 100 && get('spectator-panel').hidden);
  const operations = sent.map(request => request.cmd).filter(cmd => cmd === CMD.LEAVE || cmd === CMD.START_GAME);
  check(operations.join(',') === [CMD.START_GAME, CMD.LEAVE, CMD.START_GAME].join(','), '再开一局没有先确认离场');
  const players = Array.from({ length: 12 }, (_, index) => ({ userId: index === 11 ? user : `player-${index}`, nickname: index === 11 ? nickname : `玩家${index + 1}`,
    area: 100 - index, rank: index + 1, left: false }));
  const previousShifts = sent.filter(request => request.cmd === CMD.SHIFT).length;
  window.dispatchEvent(new KeyboardEvent('keydown', { key: 'd', bubbles: true }));
  await waitFor(() => sent.filter(request => request.cmd === CMD.SHIFT).length > previousShifts);
  const shiftsAtEnd = sent.filter(request => request.cmd === CMD.SHIFT).length;
  const now = Date.now();
  push(CMD.MATCH_ENDED, 'MatchRanking', { sceneId: '100', sceneInstanceId: 'scene', serverTick: '200', players,
    frozen: true, startedAtMs: String(now - 600000), endsAtMs: String(now), endedAtMs: String(now), endReason: 1 });
  await waitFor(() => get('result-dialog').open);
  await new Promise(resolve => setTimeout(resolve, 250));
  check(sent.filter(request => request.cmd === CMD.SHIFT).length === shiftsAtEnd, '结算后仍发送停止移动或其他方向输入');
  check(get('result-list').children.length === 12, '最终榜没有显示全部玩家');
  check(get('result-rank').textContent === '#12' && get('result-players').textContent === '12', '结算名次或人数错误');
  check(get('result-list').lastElementChild.textContent.includes('测试本局昵称'), '结算没有使用本局昵称');
  check(document.documentElement.scrollWidth <= innerWidth, '页面横向溢出');
  result.dataset.status = 'passed'; result.textContent = 'PASS：登录入场、分裂、吐球、吞食消息、吞噬观战、安全昵称、离场再开、完整结算。';
} catch (failure) {
  result.dataset.status = 'failed'; result.textContent = `FAIL：${failure.stack || failure}`;
}
result.style.cssText = 'position:fixed;top:0;left:0;z-index:100;background:white;font:12px monospace;max-width:100%;white-space:pre-wrap';
document.body.append(result);
