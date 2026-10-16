import { CMD, decode } from '../protocol/codec';
import type { Ball, Food, Position, Snapshot, Ranking } from '../protocol/types';
import { GameCommandRegistry } from './registry';
import { World } from './world';

export interface GameHooks {
  accepts(sceneId?: string): boolean;
  changed(): void;
  ranking(result: Ranking, ended: boolean): void;
  kicked(reason: string): void;
}

/** 与服务端 cmd 一一对应；没有客户端吃球/吃食物请求，也不从坐标推算碰撞。 */
export function registerGameHandlers(registry: GameCommandRegistry, world: World, hooks: GameHooks) {
  const add = <T>(cmd: number, type: string, handle: (body: T) => void) => registry.register({
    cmd, handle(message) {
      if (!hooks.accepts()) return;
      handle(decode<T>(type, message.body));
    },
  });
  registry.register({ cmd: CMD.KICKED_OFFLINE, acceptsErrors: true, handle: message => hooks.kicked(message.message || '账号在其他连接登录，你已被踢下线') });
  add<Snapshot>(CMD.SCENE_SNAPSHOT, 'SceneSnapshot', body => {
    if (!hooks.accepts(body.sceneId)) return;
    world.snapshot(body); hooks.changed();
  });
  add<{ serverTick: string; players: Position[] }>(CMD.MOVE, 'MoveBroadcast', body => {
    world.move(body.serverTick, body.players); hooks.changed();
  });
  add<{ balls: Ball[] }>(CMD.PLAYER_ENTERED, 'PlayerEnteredEvent', body => {
    if (!world.sceneId) return;
    world.changeBalls(body.balls, []); hooks.changed();
  });
  add<{ foods: Food[] }>(CMD.FOOD_ADDED, 'FoodAddedEvent', body => {
    if (!world.sceneId) return;
    body.foods.forEach(food => world.addFood(food)); hooks.changed();
  });
  add<{ ballId: string; foodId: string; radius: number }>(CMD.FOOD_EATEN, 'FoodEatenEvent', body => {
    if (!world.sceneId) return;
    world.eat(body.ballId, body.foodId, body.radius); hooks.changed();
  });
  add<{ userId: string; ballIds: string[] }>(CMD.PLAYER_LEFT, 'PlayerLeftEvent', body => {
    if (!world.sceneId) return;
    world.changeBalls([], body.ballIds); world.removePlayer(body.userId); hooks.changed();
  });
  add<{ upserts: Ball[]; removedBallIds: string[] }>(CMD.BALL_CHANGED, 'BallChangedEvent', body => {
    if (!world.sceneId) return;
    world.changeBalls(body.upserts, body.removedBallIds); hooks.changed();
  });
  const ranking = (result: Ranking, ended: boolean) => {
    if (!hooks.accepts(result.sceneId)) return;
    if (ended && !result.frozen) throw new Error('MATCH_ENDED 没有冻结最终榜');
    if (result.players.some(player => !Number.isFinite(player.area) || player.area < 0 || !Number.isInteger(player.rank) || player.rank < 1))
      throw new Error('服务端排名数据无效');
    hooks.ranking(result, ended);
  };
  add<Ranking>(CMD.MATCH_RANKING, 'MatchRanking', body => ranking(body, false));
  add<Ranking>(CMD.MATCH_ENDED, 'MatchRanking', body => ranking(body, true));
  return registry;
}
