import {
  AgentExecution,
  AgentExecutionStatus,
  AgentExecutionStreamCheckpoint,
  ConversationMessage,
  ConversationSession,
  MessageRole,
  ModelThinkingProcessItem,
  ThinkingProcessItem,
  ToolThinkingProcessItem,
} from '../domain/agentPrototype';
import { createIdempotencyKey } from './idempotencyKey';

const IDEMPOTENCY_KEY_HEADER = 'Idempotency-Key';
const JSON_CONTENT_TYPE = 'application/json';
const SESSION_SNAPSHOT_EVENT = 'session_snapshot';
const EXECUTION_UPDATE_EVENT = 'execution_update';
const MESSAGE_UPDATE_EVENT = 'message_update';
const STREAM_SNAPSHOT_EVENT = 'stream_snapshot';
const ASSISTANT_DELTA_EVENT = 'assistant_delta';
const THINKING_PROCESS_TYPE = 'THINKING';
const TOOL_PROCESS_TYPE = 'TOOL';
const SESSION_MISMATCH_ERROR = 'SSE_PROTOCOL_SESSION_MISMATCH';
const SESSION_EVENT_TYPES = [
  SESSION_SNAPSHOT_EVENT,
  EXECUTION_UPDATE_EVENT,
  MESSAGE_UPDATE_EVENT,
  STREAM_SNAPSHOT_EVENT,
  ASSISTANT_DELTA_EVENT,
] as const;
const EXECUTION_STATUSES: AgentExecutionStatus[] = [
  'idle',
  'running',
  'done',
  'cancelled',
  'failed',
];

interface BackendMessage {
  conversationMessageId: string;
  executionId?: string | null;
  role: string;
  author: string;
  content: string;
  version: number;
  thinkingProcess: BackendThinkingProcessItem[];
  createdAt: string;
  execution: BackendAgentExecution | null;
}

type BackendThinkingProcessItem =
  | (Omit<ModelThinkingProcessItem, 'type'> & { type?: 'THINKING' })
  | (Omit<ToolThinkingProcessItem, 'type'> & { type?: 'TOOL' });

interface BackendAgentExecutionEvent {
  executionEventId: string;
  executionEventSequence: number;
  type: string;
  occurredAt: string;
  executionId: string;
  status: string;
}

interface BackendAgentExecution {
  executionId: string;
  status: string;
  progress: number;
  objective: string;
  events: BackendAgentExecutionEvent[];
  streamCheckpoint: BackendStreamCheckpoint | null;
}

interface BackendAgentExecutionState {
  executionId: string;
  status: string;
  progress: number;
  objective: string;
  streamCheckpoint: BackendStreamCheckpoint | null;
}

interface BackendExecutionUpdate {
  conversationSessionId: string;
  execution: BackendAgentExecutionState;
  event: BackendAgentExecutionEvent;
  messages: BackendMessage[];
}

interface BackendSessionSnapshot {
  conversationSessionId: string;
  session: BackendSession;
  occurredAt: string;
}

interface BackendMessageUpdate {
  conversationSessionId: string;
  message: BackendMessage;
  occurredAt: string;
}

interface BackendStreamCheckpoint {
  modelInvocationSequence?: number;
  phase: string;
  outputDeltaSequence?: number;
  answer: string;
}

interface BackendSessionSummary {
  conversationSessionId: string;
  title: string;
  tag: string | null;
  createdAt: string;
  updatedAt: string;
  version: number;
  status?: string;
}

interface BackendSession extends BackendSessionSummary {
  messages: BackendMessage[];
  execution: BackendAgentExecution | null;
}

export type ExecutionStreamEvent =
  | ({
      type: 'stream_snapshot';
      conversationSessionId: string;
      executionId: string;
      phase: string;
      answer: string;
      occurredAt: string;
    } & StreamCursor)
  | ({
      type: 'assistant_delta';
      conversationSessionId: string;
      executionId: string;
      delta: string;
      occurredAt: string;
    } & StreamCursor);

export type ConversationStreamEvent =
  | {
      type: 'session_snapshot';
      conversationSessionId: string;
      session: ConversationSession;
      occurredAt: string;
    }
  | {
      type: 'execution_update';
      conversationSessionId: string;
      execution: Omit<AgentExecution, 'events'>;
      event: AgentExecution['events'][number];
      messages: ConversationMessage[];
    }
  | {
      type: 'message_update';
      conversationSessionId: string;
      message: ConversationMessage;
      occurredAt: string;
    }
  | ExecutionStreamEvent;

