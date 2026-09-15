import { describe, expect, it, vi } from 'vitest';
import { createFetchAgentBackendClient } from './backendAgentClient';

describe('sidebar HTTP client', () => {
  it('uses sibling paths with a proxy prefix and passes request cancellation', async () => {
    const fetchImpl = vi
      .fn()
      .mockResolvedValue(new Response(JSON.stringify({ projects: [], sessions: [] })));
    const client = createFetchAgentBackendClient({
      baseUrl: 'https://example.test/proxy/api/v1/agent-sessions/',
      fetchImpl,
    });
    const signal = new AbortController().signal;
    expect(await client.getSidebar(signal)).toEqual({ projects: [], sessions: [] });
    expect(fetchImpl).toHaveBeenCalledWith(
      'https://example.test/proxy/api/v1/agent-sidebar',
      expect.objectContaining({ signal }),
    );
  });
  it('sends explicit null without retrying mutations and handles 204', async () => {
    const fetchImpl = vi
      .fn()
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockRejectedValueOnce(new TypeError('network'));
    const client = createFetchAgentBackendClient({ fetchImpl });
    await client.deleteProject('p/1', 4);
    expect(fetchImpl).toHaveBeenNthCalledWith(
      1,
      '/api/v1/agent-projects/p%2F1?expectedVersion=4',
      expect.objectContaining({ method: 'DELETE' }),
    );
    await expect(
      client.setOrganization('s1', { projectId: null, pinned: false, expectedVersion: 1 }),
    ).rejects.toThrow('network');
    expect(fetchImpl).toHaveBeenCalledTimes(2);
    expect(JSON.parse(fetchImpl.mock.calls[1][1].body)).toEqual({
      projectId: null,
      pinned: false,
      expectedVersion: 1,
    });
  });
});
