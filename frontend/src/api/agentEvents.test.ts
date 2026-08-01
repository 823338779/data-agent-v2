import { describe, expect, it, vi } from 'vitest';
import { createMockAgentEventStream, mockAgentEvents } from '../mocks/agentEventStream';

describe('agent event stream contract', () => {
  it('keeps mock events aligned with the session/message/agent-event contract', () => {
    expect(mockAgentEvents.map((event) => event.type)).toEqual([
      'run.started',
      'message.created',
      'reasoning.delta',
      'tool.call.started',
      'tool.call.completed',
      'tool.call.started',
      'approval.requested',
    ]);

    expect(mockAgentEvents[0]).toMatchObject({
      sessionId: 'session-kafka',
      sequence: 1,
      runId: 'run-kafka-001',
    });
  });

  it('streams mock backend events through an adapter boundary', async () => {
    vi.useFakeTimers();
    const adapter = createMockAgentEventStream({ intervalMs: 25 });
    const received: string[] = [];

    const subscription = adapter.subscribe('session-kafka', (event) => {
      received.push(event.type);
    });

    await vi.advanceTimersByTimeAsync(200);
    subscription.unsubscribe();
    vi.useRealTimers();

    expect(received).toEqual(mockAgentEvents.map((event) => event.type));
  });
});
