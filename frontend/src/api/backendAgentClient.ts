import {
  AgentExecution,
  AgentExecutionStatus,
  AgentExecutionStreamCheckpoint,
  ConversationActivitySnapshot,
  ConversationSession,
  MessageRole,
} from '../domain/agentPrototype';
import { createIdempotencyKey } from './idempotencyKey';

const IDEMPOTENCY_KEY_HEADER = 'Idempotency-Key';
const JSON_CONTENT_TYPE = 'application/json';
const ACTIVITY_EVENT = 'activity_snapshot';
const STREAM_EVENT_TYPES = ['stream_snapshot', 'reasoning_delta', 'assistant_delta'] as const;
const SESSION_EVENT_TYPES = ['execution_queued', 'execution_started', 'execution_done'] as const;
const EXECUTION_STATUSES: AgentExecutionStatus[] = [
  'idle',
  'queued',
  'running',
  'waiting_for_user',
  'waiting_confirmation',
  'done',
  'cancelled',
  'failed',
];

interface BackendMessage {
  conversationMessageId: string;
  role: string;
  author: string;
  content: string;
  createdAt: string;
  execution: BackendAgentExecution | null;
}

interface BackendAgentExecutionEvent {
  executionEventId: string;
  executionEventSequence: number;
  type: string;
  occurredAt: string;
  title: string | null;
  summary: string | null;
}

interface BackendAgentExecution {
  executionId: string;
  status: string;
  progress: number;
  objective: string;
  cancellationRequestedAt?: string | null;
  events: BackendAgentExecutionEvent[];
  streamCheckpoint: BackendStreamCheckpoint | null;
}

interface BackendStreamCheckpoint {
  executionAttemptNumber?: number;
  modelInvocationSequence?: number;
  phase: string;
  outputDeltaSequence?: number;
  reasoning?: string;
  /** 兼容计划三之前后端返回的字段名。 */
  thinking?: string;
  answer: string;
  checkpointedAt?: string | null;
}

interface BackendSession {
  conversationSessionId: string;
  title: string;
  tag: string | null;
  createdAt: string;
  updatedAt: string;
  version: number;
  unread?: boolean;
  lastNotifiableEventSequence?: number;
  sortOrder?: number;
  orderingVersion?: number;
  messages: BackendMessage[];
  execution: BackendAgentExecution | null;
}

export type ExecutionStreamEvent =
  | ({
      type: 'stream_snapshot';
      phase: string;
      reasoning: string;
      answer: string;
      checkpointedAt: string | null;
    } & StreamCursor)
  | ({ type: 'reasoning_delta' | 'assistant_delta'; delta: string } & StreamCursor);

interface StreamCursor {
  executionAttemptNumber: number;
  modelInvocationSequence: number;
  outputDeltaSequence: number;
}

export interface ReorderSessionsResult {
  orderingVersion: number;
  sessions: ConversationSession[];
}

export class BackendApiError extends Error {
  constructor(readonly status: number) {
    super(`Backend request failed with status ${status}`);
  }
}

export interface AgentBackendClient {
  listSessions(): Promise<ConversationSession[]>;
  createSession(title: string, tag: string): Promise<ConversationSession>;
  getSession(conversationSessionId: string): Promise<ConversationSession>;
  renameSession(
    conversationSessionId: string,
    title: string,
    expectedVersion: number,
  ): Promise<ConversationSession>;
  submitMessage(conversationSessionId: string, content: string): Promise<ConversationSession>;
  cancelExecution(conversationSessionId: string, executionId: string): Promise<AgentExecution>;
  retryExecution(conversationSessionId: string, executionId: string): Promise<AgentExecution>;
  markRead(conversationSessionId: string, upToSequence: number): Promise<ConversationSession>;
  reorderSessions(
    conversationSessionIds: string[],
    expectedOrderingVersion: number,
  ): Promise<ReorderSessionsResult>;
  subscribeActivity(
    onSnapshot: (snapshot: ConversationActivitySnapshot) => void,
    onError: () => void,
  ): () => void;
  subscribeExecutionEvents(
    conversationSessionId: string,
    onStreamEvent: (event: ExecutionStreamEvent) => void,
    onLifecycleEvent: () => void,
    onError: () => void,
  ): () => void;
  subscribeEvents(
    conversationSessionId: string,
    onEvent: () => void,
    onError: () => void,
  ): () => void;
}

interface ClientOptions {
  baseUrl?: string;
  fetchImpl?: typeof fetch;
  eventSourceFactory?: (url: string, eventSourceInit?: EventSourceInit) => EventSource;
}

