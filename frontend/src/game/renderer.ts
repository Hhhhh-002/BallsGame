import { World, type VisualEffect } from './world';

const PALETTE = [
  { body: '#58dfc1', light: '#d5fff1', shade: '#169d88', ink: '#0f514d' },
  { body: '#ffc76d', light: '#fff0bf', shade: '#d98a38', ink: '#69401d' },
  { body: '#79aaff', light: '#d9eaff', shade: '#426fd3', ink: '#18396d' },
  { body: '#f58fab', light: '#ffe0ea', shade: '#bf527c', ink: '#713049' },
  { body: '#a99aff', light: '#eee5ff', shade: '#7863cb', ink: '#42376e' },
  { body: '#65d3f0', light: '#d1faff', shade: '#238bb5', ink: '#164b67' },
];
const TAU = Math.PI * 2;
const hash = (text: string) => [...text].reduce((value, char) => (value * 31 + char.charCodeAt(0)) | 0, 7) >>> 0;
interface View { left: number; right: number; top: number; bottom: number }

/** 展示层仅读取 World。纹理、粒子及闪光不修改位置、半径、样本、面积或网络请求。 */
export class Renderer {
  userId = '';
  followUserId = '';
  zoom = 1;
  fps = 0;
  active = false;
  private context: CanvasRenderingContext2D;
  private frame = 0;
  private width = 0; private height = 0; private scale = 1;
  private camera = { x: 50, y: 50 };
  private lastFrameAt = performance.now();
  private count = 0; private fpsAt = performance.now();
  private observer: ResizeObserver;
  private background!: CanvasGradient;
  private ballTextures: HTMLCanvasElement[];
  private foodTextures: HTMLCanvasElement[];
  private motion = window.matchMedia('(prefers-reduced-motion: reduce)');
  private reducedMotion = this.motion.matches;
  private onMotionChange = () => { this.reducedMotion = this.motion.matches; };
  private scene = '';
  private seenFoods = new Set<string>();
  private foodGlints = new Map<string, number>();
  private foodBatches: number[][] = PALETTE.map(() => []);
  private view: View = { left: 0, right: 100, top: 0, bottom: 100 };

  constructor(private canvas: HTMLCanvasElement, private world: World) {
    const context = canvas.getContext('2d');
    if (!context) throw new Error('浏览器不支持 Canvas 2D');
    this.context = context;
    this.ballTextures = PALETTE.map((_, index) => this.texture(index, false));
    this.foodTextures = PALETTE.map((_, index) => this.texture(index, true));
    this.motion.addEventListener('change', this.onMotionChange);
    this.observer = new ResizeObserver(() => this.resize()); this.observer.observe(canvas);
    this.resize(); this.draw(performance.now());
  }

  // 静态凝胶高光、渐变和柔光只绘制一次，避免每颗球每帧生成渐变或昂贵的阴影。
  private texture(index: number, food: boolean) {
    const canvas = document.createElement('canvas');
    const size = food ? 40 : 256, center = size / 2, radius = food ? 7 : 100;
    canvas.width = size; canvas.height = size;
    const c = canvas.getContext('2d')!;
    const theme = PALETTE[index];
    const glow = c.createRadialGradient(center, center, radius * .5, center, center, center);
    glow.addColorStop(0, `${theme.body}35`); glow.addColorStop(1, `${theme.body}00`);
    c.fillStyle = glow; c.fillRect(0, 0, size, size);
    const body = c.createRadialGradient(center - radius * .36, center - radius * .42, radius * .05, center + radius * .14, center + radius * .2, radius * 1.16);
    body.addColorStop(0, theme.light); body.addColorStop(.4, theme.body); body.addColorStop(1, theme.shade);
    c.fillStyle = body; c.beginPath(); c.arc(center, center, radius, 0, TAU); c.fill();
    c.strokeStyle = `${theme.light}a0`; c.lineWidth = food ? .8 : 1.8; c.stroke();
    c.fillStyle = food ? '#ffffffae' : '#ffffff66'; c.beginPath();
    c.ellipse(center - radius * .31, center - radius * .39, radius * .28, radius * .13, -.55, 0, TAU); c.fill();
    if (!food) {
      c.strokeStyle = '#ffffff38'; c.lineWidth = 3; c.beginPath(); c.arc(center, center, radius * .83, .2, 1.55); c.stroke();
      c.fillStyle = '#ffffff50'; c.beginPath(); c.arc(center + radius * .29, center + radius * .42, radius * .045, 0, TAU); c.fill();
    }
    return canvas;
  }

