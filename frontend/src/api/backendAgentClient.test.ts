import { describe, expect, it, vi } from 'vitest';
import {
  applyBackendAgentEvent,
  createFetchAgentBackendClient,
  orderBackendEvents,
  toAgentSession,
} from './backendAgentClient';
import { createSession, createStore, getActiveSession } from '../domain/agentPrototype';

const backendSession = {
  id: 'session_backend',
  title: '后端联调会话',
  tag: 'debug',
  createdAt: '2026-07-08T13:00:00Z',
  updatedAt: '2026-07-08T13:01:00Z',
  messages: [
    {
      id: 'message_1',
      role: 'assistant',
      author: 'Data Agent',
      content: 'I am connected to catalog.',
      createdAt: '2026-07-08T13:00:00Z',
    },
  ],
  run: {
    id: 'run_1',
    status: 'waiting_confirmation',
    progress: 68,
    objective: '生成日报',
    events: [
      {
        id: 'event_1',
        sequence: 3,
        type: 'reasoning_delta',
        occurredAt: '2026-07-08T13:00:03Z',
        runId: 'run_1',
        messageId: null,
        title: 'Decompose objective',
        summary: 'Identified data source discovery.',
        toolName: null,
        input: {},
        output: {},
        confirmationId: null,
        approved: null,
        status: 'done',
      },
      {
        id: 'event_2',
        sequence: 4,
        type: 'tool_call_started',
        occurredAt: '2026-07-08T13:00:04Z',
        runId: 'run_1',
        messageId: null,
        title: 'Query metadata catalog',
        summary: 'Searching recent datasets.',
        toolName: 'catalog.search',
        input: { keyword: 'task_failure_rate' },
        output: {},
        confirmationId: null,
        approved: null,
        status: 'running',
      },
      {
        id: 'event_3',
        sequence: 10,
        type: 'user_confirmation_requested',
        occurredAt: '2026-07-08T13:00:10Z',
        runId: 'run_1',
        messageId: null,
        title: 'Confirm report export',
        summary: 'Exporting the daily report requires approval.',
        toolName: null,
        input: {},
        output: {},
        confirmationId: 'confirmation_1',
        approved: null,
        status: 'waiting',
      },
    ],
    pendingConfirmation: {
      id: 'confirmation_1',
      title: 'Confirm report export',
      summary: 'Exporting the daily report requires approval.',
      detail: 'Approve to continue report generation.',
      requestedAt: '2026-07-08T13:00:10Z',
    },
  },
};

