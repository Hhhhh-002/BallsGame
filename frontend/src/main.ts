import './styles.css';
import { layout } from './ui/layout';
import { CMD, decode, commandName } from './protocol/codec';
import type { Ranking } from './protocol/types';
import { Gateway, GatewayError, type Ticket } from './network/gateway';
import { World } from './game/world';
import { Renderer } from './game/renderer';
import { Input } from './game/input';
import { GameCommandRegistry } from './game/registry';
import { registerGameHandlers } from './game/handlers';

document.querySelector<HTMLDivElement>('#app')!.innerHTML = layout;
const element = <T extends HTMLElement = HTMLElement>(id: string) => document.getElementById(id)! as T;
const text = (id: string, value: string) => { element(id).textContent = value; };
const button = (id: string) => element<HTMLButtonElement>(id);
const field = (id: string) => element<HTMLInputElement>(id);
const safeStored = (key: string) => { try { return localStorage.getItem(key); } catch { return null; } };
const remember = (key: string, value: string) => { try { localStorage.setItem(key, value); } catch { /* 禁用存储时仍可正常游戏。 */ } };
const gateway = new Gateway(), world = new World();
const renderer = new Renderer(element<HTMLCanvasElement>('game-canvas'), world);
type Phase = 'offline' | 'connecting' | 'ready' | 'authenticating' | 'registering' | 'lobby' | 'joining' | 'playing' | 'leaving' | 'ended';
let authMode: 'login' | 'register' = 'login';
let phase: Phase = 'offline', userId = '', nickname = '', ranking: Ranking | null = null;
let gatewayUrl = window.BALLS_CLIENT_CONFIG?.gatewayUrl?.trim() || safeStored('balls.gateway-url') || import.meta.env.VITE_GATEWAY_URL || 'ws://127.0.0.1:9001/ws';
let startTicket: Ticket | null = null, joinBusy = false, epoch = 0;
let leaveTicket: Ticket | null = null, leaveBusy = false;
const actionBusy = { split: false, eject: false };
let toastTimer: ReturnType<typeof setTimeout>;
const settledScenes = new Set<string>();
const labels: Record<Phase, string> = {
  offline: '准备出发', connecting: '连接网关', ready: '等待登录', authenticating: '正在登录', registering: '正在注册',
  lobby: '准备出发', joining: '正在加入', playing: '比赛进行中', leaving: '正在离场', ended: '本局已结束',
};