  private resize() {
    const rect = this.canvas.getBoundingClientRect();
    const dpr = Math.min(devicePixelRatio || 1, 2);
    this.width = rect.width; this.height = rect.height;
    this.canvas.width = Math.max(1, Math.round(rect.width * dpr));
    this.canvas.height = Math.max(1, Math.round(rect.height * dpr));
    this.context.setTransform(dpr, 0, 0, dpr, 0, 0);
    this.background = this.context.createRadialGradient(rect.width * .42, rect.height * .4, 0, rect.width * .5, rect.height * .5, Math.max(1, Math.hypot(rect.width, rect.height) * .65));
    this.background.addColorStop(0, '#203e69'); this.background.addColorStop(1, '#102746');
  }

  private draw = (now: number) => {
    const c = this.context, w = this.width, h = this.height;
    c.clearRect(0, 0, w, h); c.fillStyle = this.background; c.fillRect(0, 0, w, h);
    if (w > 0 && h > 0) {
      if (!this.world.sceneId) {
        this.scene = ''; this.seenFoods.clear(); this.foodGlints.clear(); this.preview(now);
      } else this.battle(now);
    }
    this.count++; this.lastFrameAt = now;
    if (now - this.fpsAt >= 1000) { this.fps = Math.round(this.count * 1000 / (now - this.fpsAt)); this.count = 0; this.fpsAt = now; }
    this.frame = requestAnimationFrame(this.draw);
  };

