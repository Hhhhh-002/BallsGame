const icon = (name: 'settings' | 'expand' | 'split' | 'eject' | 'trophy' | 'close') => {
  const paths = {
    settings: '<path d="m9 3 1-1h4l1 1 .4 2 1.6 1 2-.6 2 3.5-1.4 1.5v2l1.4 1.5-2 3.5-2-.6-1.6 1-.4 2-1 1h-4l-1-1-.4-2-1.6-1-2 .6-2-3.5L4.4 13v-2L3 9.5 5 6l2 .6 1.6-1L9 3Z"/><circle cx="12" cy="12" r="3"/>',
    expand: '<path d="M8 3H3v5m13-5h5v5M3 16v5h5m13-5v5h-5"/>',
    split: '<circle cx="7" cy="15" r="5"/><circle cx="17" cy="6" r="3"/><path d="m13 11 4-1-1 4"/>',
    eject: '<circle cx="8" cy="15" r="5"/><path d="m13 10 6-6m-5 0h5v5"/><circle cx="20" cy="15" r="1"/>',
    trophy: '<path d="M7 3h10v6a5 5 0 0 1-10 0V3Zm0 2H3v3a4 4 0 0 0 4 4m10-7h4v3a4 4 0 0 1-4 4m-5 2v5m-4 2h8m-6-2h4"/>',
    close: '<path d="m6 6 12 12M6 18 18 6"/>',
  };
  return `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${paths[name]}</svg>`;
};

