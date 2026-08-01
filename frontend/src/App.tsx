import {
  ArrowRight,
  ArrowUp,
  Bot,
  MessageSquarePlus,
  PanelLeftClose,
  RefreshCw,
  Search,
  UserRound,
} from 'lucide-react';
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
import { ConversationSession, formatTimestamp } from './domain/agentPrototype';

interface AppProps {
  subject?: string;
  onLogout?: () => Promise<void>;
  onAuthenticationRequired?: () => void;
  client?: AgentBackendClient;
}

const narrowScreenQuery = '(max-width: 820px)';

function isNarrowScreen(): boolean {
  return window.matchMedia?.(narrowScreenQuery).matches ?? false;
}

function executionStatusLabel(status?: string): string {
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
  const [sessions, setSessions] = useState<ConversationSession[]>([]);
  const [historyOpen, setHistoryOpen] = useState(() => !isNarrowScreen());
  const [search, setSearch] = useState('');
  const [prompt, setPrompt] = useState('');
  const [loading, setLoading] = useState(true);
  const [sending, setSending] = useState(false);
  const [creating, setCreating] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const messagesRef = useRef<HTMLDivElement>(null);

  const active =
    route.kind === 'session'
      ? sessions.find((session) => session.conversationSessionId === route.conversationSessionId)
      : undefined;
  const visibleSessions = sessions.filter((session) =>
    `${session.title} ${session.tag}`.toLowerCase().includes(search.trim().toLowerCase()),
  );
  const assistantMessages =
    active?.messages.filter((message) => message.role === 'assistant') ?? [];
  const hasMessageExecutionMetadata = assistantMessages.some((message) => message.execution);
  const activeExecutionAnswerPersisted = Boolean(
    active?.execution?.streamCheckpoint?.answer &&
    (assistantMessages.some(
      (message) => message.execution?.executionId === active.execution?.executionId,
    ) ||
      (!hasMessageExecutionMetadata &&
        assistantMessages.some(
          (message) => message.content === active.execution?.streamCheckpoint?.answer,
        ))),
  );

  function handleFailure(failure: unknown, message: string) {
    if (failure instanceof BackendApiError && failure.status === 401) {
      onAuthenticationRequired();
      return;
    }
    setError(message);
  }

  function replaceSession(session: ConversationSession) {
    setSessions((current) => {
      const exists = current.some(
        (item) => item.conversationSessionId === session.conversationSessionId,
      );
      return exists
        ? current.map((item) =>
            item.conversationSessionId === session.conversationSessionId ? session : item,
          )
        : [session, ...current];
    });
  }

  async function loadSessions() {
    setLoading(true);
    setError(null);
    try {
      const loaded = await backend.listSessions();
      setSessions(loaded);
      if (
        route.kind === 'session' &&
        !loaded.some((item) => item.conversationSessionId === route.conversationSessionId)
      ) {
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
    if (!window.matchMedia) return;
    const media = window.matchMedia(narrowScreenQuery);
    const collapseOnNarrowScreen = (event: MediaQueryListEvent) => {
      if (event.matches) setHistoryOpen(false);
    };
    media.addEventListener('change', collapseOnNarrowScreen);
    return () => media.removeEventListener('change', collapseOnNarrowScreen);
  }, []);
  useEffect(() => {
    void loadSessions();
    // 首次加载由后端会话列表决定可用页面。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [backend]);

  useEffect(() => {
    if (!active) return;
    const close = backend.subscribeEvents(
      active.conversationSessionId,
      () =>
        void backend
          .getSession(active.conversationSessionId)
          .then(replaceSession)
          .catch((failure) => handleFailure(failure, '无法刷新最新运行状态。')),
      () => setError('实时状态连接已中断；会话内容仍可继续查看。'),
    );
    return close;
  }, [active?.conversationSessionId, backend]);

  useEffect(() => {
    const element = messagesRef.current;
    if (element) element.scrollTop = element.scrollHeight;
  }, [active?.messages.length, active?.execution?.streamCheckpoint?.answer]);

  async function createSession() {
    if (creating) return;
    setCreating(true);
    setError(null);
    try {
      const session = await backend.createSession('新会话', '');
      replaceSession(session);
      navigateWorkspace({
        kind: 'session',
        conversationSessionId: session.conversationSessionId,
      });
      if (isNarrowScreen()) setHistoryOpen(false);
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
      const session = await backend.submitMessage(active.conversationSessionId, content);
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

  function openSession(conversationSessionId: string) {
    navigateWorkspace({ kind: 'session', conversationSessionId });
    if (isNarrowScreen()) setHistoryOpen(false);
  }

  return (
    <AppShell
      subject={subject}
      historyOpen={historyOpen}
      onToggleHistory={() => setHistoryOpen((open) => !open)}
      onLogout={onLogout}
    >
      {historyOpen && (
        <aside className="session-sidebar" aria-label="会话列表">
          <div className="session-sidebar-heading">
            <span className="session-sidebar-title">
              <strong>会话</strong>
              <small>{sessions.length}</small>
            </span>
            <button
              className="icon-button"
              type="button"
              aria-label="收起会话列表"
              onClick={() => setHistoryOpen(false)}
            >
              <PanelLeftClose size={17} />
            </button>
          </div>
          <button
            className="sidebar-new-session"
            type="button"
            onClick={() => void createSession()}
            disabled={creating}
          >
            <MessageSquarePlus size={17} />
            {creating ? '正在创建…' : '新会话'}
          </button>
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
                className={
                  active?.conversationSessionId === session.conversationSessionId
                    ? 'session-link active'
                    : 'session-link'
                }
                key={session.conversationSessionId}
                type="button"
                onClick={() => openSession(session.conversationSessionId)}
              >
                <span>{session.title}</span>
                <small>{formatTimestamp(session.updatedAt)}</small>
              </button>
            ))}
          </nav>
        </aside>
      )}
      {historyOpen && (
        <button
          className="sidebar-backdrop"
          type="button"
          aria-label="关闭会话列表"
          onClick={() => setHistoryOpen(false)}
        />
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
                  key={session.conversationSessionId}
                  type="button"
                  onClick={() => openSession(session.conversationSessionId)}
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
            <span className={`execution-status ${active.execution?.status ?? 'idle'}`}>
              {executionStatusLabel(active.execution?.status)}
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
              <article className={`message ${message.role}`} key={message.conversationMessageId}>
                <div className="message-avatar">
                  {message.role === 'user' ? <UserRound size={16} /> : <Bot size={16} />}
                </div>
                <div className="message-content">
                  <header>
                    <strong>{message.role === 'user' ? subject : message.author}</strong>
                    <time>{formatTimestamp(message.timestamp)}</time>
                  </header>
                  <ReactMarkdown remarkPlugins={[remarkGfm]}>{message.content}</ReactMarkdown>
                  {message.role === 'assistant' && message.execution && (
                    <details className="execution-detail message-execution-detail">
                      <summary>查看运行过程</summary>
                      {message.execution.streamCheckpoint?.thinking && (
                        <p>{message.execution.streamCheckpoint.thinking}</p>
                      )}
                    </details>
                  )}
                </div>
              </article>
            ))}
            {!activeExecutionAnswerPersisted && active.execution?.streamCheckpoint?.thinking && (
              <details className="execution-detail">
                <summary>查看运行过程</summary>
                <p>{active.execution.streamCheckpoint.thinking}</p>
              </details>
            )}
            {!activeExecutionAnswerPersisted && active.execution?.streamCheckpoint?.answer && (
              <article className="message assistant stream-answer">
                <div className="message-avatar">
                  <Bot size={16} />
                </div>
                <div className="message-content">
                  <ReactMarkdown remarkPlugins={[remarkGfm]}>
                    {active.execution.streamCheckpoint.answer}
                  </ReactMarkdown>
                </div>
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