  private battle(now: number) {
    const c = this.context, w = this.width, h = this.height;
    const newScene = this.scene !== this.world.sceneId;
    if (newScene) { this.scene = this.world.sceneId; this.seenFoods.clear(); this.foodGlints.clear(); }
    this.scale = Math.max(.001, Math.min((w - 60) / this.world.width, (h - 60) / this.world.height) * this.zoom);
    const own = this.world.owned(this.userId);
    const followed = this.world.owned(this.followUserId || this.userId);
    const center = followed.length ? followed.reduce((sum, ball) => {
      const p = this.world.position(ball, now); return { x: sum.x + p.x / followed.length, y: sum.y + p.y / followed.length };
    }, { x: 0, y: 0 }) : { x: this.world.width / 2, y: this.world.height / 2 };
    const halfW = w / this.scale / 2, halfH = h / this.scale / 2;
    const targetX = halfW * 2 >= this.world.width ? this.world.width / 2 : Math.min(this.world.width - halfW, Math.max(halfW, center.x));
    const targetY = halfH * 2 >= this.world.height ? this.world.height / 2 : Math.min(this.world.height - halfH, Math.max(halfH, center.y));
    const blend = 1 - Math.exp(-Math.min(100, Math.max(0, now - this.lastFrameAt)) / 140);
    this.camera.x += (targetX - this.camera.x) * blend; this.camera.y += (targetY - this.camera.y) * blend;
    this.view = { left: this.camera.x - halfW, right: this.camera.x + halfW, top: this.camera.y - halfH, bottom: this.camera.y + halfH };
    c.save(); c.translate(w / 2, h / 2); c.scale(this.scale, this.scale); c.translate(-this.camera.x, -this.camera.y);
    this.field();

    const visibleFoods = new Set<string>();
    // 食物密集且显示尺寸小时合批画圆，避免数千次纹理绘制挤占帧预算。
    const batchFood = this.world.foods.size > 2000 && this.scale * .45 < 4;
    for (const batch of this.foodBatches) batch.length = 0;
    for (const food of this.world.foods.values()) {
      if (!newScene && !this.reducedMotion && !this.seenFoods.has(food.foodId) && this.foodGlints.size < 96) this.foodGlints.set(food.foodId, now);
      visibleFoods.add(food.foodId);
      if (!this.visible(food.x, food.y, 2)) continue;
      const index = hash(food.foodId) % PALETTE.length, radius = .45;
      // 半径仍为原有显示半径 0.45；纹理边缘只是非碰撞柔光。
      if (batchFood) this.foodBatches[index].push(food.x, food.y);
      else c.drawImage(this.foodTextures[index], food.x - radius * 20 / 7, food.y - radius * 20 / 7, radius * 40 / 7, radius * 40 / 7);
      const born = this.foodGlints.get(food.foodId);
      if (born !== undefined && !this.reducedMotion) {
        const progress = (now - born) / 450;
        if (progress < 1) this.ring(food.x, food.y, .6 + progress * 1.1, PALETTE[index].light, (1 - progress) * .65);
      }
    }
    if (batchFood) for (let index = 0; index < this.foodBatches.length; index++) {
      const positions = this.foodBatches[index];
      if (!positions.length) continue;
      c.fillStyle = PALETTE[index].body; c.beginPath();
      for (let i = 0; i < positions.length; i += 2) { c.moveTo(positions[i] + .45, positions[i + 1]); c.arc(positions[i], positions[i + 1], .45, 0, TAU); }
      c.fill();
    }
    this.seenFoods = visibleFoods;
    for (const [id, born] of this.foodGlints) if (now - born >= 450 || !visibleFoods.has(id)) this.foodGlints.delete(id);

    const balls = [...this.world.balls.values()].sort((a, b) => a.radius - b.radius);
    for (const ball of balls) {
      const p = this.world.position(ball, now), mine = ball.userId === this.userId;
      if (!this.visible(p.x, p.y, p.radius * 1.3 + 4 / this.scale)) continue;
      const index = mine ? 0 : 1 + hash(ball.userId) % (PALETTE.length - 1);
      this.ball(p.x, p.y, p.radius, index, mine, this.scale);
      const fontSize = Math.min(18 / this.scale, Math.max(11 / this.scale, p.radius * .5));
      c.fillStyle = PALETTE[index].ink; c.font = `700 ${fontSize}px "Microsoft YaHei UI", sans-serif`;
      c.textAlign = 'center'; c.textBaseline = 'middle';
      // fillText 不解析 HTML；字体最大宽度随真实球半径，不改变昵称内容或权威尺寸。
      c.fillText(ball.nickname || '玩家', p.x, p.y - (mine && p.radius > 2.7 ? p.radius * .1 : 0), p.radius * 1.65);
      if (mine && p.radius > 2.7) {
        c.fillStyle = '#17655c'; c.font = `600 ${Math.min(10 / this.scale, p.radius * .3)}px "Microsoft YaHei UI", sans-serif`;
        c.fillText('我', p.x, p.y + p.radius * .38);
      }
    }
    // 已确认的增删 / 吞食事件产生粒子；不在本地猜测哪个动作成功，也不做物理预测。
    for (const effect of this.world.effects) this.effect(effect, now);
    c.restore(); this.minimap();
    if (this.active && own.length === 0) {
      c.fillStyle = '#c3d9f5'; c.font = '11px "Microsoft YaHei UI", sans-serif'; c.textAlign = 'center';
      c.fillText('观战中', w / 2, h - 12);
    }
  }

  private visible(x: number, y: number, radius: number) {
    return x + radius >= this.view.left && x - radius <= this.view.right && y + radius >= this.view.top && y - radius <= this.view.bottom;
  }

