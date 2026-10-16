/* 真实联调：只连接当前运行的页面和 Gateway，不替换响应、不改服务配置。 */
const { chromium } = require('../.local-preview/tooling/node_modules/playwright');
const protobuf = require('protobufjs');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const run = new Date().toISOString().replace(/[:.]/g, '-');
const output = path.resolve(__dirname, '../.local-preview', `live-${run}`);
fs.mkdirSync(output, { recursive: true });
const report = { run, startedAt: new Date().toISOString(), page: 'http://127.0.0.1:5173/',
  accounts: [], checks: [], errors: [], output };
const players = [];
let browser;
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const check = (name, pass, details) => { report.checks.push({ name, pass, details }); console.log(JSON.stringify({ name, pass, details })); };
const save = () => fs.writeFileSync(path.join(output, 'report.json'), JSON.stringify(report, null, 2));
const state = page => page.evaluate(() => {
  const s = window.__live;
  return { ...s, socket: undefined, codec: undefined, send: undefined, keys: undefined,
    balls: Object.values(s.balls), foods: Object.values(s.foods), phase: document.querySelector('#arena-phase').textContent,
    fps: document.querySelector('#fps').textContent, hint: document.querySelector('#game-hint').textContent };
});
async function waitResponse(page, cmd, start = 0, timeout = 45000) {
  await page.waitForFunction(({ cmd, start }) => window.__live.responses.slice(start).some(r => r.cmd === cmd), { cmd, start }, { timeout });
  return page.evaluate(({ cmd, start }) => window.__live.responses.slice(start).find(r => r.cmd === cmd), { cmd, start });
}
async function submit(page, cmd, action, timeout) {
  const n = await page.evaluate(() => window.__live.responses.length);
  await action();
  const r = await waitResponse(page, cmd, n, timeout);
  if (r.code) throw new Error(`cmd=${cmd} code=${r.code} ${r.message}`);
  return r;
}
async function setup(index, password) {
  const context = await browser.newContext({ viewport: { width: 1280, height: 820 } });
  const page = await context.newPage();
  page.on('pageerror', e => report.errors.push({ player: index, kind: 'pageerror', message: e.message }));
  await page.addInitScript(() => {
    const NativeSocket = window.WebSocket;
    const s = window.__live = { balls: {}, foods: {}, responses: [], counts: {}, errors: [], seq: '0',
      moves: [], foodEaten: 0, removed: 0, added: 0, closed: [], sent: {}, pending: {}, keys: [] };
    window.WebSocket = class extends NativeSocket {
      constructor(...args) {
        super(...args); s.socket = this; s.url = args[0];
        this.addEventListener('close', e => s.closed.push({code:e.code,reason:e.reason,at:Date.now()}));
        this.addEventListener('message', e => {
          if (!s.codec || !(e.data instanceof ArrayBuffer)) return;
          try {
            const r = s.codec.decodeEnvelope(e.data);
            s.counts[r.cmd] = (s.counts[r.cmd] || 0) + 1;
            if (r.requestId !== '0') {
              s.responses.push({cmd:r.cmd,requestId:r.requestId,code:r.code,message:r.message,body:Array.from(r.body)});
              return;
            }
            const decode = type => s.codec.decode(type, r.body);
            if (r.cmd === 3002) {
              const b = decode('SceneSnapshot'); s.snapshot = {...b,balls:undefined,foods:undefined}; s.initialFoods ??= b.foods.length;
              s.balls = Object.fromEntries(b.balls.map(b=>[b.ballId,b])); s.foods = Object.fromEntries(b.foods.map(f=>[f.foodId,f])); s.tick=b.serverTick;
            } else if (r.cmd === 2001) {
              const b = decode('MoveBroadcast'); s.tick=b.serverTick; s.lastMove=Date.now();
              s.moves.push({tick:b.serverTick,at:Date.now()}); if(s.moves.length>120)s.moves.shift();
              for(const p of b.players) s.balls[p.ballId] = {...s.balls[p.ballId],...p};
            } else if (r.cmd === 3003) {
              for(const b of decode('PlayerEnteredEvent').balls)s.balls[b.ballId]=b;
            } else if (r.cmd === 3004) {
              for(const f of decode('FoodAddedEvent').foods){s.foods[f.foodId]=f;s.added++;}
            } else if (r.cmd === 3005) {
              const b=decode('FoodEatenEvent');delete s.foods[b.foodId];if(s.balls[b.ballId])s.balls[b.ballId].radius=b.radius;s.foodEaten++;
            } else if (r.cmd === 3006) {
              const b=decode('PlayerLeftEvent');for(const id of b.ballIds)delete s.balls[id];s.left=b.userId;
            } else if (r.cmd === 3007) {
              const b=decode('BallChangedEvent');for(const id of b.removedBallIds){delete s.balls[id];s.removed++;}for(const ball of b.upserts)s.balls[ball.ballId]=ball;
            } else if(r.cmd===3008 || r.cmd===3009) {
              s.ranking=decode('MatchRanking');if(r.cmd===3009)s.ended=s.ranking;
            } else if(r.code) s.errors.push({cmd:r.cmd,code:r.code,message:r.message});
          } catch(error){s.errors.push({decode:error.message});}
        });
      }
      send(bytes) {
        if(s.codec && ArrayBuffer.isView(bytes)) {
          try {const e=s.codec.decode('ClientEnvelope',bytes);s.sent[e.cmd]=(s.sent[e.cmd]||0)+1;
            if(BigInt(e.clientSequence)>BigInt(s.seq))s.seq=e.clientSequence;}catch{}
        }
        return super.send(bytes);
      }
    };
  });
  await page.goto(report.page, { waitUntil: 'networkidle' });
  await page.evaluate(async () => { window.__live.codec = await import('/src/protocol/codec.ts'); });
  const account = `live-${run.replace(/\D/g,'').slice(-13)}-${String(index+1).padStart(2,'0')}@example.com`;
  const player = { context, page, account, index }; players.push(player); report.accounts.push(account);
  await page.locator('#auth-mode').click();
  await page.locator('#account').fill(account); await page.locator('#password').fill(password);
  await page.locator('#confirm-password').fill(password);
  await submit(page,1002,()=>page.locator('#login-button').click());
  await page.waitForFunction(()=>document.querySelector('#confirm-password-group').hidden);
  await page.locator('#password').fill(password);
  await submit(page,1001,()=>page.locator('#login-button').click(),105000);
  await page.locator('#start-form').waitFor({state:'visible'});
  await page.locator('#nickname').fill(`联调${String(index+1).padStart(2,'0')}`);
  console.log(`LOGIN_OK ${index+1}/10`);save();return player;
}
async function join(player) {
  await submit(player.page,2006,()=>player.page.locator('#start-button').click());
  await player.page.waitForFunction(()=>Boolean(window.__live.snapshot));
  player.joined=true;
}
async function keys(player, x=0, y=0) {
  await player.page.evaluate(({x,y})=>{
    const s=window.__live;
    const wanted=[...(x<0?['a']:x>0?['d']:[]),...(y<0?['w']:y>0?['s']:[])];
    for(const key of s.keys)if(!wanted.includes(key))window.dispatchEvent(new KeyboardEvent('keyup',{key,bubbles:true}));
    for(const key of wanted)if(!s.keys.includes(key))window.dispatchEvent(new KeyboardEvent('keydown',{key,bubbles:true}));
    s.keys=wanted;
  },{x,y});
}
async function raw(player, cmd, type, body, repeat=1, sequence) {
  const id=(BigInt(Date.now())*1000000n+BigInt(player.index*1000+report.checks.length)).toString();
  const n=await player.page.evaluate(()=>window.__live.responses.length);
  await player.page.evaluate(({cmd,type,body,repeat,id,sequence})=>{
    const s=window.__live;const bytes=s.codec.encode('ClientEnvelope',{cmd,requestId:id,clientSequence:sequence||'0',body:s.codec.encode(type,body)});
    for(let i=0;i<repeat;i++)s.socket.send(bytes);
  },{cmd,type,body,repeat,id,sequence});
  if(cmd===2004)return id;
  const response=await waitResponse(player.page,cmd,n,45000);return response;
}
async function drive(player, target, snapshot) {
  const b=snapshot.balls.find(b=>b.userId===player.account);
  if(!b||!target){await keys(player);return;}
  const dx=target.x-b.x,dy=target.y-b.y;
  const threshold=Math.max(.8,b.radius*.4);
  await keys(player,Math.abs(dx)>threshold?Math.sign(dx):0,Math.abs(dy)>threshold?Math.sign(dy):0);
}
(async()=>{
  browser=await chromium.launch({headless:true,args:['--disable-background-timer-throttling','--disable-renderer-backgrounding','--disable-backgrounding-occluded-windows']});
  const password=`Test!${crypto.randomBytes(12).toString('hex')}9`;
  const first=await setup(0,password);await join(first);
  const waiting=await state(first.page);await sleep(1500);const stillWaiting=await state(first.page);
  check('单人等待且不推进逻辑帧',!waiting.snapshot.started && waiting.tick===stillWaiting.tick,{sceneId:waiting.snapshot.sceneId,foods:waiting.foods.length,tick:stillWaiting.tick,phase:stillWaiting.phase});
  check('初始批量随机食物',waiting.foods.length>=20 && new Set(waiting.foods.map(f=>`${f.x},${f.y}`)).size===waiting.foods.length,{count:waiting.foods.length});
  for(let i=1;i<10;i++){const p=await setup(i,password);await join(p);}
  await sleep(1200);
  const all=await Promise.all(players.map(p=>state(p.page)));
  const scenes=[...new Set(all.map(s=>s.snapshot.sceneId))];report.sceneId=scenes[0];
  check('10个浏览器真实注册和登录',players.length===10,{gateway:all[0].url});
  check('10人同场开赛',scenes.length===1 && all.every(s=>s.snapshot.started) && all[0].ranking?.players.length===10,{scenes,rankingPlayers:all[0].ranking?.players.length,visiblePlayers:all.map(s=>new Set(s.balls.map(b=>b.userId)).size)});
  await first.page.screenshot({path:path.join(output,'10-player-start.png'),fullPage:true});
  const start=all[0].balls.find(b=>b.userId===first.account);
  await first.page.locator('#game-canvas').focus();await first.page.keyboard.down('d');await sleep(1100);await first.page.keyboard.up('d');await sleep(400);
  const moved=await state(first.page),pos=moved.balls.find(b=>b.ballId===start.ballId);
  check('浏览器键盘移动收到服务端位置广播',pos&&pos.x>start.x+3,{from:start,to:pos});
  const observer=await state(players[1].page);const other=observer.balls.find(b=>b.ballId===start.ballId);
  check('其他玩家能看到相同位置',Boolean(pos&&other&&Math.abs(pos.x-other.x)<1),{self:pos?.x,observer:other?.x});
  const ranking=await state(first.page);report.expectedEndsAt=ranking.ranking?.endsAtMs;
  console.log(`MATCH_RUNNING endsAt=${report.expectedEndsAt}`);save();
  let splitDone=false,ejectDone=false,mergeDone=false,swallowDone=false,leftDone=false,disconnectDone=false;
  let splitAt=0;const began=Date.now();let progressAt=0;
  while(Date.now()-began<660000){
    const states=await Promise.all(players.filter(p=>!p.disconnected).map(p=>state(p.page)));
    const s=states[0];
    if(s.ended){report.final=s.ended;break;}
    if(s.closed.length)throw new Error(`首个客户端断线: ${JSON.stringify(s.closed)}`);
    const own=s.balls.filter(b=>b.userId===first.account);
    if(!ejectDone && own.some(b=>b.radius>=Math.sqrt(10)+.1)){
      await keys(first);await sleep(180);
      const before=await state(first.page);const response=await raw(first,2003,'EjectRequest',{directionX:1,directionY:0},2);await sleep(500);
      const after=await state(first.page);
      check('吐球及同requestId重发',response.code===0&&after.added-before.added===1,{code:response.code,newFoodEvents:after.added-before.added});ejectDone=true;
    }
    if(ejectDone&&!splitDone&&own.some(b=>b.radius>=3.05)){
      await keys(first);await sleep(180);
      const before=await state(first.page);const response=await raw(first,2002,'SplitRequest',{directionX:1,directionY:0},2);await sleep(500);
      const after=await state(first.page),balls=after.balls.filter(b=>b.userId===first.account);
      check('分裂及同requestId重发只执行一次',response.code===0&&balls.length===2,{code:response.code,balls:balls.length});
      splitDone=true;splitAt=Date.now();
    }
    if(splitDone&&!mergeDone){
      if(own.length===1 && Date.now()-splitAt>14000){mergeDone=true;check('分裂球冷却后重新合并',true,{radius:own[0].radius});}
      else await drive(first,{x:s.snapshot.width,y:s.snapshot.height},s);
    }else{
      const b=own[0];
      const prey=splitDone&&mergeDone?s.balls.find(p=>p.userId!==first.account&&p.radius*1.15<b?.radius):null;
      const targets=prey?[prey]:s.foods;
      const target=b?targets.slice().sort((a,c)=>Math.hypot(a.x-b.x,a.y-b.y)-Math.hypot(c.x-b.x,c.y-b.y))[0]:null;
      await drive(first,target,s);
    }
    if(!swallowDone && players.slice(1).some(p=>!s.balls.some(b=>b.userId===p.account))){
      const eaten=players.slice(1).find(p=>!s.balls.some(b=>b.userId===p.account));
      const visible=await eaten.page.locator('#spectator-panel').isVisible();
      check('吞噬后客户端进入观战',visible,{user:eaten.account});
      if(visible){const values=await eaten.page.locator('#spectate-target option').evaluateAll(es=>es.map(e=>e.value));if(values.includes(first.account))await eaten.page.locator('#spectate-target').selectOption(first.account);}
      swallowDone=true;
    }
    if(!leftDone && Date.now()-began>75000){
      const p=players[9];await keys(p);await submit(p.page,2005,()=>p.page.locator('#leave').click());p.left=true;
      await sleep(400);const after=await state(first.page);
      check('主动离场其他玩家收到移除消息',!after.ranking?.players.some(e=>e.userId===p.account)&&!after.balls.some(b=>b.userId===p.account),{user:p.account});leftDone=true;
    }
    if(!disconnectDone&&Date.now()-began>95000){
      const p=players[8];await p.context.close();p.disconnected=true;
      await sleep(2000);const after=await state(first.page);
      check('断线玩家自动离场',!after.ranking?.players.some(e=>e.userId===p.account)&&!after.balls.some(b=>b.userId===p.account),{user:p.account});disconnectDone=true;
    }
    if(Date.now()-progressAt>20000){
      progressAt=Date.now();console.log(JSON.stringify({progressSeconds:Math.round((Date.now()-began)/1000),tick:s.tick,remainingMs:Number(s.ranking?.endsAtMs)-Date.now(),foods:s.foods.length,foodEaten:s.foodEaten,own:own.map(b=>({id:b.ballId,radius:b.radius})),events:s.counts,fps:s.fps}));save();
    }
    await sleep(250);
  }
  const last=await state(first.page);
  check('吃食物由服务端增长面积',last.foodEaten>0,{foodEaten:last.foodEaten});
  check('周期食物刷新',last.added>0,{events:last.added});
  check('整局到时冻结最终榜',Boolean(last.ended?.frozen),{ended:last.ended});
  check('已离场及断线玩家不进入最终榜',Boolean(last.ended)&&!last.ended.players.some(p=>players.filter(p=>p.left||p.disconnected).some(t=>t.account===p.userId)),{players:last.ended?.players.length});
  const active=players.filter(p=>!p.left&&!p.disconnected);
  const finals=await Promise.all(active.map(p=>state(p.page)));
  check('仍在场玩家全部收到同一最终榜',finals.every(s=>s.ended?.sceneId===report.sceneId&&JSON.stringify(s.ended.players)===JSON.stringify(last.ended?.players)),{received:finals.filter(s=>s.ended).length,expected:active.length});
  await first.page.screenshot({path:path.join(output,'final-ranking.png'),fullPage:true});
  const root=await protobuf.load(['D:/IdeaProjects/BallsGame/game-protocol/src/main/proto/game.proto','D:/IdeaProjects/BallsGame/game-protocol/src/main/proto/leave.proto']);
  for(const p of [first,players[9]]){
    const type=root.lookupType('ballsgame.MatchHistoryRequest');const env=root.lookupType('ballsgame.ClientEnvelope');
    const n=await p.page.evaluate(()=>window.__live.responses.length);
    const bytes=env.encode(env.fromObject({cmd:1003,requestId:(BigInt(Date.now())*1000000n+BigInt(p.index)).toString(),body:type.encode({limit:20}).finish()})).finish();
    await p.page.evaluate(bytes=>window.__live.socket.send(new Uint8Array(bytes)),Array.from(bytes));
    const r=await waitResponse(p.page,1003,n);const responseType=root.lookupType('ballsgame.MatchHistoryResponse');
    const history=r.code?null:responseType.toObject(responseType.decode(new Uint8Array(r.body)),{longs:String,defaults:true,arrays:true});
    const expectedMatchId=`${last.ended?.sceneInstanceId}:${report.sceneId}`;
    const record=history?.records.find(e=>e.matchId===expectedMatchId);
    check(p===first?'结束玩家能查询本局战绩':'中途退出不保存本局战绩',r.code===0&&(p===first?Boolean(record):!record),{code:r.code,record:record||null});
  }
  report.features={splitDone,ejectDone,mergeDone,swallowDone,leftDone,disconnectDone};
  report.clients=await Promise.all(players.filter(p=>!p.disconnected).map(async p=>({account:p.account,state:await state(p.page)})));
})().catch(e=>{report.errors.push({fatal:e.message,stack:e.stack});console.error(e);process.exitCode=1;}).finally(async()=>{
  report.finishedAt=new Date().toISOString();save();
  if(browser)await browser.close();
  console.log(`REPORT ${path.join(output,'report.json')}`);
});
