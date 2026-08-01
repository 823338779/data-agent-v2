import { ArrowRight, ArrowUp, Bot, RefreshCw, Search, UserRound } from 'lucide-react';
import { FormEvent, KeyboardEvent, useEffect, useMemo, useRef, useState } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import {
  AgentBackendClient,
  BackendApiError,
  createFetchAgentBackendClient,
} from './api/backendAgentClient';
import {
  navigateWorkspace,
  parseWorkspaceRoute,
  subscribeWorkspaceRoute,
  WorkspaceRoute,
} from './app/workspaceRoute';
import { AppShell } from './components/shell/AppShell';
import { AgentSession, formatTimestamp } from './domain/agentPrototype';

interface AppProps {
  subject?: string;
  onLogout?: () => Promise<void>;
  onAuthenticationRequired?: () => void;
  client?: AgentBackendClient;
}

function runLabel(status?: string): string {
  switch (status) {
    case 'running':
      return '处理中';
    case 'completed':
    case 'done':
      return '已完成';
    case 'failed':
      return '失败';
    case 'cancelled':
      return '已取消';
    default:
      return '就绪';
  }
}

export default function App({
  subject = 'admin',
  onLogout = async () => undefined,
  onAuthenticationRequired = () => undefined,
  client,
}: AppProps) {
  const backend = useMemo(() => client ?? createFetchAgentBackendClient(), [client]);
  const [route, setRoute] = useState<WorkspaceRoute>(parseWorkspaceRoute);
  const [sessions, setSessions] = useState<AgentSession[]>([]);
  const [historyOpen, setHistoryOpen] = useState(true);
  const [search, setSearch] = useState('');
  const [prompt, setPrompt] = useState('');
  const [loading, setLoading] = useState(true);
  const [sending, setSending] = useState(false);
  const [creating, setCreating] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const messagesRef = useRef<HTMLDivElement>(null);

  const active =
    route.kind === 'session' ? sessions.find((session) => session.id === route.sessionId) : undefined;
  const visibleSessions = sessions.filter((session) =>
    `${session.title} ${session.tag}`.toLowerCase().includes(search.trim().toLowerCase()),
  );

  function handleFailure(failure: unknown, message: string) {
    if (failure instanceof BackendApiError && failure.status === 401) {
      onAuthenticationRequired();
      return;
    }
    setError(message);
  }

  function replaceSession(session: AgentSession) {
    setSessions((current) => {
      const exists = current.some((item) => item.id === session.id);
      return exists
        ? current.map((item) => (item.id === session.id ? session : item))
        : [session, ...current];
    });
  }

  async function loadSessions() {
    setLoading(true);
    setError(null);
    try {
      const loaded = await backend.listSessions();
      setSessions(loaded);
      if (route.kind === 'session' && !loaded.some((item) => item.id === route.sessionId)) {
        navigateWorkspace({ kind: 'home' }, true);
      }
    } catch (failure) {
      handleFailure(failure, '暂时无法读取会话，请检查服务端后重试。');
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => subscribeWorkspaceRoute(setRoute), []);
  useEffect(() => {
    void loadSessions();
    // 首次加载由后端会话列表决定可用页面。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [backend]);

  useEffect(() => {
    if (!active) return;
    const close = backend.subscribeEvents(
      active.id,
      () => void backend.getSession(active.id).then(replaceSession),
      () => setError('实时状态连接已中断；会话内容仍可继续查看。'),
    );
    return close;
  }, [active?.id, backend]);

  useEffect(() => {
    const element = messagesRef.current;
    if (element) element.scrollTop = element.scrollHeight;
  }, [active?.messages.length, active?.run?.stream?.answer]);

  async function createSession() {
    if (creating) return;
    setCreating(true);
    setError(null);
    try {
      const session = await backend.createSession('新会话', '');
      replaceSession(session);
      navigateWorkspace({ kind: 'session', sessionId: session.id });
    } catch (failure) {
      handleFailure(failure, '新会话创建失败，请稍后重试。');
    } finally {
      setCreating(false);
    }
  }

  async function sendPrompt(event?: FormEvent<HTMLFormElement>) {
    event?.preventDefault();
    if (!active || sending || !prompt.trim()) return;
    const content = prompt.trim();
    setSending(true);
    setError(null);
    try {
      const session = await backend.submitMessage(active.id, content);
      replaceSession(session);
      setPrompt('');
    } catch (failure) {
      handleFailure(failure, '消息发送失败，输入内容已保留。');
    } finally {
      setSending(false);
    }
  }

  function submitOnEnter(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (event.key !== 'Enter' || event.shiftKey || event.nativeEvent.isComposing) return;
    event.preventDefault();
    void sendPrompt();
  }

  return (
    <AppShell
      subject={subject}
      historyOpen={historyOpen}
      onNewSession={() => void createSession()}
      onToggleHistory={() => setHistoryOpen((open) => !open)}
      onLogout={onLogout}
    >
      {historyOpen && (
        <aside className="session-sidebar" aria-label="会话列表">
          <div className="session-sidebar-heading">
            <strong>会话</strong>
            <span>{sessions.length}</span>
          </div>
          <label className="session-search">
            <Search size={15} />
            <span className="sr-only">搜索会话</span>
            <input
              value={search}
              onChange={(event) => setSearch(event.target.value)}
              placeholder="搜索会话"
            />
          </label>
          <nav>
            {visibleSessions.map((session) => (
              <button
                className={active?.id === session.id ? 'session-link active' : 'session-link'}
                key={session.id}
                type="button"
                onClick={() => navigateWorkspace({ kind: 'session', sessionId: session.id })}
              >
                <span>{session.title}</span>
                <small>{formatTimestamp(session.updatedAt)}</small>
              </button>
            ))}
          </nav>
        </aside>
      )}

      {route.kind === 'home' && (
        <section className="home-view">
          <div className="home-copy">
            <p className="eyebrow">DATA AGENT</p>
            <h1>从一个清晰的问题开始。</h1>
            <p>创建会话，提交分析目标，并在同一处查看服务端返回的消息与运行状态。</p>
            <button type="button" onClick={() => void createSession()} disabled={creating}>
              {creating ? '正在创建…' : '开始新会话'}
              <ArrowRight size={17} />
            </button>
          </div>
          {!loading && sessions.length > 0 && (
            <div className="recent-sessions">
              <span>最近会话</span>
              {sessions.slice(0, 3).map((session) => (
                <button
                  key={session.id}
                  type="button"
                  onClick={() => navigateWorkspace({ kind: 'session', sessionId: session.id })}
                >
                  <strong>{session.title}</strong>
                  <small>{formatTimestamp(session.updatedAt)}</small>
                </button>
              ))}
            </div>
          )}
        </section>
      )}

      {route.kind === 'session' && active && (
        <section className="conversation-view">
          <header className="conversation-header">
            <div>
              <h1>{active.title}</h1>
              {active.tag && <span>{active.tag}</span>}
            </div>
            <span className={`run-status ${active.run?.status ?? 'idle'}`}>
              {runLabel(active.run?.status)}
            </span>
          </header>

          <div className="message-list" aria-label="消息列表" ref={messagesRef}>
            {active.messages.length === 0 && (
              <div className="empty-conversation">
                <Bot size={24} />
                <h2>描述你要解决的数据问题</h2>
                <p>当前服务端支持保存会话、提交消息并返回运行状态。</p>
              </div>
            )}
            {active.messages.map((message) => (
              <article className={`message ${message.role}`} key={message.id}>
                <div className="message-avatar">
                  {message.role === 'user' ? <UserRound size={16} /> : <Bot size={16} />}
                </div>
                <div>
                  <header>
                    <strong>{message.role === 'user' ? subject : message.author}</strong>
                    <time>{formatTimestamp(message.timestamp)}</time>
                  </header>
                  <ReactMarkdown remarkPlugins={[remarkGfm]}>{message.content}</ReactMarkdown>
                </div>
              </article>
            ))}
            {active.run?.stream?.thinking && (
              <details className="run-detail">
                <summary>查看运行过程</summary>
                <p>{active.run.stream.thinking}</p>
              </details>
            )}
            {active.run?.stream?.answer && (
              <article className="message assistant stream-answer">
                <div className="message-avatar">
                  <Bot size={16} />
                </div>
                <ReactMarkdown remarkPlugins={[remarkGfm]}>{active.run.stream.answer}</ReactMarkdown>
              </article>
            )}
          </div>

          <form className="composer" onSubmit={(event) => void sendPrompt(event)}>
            <label htmlFor="prompt" className="sr-only">
              输入消息
            </label>
            <textarea
              id="prompt"
              rows={2}
              value={prompt}
              onChange={(event) => setPrompt(event.target.value)}
              onKeyDown={submitOnEnter}
              placeholder="输入你的数据分析目标…"
            />
            <button type="submit" aria-label="发送消息" disabled={sending || !prompt.trim()}>
              {sending ? <RefreshCw className="spin" size={17} /> : <ArrowUp size={18} />}
            </button>
            <small>Enter 发送 · Shift + Enter 换行</small>
          </form>
        </section>
      )}

      {!loading && route.kind === 'session' && !active && (
        <section className="not-found">
          <h1>会话不存在</h1>
          <button type="button" onClick={() => navigateWorkspace({ kind: 'home' })}>
            返回首页
          </button>
        </section>
      )}

      {loading && <div className="loading-state">正在读取会话…</div>}
      {error && (
        <div className="error-banner" role="alert">
          <span>{error}</span>
          <button type="button" onClick={() => void loadSessions()}>
            重试
          </button>
        </div>
      )}
    </AppShell>
  );
}