function log(message: string) {
  const entry = document.createElement('li'); entry.textContent = `${new Date().toLocaleTimeString('zh-CN', { hour12: false })}  ${message}`;
  element('logs').prepend(entry); while (element('logs').childElementCount > 30) element('logs').lastElementChild?.remove();
}
function notify(message: string, error = false) {
  const toast = element('toast'); toast.textContent = message; toast.dataset.error = String(error); toast.hidden = false;
  clearTimeout(toastTimer); toastTimer = setTimeout(() => { toast.hidden = true; }, 4500);
  log(message);
}
function status(message: string, error = false) { text('form-status', message); element('form-status').dataset.error = String(error); }
function updateUI() {
  const connected = gateway.open, logged = Boolean(userId), inGame = phase === 'playing' || phase === 'leaving';
  const authBusy = ['connecting', 'authenticating', 'registering'].includes(phase);
  const registering = !logged && authMode === 'register';
  const owned = world.owned(userId);
  const waiting = phase === 'playing' && Boolean(world.sceneId) && !world.started;
  const playable = phase === 'playing' && world.started && !leaveTicket && owned.length > 0;
  if (!playable && input.enabled) input.stop();
  input.enabled = playable; renderer.active = phase === 'playing'; renderer.userId = userId;
  text('connection-label', phase === 'connecting' ? '正在连接…' : connected ? '网关已连接' : '未连接网关');
  element('connection-dot').classList.toggle('connected', connected);
  text('arena-phase', waiting ? `等待玩家 ${world.playerCount}/${world.minPlayersToStart}` : labels[phase]);
  text('scene-label', world.sceneId ? `比赛 #${world.sceneId.slice(-7)}` : '等待加入比赛');
  text('map-label', world.sceneId ? `${world.width} × ${world.height} / ARENA` : '画面示意 / PREVIEW');
  element('lobby').hidden = inGame;
  element('arena-stats').hidden = !world.sceneId;
  element('login-form').hidden = logged;
  element('start-form').hidden = !logged;
  element('cancel-join').hidden = !logged || phase !== 'joining';
  button('cancel-join').disabled = joinBusy || leaveBusy;
  button('login-button').disabled = authBusy;
  button('login-button').textContent = registering ? '创建账号 ↗' : '登录并准备 ↗';
  button('auth-mode').hidden = logged; button('auth-mode').disabled = authBusy;
  button('auth-mode').textContent = registering ? '已有账号？返回登录' : '还没有账号？注册邮箱';
  element('lobby').classList.toggle('registration', registering);
  element('confirm-password-group').hidden = !registering;
  element('register-hint').hidden = !registering;
  field('account').disabled = authBusy; field('password').disabled = authBusy;
  field('password').autocomplete = registering ? 'new-password' : 'current-password';
  field('confirm-password').disabled = !registering || authBusy;
  field('confirm-password').required = registering;
  button('start-button').disabled = joinBusy || phase === 'leaving' || Boolean(leaveTicket);
  field('nickname').disabled = Boolean(startTicket);
  button('start-button').textContent = joinBusy ? '正在加入，请稍候…' : startTicket ? '重试本次入场 ↗' : '开始比赛 ↗';
  text('lobby-title', logged ? phase === 'ended' ? '再开始一个新故事' : '准备好长大了吗？' : registering ? '从一个新名字开始' : '你的下一场冒险');
  text('lobby-description', logged ? `你好，${nickname || '玩家'}。每一局都可以换个新名字。` : registering ? '用邮箱创建账号，注册成功后再登录。' : '登录你的账号，加入一场真实的比赛。');
  text('lobby-note', logged ? '移动、碰撞、得分均由服务端计算' : '背景为画面示意 · 没有使用模拟服务器');
  const largeEnough = owned.some(ball => ball.radius >= 3); // 当前服务端两种动作的固定半径门槛；最终仍由 Scene 判定。
  for (const id of ['split', 'eject'] as const) {
    button(id).disabled = !playable || !largeEnough || actionBusy[id];
    button(id).textContent = actionBusy[id] ? `${id === 'split' ? '分裂' : '吐球'}确认中…` : id === 'split' ? '分裂 ⤢' : '吐球 ↗';
    button(id).title = !largeEnough ? '至少需要一个半径达到 3 的球；先吃食物成长' : '冷却和数量上限由服务端判定';
  }
  const spectating = phase === 'playing' && Boolean(world.sceneId) && owned.length === 0;
  element('spectator-panel').hidden = !spectating;
  button('restart-game').disabled = leaveBusy || joinBusy;
  button('restart-game').textContent = leaveTicket ? '重试离场后再开一局' : '离场后再开一局';
  if (spectating) updateSpectators(); else renderer.followUserId = '';
  text('game-hint', leaveTicket ? '离场结果正在确认，暂停战斗输入；重试仍沿用原请求。'
    : waiting ? `等待其他玩家，至少 ${world.minPlayersToStart} 人才能开赛；当前 ${world.playerCount} 人。等待期间不计时、不移动。`
    : spectating ? '已进入观战模式；再开一局会先确认离场。'
    : phase === 'playing' && !world.sceneId ? '等待服务端全量场景，收到后才能操作。'
    : playable ? largeEnough ? '移动、分裂、吐球只提交操作；吞噬、合并与得分以服务端消息为准。' : '先收集食物成长：分裂和吐球至少需要一个半径达到 3 的球。'
    : '进入比赛后可操作；碰撞和吞噬由服务端判定。');
  button('leave').disabled = !logged || (!world.sceneId && !leaveTicket) || leaveBusy || phase === 'ended';
  button('leave').textContent = leaveBusy ? '正在离场…' : leaveTicket ? '重试本次离场' : '离开';
  button('disconnect').disabled = !connected;
}
function updateSpectators() {
  const select = element<HTMLSelectElement>('spectate-target'), players = world.players();
  const signature = JSON.stringify(players.map(player => [player.userId, player.nickname]));
  if (select.dataset.players !== signature) {
    select.replaceChildren(); select.dataset.players = signature;
    if (!players.length) {
      const option = document.createElement('option'); option.value = ''; option.textContent = '暂无可跟随玩家'; select.append(option);
    }
    for (const player of players) {
      const option = document.createElement('option'); option.value = player.userId; option.textContent = player.nickname; select.append(option);
    }
  }
  if (!players.some(player => player.userId === renderer.followUserId)) renderer.followUserId = players[0]?.userId || '';
  select.value = renderer.followUserId; select.disabled = !players.length;
}

