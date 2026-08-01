import { describe, expect, it } from 'vitest';
import {
  applyAgentEvent,
  createSession,
  createStore,
  getActiveSession,
  resolveApproval,
  sendUserPrompt,
  startAgentRun,
} from './agentPrototype';
import { mockAgentEvents } from '../mocks/agentEventStream';

describe('agent prototype state model', () => {
  it('creates a multi-session workspace and switches active sessions', () => {
    const first = createSession('Lakehouse 成本分析');
    const second = createSession('Kafka 延迟诊断');
    const store = createStore([first, second], second.id);

    expect(store.sessions).toHaveLength(2);
    expect(getActiveSession(store).title).toBe('Kafka 延迟诊断');
  });

  it('appends a user prompt to the active conversation', () => {
    const store = createStore([createSession('默认会话')]);
    const next = sendUserPrompt(store, '帮我分析过去 7 天的任务失败率');

    expect(getActiveSession(next).messages.at(-1)).toMatchObject({
      role: 'user',
      content: '帮我分析过去 7 天的任务失败率',
    });
  });

  it('starts an empty visible agent run and waits for agent events to populate it', () => {
    const store = sendUserPrompt(createStore([createSession('默认会话')]), '生成数据质量日报');
    const next = startAgentRun(store);
    const active = getActiveSession(next);

    expect(active.agentRun?.status).toBe('running');
    expect(active.agentRun?.progress).toBe(0);
    expect(active.agentRun?.steps).toEqual([]);
  });

  it('projects backend agent events into visible run steps', () => {
    const base = createStore(
      [{ ...createSession('Kafka 延迟诊断'), id: 'session-kafka' }],
      'session-kafka',
    );
    const next = mockAgentEvents.reduce(applyAgentEvent, base);
    const active = getActiveSession(next);

    expect(active.agentRun?.id).toBe('run-kafka-001');
    expect(active.agentRun?.steps.map((step) => step.kind)).toEqual([
      'reasoning',
      'tool',
      'tool',
      'approval',
    ]);
    expect(active.agentRun?.approvalRequest).toMatchObject({
      id: 'approval-export-daily',
      status: 'pending',
    });
  });

  it('resolves approval decisions on the active session run', () => {
    const base = createStore(
      [{ ...createSession('Kafka 延迟诊断'), id: 'session-kafka' }],
      'session-kafka',
    );
    const withRun = mockAgentEvents.reduce(applyAgentEvent, base);

    const approved = resolveApproval(withRun, 'approved');
    expect(getActiveSession(approved).agentRun?.approvalRequest?.status).toBe('approved');

    const rejected = resolveApproval(withRun, 'rejected');
    expect(getActiveSession(rejected).agentRun?.status).toBe('waiting');
    expect(getActiveSession(rejected).agentRun?.approvalRequest?.status).toBe('rejected');
  });

  it('records failed and cancelled runs as explicit terminal states', () => {
    const base = createStore(
      [{ ...createSession('实时联调'), id: 'session-terminal' }],
      'session-terminal',
    );
    const running = applyAgentEvent(base, {
      eventId: 'run-started',
      type: 'run.started',
      sessionId: 'session-terminal',
      runId: 'run-terminal',
      sequence: 1,
      createdAt: '2026-07-11T08:00:00Z',
      payload: { objective: '验证终态' },
    });
    const failed = applyAgentEvent(running, {
      eventId: 'run-failed',
      type: 'run.failed',
      sessionId: 'session-terminal',
      runId: 'run-terminal',
      sequence: 2,
      createdAt: '2026-07-11T08:00:02Z',
      payload: { summary: '工具执行失败' },
    });

    expect(getActiveSession(failed).agentRun).toMatchObject({ status: 'failed', progress: 100 });
  });
});