export const layout = `
  <div class="shell">
    <header class="topbar">
      <a class="brand" href="#" aria-label="球球首页"><span class="brand-mark" aria-hidden="true"><i></i><i></i><i></i></span><span>球球<span class="brand-sub">一起，把世界玩大。</span></span></a>
      <nav aria-label="当前页面"><span class="nav-active">球球竞技场</span><span class="nav-caption">收集 · 分裂 · 生存</span></nav>
      <div class="connection"><span id="connection-dot" class="status-dot"></span><span id="connection-label">未连接网关</span><button id="settings-button" class="icon-button" aria-label="网关连接设置" title="网关连接设置">${icon('settings')}</button></div>
    </header>
    <main class="main">
      <section class="page-heading"><div><h1>每颗小球，都有大场面。</h1><p>找到你的节奏，收集能量，灵活分裂。</p></div><div class="arena-key" aria-label="球体说明"><span><i class="key-own"></i>你的球</span><span><i class="key-food"></i>能量颗粒</span></div></section>
      <div class="game-layout">
        <section class="arena-section" aria-label="游戏竞技场">
          <div class="arena-header"><div><span class="live-pill"><span></span><span id="arena-phase">准备出发</span></span><span id="scene-label" class="muted">等待加入比赛</span></div><div class="arena-tools"><button id="zoom-out" class="icon-button" aria-label="缩小视野" title="缩小视野">−</button><button id="zoom-in" class="icon-button" aria-label="放大视野" title="放大视野">＋</button><button id="fullscreen" class="icon-button" aria-label="切换全屏" title="切换全屏">${icon('expand')}</button></div></div>
          <div class="arena" id="arena">
            <canvas id="game-canvas" aria-label="球球战场，使用鼠标或方向键移动" tabindex="0"></canvas>
            <div class="arena-stats" id="arena-stats" hidden><div class="area-stat"><span>我的面积</span><strong id="my-area">0</strong></div><div><span>我的球</span><strong id="ball-count">0</strong></div><div><span>场内成员</span><strong id="player-count">0</strong></div><div><span>剩余时间</span><strong id="countdown">--:--</strong></div></div>
            <div class="spectator-panel" id="spectator-panel" hidden><span class="spectator-badge">观战席</span><strong>你的球已全部被吞噬</strong><p>你仍在本局，可以观战或离场后再开一局。</p><label for="spectate-target">跟随玩家</label><select id="spectate-target" aria-label="选择观战玩家"></select><button id="restart-game" class="secondary-button" type="button">离场后再开一局</button></div>
            <div class="lobby" id="lobby">
              <div class="lobby-emblem" aria-hidden="true"><span></span><i></i><i></i></div>
              <h2 id="lobby-title">你的下一场冒险</h2><p id="lobby-description">登录你的账号，加入一场真实的比赛。</p>
              <form id="login-form" class="form">
                <label for="account">邮箱账号</label><input id="account" name="username" type="email" autocomplete="username" maxlength="64" placeholder="you@example.com" required />
                <label for="password">密码</label><input id="password" name="password" type="password" autocomplete="current-password" minlength="9" placeholder="输入你的登录密码" required />
                <p id="register-hint" class="password-hint" hidden>至少 9 位，包含字母、数字和特殊字符，不能包含空白。</p>
                <div id="confirm-password-group" hidden><label for="confirm-password">确认密码</label><input id="confirm-password" type="password" autocomplete="new-password" placeholder="再次输入密码" disabled /></div>
                <button class="primary-button" id="login-button" type="submit">登录并准备</button>
              </form>
              <button id="auth-mode" class="text-button auth-mode" type="button">还没有账号？注册邮箱</button>
              <form id="start-form" class="form" hidden><label for="nickname">这一局，你叫什么？</label><input id="nickname" maxlength="64" placeholder="取一个本局昵称（可选）" /><button id="start-button" class="primary-button" type="submit">开始比赛</button></form>
              <button id="cancel-join" class="text-button" hidden>取消入场并清理场景</button>
              <div id="form-status" class="form-status" role="status" aria-live="polite"></div>
              <div class="lobby-note" id="lobby-note">背景为画面示意 · 没有使用模拟服务器</div>
            </div>
            <div class="map-label" id="map-label">画面示意</div><div class="render-label"><span id="fps">--</span> FPS <span class="label-separator">/</span> <span id="tick-rate">--</span> Hz 逻辑</div>
          </div>
          <div class="control-bar"><div class="instructions"><span><kbd>W A S D</kbd> 移动</span><span><kbd>Space</kbd> 分裂</span><span><kbd>E</kbd> 吐球</span><span class="touch-hint">按住战场拖动移动</span></div><div class="control-actions"><button id="split" class="secondary-button split-button" disabled>分裂 ${icon('split')}</button><button id="eject" class="secondary-button eject-button" disabled>吐球 ${icon('eject')}</button><button id="leave" class="text-button" disabled>离开</button></div></div>
          <div id="game-hint" class="game-hint" role="status" aria-live="polite">进入比赛后可操作；碰撞和吞噬由服务端判定。</div>
        </section>
        <aside class="sidebar">
          <section class="ranking-card"><div class="card-heading"><div><h2>本局排行榜</h2><p>每一点成长，都算数。</p></div><span class="ranking-icon" aria-hidden="true">${icon('trophy')}</span></div><div id="ranking-state" class="ranking-state">入场后显示实时排名</div><ol id="ranking-list" class="ranking-list"></ol><div id="my-rank" class="my-rank" hidden></div><div class="ranking-footer"><span class="status-dot soft"></span>按所有球的总面积排名</div></section>
          <section class="tips-card"><h3>大球，也要有小心思。</h3><p>分裂能抢占空间，也会让你变小。<br />吃下能量，留好退路。</p><div class="strategy-visual" aria-hidden="true"><i></i><i></i><i></i><span></span></div><div class="tips-divider"></div><div class="guide-row"><span class="guide-icon">${icon('split')}</span><div><strong>分裂出击</strong><span>空格键 / 分裂按钮</span></div></div><div class="guide-row"><span class="guide-icon">${icon('eject')}</span><div><strong>吐出能量</strong><span>E 键 / 吐球按钮</span></div></div><p class="tips-footnote">至少一颗球半径达到 3 才能分裂或吐球。</p></section>
          <details class="diagnostics"><summary>连接日志 <span id="latency">-- ms</span></summary><ol id="logs"></ol><button id="disconnect" class="text-button" disabled>断开网关 / 退出账号</button></details>
        </aside>
      </div>
    </main>
    <footer class="footer"><span>小球出发，大场面见。</span><span>鼠标指向移动，也支持方向键与触屏</span></footer>
  </div>
  <div id="toast" role="status" aria-live="polite" hidden></div>
  <dialog id="settings-dialog"><div class="dialog-heading"><h2>网关连接设置</h2><button id="close-settings" class="icon-button" aria-label="关闭设置">${icon('close')}</button></div><p>填写游戏网关的 WebSocket 地址。</p><form id="settings-form" class="form"><label for="gateway-url">WebSocket 地址</label><input id="gateway-url" type="url" required placeholder="ws://10.21.85.37:9001/ws" /><button class="primary-button" type="submit">保存地址</button></form><p class="dialog-note">切换地址会断开当前连接，需要重新登录。</p></dialog>
  <dialog id="result-dialog" class="result-dialog"><div class="result-orb" aria-hidden="true">${icon('trophy')}</div><h2>这一局，漂亮收场。</h2><p id="result-description"></p><div class="result-metrics"><div><span>最终名次</span><strong id="result-rank">--</strong></div><div><span>最终面积</span><strong id="result-area">--</strong></div><div><span>结算在场</span><strong id="result-players">--</strong></div></div><ol id="result-list" class="ranking-list" aria-label="服务端完整最终排名"></ol><button id="next-round" class="primary-button">再来一局</button><button id="close-result" class="text-button">返回大厅</button></dialog>
`;
