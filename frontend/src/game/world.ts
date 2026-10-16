import type { Ball, Food, Position, Snapshot } from '../protocol/types';

interface Sample { time: number; x: number; y: number; radius: number }
export interface RenderBall extends Ball { samples: Sample[] }
export interface VisualEffect { kind: 'food' | 'appear' | 'vanish'; x: number; y: number; radius: number; startedAt: number; duration: number }

/** 服务端状态只由回包修改；插值仅改变显示，不在浏览器判定吞噬、加分或食物归属。 */
export class World {
  sceneId = '';
  width = 100; height = 100; tickRate = 25;
  started = false; minPlayersToStart = 2; playerCount = 0;
  balls = new Map<string, RenderBall>();
  foods = new Map<string, Food>();
  effects: VisualEffect[] = [];
  lastTick = 0n;
  private baseTick = 0n;
  private serverTime = 0;
  private lastReceivedAt = 0;
  readonly interpolationMs = 220;
  reset() {
    this.sceneId = ''; this.balls.clear(); this.foods.clear(); this.lastTick = 0n;
    this.baseTick = 0n; this.serverTime = 0; this.lastReceivedAt = 0;
    this.effects = [];
    this.started = false; this.minPlayersToStart = 2; this.playerCount = 0;
  }
  snapshot(snapshot: Snapshot) {
    const tick = BigInt(snapshot.serverTick);
    if (this.sceneId === snapshot.sceneId && tick < this.lastTick) return;
    if (this.sceneId !== snapshot.sceneId) { this.reset(); this.baseTick = tick; }
    this.sceneId = snapshot.sceneId;
    if (!(snapshot.width > 0 && snapshot.height > 0 && snapshot.tickRate > 0)) throw new Error('场景尺寸或逻辑频率无效');
    this.width = snapshot.width; this.height = snapshot.height; this.tickRate = snapshot.tickRate;
    this.started = snapshot.started;
    this.minPlayersToStart = snapshot.minPlayersToStart || 2;
    this.playerCount = snapshot.playerCount;
    const time = this.setClock(tick);
    const ids = new Set(snapshot.balls.map(ball => ball.ballId));
    for (const id of this.balls.keys()) if (!ids.has(id)) this.balls.delete(id);
    snapshot.balls.forEach(ball => this.upsert(ball, time));
    this.foods = new Map(snapshot.foods.map(food => [food.foodId, food]));
  }
  move(tickValue: string, positions: Position[]) {
    if (!this.sceneId) return;
    const tick = BigInt(tickValue);
    if (tick < this.lastTick) return;
    const time = this.setClock(tick);
    for (const position of positions) {
      const ball = this.balls.get(position.ballId);
      if (ball) this.upsert({ ...ball, ...position }, time);
      // 未知球等待 ENTER / BALL_CHANGED / 全量快照，不能凭 MOVE 猜测昵称或创建身份。
    }
  }
  upsert(ball: Ball, time = this.serverTime) {
    if (![ball.x, ball.y, ball.radius].every(Number.isFinite) || ball.radius <= 0) throw new Error('非法球状态');
    const old = this.balls.get(ball.ballId);
    const samples = old?.samples ?? [];
    const sample = { time, x: ball.x, y: ball.y, radius: ball.radius };
    if (samples.at(-1)?.time === time) samples[samples.length - 1] = sample;
    else samples.push(sample);
    if (samples.length > 12) samples.shift();
    this.balls.set(ball.ballId, { ...ball, samples });
  }
  eat(ballId: string, foodId: string, radius: number) {
    if (!Number.isFinite(radius) || radius <= 0) throw new Error('非法吃食物结果');
    const food = this.foods.get(foodId);
    if (food) this.effect('food', food.x, food.y, 1.5);
    this.foods.delete(foodId);
    const ball = this.balls.get(ballId);
    if (ball) this.upsert({ ...ball, radius });
  }
  addFood(food: Food) {
    if (food.foodId === '0' || ![food.x, food.y].every(Number.isFinite)) throw new Error('非法食物状态');
    this.foods.set(food.foodId, food);
  }
  changeBalls(upserts: Ball[], removed: string[]) {
    const deleted = new Set(removed);
    for (const id of deleted) {
      const ball = this.balls.get(id);
      if (ball) this.effect('vanish', ball.x, ball.y, ball.radius);
      this.balls.delete(id);
    }
    for (const ball of upserts) {
      if (deleted.has(ball.ballId)) continue;
      const old = this.balls.get(ball.ballId);
      if (!old || ball.radius > old.radius) this.effect('appear', ball.x, ball.y, ball.radius);
      this.upsert(ball);
    }
  }
  owned(userId: string) { return [...this.balls.values()].filter(ball => ball.userId === userId); }
  players() {
    const players = new Map<string, { userId: string; nickname: string; area: number }>();
    for (const ball of this.balls.values()) {
      const player = players.get(ball.userId) ?? { userId: ball.userId, nickname: ball.nickname || '玩家', area: 0 };
      player.area += Math.PI * ball.radius * ball.radius; players.set(ball.userId, player);
    }
    return [...players.values()].sort((a, b) => b.area - a.area);
  }
  private effect(kind: VisualEffect['kind'], x: number, y: number, radius: number) {
    const now = performance.now();
    this.effects = this.effects.filter(effect => effect.startedAt + effect.duration > now).slice(-63);
    this.effects.push({ kind, x, y, radius, startedAt: now, duration: kind === 'food' ? 350 : 600 });
  }
  removePlayer(userId: string) { for (const [id, ball] of this.balls) if (ball.userId === userId) this.balls.delete(id); }
  renderTime(now: number) { return this.serverTime + Math.max(0, now - this.lastReceivedAt) - this.interpolationMs; }
  position(ball: RenderBall, now: number): Sample {
    const time = this.renderTime(now), samples = ball.samples;
    if (samples.length < 2 || time <= samples[0].time) return samples[0];
    for (let i = 1; i < samples.length; i++) {
      const a = samples[i - 1], b = samples[i];
      if (time <= b.time) {
        const fraction = Math.max(0, Math.min(1, (time - a.time) / Math.max(1, b.time - a.time)));
        return { time, x: a.x + (b.x - a.x) * fraction, y: a.y + (b.y - a.y) * fraction,
          radius: a.radius + (b.radius - a.radius) * fraction };
      }
    }
    return samples.at(-1)!; // 没有下一个权威样本时保持位置，不无限外推。
  }
  private setClock(tick: bigint) {
    this.lastTick = tick; this.serverTime = Number(tick - this.baseTick) * 1000 / this.tickRate;
    this.lastReceivedAt = performance.now(); return this.serverTime;
  }
}
