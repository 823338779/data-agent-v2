import { describe, expect, it, vi } from 'vitest';
import { applyExecutionStreamEvent, createFetchAgentBackendClient } from './backendAgentClient';

const session = {
  conversationSessionId: 'session_1',
  title: '后端会话',
  tag: '',
  createdAt: '2026-08-01T08:00:00Z',
  updatedAt: '2026-08-01T08:00:00Z',
  version: 4,
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

  it('renames a session with its expected version and maps the updated version', async () => {
    const fetchImpl = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ ...session, title: '新的标题', version: 5 }),
    });
    const client = createFetchAgentBackendClient({ fetchImpl });

    const renamed = await client.renameSession('session_1', '新的标题', 4);

    expect(renamed.version).toBe(5);
    expect(fetchImpl).toHaveBeenCalledWith(
      '/api/v1/agent-sessions/session_1',
      expect.objectContaining({
        method: 'PATCH',
        credentials: 'include',
        body: JSON.stringify({ title: '新的标题', expectedVersion: 4 }),
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
    expect(mapped.messages[1].execution?.streamCheckpoint?.reasoning).toBe('检查订单时间');
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

  it('reuses one idempotency key for a network retry and rotates it for a new operation', async () => {
    const fetchImpl = vi
      .fn()
      .mockRejectedValueOnce(new TypeError('network unavailable'))
      .mockResolvedValue({ ok: true, json: async () => session });
    const client = createFetchAgentBackendClient({ fetchImpl });

    await client.submitMessage('session_1', '第一次操作');
    await client.submitMessage('session_1', '第二次操作');

    const firstKey = (fetchImpl.mock.calls[0][1]?.headers as Record<string, string>)[
      'Idempotency-Key'
    ];
    const retryKey = (fetchImpl.mock.calls[1][1]?.headers as Record<string, string>)[
      'Idempotency-Key'
    ];
    const nextOperationKey = (fetchImpl.mock.calls[2][1]?.headers as Record<string, string>)[
      'Idempotency-Key'
    ];
    expect(retryKey).toBe(firstKey);
    expect(nextOperationKey).not.toBe(firstKey);
  });

  it('calls control, read, and reorder endpoints with cookie authentication', async () => {
    const execution = {
      executionId: 'execution_1',
      status: 'cancelled',
      progress: 25,
      objective: '分析订单',
      cancellationRequestedAt: '2026-08-01T08:01:00Z',
      events: [],
      streamCheckpoint: null,
    };
    const fetchImpl = vi
      .fn()
      .mockResolvedValueOnce({ ok: true, json: async () => execution })
      .mockResolvedValueOnce({ ok: true, json: async () => execution })
      .mockResolvedValueOnce({ ok: true, json: async () => ({ ...session, unread: false }) })
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({ orderingVersion: 3, sessions: [] }),
      });
    const client = createFetchAgentBackendClient({ fetchImpl });

    await client.cancelExecution('session_1', 'execution_1');
    await client.retryExecution('session_1', 'execution_1');
    await client.markRead('session_1', 8);
    await client.reorderSessions(['session_2', 'session_1'], 2);

    expect(fetchImpl.mock.calls.map(([url]) => url)).toEqual([
      '/api/v1/agent-sessions/session_1/executions/execution_1:cancel',
      '/api/v1/agent-sessions/session_1/executions/execution_1:retry',
      '/api/v1/agent-sessions/session_1:mark-read',
      '/api/v1/agent-sessions:reorder',
    ]);
    expect(fetchImpl.mock.calls.every(([, init]) => init?.credentials === 'include')).toBe(true);
    expect(fetchImpl.mock.calls[3][1]?.body).toBe(
      JSON.stringify({
        conversationSessionIds: ['session_2', 'session_1'],
        expectedOrderingVersion: 2,
      }),
    );
  });

  it('opens activity and detail streams with credentials', () => {
    const sources: Array<{ url: string; init?: EventSourceInit }> = [];
    const eventSourceFactory = vi.fn((url: string, init?: EventSourceInit) => {
      sources.push({ url, init });
      return {
        addEventListener: vi.fn(),
        close: vi.fn(),
        onerror: null,
      } as unknown as EventSource;
    });
    const client = createFetchAgentBackendClient({ eventSourceFactory });

    client.subscribeActivity(vi.fn(), vi.fn());
    client.subscribeExecutionEvents('session_1', vi.fn(), vi.fn(), vi.fn());

    expect(sources).toEqual([
      { url: '/api/v1/agent-sessions/activity-events', init: { withCredentials: true } },
      { url: '/api/v1/agent-sessions/session_1/events', init: { withCredentials: true } },
    ]);
  });

  it('accepts only continuous deltas and replaces partial output on a newer turn snapshot', () => {
    const initial = {
      executionAttemptNumber: 1,
      modelInvocationSequence: 1,
      phase: 'THINKING',
      outputDeltaSequence: 1,
      reasoning: 'A',
      answer: '',
      checkpointedAt: null,
    };
    const duplicate = applyExecutionStreamEvent(initial, {
      type: 'reasoning_delta',
      executionAttemptNumber: 1,
      modelInvocationSequence: 1,
      outputDeltaSequence: 1,
      delta: 'A',
    });
    const gap = applyExecutionStreamEvent(initial, {
      type: 'assistant_delta',
      executionAttemptNumber: 1,
      modelInvocationSequence: 1,
      outputDeltaSequence: 3,
      delta: 'gap',
    });
    const next = applyExecutionStreamEvent(initial, {
      type: 'assistant_delta',
      executionAttemptNumber: 1,
      modelInvocationSequence: 1,
      outputDeltaSequence: 2,
      delta: 'answer',
    });
    const replaced = applyExecutionStreamEvent(next, {
      type: 'stream_snapshot',
      executionAttemptNumber: 1,
      modelInvocationSequence: 2,
      outputDeltaSequence: 1,
      phase: 'THINKING',
      reasoning: 'B',
      answer: '',
      checkpointedAt: '2026-08-01T08:02:00Z',
    });

    expect(duplicate).toBe(initial);
    expect(gap).toBe(initial);
    expect(next.answer).toBe('answer');
    expect(replaced).toMatchObject({
      modelInvocationSequence: 2,
      reasoning: 'B',
      answer: '',
      outputDeltaSequence: 1,
    });
  });
});