const input = new Input(element<HTMLCanvasElement>('game-canvas'), renderer,
  (x, y) => { try { gateway.shift(x, y); } catch (failure) { notify(errorMessage(failure), true); } },
  (name, x, y) => { void action(name, x, y); });
const errorMessage = (failure: unknown) => failure instanceof Error ? failure.message : String(failure);

gateway.onLog = log;
gateway.onClose = reason => {
  epoch++; input.stop(); input.enabled = false; userId = ''; nickname = ''; startTicket = null; joinBusy = false;
  leaveTicket = null; leaveBusy = false;
  actionBusy.split = false; actionBusy.eject = false; renderer.followUserId = '';
  field('password').value = ''; field('confirm-password').value = '';
  world.reset(); ranking = null; phase = 'offline';
  element<HTMLDialogElement>('result-dialog').close(); drawRanking(); status(reason, true); updateUI();
};

element<HTMLFormElement>('login-form').addEventListener('submit', event => {
  event.preventDefault(); if (authMode === 'register') void register(); else void login();
});
button('auth-mode').onclick = () => {
  if (userId || ['connecting', 'authenticating', 'registering'].includes(phase)) return;
  authMode = authMode === 'login' ? 'register' : 'login';
  field('password').value = ''; field('confirm-password').value = '';
  field('confirm-password').setCustomValidity(''); status(''); updateUI();
  field('account').focus();
};
for (const id of ['password', 'confirm-password']) {
  field(id).addEventListener('input', () => field('confirm-password').setCustomValidity(''));
}

/** 注册独立于登录，不自动重试 INSERT；不确定结果提示用户用同一邮箱尝试登录。 */
async function register() {
  if (userId || ['connecting', 'authenticating', 'registering'].includes(phase)) return;
  const account = field('account').value; let password = field('password').value;
  field('confirm-password').setCustomValidity(password === field('confirm-password').value ? '' : '两次输入的密码不一致');
  if (!element<HTMLFormElement>('login-form').reportValidity()) return;
  if (new TextEncoder().encode(password).length > 72) { status('密码 UTF-8 编码不能超过 72 字节', true); return; }
  let current = epoch;
  try {
    if (!gateway.open) {
      phase = 'connecting'; status('正在连接 Gateway…'); updateUI(); await gateway.connect(gatewayUrl);
    }
    current = epoch; phase = 'registering'; status('正在校验邮箱并创建账号…'); updateUI();
    const ticket = gateway.prepare(CMD.REGISTER, 'RegisterRequest', { account, password });
    password = ''; field('password').value = ''; field('confirm-password').value = '';
    const response = await gateway.execute(ticket, 12000);
    if (current !== epoch) return;
    const result = decode<{ userId: string }>('RegisterResponse', response.body);
    if (!result.userId || result.userId !== account) throw new Error('注册响应账号与请求不一致');
    authMode = 'login'; phase = 'ready';
    status('注册成功，请输入密码登录。'); notify('账号已创建，欢迎加入。'); updateUI(); field('password').focus();
  } catch (failure) {
    password = ''; field('password').value = ''; field('confirm-password').value = '';
    if (current !== epoch) return;
    phase = gateway.open ? 'ready' : 'offline';
    const uncertain = failure instanceof GatewayError && (failure.uncertain || failure.code === 5001);
    status(`${errorMessage(failure)}${uncertain ? '；注册结果未确认，可返回登录尝试此邮箱，不要认定账号未创建。' : ''}`, true);
    updateUI();
  }
}

