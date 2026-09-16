import { describe, expect, it, vi } from 'vitest';
import { createGitChangeClient } from './gitChangeClient';
describe('Git change HTTP boundary', () => {
  it('encodes trusted IDs and preserves cancellation for detail, files and diff', async () => {
    const fetchImpl = vi.fn().mockImplementation(async () => new Response('{}'));
    const client = createGitChangeClient({ fetchImpl });
    const signal = new AbortController().signal;
    await client.detail('s/1', 'gc/2', signal);
    await client.files('s/1', 'gc/2', signal);
    await client.diff('s/1', 'gc/2', 'a&b', signal);
    expect(fetchImpl.mock.calls.map((call) => call[0])).toEqual([
      '/api/v1/agent-sessions/s%2F1/git-changes/gc%2F2',
      '/api/v1/agent-sessions/s%2F1/git-changes/gc%2F2/files',
      '/api/v1/agent-sessions/s%2F1/git-changes/gc%2F2/diff?fileId=a%26b',
    ]);
    expect(fetchImpl.mock.calls.every((call) => call[1].signal === signal)).toBe(true);
  });
  it.each(['confirm', 'cancel', 'retry', 'reconcile'] as const)(
    'posts %s exactly once with version zero',
    async (action) => {
      const fetchImpl = vi
        .fn()
        .mockResolvedValue(
          new Response('{"code":"GIT_CONFLICT","detail":"候选已变化"}', { status: 409 }),
        );
      const signal = new AbortController().signal;
      await expect(
        createGitChangeClient({ fetchImpl }).act('s1', 'gc1', action, 0, signal),
      ).rejects.toMatchObject({ status: 409, code: 'GIT_CONFLICT', message: '候选已变化' });
      expect(fetchImpl).toHaveBeenCalledTimes(1);
      expect(fetchImpl).toHaveBeenCalledWith(
        `/api/v1/agent-sessions/s1/git-changes/gc1/${action}`,
        expect.objectContaining({
          method: 'POST',
          body: '{"version":0}',
          signal,
          headers: { 'Content-Type': 'application/json' },
        }),
      );
    },
  );
});
