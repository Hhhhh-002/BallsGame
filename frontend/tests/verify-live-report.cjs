// 读取真实联调报告和本次比赛的 MySQL 数据；不更新、删除任何数据库记录。
const fs = require('node:fs');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const protobuf = require('protobufjs');
const file = path.resolve(process.argv[2]);
const report = JSON.parse(fs.readFileSync(file, 'utf8'));
const checks = [];
const check = (name, pass, details) => checks.push({ name, pass, details });
const docker = args => {
  const r = spawnSync('docker', args, { encoding: 'utf8', windowsHide: true });
  if(r.status!==0)throw new Error(`Docker 读取失败: ${r.stderr}`);
  return r.stdout.trim();
};
(async()=>{
  if(!report.final)throw new Error('报告尚未收到最终榜');
  const root=await protobuf.load(['D:/IdeaProjects/BallsGame/game-protocol/src/main/proto/game.proto', 'D:/IdeaProjects/BallsGame/game-protocol/src/main/proto/match.proto']);
  const type=root.lookupType('ballsgame.persistence.MatchProto');
  const resultType=root.lookupType('ballsgame.persistence.MatchResultProto');
  const historyType=root.lookupType('ballsgame.MatchHistoryResponse');
  const matchId=`${report.final.sceneInstanceId}:${report.sceneId}`;
  if(!/^[\w-]+:\d+$/.test(matchId))throw new Error('测试比赛主键格式异常');
  const env=JSON.parse(docker(['inspect','ballsgame-mysql','--format','{{json .Config.Env}}']));
  const credential=env.find(e=>e.startsWith('MYSQL_ROOT_PASSWORD='));
  if(!credential)throw new Error('未找到测试数据库连接凭据');
  const sql=q=>docker(['exec','-e',`MYSQL_PWD=${credential.slice('MYSQL_ROOT_PASSWORD='.length)}`,'ballsgame-mysql','mysql','-u','root','-N','-B','ballsgame','-e',q]);
  const matchRow=sql(`SELECT HEX(data) FROM game_match WHERE match_id='${matchId}'`);
  const match=type.toObject(type.decode(Buffer.from(matchRow,'hex')),{longs:String,defaults:true,arrays:true});
  check('MySQL 比赛记录有开始时间、结束时间和完整最终榜',match.matchId===matchId&&BigInt(match.endTime)>BigInt(match.startTime)&&match.finalPlayerCount===report.final.players.length,{match});
  const rows=sql(`SELECT HEX(data) FROM game_match_result WHERE result_id LIKE '%:${matchId}'`).split('\n').filter(Boolean);
  const results=rows.map(hex=>resultType.toObject(resultType.decode(Buffer.from(hex,'hex')),{longs:String,defaults:true,arrays:true}));
  check('MySQL 个人战绩与最终榜逐人一致',results.length===report.final.players.length&&report.final.players.every(p=>results.some(r=>r.userId===p.userId&&r.nickname===p.nickname&&r.rank===p.rank&&r.area===p.area&&r.finalPlayerCount===report.final.players.length&&r.matchId===matchId)),{count:results.length});
  const excluded=report.accounts.slice(8);
  check('MySQL 不包含主动退出及断线玩家',excluded.every(user=>!results.some(r=>r.userId===user)),{excluded});
  for(const user of [report.accounts[0],report.accounts[9]]){
    const client=report.clients.find(p=>p.account===user);
    const response=client?.state.responses.filter(r=>r.cmd===1003).at(-1);
    const history=response?.code===0?historyType.toObject(historyType.decode(new Uint8Array(response.body)),{longs:String,defaults:true,arrays:true}):null;
    const record=history?.records.find(r=>r.matchId===matchId);
    check(user===report.accounts[0]?'Gateway 战绩查询返回本局结果':'Gateway 战绩查询不返回已离场玩家本局结果',Boolean(history)&&(user===report.accounts[0]?Boolean(record):!record),{user,record:record||null});
  }
  const movement=report.clients[0].state.moves;
  for(const cmd of [2002,2003]){
    const responses=report.clients[0].state.responses.filter(r=>r.cmd===cmd);
    check(cmd===2002?'分裂重复请求重放同一成功响应':'吐球重复请求重放同一成功响应',responses.length===2&&responses.every(r=>r.code===0&&r.requestId===responses[0].requestId&&JSON.stringify(r.body)===JSON.stringify(responses[0].body)),{cmd,responses:responses.map(r=>({requestId:r.requestId,code:r.code}))});
  }
  const span=movement.at(-1).at-movement[0].at;
  check('位置广播约5Hz，逻辑推进约25Hz',Math.abs((movement.length-1)*1000/span-5)<.5&&Math.abs((Number(movement.at(-1).tick)-Number(movement[0].tick))*1000/span-25)<1,{broadcastHz:(movement.length-1)*1000/span,logicHz:(Number(movement.at(-1).tick)-Number(movement[0].tick))*1000/span});
  check('整个比赛无浏览器异常或意外断线',report.errors.length===0&&report.clients.every(p=>p.state.closed.length===0),{errors:report.errors});
  const businessErrors=report.clients.flatMap(p=>p.state.errors.map(e=>({user:p.account,...e})));
  check('没有多余业务错误推送',businessErrors.length===0,{businessErrors});
  const output=path.join(path.dirname(file),'verified-report.json');
  fs.writeFileSync(output,JSON.stringify({matchId,checks,results,features:report.features,
    testCorrection:'原实时脚本曾只用 Scene ID 比较战绩 matchId；本文件按实际的 Scene 实例 ID:Scene ID 重新解析真实回包和数据库核对，业务查询通过。'},null,2));
  console.log(JSON.stringify(checks,null,2));console.log(`VERIFIED_REPORT ${output}`);
  if(checks.some(c=>!c.pass))process.exitCode=1;
})().catch(e=>{console.error(e.message);process.exitCode=1;});