async function login() {
  if (['connecting', 'authenticating', 'registering'].includes(phase) || userId) return;
  const account = field('account').value; let password = field('password').value;
  if (!element<HTMLFormElement>('login-form').reportValidity()) return;
  if (new TextEncoder().encode(password).length > 72) { status('密码 UTF-8 编码不能超过 72 字节', true); return; }
  let current = epoch;
  try {
    if (!gateway.open) {
      phase = 'connecting'; status('正在连接 Gateway…'); updateUI(); await gateway.connect(gatewayUrl);
    }
    current = epoch; phase = 'authenticating'; updateUI(); status('正在验证账号并准备玩家服务，首次登录可能需要一些时间…');
    const ticket = gateway.prepare(CMD.LOGIN, 'LoginRequest', { account, password });
    // 密码只随本次请求发送，不写 localStorage/日志；Gateway 尚未提供登录响应重放，不自动重发。
    password = ''; field('password').value = '';
    const response = await gateway.execute(ticket, 100000);
    if (current !== epoch) return;
    const body = decode<{ userId: string; player?: { nickname: string } }>('LoginResponse', response.body);
    if (!body.userId || body.userId !== account) throw new Error('登录成功响应账号与请求不一致');
    userId = body.userId; nickname = body.player?.nickname || '玩家'; phase = 'lobby';
    field('nickname').value = safeStored('balls.nickname') || '';
    status('登录成功，取一个本局昵称就可以出发了。'); notify('欢迎回来，连接已就绪。'); updateUI();
  } catch (failure) {
    password = ''; field('password').value = '';
    if (current !== epoch) return;
    phase = gateway.open ? 'ready' : 'offline';
    status(errorMessage(failure), true); updateUI();
  }
}

element<HTMLFormElement>('start-form').addEventListener('submit', event => { event.preventDefault(); void start(); });
async function start() {
  if (!userId || !gateway.open || joinBusy || leaveTicket || phase === 'playing' || phase === 'leaving') return;
  const name = field('nickname').value;
  if ([...name].length > 32 || /\p{C}/u.test(name)) { status('昵称最多 32 个字符，不能包含控制字符', true); return; }
  element<HTMLDialogElement>('result-dialog').close();
  if (!startTicket) {
    world.reset(); ranking = null; drawRanking(); startTicket = gateway.prepare(CMD.START_GAME, 'StartGameRequest', { nickname: name });
    remember('balls.nickname', name);
  }
  const ticket = startTicket, current = epoch;
  joinBusy = true; phase = 'joining'; status('正在查找场景并加入比赛…'); updateUI();
  try {
    const response = await gateway.execute(ticket, 20000, 2);
    if (current !== epoch) return;
    const result = decode<{ sceneId: string }>('StartGameResponse', response.body);
    if (!result.sceneId || result.sceneId === '0') throw new Error('入场成功响应缺少比赛 ID');
    if (world.sceneId && world.sceneId !== result.sceneId) throw new Error('入场响应与场景快照不一致');
    phase = 'playing'; startTicket = null; status('');
    notify('入场成功。移动鼠标，开始收集能量吧。');
  } catch (failure) {
    if (current !== epoch) return;
    // 超时/临时错误可能发生在成功加入之后，保留原 request_id 和昵称，不盲目发送新 START_GAME。
    const uncertain = failure instanceof GatewayError && (failure.uncertain || [5001, 5002, 4004, 40019].includes(failure.code));
    if (!uncertain) { startTicket = null; world.reset(); phase = 'lobby'; }
    status(`${errorMessage(failure)}${uncertain ? '；可重试本次入场或取消清理。' : ''}`, true);
  } finally {
    if (current === epoch) {
      joinBusy = false; updateUI();
      if (phase === 'playing') element<HTMLCanvasElement>('game-canvas').focus({ preventScroll: true });
    }
  }
}

