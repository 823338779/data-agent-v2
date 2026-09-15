import { describe, expect, it, vi } from 'vitest';
import {
  applyConversationStreamEvent,
  applyExecutionStreamEvent,
  createFetchAgentBackendClient,
} from './backendAgentClient';

const session = {
  conversationSessionId: 'session_1',
  title: '后端会话',
  tag: '',
  projectId: null,
  pinnedAt: null,
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
      expect.any(Object),
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
      streamCheckpoint: { phase: 'RECEIVED', answer: '发现延迟订单' },
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
              author: 'user_admin',
              content: '分析订单延迟',
              createdAt: '2026-08-01T08:01:00Z',
              version: 0,
              thinkingProcess: [],
              execution: null,
            },
            {
              conversationMessageId: 'message_2',
              role: 'assistant',
              author: 'assistant',
              content: '发现延迟订单',
              createdAt: '2026-08-01T08:02:00Z',
              version: 2,
              thinkingProcess: [],
              artifacts: [
                {
                  artifactId: 'artifact_1',
                  artifactType: 'DOCX',
                  displayName: 'report.docx',
                  mediaType:
                    'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
                  sizeBytes: 3,
                  sha256: 'a'.repeat(64),
                  pageOrSheetCount: 1,
                  downloadUrl: '/api/v1/agent-sessions/session_1/artifacts/artifact_1/content',
                },
              ],
              uiBlocks: [
                {
                  blockId: 'block_1',
                  type: 'artifact-card',
                  schemaVersion: 1,
                  variant: 'default',
                  payload: { artifactId: 'artifact_1' },
                },
              ],
              execution,
            },
          ],
          execution,
        },
      ],
    });

    const [mapped] = await createFetchAgentBackendClient({ fetchImpl }).listSessions();

    expect(mapped.messages[0]).toMatchObject({ role: 'user', content: '分析订单延迟' });
    expect(mapped.messages[0]).toMatchObject({ artifacts: [], uiBlocks: [] });
    expect(mapped.messages[1].artifacts[0]).toMatchObject({
      artifactId: 'artifact_1',
      artifactType: 'DOCX',
    });
    expect(mapped.messages[1].uiBlocks[0]).toMatchObject({ blockId: 'block_1' });
    expect(mapped.messages[1].execution?.streamCheckpoint).not.toHaveProperty('reasoning');
    expect(mapped.execution).toMatchObject({ status: 'running', progress: 5 });
    expect(mapped).not.toHaveProperty('metricPlatformCredential');
  });

  it('restores a missing process type before rendering a backend snapshot', async () => {
    const fetchImpl = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        ...session,
        messages: [
          {
            conversationMessageId: 'message_thinking',
            role: 'assistant',
            author: 'assistant',
            content: '',
            createdAt: '2026-08-04T01:00:00Z',
            version: 1,
            thinkingProcess: [
              {
                processItemId: 'thinking_1',
                sequence: 1,
                status: 'SUCCEEDED',
                createdAt: '2026-08-04T01:00:00Z',
                startedAt: '2026-08-04T01:00:00Z',
                completedAt: '2026-08-04T01:00:00.012Z',
                durationMillis: 12,
                schemaVersion: 1,
                content: '分析数据范围',
                contentTruncated: false,
                originalContentSizeBytes: 18,
                contentSha256: 'thinking-sha',
                modelInvocationSequence: 1,
                modelProvider: 'openai',
                modelName: 'gpt-test',
              },
            ],
            execution: null,
          },
        ],
      }),
    });

    const mapped = await createFetchAgentBackendClient({ fetchImpl }).getSession('session_1');

    expect(mapped.messages[0].thinkingProcess[0]).toMatchObject({
      type: 'THINKING',
      content: '分析数据范围',
    });
  });

  it('subscribes to one snapshot and directly reducible incremental event protocol', () => {
    const addEventListener = vi.fn();
    const close = vi.fn();
    const source = { addEventListener, close, onerror: null } as unknown as EventSource;
    const client = createFetchAgentBackendClient({ eventSourceFactory: () => source });

    const unsubscribe = client.subscribeSessionEvents('session_1', vi.fn(), vi.fn());

    expect(addEventListener.mock.calls.map(([eventName]) => eventName)).toEqual([
      'session_snapshot',
      'execution_update',
      'message_update',
      'stream_snapshot',
      'assistant_delta',
      'workflow_snapshot',
      'workflow_update',
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

  it('rejects an SSE event routed for another session', () => {
    const listeners = new Map<string, EventListener>();
    const onEvent = vi.fn();
    const onError = vi.fn();
    const client = createFetchAgentBackendClient({
      eventSourceFactory: () =>
        ({
          addEventListener: vi.fn((type: string, listener: EventListener) =>
            listeners.set(type, listener),
          ),
          close: vi.fn(),
          onerror: null,
        }) as unknown as EventSource,
    });
    client.subscribeSessionEvents('session_1', onEvent, onError);

    listeners.get('assistant_delta')?.({
      data: JSON.stringify({
        type: 'assistant_delta',
        conversationSessionId: 'session_2',
        modelInvocationSequence: 1,
        outputDeltaSequence: 1,
        delta: '错误会话',
      }),
    } as MessageEvent<string>);

    expect(onEvent).not.toHaveBeenCalled();
    expect(onError).toHaveBeenCalledWith(
      expect.objectContaining({ message: 'SSE_PROTOCOL_SESSION_MISMATCH' }),
    );
  });

  it('accepts continuous answer deltas and replaces partial output on a newer turn snapshot', () => {
    const initial = {
      modelInvocationSequence: 1,
      phase: 'ANSWERING',
      outputDeltaSequence: 1,
      answer: '',
    };
    const duplicate = applyExecutionStreamEvent(initial, {
      type: 'assistant_delta',
      conversationSessionId: 'session_1',
      executionId: 'execution_1',
      occurredAt: '2026-08-01T08:01:00Z',
      modelInvocationSequence: 1,
      outputDeltaSequence: 1,
      delta: 'A',
    });
    const gap = applyExecutionStreamEvent(initial, {
      type: 'assistant_delta',
      conversationSessionId: 'session_1',
      executionId: 'execution_1',
      occurredAt: '2026-08-01T08:01:00Z',
      modelInvocationSequence: 1,
      outputDeltaSequence: 3,
      delta: 'answer',
    });
    const next = applyExecutionStreamEvent(initial, {
      type: 'assistant_delta',
      conversationSessionId: 'session_1',
      executionId: 'execution_1',
      occurredAt: '2026-08-01T08:01:00Z',
      modelInvocationSequence: 1,
      outputDeltaSequence: 2,
      delta: 'answer',
    });
    const replaced = applyExecutionStreamEvent(next, {
      type: 'stream_snapshot',
      conversationSessionId: 'session_1',
      executionId: 'execution_1',
      occurredAt: '2026-08-01T08:02:00Z',
      modelInvocationSequence: 2,
      outputDeltaSequence: 1,
      phase: 'ANSWERING',
      answer: '',
    });

    expect(duplicate).toBe(initial);
    expect(gap).toBe(initial);
    expect(next.answer).toBe('answer');
    expect(replaced).toMatchObject({
      modelInvocationSequence: 2,
      answer: '',
      outputDeltaSequence: 1,
    });
  });

  it('replaces a message only when message_update advances its version', () => {
    const current = {
      ...session,
      messages: [
        {
          conversationMessageId: 'assistant_1',
          executionId: 'execution_1',
          role: 'assistant' as const,
          author: 'assistant',
          content: '旧回答',
          timestamp: '2026-08-04T01:00:00Z',
          version: 1,
          thinkingProcess: [],
          execution: null,
        },
      ],
      execution: null,
    };
    const message = { ...current.messages[0], content: '新回答', version: 2 };

    const advanced = applyConversationStreamEvent(current, {
      type: 'message_update',
      conversationSessionId: 'session_1',
      message,
      occurredAt: '2026-08-04T01:00:01Z',
    });
    const duplicate = applyConversationStreamEvent(advanced, {
      type: 'message_update',
      conversationSessionId: 'session_1',
      message: { ...message, content: '重复回答' },
      occurredAt: '2026-08-04T01:00:02Z',
    });

    expect(advanced.messages[0]).toMatchObject({ content: '新回答', version: 2 });
    expect(duplicate).toBe(advanced);
  });

  it('reduces workflow snapshot and only the next matching update', () => {
    const current = {
      ...session,
      execution: {
        executionId: 'execution_1',
        status: 'running' as const,
        progress: 10,
        objective: '分析销售额',
        events: [],
        streamCheckpoint: null,
      },
    };
    const snapshot = applyConversationStreamEvent(current, {
      type: 'workflow_snapshot',
      conversationSessionId: 'session_1',
      executionId: 'execution_1',
      occurredAt: '2026-08-30T12:00:00Z',
      workflow: {
        workflowId: 'workflow_1',
        executionId: 'execution_1',
        latestSequence: 1,
        status: 'running',
        currentStep: 'generate_sql',
        tasks: [],
        startedAt: '2026-08-30T12:00:00Z',
        updatedAt: '2026-08-30T12:00:00Z',
      },
    });
    const update = {
      type: 'workflow_update' as const,
      conversationSessionId: 'session_1',
      executionId: 'execution_1',
      workflowId: 'workflow_1',
      sequence: 2,
      eventType: 'step_succeeded' as const,
      step: 'explain_sql' as const,
      status: 'running' as const,
      task: { taskId: 'sales', purpose: '统计销售额', index: 1, total: 1 },
      detail: {
        semanticResourceNames: [],
        sql: 'SELECT SUM(paid_amount) FROM fact_orders LIMIT 1000',
        returnedRows: null,
        truncated: null,
        attempt: 1,
        maxAttempts: 3,
        elapsedMillis: 5,
        message: 'SQL 已通过预检',
        failureCode: null,
      },
      occurredAt: '2026-08-30T12:00:01Z',
    };
    const advanced = applyConversationStreamEvent(snapshot, update);
    const duplicate = applyConversationStreamEvent(advanced, update);
    const gap = applyConversationStreamEvent(advanced, { ...update, sequence: 4 });

    expect(advanced.workflow?.latestSequence).toBe(2);
    expect(advanced.workflow?.tasks[0].sql).toContain('SELECT SUM');
    expect(duplicate).toBe(advanced);
    expect(gap).toBe(advanced);
  });
});