interface StreamCursor {
  modelInvocationSequence: number;
  outputDeltaSequence: number;
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
  subscribeSessionEvents(
    conversationSessionId: string,
    onEvent: (event: ConversationStreamEvent) => void,
    onError: (error?: Error) => void,
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
      modelInvocationSequence: event.modelInvocationSequence,
      phase: event.phase,
      outputDeltaSequence: event.outputDeltaSequence,
      answer: event.answer,
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
    answer: current.answer + event.delta,
  };
}

/** 将详情 SSE 的快照或增量幂等合并到单个会话状态。 */
export function applyConversationStreamEvent(
  current: ConversationSession,
  event: ConversationStreamEvent,
): ConversationSession {
  // 路由会话标识是详情流的隔离边界，异常载荷不能污染当前页面。
  if (event.conversationSessionId !== current.conversationSessionId) return current;
  if (event.type === SESSION_SNAPSHOT_EVENT) return event.session;
  if (event.type === MESSAGE_UPDATE_EVENT) {
    const index = current.messages.findIndex(
      (message) => message.conversationMessageId === event.message.conversationMessageId,
    );
    if (index >= 0 && current.messages[index].version >= event.message.version) return current;
    const messages = [...current.messages];
    if (index >= 0) messages[index] = event.message;
    else messages.push(event.message);
    return { ...current, messages };
  }
  if (event.type === STREAM_SNAPSHOT_EVENT || event.type === ASSISTANT_DELTA_EVENT) {
    if (!current.execution) return current;
    return {
      ...current,
      execution: {
        ...current.execution,
        streamCheckpoint: applyExecutionStreamEvent(current.execution.streamCheckpoint, event),
      },
    };
  }

  const previousExecution =
    current.execution?.executionId === event.execution.executionId ? current.execution : null;
  const events = previousExecution?.events ?? [];
  const nextEvents = events.some(
    (item) => item.executionEventSequence === event.event.executionEventSequence,
  )
    ? events
    : [...events, event.event].sort(
        (left, right) => left.executionEventSequence - right.executionEventSequence,
      );
  const execution: AgentExecution = {
    ...event.execution,
    events: nextEvents,
    streamCheckpoint:
      event.execution.streamCheckpoint ?? previousExecution?.streamCheckpoint ?? null,
  };

  // 先更新已经显示的同 execution 回答，再按消息标识追加本次新增消息。
  const messages = current.messages.map((message) =>
    message.role === 'assistant' &&
    (message.executionId === execution.executionId ||
      message.execution?.executionId === execution.executionId)
      ? { ...message, execution }
      : message,
  );
  event.messages.forEach((message) => {
    const nextMessage =
      message.executionId === execution.executionId && message.role === 'assistant'
        ? { ...message, execution }
        : message;
    const index = messages.findIndex(
      (item) => item.conversationMessageId === nextMessage.conversationMessageId,
    );
    if (index >= 0) messages[index] = nextMessage;
    else messages.push(nextMessage);
  });
  return { ...current, messages, execution };
}

function compareTurn(left: StreamCursor, right: StreamCursor): number {
  return left.modelInvocationSequence - right.modelInvocationSequence;
}

function toStatus(status: string): AgentExecutionStatus {
  return EXECUTION_STATUSES.includes(status as AgentExecutionStatus)
    ? (status as AgentExecutionStatus)
    : 'idle';
}

function toCheckpoint(checkpoint: BackendStreamCheckpoint): AgentExecutionStreamCheckpoint {
  return {
    modelInvocationSequence: checkpoint.modelInvocationSequence ?? 0,
    phase: checkpoint.phase,
    outputDeltaSequence: checkpoint.outputDeltaSequence ?? 0,
    answer: checkpoint.answer,
  };
}

function toExecution(execution: BackendAgentExecution): AgentExecution {
  return {
    executionId: execution.executionId,
    status: toStatus(execution.status),
    progress: execution.progress,
    objective: execution.objective,
    events: execution.events.map(toExecutionEvent),
    streamCheckpoint: execution.streamCheckpoint ? toCheckpoint(execution.streamCheckpoint) : null,
  };
}

function toExecutionEvent(event: BackendAgentExecutionEvent): AgentExecution['events'][number] {
  return {
    ...event,
    status: toStatus(event.status) as Exclude<AgentExecutionStatus, 'idle'>,
  };
}

function toExecutionState(execution: BackendAgentExecutionState): Omit<AgentExecution, 'events'> {
  return {
    executionId: execution.executionId,
    status: toStatus(execution.status),
    progress: execution.progress,
    objective: execution.objective,
    streamCheckpoint: execution.streamCheckpoint ? toCheckpoint(execution.streamCheckpoint) : null,
  };
}