async function leave() {
  if (!userId || !gateway.open || leaveBusy || joinBusy) return;
  const current = epoch, previous = phase;
  input.stop(); input.enabled = false;
  leaveTicket ??= gateway.prepare(CMD.LEAVE, 'LeaveRequest', {});
  leaveBusy = true; phase = 'leaving'; updateUI();
  try {
    await gateway.execute(leaveTicket, 20000, 2);
    if (current !== epoch) return;
    epoch++; startTicket = null; leaveTicket = null; leaveBusy = false; actionBusy.split = false; actionBusy.eject = false;
    renderer.followUserId = ''; world.reset(); ranking = null; phase = 'lobby';
    drawRanking(); status('已离开比赛，账号仍保持登录。'); notify('已回到大厅。');
  } catch (failure) {
    if (current !== epoch) return;
    const uncertain = failure instanceof GatewayError && failure.uncertain;
    if (!uncertain) leaveTicket = null;
    phase = previous; notify(`${errorMessage(failure)}${uncertain ? '；结果未确认，请重试本次离场，期间停止发送战斗操作。' : ''}`, true);
  } finally {
    if (current === epoch || phase === 'lobby') { leaveBusy = false; updateUI(); }
  }
}
button('leave').onclick = () => { void leave(); };
button('cancel-join').onclick = () => { void leave(); };
button('split').onclick = () => input.trigger('split'); button('eject').onclick = () => input.trigger('eject');
element<HTMLSelectElement>('spectate-target').onchange = event => {
  renderer.followUserId = (event.target as HTMLSelectElement).value;
};
button('restart-game').onclick = () => { void (async () => {
  await leave();
  if (phase === 'lobby' && gateway.open && userId) await start();
})(); };
async function action(name: 'split' | 'eject', x: number, y: number) {
  if (phase !== 'playing' || !world.started || leaveTicket || actionBusy[name] || !world.owned(userId).some(ball => ball.radius >= 3)) return;
  const current = epoch;
  actionBusy[name] = true; updateUI();
  try { await gateway.execute(gateway.prepare(name === 'split' ? CMD.SPLIT : CMD.EJECT,
    name === 'split' ? 'SplitRequest' : 'EjectRequest', { directionX: x, directionY: y }), 10000, 2); }
  catch (failure) { if (current === epoch) notify(errorMessage(failure), true); }
  finally { if (current === epoch) { actionBusy[name] = false; updateUI(); } }
}

const gameHandlers = registerGameHandlers(new GameCommandRegistry(), world, {
  accepts(sceneId) {
    if (!['joining', 'playing', 'leaving'].includes(phase)) return false;
    return !sceneId || (!settledScenes.has(sceneId) && (!world.sceneId || sceneId === world.sceneId));
  },
  changed() { text('tick-rate', String(world.tickRate)); updateUI(); },
  ranking(result, ended) {
    if (ranking && ranking.sceneId === result.sceneId && BigInt(result.serverTick) < BigInt(ranking.serverTick)) return;
    ranking = result; drawRanking();
    if (ended) showResult(result); else updateUI();
  },
  kicked(reason) { gateway.disconnect(reason); notify(reason, true); },
});
gateway.onMessage = message => {
  if (gameHandlers.dispatch(message)) return;
  if (message.code) notify(`${message.message || '请求失败'}（${message.code}）`, true);
  else log(`忽略未识别推送 ${commandName(message.cmd)}`);
};

