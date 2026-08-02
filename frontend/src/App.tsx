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
  applyExecutionStreamEvent,
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
import { ConversationList } from './components/ConversationList';
import { ConversationStatus } from './components/ConversationStatus';
import { RunStreamPanel } from './components/RunStreamPanel';
import { ConversationSession, formatTimestamp } from './domain/agentPrototype';

interface AppProps {
  subject?: string;
  onLogout?: () => Promise<void>;
  onAuthenticationRequired?: () => void;
  client?: AgentBackendClient;
}

const narrowScreenQuery = '(max-width: 820px)';
const TITLE_CONFLICT_MESSAGE = '标题已在其他位置更新，已加载最新标题。';
const TITLE_SAVE_ERROR_MESSAGE = '标题保存失败，请稍后重试。';

function isNarrowScreen(): boolean {
  return window.matchMedia?.(narrowScreenQuery).matches ?? false;
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
  const [reordering, setReordering] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const messagesRef = useRef<HTMLDivElement>(null);
  const readCursorRef = useRef<Record<string, number>>({});

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

  // activity SSE 跨会话常驻，只合并轻量字段；sortOrder 才能改变列表顺序。
  useEffect(() => {
    const close = backend.subscribeActivity(
      (snapshot) => {
        const activityById = new Map(
          snapshot.sessions.map((activity) => [activity.conversationSessionId, activity]),
        );
        setSessions((current) =>
          current
            .map((session) => {
              const activity = activityById.get(session.conversationSessionId);
              return activity
                ? {
                    ...session,
                    activityStatus: activity.status,
                    unread: activity.unread,
                    lastNotifiableEventSequence: activity.lastNotifiableEventSequence,
                    sortOrder: activity.sortOrder,
                    orderingVersion: snapshot.orderingVersion,
                    updatedAt: activity.updatedAt,
                  }
                : session;
            })
            .sort((left, right) => left.sortOrder - right.sortOrder),
        );
      },
      () => {
        setError('会话活动连接已中断，正在重新同步。');
        void loadSessions();
      },
    );
    return close;
    // activity 连接不随当前会话切换而重建。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [backend]);

  useEffect(() => {
    if (!active) return;
    const conversationSessionId = active.conversationSessionId;
    const close = backend.subscribeExecutionEvents(
      conversationSessionId,
      (event) =>
        setSessions((current) =>
          current.map((session) =>
            session.conversationSessionId === conversationSessionId && session.execution
              ? {
                  ...session,
                  execution: {
                    ...session.execution,
                    streamCheckpoint: applyExecutionStreamEvent(
                      session.execution.streamCheckpoint,
                      event,
                    ),
                  },
                }
              : session,
          ),
        ),
      () =>
        void backend
          .getSession(conversationSessionId)
          .then(replaceSession)
          .catch((failure) => handleFailure(failure, '无法刷新最新运行状态。')),
      () => setError('实时状态连接已中断；会话内容仍可继续查看。'),
    );
    return close;
  }, [active?.conversationSessionId, backend]);

  // 当前会话的通知已提交到 DOM 后，按活动快照中的精确序号推进已读游标。
  useEffect(() => {
    if (!active?.unread || active.lastNotifiableEventSequence <= 0) return;
    const sequence = active.lastNotifiableEventSequence;
    if ((readCursorRef.current[active.conversationSessionId] ?? 0) >= sequence) return;
    readCursorRef.current[active.conversationSessionId] = sequence;
    void backend
      .markRead(active.conversationSessionId, sequence)
      .then(replaceSession)
      .catch((failure) => handleFailure(failure, '无法更新会话已读状态。'));
  }, [active?.conversationSessionId, active?.unread, active?.lastNotifiableEventSequence, backend]);

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

  async function reorderSessions(visibleIds: string[]) {
    if (reordering) return;
    const visible = new Set(visibleIds);
    const completeIds = [
      ...visibleIds,
      ...sessions
        .filter((item) => !visible.has(item.conversationSessionId))
        .map((item) => item.conversationSessionId),
    ];
    const previous = sessions;
    const byId = new Map(sessions.map((item) => [item.conversationSessionId, item]));
    setSessions(completeIds.map((id, index) => ({ ...byId.get(id)!, sortOrder: index })));
    setReordering(true);
    try {
      const result = await backend.reorderSessions(completeIds, sessions[0]?.orderingVersion ?? 0);
      setSessions(result.sessions);
    } catch (failure) {
      if (failure instanceof BackendApiError && failure.status === 409) {
        await loadSessions();
        setError('会话顺序已在另一设备更新，已恢复服务端顺序。');
      } else {
        setSessions(previous);
        handleFailure(failure, '会话排序失败，已恢复原顺序。');
      }
    } finally {
      setReordering(false);
    }
  }

  // 标题保存使用会话版本做乐观锁；冲突时刷新服务端最新会话，普通失败则保留编辑态。
  async function renameSession(conversationSessionId: string, title: string): Promise<boolean> {
    const session = sessions.find((item) => item.conversationSessionId === conversationSessionId);
    if (!session) return true;

    setError(null);
    try {
      replaceSession(await backend.renameSession(conversationSessionId, title, session.version));
      return true;
    } catch (failure) {
      if (failure instanceof BackendApiError && failure.status === 409) {
        try {
          replaceSession(await backend.getSession(conversationSessionId));
          setError(TITLE_CONFLICT_MESSAGE);
          return true;
        } catch (refreshFailure) {
          handleFailure(refreshFailure, TITLE_SAVE_ERROR_MESSAGE);
          return false;
        }
      }
      handleFailure(failure, TITLE_SAVE_ERROR_MESSAGE);
      return false;
    }
  }

  async function cancelActiveExecution() {
    if (!active?.execution) return;
    try {
      await backend.cancelExecution(active.conversationSessionId, active.execution.executionId);
      replaceSession(await backend.getSession(active.conversationSessionId));
    } catch (failure) {
      handleFailure(failure, '取消运行失败，请稍后重试。');
    }
  }

  async function retryActiveExecution() {
    if (!active?.execution) return;
    try {
      await backend.retryExecution(active.conversationSessionId, active.execution.executionId);
      replaceSession(await backend.getSession(active.conversationSessionId));
    } catch (failure) {
      handleFailure(failure, '重试运行失败，请稍后重试。');
    }
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
          <ConversationList
            sessions={visibleSessions}
            activeSessionId={active?.conversationSessionId}
            onOpen={openSession}
            onReorder={(ids) => void reorderSessions(ids)}
            onRename={renameSession}
          />
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
            <ConversationStatus
              status={active.execution?.status ?? 'idle'}
              onCancel={() => void cancelActiveExecution()}
              onRetry={() => void retryActiveExecution()}
            />
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
                    <RunStreamPanel
                      historical
                      hideAnswer
                      status={message.execution.status}
                      checkpoint={message.execution.streamCheckpoint}
                    />
                  )}
                </div>
              </article>
            ))}
            {!activeExecutionAnswerPersisted && active.execution && (
              <RunStreamPanel
                status={active.execution.status}
                checkpoint={active.execution.streamCheckpoint}
              />
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
