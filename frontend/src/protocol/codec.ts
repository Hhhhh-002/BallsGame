import protobuf from 'protobufjs';
import Long from 'long';
import generated from './schema.json';
import type { ServerMessage } from './types';

protobuf.util.Long = Long;
protobuf.configure();
const root = protobuf.Root.fromJSON(generated.schema);
export const CMD = generated.commands;
export const commandName = (cmd: number) => Object.entries(CMD).find(([, value]) => value === cmd)?.[0] ?? `CMD_${cmd}`;

export function encode(type: string, body: object): Uint8Array {
  const messageType = root.lookupType(`ballsgame.${type}`);
  const message = messageType.fromObject(body); // fromObject 正确处理十进制 uint64 字符串。
  const failure = messageType.verify(message);
  if (failure) throw new Error(`${type}: ${failure}`);
  return messageType.encode(message).finish();
}
export function decode<T>(type: string, bytes: Uint8Array): T {
  const messageType = root.lookupType(`ballsgame.${type}`);
  return messageType.toObject(messageType.decode(bytes), {
    longs: String, defaults: true, arrays: true, bytes: Uint8Array,
  }) as T;
}
export function decodeEnvelope(bytes: ArrayBuffer): ServerMessage {
  if (bytes.byteLength > 256 * 1024) throw new Error('服务端消息超过 256 KiB 上限');
  return decode<ServerMessage>('ServerEnvelope', new Uint8Array(bytes));
}
