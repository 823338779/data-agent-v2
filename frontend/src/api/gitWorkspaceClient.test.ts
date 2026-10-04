import { describe, expect, it, vi } from 'vitest';
import { createGitWorkspaceClient } from './gitWorkspaceClient';

describe('独立工作区 HTTP 契约', () => {
  it('不同会话可在同一分支创建独立草稿，并保留创建幂等键', async () => {
    const fetchImpl = vi.fn().mockImplementation(async () => new Response('{}'));
    const client = createGitWorkspaceClient({ fetchImpl });
    const signal = new AbortController().signal;
    await client.create(
      { sessionId: 's1', branchId: 'b1', baseCommitSha: 'a'.repeat(40), idempotencyKey: 'k1' },
      signal,
    );
    await client.create(
      { sessionId: 's2', branchId: 'b1', baseCommitSha: 'a'.repeat(40), idempotencyKey: 'k2' },
      signal,
    );
    const bodies = fetchImpl.mock.calls.map((call) => JSON.parse(call[1].body));
    expect(bodies.map((body) => body.sessionId)).toEqual(['s1', 's2']);
    expect(bodies.map((body) => body.branchId)).toEqual(['b1', 'b1']);
    expect(bodies.map((body) => body.idempotencyKey)).toEqual(['k1', 'k2']);
    await client.attach('s/2', 'w/1', signal);
    expect(fetchImpl).toHaveBeenLastCalledWith(
      '/api/v1/agent-sessions/s%2F2/git-workspaces/w%2F1',
      expect.objectContaining({ method: 'PUT', signal }),
    );
  });
});