/** 复合序号 reducer：重复和缺口 delta 均等待服务端 snapshot 校正。 */
export function applyExecutionStreamEvent(
  current: AgentExecutionStreamCheckpoint | null,
  event: ExecutionStreamEvent,
): AgentExecutionStreamCheckpoint | null {
  if (event.type === 'stream_snapshot') {
    if (current && compareTurn(event, current) < 0) return current;
    if (
      current &&
      compareTurn(event, current) === 0 &&
      event.outputDeltaSequence < current.outputDeltaSequence
    ) {
      return current;
    }
    return {
      executionAttemptNumber: event.executionAttemptNumber,
      modelInvocationSequence: event.modelInvocationSequence,
      phase: event.phase,
      outputDeltaSequence: event.outputDeltaSequence,
      reasoning: event.reasoning,
      answer: event.answer,
      checkpointedAt: event.checkpointedAt,
    };
  }
  if (
    !current ||
    compareTurn(event, current) !== 0 ||
    event.outputDeltaSequence !== current.outputDeltaSequence + 1
  ) {
    return current;
  }
  return {
    ...current,
    outputDeltaSequence: event.outputDeltaSequence,
    reasoning:
      event.type === 'reasoning_delta' ? current.reasoning + event.delta : current.reasoning,
    answer: event.type === 'assistant_delta' ? current.answer + event.delta : current.answer,
  };
}

function compareTurn(left: StreamCursor, right: StreamCursor): number {
  return (
    left.executionAttemptNumber - right.executionAttemptNumber ||
    left.modelInvocationSequence - right.modelInvocationSequence
  );
}

function toStatus(status: string): AgentExecutionStatus {
  return EXECUTION_STATUSES.includes(status as AgentExecutionStatus)
    ? (status as AgentExecutionStatus)
    : 'idle';
}

function toCheckpoint(checkpoint: BackendStreamCheckpoint): AgentExecutionStreamCheckpoint {
  return {
    executionAttemptNumber: checkpoint.executionAttemptNumber ?? 0,
    modelInvocationSequence: checkpoint.modelInvocationSequence ?? 0,
    phase: checkpoint.phase,
    outputDeltaSequence: checkpoint.outputDeltaSequence ?? 0,
    reasoning: checkpoint.reasoning ?? checkpoint.thinking ?? '',
    answer: checkpoint.answer,
    checkpointedAt: checkpoint.checkpointedAt ?? null,
  };
}

function toExecution(execution: BackendAgentExecution): AgentExecution {
  return {
    executionId: execution.executionId,
    status: toStatus(execution.status),
    progress: execution.progress,
    objective: execution.objective,
    cancellationRequestedAt: execution.cancellationRequestedAt ?? null,
    events: execution.events.map((event) => ({ ...event })),
    streamCheckpoint: execution.streamCheckpoint ? toCheckpoint(execution.streamCheckpoint) : null,
  };
}

function toSession(session: BackendSession): ConversationSession {
  return {
    conversationSessionId: session.conversationSessionId,
    title: session.title,
    tag: session.tag ?? '',
    createdAt: session.createdAt,
    updatedAt: session.updatedAt,
    version: session.version,
    unread: session.unread ?? false,
    lastNotifiableEventSequence: session.lastNotifiableEventSequence ?? 0,
    sortOrder: session.sortOrder ?? 0,
    orderingVersion: session.orderingVersion ?? 0,
    messages: session.messages.map((message) => ({
      conversationMessageId: message.conversationMessageId,
      role: (['user', 'assistant', 'system'].includes(message.role)
        ? message.role
        : 'system') as MessageRole,
      author: message.author,
      content: message.content,
      timestamp: message.createdAt,
      execution: message.execution ? toExecution(message.execution) : null,
    })),
    execution: session.execution ? toExecution(session.execution) : null,
  };
}

async function json<T>(response: Response): Promise<T> {
  if (!response.ok) throw new BackendApiError(response.status);
  return response.json() as Promise<T>;
}