function appendRanks(container: HTMLElement, result: Ranking, limit: number) {
  container.replaceChildren();
  for (const player of result.players.slice(0, limit)) {
    const item = document.createElement('li'); item.className = player.userId === userId ? 'ranking-entry own' : 'ranking-entry';
    const place = document.createElement('span'); place.className = 'rank-position'; place.textContent = String(player.rank).padStart(2, '0');
    const avatar = document.createElement('span'); avatar.className = 'rank-avatar'; avatar.textContent = [...(player.nickname || '玩家')][0];
    const name = document.createElement('span'); name.className = 'rank-name'; name.textContent = `${player.nickname || '玩家'}${player.userId === userId ? ' · 我' : ''}${player.left ? ' · 已离场' : ''}`;
    const score = document.createElement('strong'); score.textContent = player.area.toFixed(1);
    item.append(place, avatar, name, score); container.append(item);
  }
}
function drawRanking() {
  element('ranking-list').replaceChildren(); element('my-rank').hidden = !ranking;
  text('ranking-state', ranking ? ranking.frozen ? 'FINAL / 最终结果已冻结' : 'LIVE / 每秒更新' : '入场后显示实时排名');
  if (!ranking) { element('ranking-list').textContent = '这里，等着你的名字。'; element('ranking-list').classList.add('empty'); return; }
  element('ranking-list').classList.remove('empty'); appendRanks(element('ranking-list'), ranking, 10);
  const mine = ranking.players.find(player => player.userId === userId);
  element('my-rank').textContent = mine ? `我的排名  #${mine.rank}   ·   ${mine.area.toFixed(1)} 面积${mine.left ? ' · 已离场' : ''}` : '等待你的排名';
}
function showResult(result: Ranking) {
  settledScenes.add(result.sceneId); if (settledScenes.size > 32) settledScenes.delete(settledScenes.values().next().value!);
  epoch++; input.stop(false); input.enabled = false; phase = 'ended'; startTicket = null; joinBusy = false;
  gateway.cancelRequests([CMD.SPLIT, CMD.EJECT], '本局已结算');
  leaveTicket = null; leaveBusy = false; updateUI();
  actionBusy.split = false; actionBusy.eject = false; renderer.followUserId = '';
  const mine = result.players.find(player => player.userId === userId);
  text('result-rank', mine ? `#${mine.rank}` : '--'); text('result-area', mine?.area.toFixed(1) ?? '--');
  text('result-description', result.endReason === 2 ? '所有玩家已离场，最终排名已保存。' : '本局时间已到，最终排名已保存。');
  text('result-players', String(result.players.filter(player => !player.left).length));
  appendRanks(element('result-list'), result, result.players.length);
  element<HTMLDialogElement>('result-dialog').showModal(); log(`比赛结束 · #${result.sceneId} · 最终榜已确认`);
}
button('close-result').onclick = () => { element<HTMLDialogElement>('result-dialog').close(); world.reset(); updateUI(); };
button('next-round').onclick = () => { element<HTMLDialogElement>('result-dialog').close(); void start(); };
button('settings-button').onclick = () => { field('gateway-url').value = gatewayUrl; element<HTMLDialogElement>('settings-dialog').showModal(); };
button('close-settings').onclick = () => element<HTMLDialogElement>('settings-dialog').close();
element<HTMLFormElement>('settings-form').onsubmit = event => {
  event.preventDefault();
  try {
    const url = new URL(field('gateway-url').value);
    if (!['ws:', 'wss:'].includes(url.protocol)) throw new Error('地址必须以 ws:// 或 wss:// 开头');
    if (url.username || url.password || url.hash) throw new Error('网关地址不能包含账号、密码或 URL 片段');
    if (gatewayUrl !== url.href) { gateway.disconnect('已切换网关地址，请重新登录'); gatewayUrl = url.href; remember('balls.gateway-url', gatewayUrl); }
    element<HTMLDialogElement>('settings-dialog').close(); status('网关地址已保存。');
  } catch (failure) { notify(errorMessage(failure), true); }
};
button('disconnect').onclick = () => gateway.disconnect();
button('zoom-in').onclick = () => { renderer.zoom = Math.min(2.5, renderer.zoom + .2); };
button('zoom-out').onclick = () => { renderer.zoom = Math.max(.8, renderer.zoom - .2); };
button('fullscreen').onclick = () => {
  const operation = document.fullscreenElement ? document.exitFullscreen() : element('arena').requestFullscreen();
  void operation.catch(() => notify('当前浏览器不允许切换全屏', true));
};
const statsTimer = setInterval(() => {
  text('fps', String(renderer.fps || '--')); text('latency', gateway.latency === null ? '-- ms' : `${gateway.latency} ms`);
  const owned = [...world.balls.values()].filter(ball => ball.userId === userId);
  const area = owned.reduce((sum, ball) => sum + Math.PI * ball.radius * ball.radius, 0);
  text('my-area', area.toFixed(1)); text('player-count', String(world.playerCount));
  text('ball-count', String(owned.length));
  // 名称明确：球已被吞噬但仍在比赛的成员，MOVE 不提供成员总数，优先从排行榜统计未离场者。
  if (ranking) text('player-count', String(ranking.players.filter(player => !player.left).length));
  const remain = ranking ? Math.max(0, Math.ceil((Number(ranking.endsAtMs) - Date.now()) / 1000)) : null;
  text('countdown', world.sceneId && !world.started ? '等待开赛' : remain === null ? '--:--' : ranking?.frozen ? '00:00' : `${String(Math.floor(remain / 60)).padStart(2, '0')}:${String(remain % 60).padStart(2, '0')}`);
}, 500);
const onPageHide = () => gateway.disconnect('页面已离开');
window.addEventListener('pagehide', onPageHide);
if (import.meta.hot) import.meta.hot.dispose(() => {
  gateway.disconnect('客户端热更新'); input.destroy(); renderer.destroy();
  clearInterval(statsTimer); clearTimeout(toastTimer); window.removeEventListener('pagehide', onPageHide);
});
drawRanking(); updateUI(); log('客户端就绪 · 只连接 Gateway · 不保存密码');
