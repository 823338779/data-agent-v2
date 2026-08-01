import { AgentExecution, ConversationSession, MessageRole } from '../domain/agentPrototype';

const IDEMPOTENCY_KEY_HEADER = 'Idempotency-Key';
const SESSION_EVENT_TYPES = ['execution_queued', 'execution_started', 'execution_done'] as const;

interface BackendMessage {
  conversationMessageId: string;
  role: string;
  author: string;
  content: string;
  createdAt: string;
  /** 后端附加到 assistant 消息的历史 Agent 执行投影。 */
  execution: BackendAgentExecution | null;
}

/** 后端返回的 Agent 执行时间线事件。 */
interface BackendAgentExecutionEvent {
  executionEventId: string;
  /** 事件在会话级执行时间线中的严格递增业务序号。 */
  executionEventSequence: number;
  type: string;
  occurredAt: string;
  title: string | null;
  summary: string | null;
}

/** 后端返回的单次 Agent 执行及其最新输出检查点。 */
interface BackendAgentExecution {
  executionId: string;
  status: string;
  progress: number;
  objective: string;
  events: BackendAgentExecutionEvent[];
  streamCheckpoint: {
    phase: string;
    thinking: string;
    answer: string;
  } | null;
}

interface BackendSession {
  conversationSessionId: string;
  title: string;
  tag: string | null;
  createdAt: string;
  updatedAt: string;
  messages: BackendMessage[];
  execution: BackendAgentExecution | null;
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
  submitMessage(conversationSessionId: string, content: string): Promise<ConversationSession>;
  subscribeEvents(
    conversationSessionId: string,
    onEvent: () => void,
    onError: () => void,
  ): () => void;
}

interface ClientOptions {
  baseUrl?: string;
  fetchImpl?: typeof fetch;
  eventSourceFactory?: (url: string) => EventSource;
}

function toSession(session: BackendSession): ConversationSession {
  return {
    conversationSessionId: session.conversationSessionId,
    title: session.title,
    tag: session.tag ?? '',
    createdAt: session.createdAt,
    updatedAt: session.updatedAt,
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

/** 将后端执行投影转换为前端领域对象，并复制可变的集合和检查点。 */
function toExecution(execution: BackendAgentExecution): AgentExecution {
  return {
    executionId: execution.executionId,
    status: execution.status,
    progress: execution.progress,
    objective: execution.objective,
    events: execution.events.map((event) => ({ ...event })),
    streamCheckpoint: execution.streamCheckpoint ? { ...execution.streamCheckpoint } : null,
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
    (typeof EventSource === 'undefined' ? undefined : (url: string) => new EventSource(url));

  async function request(path = '', init?: RequestInit): Promise<ConversationSession> {
    const response = await fetchImpl(`${baseUrl}${path}`, {
      credentials: 'include',
      ...init,
      headers: init?.body ? { 'Content-Type': 'application/json', ...init.headers } : init?.headers,
    });
    return toSession(await json<BackendSession>(response));
  }

  return {
    async listSessions() {
      const response = await fetchImpl(baseUrl, { credentials: 'include' });
      return (await json<BackendSession[]>(response)).map(toSession);
    },
    createSession(title, tag) {
      return request('', { method: 'POST', body: JSON.stringify({ title, tag }) });
    },
    getSession(conversationSessionId) {
      return request(`/${encodeURIComponent(conversationSessionId)}`);
    },
    submitMessage(conversationSessionId, content) {
      // 每次用户提交生成独立幂等键，避免重放请求创建第二次 Agent 执行。
      const idempotencyKey = crypto.randomUUID();
      return request(`/${encodeURIComponent(conversationSessionId)}/messages`, {
        method: 'POST',
        headers: { [IDEMPOTENCY_KEY_HEADER]: idempotencyKey },
        body: JSON.stringify({ content }),
      });
    },
    subscribeEvents(conversationSessionId, onEvent, onError) {
      if (!eventSourceFactory) return () => undefined;
      const source = eventSourceFactory(
        `${baseUrl}/${encodeURIComponent(conversationSessionId)}/events`,
      );
      // Every persisted lifecycle transition can change the visible session snapshot.
      SESSION_EVENT_TYPES.forEach((eventType) => source.addEventListener(eventType, onEvent));
      source.onerror = onError;
      return () => source.close();
    },
  };
}
