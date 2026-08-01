import { describe, expect, it, vi } from 'vitest';
import { createFetchAgentBackendClient } from './backendAgentClient';

const session = {
  id: 'session_1',
  title: '后端会话',
  tag: '',
  createdAt: '2026-08-01T08:00:00Z',
  updatedAt: '2026-08-01T08:00:00Z',
  messages: [],
  run: null,
};

describe('backend agent client', () => {
  it('uses only the implemented session endpoints', async () => {
    const fetchImpl = vi.fn().mockResolvedValue({ ok: true, json: async () => session });
    const client = createFetchAgentBackendClient({ fetchImpl });

    await client.createSession('新会话', '');
    await client.getSession('session_1');
    await client.submitMessage('session_1', '分析订单延迟');

    expect(fetchImpl).toHaveBeenNthCalledWith(
      1,
      '/api/v1/agent-sessions',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ title: '新会话', tag: '' }),
      }),
    );
    expect(fetchImpl).toHaveBeenNthCalledWith(
      2,
      '/api/v1/agent-sessions/session_1',
      expect.objectContaining({ credentials: 'include' }),
    );
    expect(fetchImpl).toHaveBeenNthCalledWith(
      3,
      '/api/v1/agent-sessions/session_1/messages',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ content: '分析订单延迟' }),
      }),
    );
  });

  it('maps the current backend snapshot without synthesizing unsupported state', async () => {
    const fetchImpl = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => [
        {
          ...session,
          messages: [
            {
              id: 'message_1',
              role: 'user',
              author: 'admin',
              content: '分析订单延迟',
              createdAt: '2026-08-01T08:01:00Z',
            },
          ],
          run: {
            id: 'run_1',
            status: 'running',
            progress: 5,
            objective: '分析订单延迟',
            events: [],
            stream: { phase: 'RECEIVED', thinking: '', answer: '' },
          },
        },
      ],
    });

    const [mapped] = await createFetchAgentBackendClient({ fetchImpl }).listSessions();

    expect(mapped.messages[0]).toMatchObject({ role: 'user', content: '分析订单延迟' });
    expect(mapped.run).toMatchObject({ status: 'running', progress: 5 });
    expect(mapped).not.toHaveProperty('metricIdentity');
  });
});