export function createFetchAgentBackendClient(options: ClientOptions = {}): AgentBackendClient {
  const baseUrl = options.baseUrl ?? '/api/v1/agent-sessions';
  const fetchImpl = options.fetchImpl ?? fetch;
  const eventSourceFactory =
    options.eventSourceFactory ??
    (typeof EventSource === 'undefined'
      ? undefined
      : (url: string, init?: EventSourceInit) => new EventSource(url, init));

  // 所有 HTTP 调用统一携带 cookie；只有拥有幂等键的操作才自动重试网络故障。
  async function fetchResponse(path: string, init?: RequestInit, retryNetwork = false) {
    const requestInit: RequestInit = {
      credentials: 'include',
      ...init,
      headers: init?.body ? { 'Content-Type': JSON_CONTENT_TYPE, ...init.headers } : init?.headers,
    };
    try {
      return await fetchImpl(`${baseUrl}${path}`, requestInit);
    } catch (error) {
      if (!retryNetwork) throw error;
      return fetchImpl(`${baseUrl}${path}`, requestInit);
    }
  }

  async function sessionRequest(path = '', init?: RequestInit, retryNetwork = false) {
    return toSession(await json<BackendSession>(await fetchResponse(path, init, retryNetwork)));
  }

  async function executionRequest(path: string, init?: RequestInit, retryNetwork = false) {
    return toExecution(
      await json<BackendAgentExecution>(await fetchResponse(path, init, retryNetwork)),
    );
  }

  function openSource(path: string): EventSource | undefined {
    return eventSourceFactory?.(`${baseUrl}${path}`, { withCredentials: true });
  }

  function listenJson<T>(source: EventSource, type: string, listener: (value: T) => void) {
    source.addEventListener(type, ((raw: MessageEvent<string>) => {
      listener(JSON.parse(raw.data) as T);
    }) as EventListener);
  }

  return {
    async listSessions() {
      return (await json<BackendSession[]>(await fetchResponse(''))).map(toSession);
    },
    createSession(title, tag) {
      return sessionRequest('', { method: 'POST', body: JSON.stringify({ title, tag }) });
    },
    getSession(conversationSessionId) {
      return sessionRequest(`/${encodeURIComponent(conversationSessionId)}`);
    },
    renameSession(conversationSessionId, title, expectedVersion) {
      return sessionRequest(`/${encodeURIComponent(conversationSessionId)}`, {
        method: 'PATCH',
        body: JSON.stringify({ title, expectedVersion }),
      });
    },
    submitMessage(conversationSessionId, content) {
      const idempotencyKey = createIdempotencyKey();
      return sessionRequest(
        `/${encodeURIComponent(conversationSessionId)}/messages`,
        {
          method: 'POST',
          headers: { [IDEMPOTENCY_KEY_HEADER]: idempotencyKey },
          body: JSON.stringify({ content }),
        },
        true,
      );
    },
    cancelExecution(conversationSessionId, executionId) {
      return executionRequest(
        `/${encodeURIComponent(conversationSessionId)}/executions/${encodeURIComponent(executionId)}:cancel`,
        { method: 'POST' },
      );
    },
    retryExecution(conversationSessionId, executionId) {
      const idempotencyKey = createIdempotencyKey();
      return executionRequest(
        `/${encodeURIComponent(conversationSessionId)}/executions/${encodeURIComponent(executionId)}:retry`,
        { method: 'POST', headers: { [IDEMPOTENCY_KEY_HEADER]: idempotencyKey } },
        true,
      );
    },
    markRead(conversationSessionId, upToSequence) {
      return sessionRequest(`/${encodeURIComponent(conversationSessionId)}:mark-read`, {
        method: 'POST',
        body: JSON.stringify({ upToSequence }),
      });
    },
    async reorderSessions(conversationSessionIds, expectedOrderingVersion) {
      const response = await json<{ orderingVersion: number; sessions: BackendSession[] }>(
        await fetchResponse(':reorder', {
          method: 'POST',
          body: JSON.stringify({ conversationSessionIds, expectedOrderingVersion }),
        }),
      );
      return {
        orderingVersion: response.orderingVersion,
        sessions: response.sessions.map(toSession),
      };
    },
    subscribeActivity(onSnapshot, onError) {
      const source = openSource('/activity-events');
      if (!source) return () => undefined;
      listenJson(source, ACTIVITY_EVENT, onSnapshot);
      source.onerror = onError;
      return () => source.close();
    },
    subscribeExecutionEvents(conversationSessionId, onStreamEvent, onLifecycleEvent, onError) {
      const source = openSource(`/${encodeURIComponent(conversationSessionId)}/events`);
      if (!source) return () => undefined;
      STREAM_EVENT_TYPES.forEach((eventType) => listenJson(source, eventType, onStreamEvent));
      SESSION_EVENT_TYPES.forEach((eventType) =>
        source.addEventListener(eventType, onLifecycleEvent),
      );
      source.onerror = onError;
      return () => source.close();
    },
    subscribeEvents(conversationSessionId, onEvent, onError) {
      const source = openSource(`/${encodeURIComponent(conversationSessionId)}/events`);
      if (!source) return () => undefined;
      SESSION_EVENT_TYPES.forEach((eventType) => source.addEventListener(eventType, onEvent));
      source.onerror = onError;
      return () => source.close();
    },
  };
}