describe('backend agent client', () => {
  it('posts user messages to the Spring Boot session endpoint', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => backendSession,
    });
    const client = createFetchAgentBackendClient({ fetchImpl: fetchMock });

    await client.submitMessage('session_backend', '帮我生成日报');

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/agent-sessions/session_backend/messages',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ content: '帮我生成日报' }),
      }),
    );
  });

  it('normalizes backend session responses into the conversation state model', () => {
    const session = toAgentSession(backendSession);

    expect(session.id).toBe('session_backend');
    expect(session.messages[0]).toMatchObject({
      role: 'assistant',
      author: 'Data Agent',
    });
    expect(session.agentRun?.status).toBe('waiting');
    expect(session.agentRun?.steps.map((step) => step.kind)).toEqual([
      'reasoning',
      'tool',
      'approval',
    ]);
    expect(session.agentRun?.approvalRequest).toMatchObject({
      id: 'confirmation_1',
      status: 'pending',
    });
  });

  it('posts confirmation decisions to the backend confirmation endpoint', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => backendSession,
    });
    const client = createFetchAgentBackendClient({ fetchImpl: fetchMock });

    await client.confirm('session_backend', 'confirmation_1', 'approved');

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/agent-sessions/session_backend/confirmations/confirmation_1',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ approved: true, comment: '' }),
      }),
    );
  });

  it('projects streamed backend events into the active session instead of rendering fixed run content', () => {
    const initial = createStore(
      [{ ...createSession('实时联调'), id: 'session_backend' }],
      'session_backend',
    );
    const running = applyBackendAgentEvent(initial, 'session_backend', {
      id: 'event_tool_started',
      sequence: 1,
      type: 'tool_call_started',
      occurredAt: '2026-07-08T13:00:00Z',
      runId: 'run_stream',
      messageId: null,
      title: 'Query metadata catalog',
      summary: 'Searching recent datasets.',
      toolName: 'catalog.search',
      input: { keyword: 'task_failure_rate' },
      output: {},
      confirmationId: null,
      approved: null,
      status: 'running',
    });
    const waiting = applyBackendAgentEvent(running, 'session_backend', {
      id: 'event_approval',
      sequence: 2,
      type: 'user_confirmation_requested',
      occurredAt: '2026-07-08T13:00:02Z',
      runId: 'run_stream',
      messageId: null,
      title: 'Confirm report export',
      summary: 'Approval is required before export.',
      toolName: null,
      input: {},
      output: {},
      confirmationId: 'confirmation_1',
      approved: null,
      status: 'waiting',
    });

    expect(getActiveSession(running).agentRun?.steps[0]).toMatchObject({
      kind: 'tool',
      toolName: 'catalog.search',
      status: 'running',
    });
    expect(getActiveSession(waiting).agentRun?.approvalRequest).toMatchObject({
      id: 'confirmation_1',
      status: 'pending',
    });
  });

  it('orders event replay by sequence and ignores duplicate run sequences', () => {
    const earlier = { ...backendSession.run.events[0], id: 'event_earlier', sequence: 2 };
    const later = { ...backendSession.run.events[1], id: 'event_later', sequence: 5 };
    const replayedLater = { ...later, id: 'event_replayed' };

    expect(orderBackendEvents([later, earlier, replayedLater]).map((event) => event.id)).toEqual([
      'event_earlier',
      'event_later',
    ]);
  });

  it('projects incremental ReAct observations and terminal errors without waiting for a final answer', () => {
    const initial = createStore(
      [{ ...createSession('ReAct session'), id: 'session_backend' }],
      'session_backend',
    );
    const reasoning = applyBackendAgentEvent(initial, 'session_backend', {
      id: 'reason_1',
      sequence: 1,
      type: 'reasoning_delta',
      occurredAt: '2026-07-18T00:00:01Z',
      runId: 'run_react',
      messageId: null,
      title: 'ReAct decision',
      summary: 'Discover active sources',
      toolName: null,
      input: {},
      output: {},
      confirmationId: null,
      approved: null,
      status: 'done',
    });
    const started = applyBackendAgentEvent(reasoning, 'session_backend', {
      id: 'tool_1',
      sequence: 2,
      type: 'tool_call_started',
      occurredAt: '2026-07-18T00:00:02Z',
      runId: 'run_react',
      messageId: null,
      title: 'Tool call started',
      summary: 'Discover active sources',
      toolName: 'list_active_data_sources',
      input: {},
      output: {},
      confirmationId: null,
      approved: null,
      status: 'running',
    });
    const observed = applyBackendAgentEvent(started, 'session_backend', {
      id: 'tool_2',
      sequence: 3,
      type: 'tool_call_result',
      occurredAt: '2026-07-18T00:00:03Z',
      runId: 'run_react',
      messageId: null,
      title: 'Active data sources',
      summary: 'Found one active source',
      toolName: 'list_active_data_sources',
      input: {},
      output: { dataSources: [{ id: 'source-1', name: 'Warehouse', engine: 'STARROCKS' }] },
      confirmationId: null,
      approved: null,
      status: 'done',
    });

    expect(getActiveSession(reasoning).agentRun?.steps).toHaveLength(1);
    expect(getActiveSession(started).agentRun?.steps.at(-1)).toMatchObject({
      toolName: 'list_active_data_sources',
      status: 'running',
    });
    expect(getActiveSession(observed).agentRun?.steps.at(-1)).toMatchObject({ status: 'done' });
    expect(getActiveSession(observed).agentRun?.steps.at(-1)?.output).toContain('Warehouse');

    const failed = applyBackendAgentEvent(observed, 'session_backend', {
      id: 'error_1',
      sequence: 4,
      type: 'error',
      occurredAt: '2026-07-18T00:00:04Z',
      runId: 'run_react',
      messageId: null,
      title: 'Agent run failed',
      summary: 'Tool execution failed',
      toolName: 'list_active_data_sources',
      input: {},
      output: {},
      confirmationId: null,
      approved: null,
      status: 'failed',
    });
    expect(getActiveSession(failed).agentRun?.status).toBe('failed');
  });

  it('notifies subscribers when EventSource opens', () => {
    const listeners = new Map<string, EventListener>();
    const source = {
      addEventListener: vi.fn((type: string, listener: EventListener) =>
        listeners.set(type, listener),
      ),
      close: vi.fn(),
      onerror: null as (() => void) | null,
    };
    const onOpen = vi.fn();
    const client = createFetchAgentBackendClient({
      eventSourceFactory: () => source as unknown as EventSource,
    });

    client.subscribeEvents('session_backend', vi.fn(), undefined, onOpen);
    listeners.get('open')?.(new Event('open'));

    expect(onOpen).toHaveBeenCalledOnce();
  });

  it('closes EventSource after receiving a terminal run event', () => {
    const listeners = new Map<string, EventListener>();
    const source = {
      addEventListener: vi.fn((type: string, listener: EventListener) =>
        listeners.set(type, listener),
      ),
      close: vi.fn(),
      onerror: null as (() => void) | null,
    };
    const client = createFetchAgentBackendClient({
      eventSourceFactory: () => source as unknown as EventSource,
    });
    const onEvent = vi.fn();

    client.subscribeEvents('session_backend', onEvent);
    const event = {
      id: 'complete_1',
      sequence: 2,
      type: 'run_completed',
      occurredAt: '2026-07-18T00:00:02Z',
      runId: 'run_react',
      messageId: null,
      title: 'Run completed',
      summary: 'Done',
      toolName: null,
      input: {},
      output: {},
      confirmationId: null,
      approved: null,
      status: 'done',
    };
    listeners.get('run_completed')?.(
      new MessageEvent('run_completed', { data: JSON.stringify(event) }),
    );

    expect(onEvent).toHaveBeenCalledWith(event);
    expect(source.close).toHaveBeenCalledOnce();
  });
});
