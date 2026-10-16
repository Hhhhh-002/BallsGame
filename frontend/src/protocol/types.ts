/** 所有 uint64 ID 用十进制字符串，不能转成 Number；Scene ID 已超过 JS 安全整数范围。 */
export interface Ball { ballId: string; userId: string; x: number; y: number; radius: number; nickname: string }
export interface Food { foodId: string; x: number; y: number }
export interface Position { ballId: string; userId: string; x: number; y: number; radius: number }
export interface Snapshot { sceneId: string; serverTick: string; balls: Ball[]; foods: Food[]; width: number; height: number; tickRate: number;
  started: boolean; minPlayersToStart: number; playerCount: number }
export interface RankingEntry { userId: string; nickname: string; area: number; rank: number; left: boolean }
export interface Ranking {
  sceneId: string; sceneInstanceId: string; serverTick: string; players: RankingEntry[];
  frozen: boolean; startedAtMs: string; endsAtMs: string; endedAtMs: string; endReason: number;
}
export interface ServerMessage { cmd: number; requestId: string; code: number; message: string; body: Uint8Array }
