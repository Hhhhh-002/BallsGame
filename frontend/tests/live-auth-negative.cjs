// 补充真实登录边界测试，不加入正在测试的比赛。
const { chromium } = require('../.local-preview/tooling/node_modules/playwright');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const results = [];
let browser;
(async () => {
  browser = await chromium.launch({ headless: true });
  const first = await browser.newPage(), second = await browser.newPage();
  for (const page of [first, second]) {
    await page.goto('http://127.0.0.1:5173/', { waitUntil: 'networkidle' });
    await page.evaluate(async () => {
      const { Gateway } = await import('/src/network/gateway.ts');
      window.testGateway = new Gateway(); window.testEvents = []; window.testClosed = [];
      window.testGateway.onMessage = e => window.testEvents.push({ cmd:e.cmd,code:e.code,message:e.message });
      window.testGateway.onClose = e => window.testClosed.push(e);
      await window.testGateway.connect(window.BALLS_CLIENT_CONFIG.gatewayUrl);
    });
  }
  const account = `boundary-${Date.now()}@example.com`, password = `Safe!9${crypto.randomBytes(12).toString('hex')}`;
  const execute = (page, cmd, type, body) => page.evaluate(async ({cmd,type,body}) => {
    try { const r=await window.testGateway.execute(window.testGateway.prepare(cmd,type,body),100000);
      return { code:r.code,message:r.message }; }
    catch(e) {return {code:e.code||-1,message:e.message};}
  }, {cmd,type,body});
  const record = (name, response, expected) => { const r={name,pass:response.code===expected,...response}; results.push(r); console.log(JSON.stringify(r)); };
  // 当前 Gateway 的战斗入口按是否已绑定 Agent 检查，未登录返回 AGENT_ROUTE_MISSING(4006)。
  record('未登录不能开始比赛',await execute(first,2006,'StartGameRequest',{nickname:'未登录'}),4006);
  record('注册新邮箱',await execute(first,1002,'RegisterRequest',{account,password}),0);
  record('重复邮箱拒绝注册',await execute(first,1002,'RegisterRequest',{account,password}),40022);
  record('错误密码拒绝登录',await execute(first,1001,'LoginRequest',{account,password:`Wrong!9${crypto.randomBytes(8).toString('hex')}`}),40013);
  record('正确密码登录',await execute(first,1001,'LoginRequest',{account,password}),0);
  record('服务端拒绝控制字符昵称',await execute(first,2006,'StartGameRequest',{nickname:'非法\u0000昵称'}),40105);
  record('同账号第二个连接登录成功',await execute(second,1001,'LoginRequest',{account,password}),0);
  await new Promise(resolve=>setTimeout(resolve,1200));
  const old = await first.evaluate(()=>({events:window.testEvents,closed:window.testClosed,open:window.testGateway.open}));
  const replacement={name:'第二次登录踢掉旧连接',pass:!old.open&&old.events.some(e=>e.cmd===3001),details:old};results.push(replacement);console.log(JSON.stringify(replacement));
  await second.evaluate(()=>window.testGateway.disconnect());
})().catch(e=>{results.push({fatal:e.message});console.error(e);process.exitCode=1;}).finally(async()=>{
  if(browser)await browser.close();
  const output=path.resolve(__dirname,'../.local-preview/auth-boundary-report.json');
  fs.writeFileSync(output,JSON.stringify(results,null,2));console.log(`REPORT ${output}`);
});