  private field() {
    const c = this.context, width = this.world.width, height = this.world.height;
    c.fillStyle = '#18365b'; c.fillRect(0, 0, width, height);
    const left = Math.max(0, this.view.left), right = Math.min(width, this.view.right);
    const top = Math.max(0, this.view.top), bottom = Math.min(height, this.view.bottom);
    // 低倍缩放时控制网格密度；只遍历视野内格线。
    const step = 5 * Math.max(1, Math.ceil(12 / this.scale / 5));
    c.strokeStyle = '#8bb8ed10'; c.lineWidth = 1 / this.scale; c.beginPath();
    for (let x = Math.ceil(left / step) * step; x <= right; x += step) { c.moveTo(x, top); c.lineTo(x, bottom); }
    for (let y = Math.ceil(top / step) * step; y <= bottom; y += step) { c.moveTo(left, y); c.lineTo(right, y); }
    c.stroke();
    const major = step * 5;
    c.strokeStyle = '#9dc8ff12'; c.beginPath();
    for (let x = Math.ceil(left / major) * major; x <= right; x += major) { c.moveTo(x, top); c.lineTo(x, bottom); }
    for (let y = Math.ceil(top / major) * major; y <= bottom; y += major) { c.moveTo(left, y); c.lineTo(right, y); }
    c.stroke();
    c.strokeStyle = '#8bbdff44'; c.lineWidth = 2 / this.scale; c.strokeRect(0, 0, width, height);
    const corner = Math.min(4, width / 8, height / 8);
    c.strokeStyle = '#91c5ffa0'; c.lineWidth = 3 / this.scale; c.beginPath();
    c.moveTo(0, corner); c.lineTo(0, 0); c.lineTo(corner, 0);
    c.moveTo(width - corner, 0); c.lineTo(width, 0); c.lineTo(width, corner);
    c.moveTo(width, height - corner); c.lineTo(width, height); c.lineTo(width - corner, height);
    c.moveTo(corner, height); c.lineTo(0, height); c.lineTo(0, height - corner); c.stroke();
  }

  private ball(x: number, y: number, radius: number, index: number, mine: boolean, scale: number) {
    const c = this.context;
    c.drawImage(this.ballTextures[index], x - radius * 1.28, y - radius * 1.28, radius * 2.56, radius * 2.56);
    if (mine) {
      c.strokeStyle = '#bdfff0'; c.lineWidth = 1.6 / scale; c.beginPath(); c.arc(x, y, radius, 0, TAU); c.stroke();
      c.strokeStyle = '#7af2d940'; c.lineWidth = 1 / scale; c.beginPath(); c.arc(x, y, radius + 3 / scale, 0, TAU); c.stroke();
    }
  }

  private ring(x: number, y: number, radius: number, color: string, alpha: number) {
    const c = this.context;
    c.save(); c.globalAlpha = alpha; c.strokeStyle = color; c.lineWidth = 1.5 / this.scale;
    c.beginPath(); c.arc(x, y, Math.max(.01, radius), 0, TAU); c.stroke(); c.restore();
  }

  private effect(effect: VisualEffect, now: number) {
    const progress = (now - effect.startedAt) / effect.duration;
    if (progress < 0 || progress >= 1 || !this.visible(effect.x, effect.y, effect.radius * 2.2)) return;
    const color = effect.kind === 'food' ? '#ffda8c' : effect.kind === 'vanish' ? '#f0adc7' : '#8cf5de';
    if (this.reducedMotion) {
      this.ring(effect.x, effect.y, effect.radius, color, (1 - progress) * .3); return;
    }
    const eased = 1 - (1 - progress) ** 3;
    const radius = effect.radius * (effect.kind === 'vanish' ? 1 - eased * .55 : .8 + eased * .85);
    this.ring(effect.x, effect.y, radius, color, (1 - progress) * .7);
    if (effect.kind === 'appear') this.ring(effect.x, effect.y, effect.radius * (.5 + eased * .75), '#e3fff7', (1 - progress) * .35);
    const c = this.context, count = effect.kind === 'food' ? 6 : 9;
    c.save(); c.fillStyle = color; c.globalAlpha = (1 - progress) ** 2;
    for (let i = 0; i < count; i++) {
      const angle = i / count * TAU + effect.x * .13;
      const reach = effect.radius * (effect.kind === 'vanish' ? 1 - eased * .6 : .55 + eased * 1.3);
      const x = effect.x + Math.cos(angle) * reach, y = effect.y + Math.sin(angle) * reach;
      const size = Math.min(effect.radius * .09, 2.5 / this.scale) * (1 - progress * .6);
      c.beginPath(); c.arc(x, y, Math.max(.04, size), 0, TAU); c.fill();
    }
    c.restore();
  }

