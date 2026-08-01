/** 为一次用户操作生成幂等键；同一操作的传输重试必须复用返回值。 */
export function createIdempotencyKey(): string {
  return crypto.randomUUID();
}
