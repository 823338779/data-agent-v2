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
import {
  AgentBackendClient,
  applyConversationStreamEvent,
  BackendApiError,
  ConversationStreamEvent,
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
import { MarkdownContent } from './components/MarkdownContent';
import { MessageProcessTimeline } from './components/MessageProcessTimeline';
import { ConversationStatus } from './components/ConversationStatus';
import { RunStreamPanel } from './components/RunStreamPanel';
import { WorkflowRunPanel } from './components/WorkflowRunPanel';
import { ConversationSession, formatTimestamp, isProcessingStatus } from './domain/agent';

interface AppProps {
  client?: AgentBackendClient;
}

const narrowScreenQuery = '(max-width: 820px)';
const TITLE_CONFLICT_MESSAGE = '标题已在其他位置更新，已加载最新标题。';
const TITLE_SAVE_ERROR_MESSAGE = '标题保存失败，请稍后重试。';
const STREAM_INTERRUPTED_MESSAGE = '实时状态连接已中断；会话内容仍可继续查看。';
const FIXED_SUBJECT = 'admin';
export const BACKGROUND_POLL_INTERVAL_MS = 60_000;

/** 会话更新时间为权威顺序；业务标识只用于相同时间戳下保持稳定。 */
function sortSessionsByUpdatedAt(sessions: ConversationSession[]): ConversationSession[] {
  return [...sessions].sort(
    (left, right) =>
      right.updatedAt.localeCompare(left.updatedAt) ||
      right.conversationSessionId.localeCompare(left.conversationSessionId),
  );
}

/** 只把成功完成识别为正常结束，失败和取消仍保留原有异常提示语义。 */
function isCompletedStreamEvent(event: ConversationStreamEvent): boolean {
  if (event.type === 'session_snapshot') return event.session.execution?.status === 'done';
  return event.type === 'execution_update' && event.execution.status === 'done';
}

export default function App({ client }: AppProps) {
  const backend = useMemo(() => client ?? createFetchAgentBackendClient(), [client]);
  const [route, setRoute] = useState<WorkspaceRoute>(parseWorkspaceRoute);
  const [sessions, setSessions] = useState<ConversationSession[]>([]);
  const [historyOpen, setHistoryOpen] = useState(false);
  const [search, setSearch] = useState('');
  const [prompt, setPrompt] = useState('');
  const [loading, setLoading] = useState(true);
  const [sending, setSending] = useState(false);
  const [creating, setCreating] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [loadedDetailSessionId, setLoadedDetailSessionId] = useState<string | null>(null);
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
  const hasMessageExecutionMetadata = assistantMessages.some(
    (message) => message.executionId || message.execution,
  );
  const activeExecutionAnswer = active?.execution?.streamCheckpoint?.answer ?? '';
  const activeExecutionAnswerPersisted = Boolean(
    activeExecutionAnswer &&
    (assistantMessages.some(
      (message) =>
        (message.executionId === active?.execution?.executionId ||
          message.execution?.executionId === active?.execution?.executionId) &&
        message.content === activeExecutionAnswer,
    ) ||
      (!hasMessageExecutionMetadata &&
        assistantMessages.some((message) => message.content === activeExecutionAnswer))),
  );
  const activeAssistantMessage = assistantMessages.find(
    (message) =>
      message.executionId === active?.execution?.executionId ||
      message.execution?.executionId === active?.execution?.executionId,
  );
  const activeStatus = active?.execution?.status ?? active?.latestExecutionStatus ?? 'idle';
  // SSE effect 只依赖“是否仍在处理”，避免同一处理阶段内的状态细化触发先关闭再重连。
  const activeIsProcessing = isProcessingStatus(activeStatus);
  const hasBackgroundProcessingSession = sessions.some(
    (session) =>
      session.conversationSessionId !==
        (route.kind === 'session' ? route.conversationSessionId : null) &&
      isProcessingStatus(session.execution?.status ?? session.latestExecutionStatus ?? 'idle'),
  );

  function handleFailure(failure: unknown, message: string) {
    setError(message);
  }

  function replaceSession(session: ConversationSession) {
    setSessions((current) => {
      const exists = current.some(
        (item) => item.conversationSessionId === session.conversationSessionId,
      );
      return sortSessionsByUpdatedAt(
        exists
          ? current.map((item) =>
              item.conversationSessionId === session.conversationSessionId ? session : item,
            )
          : [session, ...current],
      );
    });
  }

  /** 摘要只能更新列表字段，不能覆盖当前页已经流式收敛的消息和执行详情。 */
  function mergeSessionSummaries(loaded: ConversationSession[]) {
    setSessions((current) =>
      sortSessionsByUpdatedAt(
        loaded.map((summary) => {
          const detail = current.find(
            (item) => item.conversationSessionId === summary.conversationSessionId,
          );
          return detail
            ? {
                ...detail,
                ...summary,
                messages: detail.messages,
                execution: detail.execution,
              }
            : summary;
        }),
      ),
    );
  }

  async function loadSessions() {
    setLoading(true);
    setError(null);
    try {
      const loaded = await backend.listSessions();
      mergeSessionSummaries(loaded);
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

  /** 后台轮询失败不打断当前会话，只保留现有摘要等待下一次轮询。 */
  async function loadSessionSummariesSilently() {
    try {
      mergeSessionSummaries(await backend.listSessions());
    } catch (failure) {
      handleFailure(failure, '后台会话状态更新失败，将在下一周期重试。');
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

  // 每次打开会话都先读取权威详情，只有该响应完成后才允许建立对应详情流。
  useEffect(() => {
    if (route.kind !== 'session') {
      setLoadedDetailSessionId(null);
      return;
    }
    const conversationSessionId = route.conversationSessionId;
    let cancelled = false;
    setLoadedDetailSessionId(null);
    void backend
      .getSession(conversationSessionId)
      .then((detail) => {
        if (cancelled) return;
        replaceSession(detail);
        setLoadedDetailSessionId(conversationSessionId);
      })
      .catch((failure) => {
        if (!cancelled) handleFailure(failure, '无法读取会话详情。');
      });
    return () => {
      cancelled = true;
    };
    // 会话标识变化时重新加载，状态更新不应重复发起详情请求。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [backend, route.kind, route.kind === 'session' ? route.conversationSessionId : null]);

  // 页面最多持有当前打开且正在处理的会话的一条 SSE，终态或切换页面会触发清理。
  useEffect(() => {
    if (!active || loadedDetailSessionId !== active.conversationSessionId || !activeIsProcessing) {
      return;
    }
    const conversationSessionId = active.conversationSessionId;
    let completedNormally = false;
    const close = backend.subscribeSessionEvents(
      conversationSessionId,
      (event) => {
        if (event.conversationSessionId !== conversationSessionId) return;
        // 最终完成事件先于服务端主动关流到达，用本连接内标记区分正常关闭与意外断线。
        if (isCompletedStreamEvent(event)) {
          completedNormally = true;
          setError(null);
        }
        setSessions((current) =>
          sortSessionsByUpdatedAt(
            current.map((session) =>
              session.conversationSessionId === conversationSessionId
                ? applyConversationStreamEvent(session, event)
                : session,
            ),
          ),
        );
      },
      () => {
        if (!completedNormally) setError(STREAM_INTERRUPTED_MESSAGE);
      },
    );
    return close;
  }, [active?.conversationSessionId, activeIsProcessing, backend, loadedDetailSessionId]);

  // 未打开的处理中会话只通过一分钟摘要轮询更新，不占用任何长连接。
  useEffect(() => {
    if (!hasBackgroundProcessingSession) return;
    const timer = window.setInterval(
      () => void loadSessionSummariesSilently(),
      BACKGROUND_POLL_INTERVAL_MS,
    );
    return () => window.clearInterval(timer);
    // 轮询只由是否存在后台处理中会话控制。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [backend, hasBackgroundProcessingSession]);

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
      setHistoryOpen(false);
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

  return (
    <AppShell historyOpen={historyOpen} onToggleHistory={() => setHistoryOpen((open) => !open)}>
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
            onRename={renameSession}
          />
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
            />
          </header>

          <div className="message-list" aria-label="消息列表" ref={messagesRef}>
            <div className="message-list-content">
              {active.messages.length === 0 && (
                <div className="empty-conversation">
                  <Bot size={24} />
                  <h2>描述你要解决的数据问题</h2>
                  <p>当前服务端支持保存会话、提交消息并返回运行状态。</p>
                </div>
              )}
              {active.messages.map((message) => {
                const isActiveAssistant =
                  message.role === 'assistant' &&
                  (message.executionId === active.execution?.executionId ||
                    message.execution?.executionId === active.execution?.executionId);
                const visibleAssistantContent =
                  isActiveAssistant && !activeExecutionAnswerPersisted && activeExecutionAnswer
                    ? activeExecutionAnswer
                    : message.content;
                return (
                  <article
                    className={`message ${message.role}`}
                    key={message.conversationMessageId}
                  >
                    <div className="message-avatar">
                      {message.role === 'user' ? <UserRound size={16} /> : <Bot size={16} />}
                    </div>
                    <div className="message-content">
                      <header>
                        <strong>{message.role === 'user' ? FIXED_SUBJECT : message.author}</strong>
                        <time>{formatTimestamp(message.timestamp)}</time>
                      </header>
                      {message.role === 'assistant' ? (
                        <>
                          {isActiveAssistant && active.workflow && (
                            <WorkflowRunPanel workflow={active.workflow} />
                          )}
                          {visibleAssistantContent ? (
                            <MarkdownContent
                              content={visibleAssistantContent}
                              artifacts={message.artifacts}
                              uiBlocks={message.uiBlocks}
                            />
                          ) : isActiveAssistant && activeIsProcessing ? (
                            <RunStreamPanel
                              checkpoint={active.execution?.streamCheckpoint ?? null}
                            />
                          ) : null}
                        </>
                      ) : (
                        <p className="plain-message-content">{message.content}</p>
                      )}
                      {message.role === 'assistant' && (
                        <MessageProcessTimeline items={message.thinkingProcess} />
                      )}
                    </div>
                  </article>
                );
              })}
              {!activeExecutionAnswerPersisted &&
                active.execution &&
                !activeAssistantMessage &&
                (activeIsProcessing || activeExecutionAnswer) && (
                  <>
                    {active.workflow && <WorkflowRunPanel workflow={active.workflow} />}
                    <RunStreamPanel checkpoint={active.execution.streamCheckpoint} />
                  </>
                )}
            </div>
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