  private minimap() {
    const c = this.context, size = this.width < 450 ? 78 : 108;
    const left = this.width - size - 14, top = this.height - size - 37;
    c.save(); c.fillStyle = '#0f2441e8'; c.strokeStyle = '#a0c8ff40'; c.lineWidth = 1;
    c.beginPath(); c.roundRect(left - 6, top - 6, size + 12, size + 12, 10); c.fill(); c.stroke();
    c.save(); c.beginPath(); c.rect(left, top, size, size); c.clip();
    c.strokeStyle = '#8bb8ed17'; c.beginPath();
    for (let i = 1; i < 4; i++) { c.moveTo(left + size * i / 4, top); c.lineTo(left + size * i / 4, top + size); c.moveTo(left, top + size * i / 4); c.lineTo(left + size, top + size * i / 4); } c.stroke();
    for (const ball of this.world.balls.values()) {
      const mine = ball.userId === this.userId, followed = ball.userId === this.followUserId;
      c.fillStyle = mine ? '#72f0d4' : followed ? '#ffda8c' : '#8aaee0'; c.beginPath();
      c.arc(left + ball.x / this.world.width * size, top + ball.y / this.world.height * size,
        Math.max(2, Math.min(7, ball.radius / this.world.width * size)), 0, TAU); c.fill();
    }
    c.strokeStyle = '#9dd8ff80';
    c.strokeRect(left + (this.camera.x - this.width / this.scale / 2) / this.world.width * size,
      top + (this.camera.y - this.height / this.scale / 2) / this.world.height * size,
      this.width / this.scale / this.world.width * size, this.height / this.scale / this.world.height * size);
    c.restore(); c.fillStyle = '#a2bfdf'; c.font = '9px "Microsoft YaHei UI", sans-serif'; c.textAlign = 'center';
    c.fillText('小地图', left + size / 2, top - 12); c.restore();
  }

  private preview(now: number) {
    const c = this.context, w = this.width, h = this.height;
    c.fillStyle = '#8ab8ef24';
    for (let x = 24; x < w; x += 32) for (let y = 24; y < h; y += 32) { c.beginPath(); c.arc(x, y, .8, 0, TAU); c.fill(); }
    // 仅未入场背景装饰，页面明确标为画面示意；减少动态效果时完全静止。
    const t = this.reducedMotion ? 0 : now / 2400;
    const shapes = [[.13, .23, 36, 2], [.85, .2, 52, 4], [.17, .76, 53, 1], [.82, .78, 72, 0], [.67, .91, 20, 3]];
    for (const [x, y, radius, color] of shapes) this.ball(x * w + (this.reducedMotion ? 0 : Math.sin(t + x * 10) * 6), y * h + (this.reducedMotion ? 0 : Math.cos(t + y * 10) * 6), radius, color, false, 1);
    let seed = 8317;
    for (let i = 0; i < 48; i++) {
      seed = Math.imul(seed, 1664525) + 1013904223 | 0;
      const x = (seed >>> 0) / 4294967296 * w;
      seed = Math.imul(seed, 1664525) + 1013904223 | 0;
      const y = (seed >>> 0) / 4294967296 * h;
      c.drawImage(this.foodTextures[i % PALETTE.length], x - 8, y - 8, 16, 16);
    }
  }

  direction(clientX: number, clientY: number): { x: number; y: number } {
    const rect = this.canvas.getBoundingClientRect(), now = performance.now();
    const own = [...this.world.balls.values()].filter(ball => ball.userId === this.userId);
    if (!own.length) return { x: 0, y: 0 };
    const center = own.reduce((sum, ball) => {
      const p = this.world.position(ball, now); return { x: sum.x + p.x / own.length, y: sum.y + p.y / own.length };
    }, { x: 0, y: 0 });
    const x = clientX - rect.left - (this.width / 2 + (center.x - this.camera.x) * this.scale);
    const y = clientY - rect.top - (this.height / 2 + (center.y - this.camera.y) * this.scale);
    const length = Math.hypot(x, y);
    return length < 9 ? { x: 0, y: 0 } : { x: x / Math.max(80, length), y: y / Math.max(80, length) };
  }

  destroy() {
    cancelAnimationFrame(this.frame); this.observer.disconnect(); this.motion.removeEventListener('change', this.onMotionChange);
    this.foodGlints.clear(); this.seenFoods.clear();
    for (const canvas of [...this.ballTextures, ...this.foodTextures]) { canvas.width = 0; canvas.height = 0; }
  }
}
