export type AgentEventType =
  | 'run.started'
  | 'message.created'
  | 'reasoning.delta'
  | 'tool.call.started'
  | 'tool.call.completed'
  | 'tool.call.failed'
  | 'approval.requested'
  | 'approval.resolved'
  | 'run.completed'
  | 'run.failed'
  | 'run.cancelled';

export type AgentMessageRole = 'user' | 'assistant' | 'system';

export type ConfirmationDecision = 'approved' | 'rejected';

export interface AgentEventBase<TType extends AgentEventType, TPayload> {
  eventId: string;
  type: TType;
  sessionId: string;
  runId: string;
  sequence: number;
  createdAt: string;
  payload: TPayload;
}

export type AgentEvent =
  | AgentEventBase<
      'run.started',
      {
        objective: string;
      }
    >
  | AgentEventBase<
      'message.created',
      {
        messageId: string;
        role: AgentMessageRole;
        author: string;
        content: string;
        timestamp: string;
      }
    >
  | AgentEventBase<
      'reasoning.delta',
      {
        stepId: string;
        title: string;
        summary: string;
      }
    >
  | AgentEventBase<
      'tool.call.started',
      {
        toolCallId: string;
        toolName: string;
        title: string;
        summary: string;
        input: unknown;
      }
    >
  | AgentEventBase<
      'tool.call.completed',
      {
        toolCallId: string;
        output: string;
        summary?: string;
      }
    >
  | AgentEventBase<
      'tool.call.failed',
      {
        toolCallId: string;
        error: string;
      }
    >
  | AgentEventBase<
      'approval.requested',
      {
        approvalId: string;
        title: string;
        description: string;
        riskLevel: 'low' | 'medium' | 'high';
      }
    >
  | AgentEventBase<
      'approval.resolved',
      {
        approvalId: string;
        decision: ConfirmationDecision;
        resolvedAt: string;
      }
    >
  | AgentEventBase<
      'run.completed',
      {
        answer: string;
      }
    >
  | AgentEventBase<
      'run.failed',
      {
        summary: string;
      }
    >
  | AgentEventBase<
      'run.cancelled',
      {
        summary: string;
      }
    >;

export interface AgentEventSubscription {
  unsubscribe: () => void;
}

export interface AgentEventStreamAdapter {
  subscribe: (sessionId: string, onEvent: (event: AgentEvent) => void) => AgentEventSubscription;
}
