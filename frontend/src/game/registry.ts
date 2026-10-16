import type { ServerMessage } from '../protocol/types';

export interface GameHandler { readonly cmd: number; readonly acceptsErrors?: boolean; handle(message: ServerMessage): void }

/** cmd 只负责找 Handler；所有状态变化来自已解码的服务端消息。 */
export class GameCommandRegistry {
  private readonly handlers = new Map<number, GameHandler>();
  register(handler: GameHandler) {
    if (this.handlers.has(handler.cmd)) throw new Error(`重复的游戏 Handler：${handler.cmd}`);
    this.handlers.set(handler.cmd, handler); return this;
  }
  dispatch(message: ServerMessage): boolean {
    const handler = this.handlers.get(message.cmd);
    if (!handler || (message.code !== 0 && !handler.acceptsErrors)) return false;
    handler.handle(message); return true;
  }
}
