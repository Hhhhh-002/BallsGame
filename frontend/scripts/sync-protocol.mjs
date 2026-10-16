import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import protobuf from 'protobufjs';

// 只读后端源码；生成物全部留在独立客户端。以后协议变化运行 npm run protocol:sync 即可。
const project = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const backendArg = process.argv.indexOf('--backend');
if (backendArg >= 0 && (!process.argv[backendArg + 1] || process.argv[backendArg + 1].startsWith('--'))) {
  throw new Error('--backend 后必须提供后端源码目录');
}
const backend = backendArg < 0 ? resolve(project, '../backend') : resolve(process.argv[backendArg + 1]);
// Git 在 Windows 上可能检出 CRLF；换行差异不应改变协议哈希或校验结果。
const normalizeLines = text => text.replace(/\r\n/g, '\n');
const game = normalizeLines(await readFile(resolve(backend, 'game-protocol/src/main/proto/game.proto'), 'utf8'));
const leave = normalizeLines(await readFile(resolve(backend, 'game-protocol/src/main/proto/leave.proto'), 'utf8'));
const commandsSource = normalizeLines(await readFile(resolve(backend, 'game-common/src/main/java/com/h/gamecommon/command/GameCommands.java'), 'utf8'));
const root = protobuf.parse(game).root;
protobuf.parse(leave, root);
root.resolveAll();
const names = [
  'ClientEnvelope', 'ServerEnvelope', 'LoginRequest', 'LoginResponse', 'PlayerPublicInfo',
  'RegisterRequest', 'RegisterResponse',
  'ShiftRequest', 'SplitRequest', 'EjectRequest', 'StartGameRequest', 'StartGameResponse', 'LeaveRequest',
  'PlayerPosition', 'MoveBroadcast', 'BallState', 'FoodState', 'SceneSnapshot',
  'PlayerEnteredEvent', 'FoodAddedEvent', 'FoodEatenEvent', 'PlayerLeftEvent', 'BallChangedEvent',
  'MatchRankingEntry', 'MatchRanking',
];
const nested = Object.fromEntries(names.map(name => [name, root.lookupType(`ballsgame.${name}`).toJSON()]));
const commands = Object.fromEntries([...commandsSource.matchAll(/\b([A-Z][A-Z_]+)\((\d+)\)/g)]
  .map(match => [match[1], Number(match[2])]));
if (!commands.LOGIN || !commands.MATCH_ENDED) throw new Error('后端 GameCommands 不完整');
const generated = {
  sourceHash: createHash('sha256').update(game + leave + commandsSource).digest('hex'),
  commands,
  schema: { nested: { ballsgame: { nested } } },
};
const target = resolve(project, 'src/protocol/schema.json');
const text = `${JSON.stringify(generated, null, 2)}\n`;
if (process.argv.includes('--check')) {
  if (normalizeLines(await readFile(target, 'utf8')) !== text) throw new Error('客户端协议已落后，请运行 npm run protocol:sync（使用自定义后端路径时也要传入相同的 --backend 参数）');
  console.log(`协议一致：${names.length} 个客户端消息，${Object.keys(commands).length} 个命令。`);
} else {
  await mkdir(dirname(target), { recursive: true });
  await writeFile(target, text);
  console.log(`已从后端同步 ${names.length} 个消息到 ${target}，没有修改后端。`);
}
