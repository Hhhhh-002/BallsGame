import { Renderer } from './renderer';

/** 最多 10Hz 发送方向；方向不变不密集发送，失焦/离场发送零方向，避免球继续移动。 */
export class Input {
  enabled = false;
  private keys = new Set<string>();
  private pointer?: { x: number; y: number };
  private touch?: { id: number; x: number; y: number };
  private direction = { x: 0, y: 0 };
  private sent = { x: 0, y: 0 };
  private sentAt = 0;
  private actionAt = { split: -Infinity, eject: -Infinity };
  private timer: ReturnType<typeof setInterval>;
  private listeners = new AbortController();
  constructor(canvas: HTMLCanvasElement, private renderer: Renderer,
    private shift: (x: number, y: number) => void, private action: (name: 'split' | 'eject', x: number, y: number) => void) {
    const options = { signal: this.listeners.signal };
    canvas.addEventListener('pointermove', event => {
      if (!this.enabled) return;
      if (event.pointerType === 'touch' && this.touch?.id === event.pointerId) {
        const x = event.clientX - this.touch.x, y = event.clientY - this.touch.y, magnitude = Math.max(65, Math.hypot(x, y));
        this.direction = { x: x / magnitude, y: y / magnitude }; this.pointer = undefined;
      } else if (event.pointerType === 'mouse') this.pointer = { x: event.clientX, y: event.clientY };
    }, options);
    canvas.addEventListener('pointerdown', event => {
      if (!this.enabled) return;
      if (event.pointerType === 'touch') {
        this.touch = { id: event.pointerId, x: event.clientX, y: event.clientY }; this.pointer = undefined;
        canvas.setPointerCapture(event.pointerId);
      }
    }, options);
    const stopPointer = () => this.stop();
    canvas.addEventListener('pointerup', event => { if (event.pointerType === 'touch') stopPointer(); }, options);
    canvas.addEventListener('pointercancel', stopPointer, options);
    canvas.addEventListener('lostpointercapture', stopPointer, options);
    canvas.addEventListener('pointerleave', event => { if (event.pointerType === 'mouse') stopPointer(); }, options);
    window.addEventListener('keydown', event => {
      if (!this.enabled || event.target instanceof HTMLInputElement || event.target instanceof HTMLTextAreaElement
        || document.querySelector('dialog[open]') || event.repeat) return;
      const key = event.key.toLowerCase();
      if (['w', 'a', 's', 'd', 'arrowup', 'arrowleft', 'arrowdown', 'arrowright'].includes(key)) { event.preventDefault(); this.keys.add(key); this.pointer = undefined; }
      if (key === ' ') { event.preventDefault(); this.trigger('split'); }
      if (key === 'e') { event.preventDefault(); this.trigger('eject'); }
    }, options);
    window.addEventListener('keyup', event => { this.keys.delete(event.key.toLowerCase()); }, options);
    window.addEventListener('blur', () => this.stop(), options);
    document.addEventListener('visibilitychange', () => { if (document.hidden) this.stop(); }, options);
    this.timer = setInterval(() => this.update(), 100);
  }
  trigger(name: 'split' | 'eject') {
    if (!this.enabled || performance.now() - this.actionAt[name] < (name === 'split' ? 600 : 300)) return;
    this.actionAt[name] = performance.now(); this.action(name, this.direction.x, this.direction.y);
  }
  /** 比赛中停止要通知服务端；已结算时只清理本地输入，不再发送本局操作。 */
  stop(notifyServer = true) {
    this.keys.clear(); this.pointer = undefined; this.touch = undefined; this.direction = { x: 0, y: 0 };
    if (notifyServer && this.enabled && (this.sent.x !== 0 || this.sent.y !== 0)) this.shift(0, 0);
    this.sent = { x: 0, y: 0 };
  }
  private update() {
    if (!this.enabled) return;
    if (this.keys.size) {
      const x = Number(this.keys.has('d') || this.keys.has('arrowright')) - Number(this.keys.has('a') || this.keys.has('arrowleft'));
      const y = Number(this.keys.has('s') || this.keys.has('arrowdown')) - Number(this.keys.has('w') || this.keys.has('arrowup'));
      const magnitude = Math.max(1, Math.hypot(x, y)); this.direction = { x: x / magnitude, y: y / magnitude };
    } else if (this.pointer) this.direction = this.renderer.direction(this.pointer.x, this.pointer.y);
    else if (!this.touch) this.direction = { x: 0, y: 0 };
    if (Math.abs(this.direction.x - this.sent.x) > .025 || Math.abs(this.direction.y - this.sent.y) > .025
      || performance.now() - this.sentAt > 10000) {
      this.shift(this.direction.x, this.direction.y); this.sent = { ...this.direction }; this.sentAt = performance.now();
    }
  }
  destroy() { this.stop(); this.enabled = false; clearInterval(this.timer); this.listeners.abort(); }
}
