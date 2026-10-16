import { CMD, encode, decodeEnvelope, commandName } from '../protocol/codec';
import type { ServerMessage } from '../protocol/types';

export class GatewayError extends Error {
  constructor(message: string, public readonly code = 0, public readonly uncertain = false) { super(message); }
}
export interface Ticket { readonly id: string; readonly cmd: number; readonly bytes: Uint8Array; readonly generation: number }
interface Pending {
  ticket: Ticket; timer: ReturnType<typeof setTimeout>; resolve: (response: ServerMessage) => void;
  reject: (failure: Error) => void;
  retry: () => boolean;
}
const retryableCommands = new Set<number>([CMD.START_GAME, CMD.LEAVE, CMD.SPLIT, CMD.EJECT]);
const temporaryErrors = new Set([5001, 5002]); // 对应当前服务端 SERVICE_UNAVAILABLE / INTERNAL_ERROR。

/** 每个浏览器标签只使用一条到 Gateway 的 WS，不直接连接 Agent/Scene，也不自动重登录。 */
export class Gateway {
  onMessage: (message: ServerMessage) => void = () => {};
  onClose: (reason: string) => void = () => {};
  onLog: (message: string) => void = () => {};
  private socket?: WebSocket;
  private generation = 0;
  private ids = BigInt(Date.now()) << 16n;
  private sequence = 0n;
  private pending = new Map<string, Pending>();
  private heartbeat?: ReturnType<typeof setInterval>;
  private keepaliveId?: string;
  private keepaliveAt = 0;
  latency: number | null = null;
  received = 0;
  get open() { return this.socket?.readyState === WebSocket.OPEN; }

