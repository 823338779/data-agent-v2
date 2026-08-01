import { describe, expect, it, vi } from 'vitest';
import { createFetchAgentBackendClient } from './backendAgentClient';

const session = {
  conversationSessionId: 'session_1',
  title: '后端会话',
  tag: '',
  createdAt: '2026-08-01T08:00:00Z',
  updatedAt: '2026-08-01T08:00:00Z',
  messages: [],
  execution: null,
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
        headers: expect.objectContaining({
          'Idempotency-Key': expect.stringMatching(
            /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i,
          ),
        }),
        body: JSON.stringify({ content: '分析订单延迟' }),
      }),
    );
  });

  it('maps the current backend snapshot without synthesizing unsupported state', async () => {
    const execution = {
      executionId: 'execution_1',
      status: 'running',
      progress: 5,
      objective: '分析订单延迟',
      events: [],
      streamCheckpoint: { phase: 'RECEIVED', thinking: '检查订单时间', answer: '发现延迟订单' },
    };
    const fetchImpl = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => [
        {
          ...session,
          messages: [
            {
              conversationMessageId: 'message_1',
              role: 'user',
              author: 'admin',
              content: '分析订单延迟',
              createdAt: '2026-08-01T08:01:00Z',
              execution: null,
            },
            {
              conversationMessageId: 'message_2',
              role: 'assistant',
              author: 'assistant',
              content: '发现延迟订单',
              createdAt: '2026-08-01T08:02:00Z',
              execution,
            },
          ],
          execution,
        },
      ],
    });

    const [mapped] = await createFetchAgentBackendClient({ fetchImpl }).listSessions();

    expect(mapped.messages[0]).toMatchObject({ role: 'user', content: '分析订单延迟' });
    expect(mapped.messages[1].execution?.streamCheckpoint?.thinking).toBe('检查订单时间');
    expect(mapped.execution).toMatchObject({ status: 'running', progress: 5 });
    expect(mapped).not.toHaveProperty('metricPlatformCredential');
  });

  it('refreshes for every persisted Agent execution lifecycle event', () => {
    const addEventListener = vi.fn();
    const close = vi.fn();
    const source = { addEventListener, close, onerror: null } as unknown as EventSource;
    const client = createFetchAgentBackendClient({ eventSourceFactory: () => source });

    const unsubscribe = client.subscribeEvents('session_1', vi.fn(), vi.fn());

    expect(addEventListener.mock.calls.map(([eventName]) => eventName)).toEqual([
      'execution_queued',
      'execution_started',
      'execution_done',
    ]);
    unsubscribe();
    expect(close).toHaveBeenCalledOnce();
  });
});
