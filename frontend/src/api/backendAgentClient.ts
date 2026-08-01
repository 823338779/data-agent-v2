import { AgentSession, MessageRole } from '../domain/agentPrototype';

interface BackendMessage {
  id: string;
  role: string;
  author: string;
  content: string;
  createdAt: string;
}

interface BackendRunEvent {
  id: string;
  sequence: number;
  type: string;
  occurredAt: string;
  title: string | null;
  summary: string | null;
}

interface BackendRun {
  id: string;
  status: string;
  progress: number;
  objective: string;
  events: BackendRunEvent[];
  stream: {
    phase: string;
    thinking: string;
    answer: string;
  } | null;
}

interface BackendSession {
  id: string;
  title: string;
  tag: string | null;
  createdAt: string;
  updatedAt: string;
  messages: BackendMessage[];
  run: BackendRun | null;
}

export class BackendApiError extends Error {
  constructor(readonly status: number) {
    super(`Backend request failed with status ${status}`);
  }
}

export interface AgentBackendClient {
  listSessions(): Promise<AgentSession[]>;
  createSession(title: string, tag: string): Promise<AgentSession>;
  getSession(sessionId: string): Promise<AgentSession>;
  submitMessage(sessionId: string, content: string): Promise<AgentSession>;
  subscribeEvents(sessionId: string, onEvent: () => void, onError: () => void): () => void;
}

interface ClientOptions {
  baseUrl?: string;
  fetchImpl?: typeof fetch;
  eventSourceFactory?: (url: string) => EventSource;
}

function toSession(session: BackendSession): AgentSession {
  return {
    id: session.id,
    title: session.title,
    tag: session.tag ?? '',
    createdAt: session.createdAt,
    updatedAt: session.updatedAt,
    messages: session.messages.map((message) => ({
      id: message.id,
      role: (['user', 'assistant', 'system'].includes(message.role)
        ? message.role
        : 'system') as MessageRole,
      author: message.author,
      content: message.content,
      timestamp: message.createdAt,
    })),
    run: session.run
      ? {
          id: session.run.id,
          status: session.run.status,
          progress: session.run.progress,
          objective: session.run.objective,
          events: session.run.events.map((event) => ({ ...event })),
          stream: session.run.stream ? { ...session.run.stream } : null,
        }
      : null,
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

  async function request(path = '', init?: RequestInit): Promise<AgentSession> {
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
    getSession(sessionId) {
      return request(`/${encodeURIComponent(sessionId)}`);
    },
    submitMessage(sessionId, content) {
      return request(`/${encodeURIComponent(sessionId)}/messages`, {
        method: 'POST',
        body: JSON.stringify({ content }),
      });
    },
    subscribeEvents(sessionId, onEvent, onError) {
      if (!eventSourceFactory) return () => undefined;
      const source = eventSourceFactory(`${baseUrl}/${encodeURIComponent(sessionId)}/events`);
      source.addEventListener('run_started', onEvent);
      source.onerror = onError;
      return () => source.close();
    },
  };
}