  async connect(address: string): Promise<void> {
    const url = new URL(address);
    if (!['ws:', 'wss:'].includes(url.protocol) || url.username || url.password || url.hash)
      throw new GatewayError('请输入有效的 ws:// 或 wss:// 网关地址');
    if (location.protocol === 'https:' && url.protocol === 'ws:') throw new GatewayError('HTTPS 页面需要 wss:// 连接');
    this.disconnect();
    const generation = ++this.generation;
    this.sequence = 0n; this.latency = null;
    const socket = new WebSocket(url.href);
    socket.binaryType = 'arraybuffer'; this.socket = socket;
    await new Promise<void>((resolve, reject) => {
      let opened = false;
      const deadline = setTimeout(() => { reject(new GatewayError('连接超时，请检查 Gateway 的 WS 端口')); socket.close(); }, 8000);
      socket.onopen = () => {
        if (generation !== this.generation) {
          clearTimeout(deadline); socket.close(); reject(new GatewayError('连接已被替换')); return;
        }
        opened = true; clearTimeout(deadline); resolve();
        this.onLog('Gateway WebSocket 已连接');
        this.heartbeat = setInterval(() => this.keepalive(), 20000);
      };
      socket.onerror = () => { if (!opened) reject(new GatewayError('无法连接网关，请检查地址、服务启动及防火墙')); };
      socket.onmessage = event => {
        if (generation !== this.generation) return;
        try {
          if (!(event.data instanceof ArrayBuffer)) throw new Error('网关返回了非二进制消息');
          const response = decodeEnvelope(event.data); this.received++;
          if (response.requestId === this.keepaliveId && response.cmd === 0) {
            if (response.code !== 4002) throw new Error('网关保活返回了非预期状态');
            this.latency = Math.round(performance.now() - this.keepaliveAt); this.keepaliveId = undefined; return;
          }
          const waiting = this.pending.get(response.requestId);
          if (waiting) {
            if (waiting.ticket.cmd !== response.cmd) throw new Error('回包 cmd 与 request_id 不匹配');
            if (temporaryErrors.has(response.code) && waiting.retry()) return;
            clearTimeout(waiting.timer); this.pending.delete(response.requestId);
            if (response.code) waiting.reject(new GatewayError(response.message || `请求失败（${response.code}）`,
              response.code, temporaryErrors.has(response.code)));
            else waiting.resolve(response);
          } else if (response.requestId === '0') this.onMessage(response);
          else this.onLog(`忽略迟到或重复响应 ${commandName(response.cmd)} · ${response.requestId}`);
        } catch (failure) {
          this.onLog(`协议错误：${failure instanceof Error ? failure.message : String(failure)}`);
          socket.close(1002, 'Invalid protobuf response');
        }
      };
      socket.onclose = event => {
        clearTimeout(deadline);
        if (generation !== this.generation) return;
        clearInterval(this.heartbeat); this.heartbeat = undefined;
        this.rejectPending('网关连接已断开，未完成请求的结果未知');
        if (!opened) reject(new GatewayError('网关连接失败'));
        this.onClose(event.reason || (event.wasClean ? '连接已关闭' : '连接中断，请检查网关和网络'));
      };
    });
  }
  prepare(cmd: number, type: string, body: object): Ticket {
    const id = (++this.ids).toString();
    return { id, cmd, generation: this.generation,
      bytes: encode('ClientEnvelope', { cmd, requestId: id, body: encode(type, body) }) };
  }
  execute(ticket: Ticket, timeoutMs = 20000, attempts = 1): Promise<ServerMessage> {
    if (!this.open || ticket.generation !== this.generation) return Promise.reject(new GatewayError('连接已变化，请重新登录'));
    if (this.pending.has(ticket.id)) return Promise.reject(new GatewayError('同一请求仍在等待响应'));
    if (!Number.isFinite(timeoutMs) || timeoutMs <= 0 || !Number.isInteger(attempts) || attempts < 1 || attempts > 5)
      return Promise.reject(new GatewayError('请求等待时间或重试次数不合法'));
    // LOGIN/REGISTER 的 Gateway 入口尚无完整客户端响应缓存，不能承诺原包重发可重放成功结果。
    const maxAttempts = retryableCommands.has(ticket.cmd) ? attempts : 1;
    return new Promise((resolve, reject) => {
      let tries = 0, retryScheduled = false;
      const waiting: Pending = { ticket, timer: undefined as unknown as ReturnType<typeof setTimeout>, resolve, reject,
        retry: () => {
          if (retryScheduled) return true;
          if (tries >= maxAttempts || !this.open || ticket.generation !== this.generation) return false;
          clearTimeout(waiting.timer); retryScheduled = true;
          waiting.timer = setTimeout(() => { retryScheduled = false; send(); }, 250);
          return true;
        } };
      this.pending.set(ticket.id, waiting);
      const send = () => {
        if (this.pending.get(ticket.id) !== waiting) return;
        try {
          this.write(ticket.bytes); tries++;
          if (ticket.cmd !== CMD.LOGIN) this.onLog(`${commandName(ticket.cmd)} · 第 ${tries} 次发送`);
          waiting.timer = setTimeout(() => {
            if (this.pending.get(ticket.id) !== waiting) return;
            if (waiting.retry()) return;
            this.pending.delete(ticket.id);
            reject(new GatewayError('等待响应超时，结果尚未确认', 0, true));
            // 原 LOGIN requestId 已贯通，但 Gateway 尚不缓存原登录成功响应；不自动重登录。
            if (ticket.cmd === CMD.LOGIN) this.disconnect('登录结果未知，请重新建立连接后登录');
          }, timeoutMs);
        } catch (failure) {
          clearTimeout(waiting.timer); this.pending.delete(ticket.id);
          reject(new GatewayError(failure instanceof Error ? failure.message : String(failure),
            failure instanceof GatewayError ? failure.code : 0, tries > 0 || (failure instanceof GatewayError && failure.uncertain)));
        }
      };
      send();
    });
  }
  shift(x: number, y: number) {
    if (!this.open) return;
    this.write(encode('ClientEnvelope', {
      cmd: CMD.SHIFT, clientSequence: (++this.sequence).toString(), body: encode('ShiftRequest', { directionX: x, directionY: y }),
    }));
  }
  /** 结算后取消仍在等待的本局动作，停止其超时重试；登录及战绩查询不受影响。 */
  cancelRequests(commands: readonly number[], reason: string) {
    for (const [id, waiting] of this.pending) {
      if (!commands.includes(waiting.ticket.cmd)) continue;
      clearTimeout(waiting.timer); this.pending.delete(id);
      waiting.reject(new GatewayError(reason));
    }
  }
  private write(bytes: Uint8Array) {
    if (!this.open || !this.socket) throw new GatewayError('网关尚未连接');
    if (this.socket.bufferedAmount > 64 * 1024) throw new GatewayError('网络发送缓冲已满，请稍后操作');
    this.socket.send(bytes as Uint8Array<ArrayBuffer>);
  }
  private keepalive() {
    if (!this.open) return;
    if (this.keepaliveId) { this.disconnect('网关保活超时'); return; }
    // 现有后端没有客户端心跳命令，浏览器也不能主动发 WS Ping。
    // cmd=0 被网关解码层明确拒绝（4002），没有登录/战斗副作用，但能刷新 reader-idle 并测 RTT。
    this.keepaliveId = (++this.ids).toString(); this.keepaliveAt = performance.now();
    try { this.write(encode('ClientEnvelope', { cmd: 0, requestId: this.keepaliveId })); }
    catch { this.disconnect('保活发送失败'); }
  }
  disconnect(reason = '已主动断开连接') {
    const socket = this.socket; this.socket = undefined;
    ++this.generation; clearInterval(this.heartbeat); this.heartbeat = undefined; this.keepaliveId = undefined;
    this.rejectPending(reason);
    socket?.close(1000, 'Client disconnected');
    if (socket) this.onClose(reason);
  }
  private rejectPending(message: string) {
    for (const waiting of this.pending.values()) { clearTimeout(waiting.timer); waiting.reject(new GatewayError(message, 0, true)); }
    this.pending.clear();
  }
}