function toMessage(message: BackendMessage): ConversationMessage {
  return {
    conversationMessageId: message.conversationMessageId,
    executionId: message.executionId ?? message.execution?.executionId ?? null,
    role: (['user', 'assistant', 'system'].includes(message.role)
      ? message.role
      : 'system') as MessageRole,
    author: message.author,
    content: message.content,
    timestamp: message.createdAt,
    version: message.version,
    thinkingProcess: message.thinkingProcess.map(toThinkingProcessItem),
    execution: message.execution ? toExecution(message.execution) : null,
  };
}

/** 兼容缺少 type 的旧快照，并在进入页面状态前恢复过程节点的判别字段。 */
function toThinkingProcessItem(item: BackendThinkingProcessItem): ThinkingProcessItem {
  if (item.type) return { ...item } as ThinkingProcessItem;
  if ('content' in item) return { ...item, type: THINKING_PROCESS_TYPE };
  return { ...item, type: TOOL_PROCESS_TYPE };
}

function toSession(session: BackendSession | BackendSessionSummary): ConversationSession {
  const detail = session as Partial<BackendSession>;
  return {
    conversationSessionId: session.conversationSessionId,
    title: session.title,
    tag: session.tag ?? '',
    createdAt: session.createdAt,
    updatedAt: session.updatedAt,
    version: session.version,
    latestExecutionStatus: session.status ? toStatus(session.status) : undefined,
    messages: detail.messages?.map(toMessage) ?? [],
    execution: detail.execution ? toExecution(detail.execution) : null,
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
  // 只有拥有幂等键的操作才自动重试网络故障。
  async function fetchResponse(path: string, init?: RequestInit, retryNetwork = false) {
    const requestInit: RequestInit = {
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

  async function executionRequest(path: string, init?: RequestInit) {
    return toExecution(await json<BackendAgentExecution>(await fetchResponse(path, init)));
  }

  function openSource(path: string): EventSource | undefined {
    return eventSourceFactory?.(`${baseUrl}${path}`);
  }

  function listenJson<T>(source: EventSource, type: string, listener: (value: T) => void) {
    source.addEventListener(type, ((raw: MessageEvent<string>) => {
      listener(JSON.parse(raw.data) as T);
    }) as EventListener);
  }

  return {
    async listSessions() {
      return (await json<BackendSessionSummary[]>(await fetchResponse(''))).map(toSession);
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
    subscribeSessionEvents(conversationSessionId, onEvent, onError) {
      const source = openSource(`/${encodeURIComponent(conversationSessionId)}/events`);
      if (!source) return () => undefined;
      SESSION_EVENT_TYPES.forEach((eventType) =>
        source.addEventListener(eventType, ((raw: MessageEvent<string>) => {
          if (eventType === SESSION_SNAPSHOT_EVENT) {
            const snapshot = JSON.parse(raw.data) as BackendSessionSnapshot;
            if (snapshot.conversationSessionId !== conversationSessionId) {
              onError(new Error(SESSION_MISMATCH_ERROR));
              return;
            }
            onEvent({
              type: SESSION_SNAPSHOT_EVENT,
              conversationSessionId: snapshot.conversationSessionId,
              session: toSession(snapshot.session),
              occurredAt: snapshot.occurredAt,
            });
            return;
          }
          if (eventType === EXECUTION_UPDATE_EVENT) {
            const update = JSON.parse(raw.data) as BackendExecutionUpdate;
            if (update.conversationSessionId !== conversationSessionId) {
              onError(new Error(SESSION_MISMATCH_ERROR));
              return;
            }
            onEvent({
              type: EXECUTION_UPDATE_EVENT,
              conversationSessionId: update.conversationSessionId,
              execution: toExecutionState(update.execution),
              event: toExecutionEvent(update.event),
              messages: update.messages.map(toMessage),
            });
            return;
          }
          if (eventType === MESSAGE_UPDATE_EVENT) {
            const update = JSON.parse(raw.data) as BackendMessageUpdate;
            if (update.conversationSessionId !== conversationSessionId) {
              onError(new Error(SESSION_MISMATCH_ERROR));
              return;
            }
            onEvent({
              type: MESSAGE_UPDATE_EVENT,
              conversationSessionId: update.conversationSessionId,
              message: toMessage(update.message),
              occurredAt: update.occurredAt,
            });
            return;
          }
          const streamEvent = JSON.parse(raw.data) as ExecutionStreamEvent;
          if (streamEvent.conversationSessionId !== conversationSessionId) {
            onError(new Error(SESSION_MISMATCH_ERROR));
            return;
          }
          onEvent(streamEvent);
        }) as EventListener),
      );
      source.onerror = () => onError();
      return () => source.close();
    },
  };
}
