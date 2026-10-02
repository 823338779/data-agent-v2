import { ArrowDown, Brain, PanelLeftClose } from 'lucide-react';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  AgentBackendClient,
  applyConversationStreamEvent,
  BackendApiError,
  ConversationStreamEvent,
  createFetchAgentBackendClient,
} from './api/backendAgentClient';
import { createFetchMemoryClient, MemoryClient } from './api/memoryClient';
import {
  navigateWorkspace,
  parseWorkspaceRoute,
  subscribeWorkspaceRoute,
  WorkspaceRoute,
} from './app/workspaceRoute';
import { AppShell } from './components/shell/AppShell';
import { ConversationSidebar } from './components/ConversationSidebar';
import { SidebarMutationResult, useConversationSidebar } from './app/useConversationSidebar';
import { MarkdownContent } from './components/MarkdownContent';
import { MessageProcessTimeline } from './components/MessageProcessTimeline';
import { ConversationStatus } from './components/ConversationStatus';
import { RunStreamPanel } from './components/RunStreamPanel';
import { PersonalMemoryPanel } from './components/memory/PersonalMemoryPanel';
import { MessageComposer } from './components/MessageComposer';
import { CopyButton } from './components/CopyButton';
import { parseAgentUiPlaceholders } from './components/ui-block/parseAgentUiPlaceholders';
import { useConversationScroll } from './app/useConversationScroll';
import { useMemoryModal } from './components/memory/useMemoryModal';
import { ConversationSession, formatTimestamp, isProcessingStatus } from './domain/agent';

interface AppProps {
  client?: AgentBackendClient;
  memoryClient?: MemoryClient;
}

const narrowScreenQuery = '(max-width: 820px)';
const TITLE_CONFLICT_MESSAGE = '标题已在其他位置更新，已加载最新标题。';
const TITLE_SAVE_ERROR_MESSAGE = '标题保存失败，请稍后重试。';
const STREAM_INTERRUPTED_MESSAGE = '实时状态连接已中断；会话内容仍可继续查看。';

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

export default function App({ client, memoryClient }: AppProps) {
  const backend = useMemo(() => client ?? createFetchAgentBackendClient(), [client]);
  const memories = useMemo(() => memoryClient ?? createFetchMemoryClient(), [memoryClient]);
  const sidebar = useConversationSidebar(backend);
  const [route, setRoute] = useState<WorkspaceRoute>(parseWorkspaceRoute);
  const [sessions, setSessions] = useState<ConversationSession[]>([]);
  const [historyOpen, setHistoryOpen] = useState(
    () => !window.matchMedia?.(narrowScreenQuery).matches,
  );
  const [narrowScreen, setNarrowScreen] = useState(
    () => window.matchMedia?.(narrowScreenQuery).matches ?? false,
  );
  const sidebarRef = useRef<HTMLElement>(null);
  const sidebarCloseRef = useRef<HTMLButtonElement>(null);
  const [memoryOpen, setMemoryOpen] = useState(false);
  useMemoryModal(
    sidebarRef,
    sidebarCloseRef,
    () => setHistoryOpen(false),
    narrowScreen && historyOpen && !memoryOpen,
  );
  // 草稿仅存于当前页面，按会话隔离；异步发送只能清理它自己的草稿。
  const [drafts, setDrafts] = useState<Record<string, string>>({});
  const draftKey = route.kind === 'session' ? route.conversationSessionId : 'home';
  const prompt = drafts[draftKey] ?? '';
  const setPrompt = (value: string) => setDrafts((current) => ({ ...current, [draftKey]: value }));
  const sendLocked = useRef(false);
  const loading = sidebar.loading;
  const [sending, setSending] = useState(false);
  const [creating, setCreating] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [loadedDetailSessionId, setLoadedDetailSessionId] = useState<string | null>(null);
  const [detailReload, setDetailReload] = useState(0);
  const [detailLoading, setDetailLoading] = useState(false);
  const detailSequence = useRef(0);
  const openMemory = useCallback(() => setMemoryOpen(true), []);
  const closeMemory = useCallback(() => setMemoryOpen(false), []);

  const active =
    route.kind === 'session'
      ? sessions.find((session) => session.conversationSessionId === route.conversationSessionId)
      : undefined;
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
  const { messagesRef, onScroll, showLatest, jumpToLatest } = useConversationScroll(
    active?.conversationSessionId,
    active?.messages,
    activeExecutionAnswer,
  );
  // 仅当前详情的运行状态参与显示；旧详情和 SSE 不得恢复侧栏的旧归属、置顶或标题。
  const sidebarSnapshot = useMemo(
    () => ({
      ...sidebar.snapshot,
      sessions: sidebar.snapshot.sessions.map((summary) =>
        summary.conversationSessionId === active?.conversationSessionId && active.execution
          ? { ...summary, latestExecutionStatus: active.execution.status }
          : summary,
      ),
    }),
    [sidebar.snapshot, active],
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
    setSessions((current) => {
      const currentRoute = parseWorkspaceRoute();
      // 创建后的摘要可能暂未包含新会话，不能据此丢弃当前详情或关闭其 SSE。
      const retained = current.filter(
        (item) =>
          currentRoute.kind === 'session' &&
          item.conversationSessionId === currentRoute.conversationSessionId &&
          !loaded.some((summary) => summary.conversationSessionId === item.conversationSessionId),
      );
      return sortSessionsByUpdatedAt([
        ...retained,
        ...loaded.map((summary) => {
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
      ]);
    });
  }

  useEffect(() => subscribeWorkspaceRoute(setRoute), []);
  useEffect(() => {
    if (!window.matchMedia) return;
    const media = window.matchMedia(narrowScreenQuery);
    const collapseOnNarrowScreen = (event: MediaQueryListEvent) => {
      setNarrowScreen(event.matches);
      if (event.matches) setHistoryOpen(false);
    };
    media.addEventListener('change', collapseOnNarrowScreen);
    return () => media.removeEventListener('change', collapseOnNarrowScreen);
  }, []);
  useEffect(() => {
    mergeSessionSummaries(sidebar.snapshot.sessions);
    // 摘要快照更新列表字段；消息和执行继续由详情生命周期管理。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sidebar.snapshot]);

  // 每次打开会话都先读取权威详情，只有该响应完成后才允许建立对应详情流。
  useEffect(() => {
    if (route.kind !== 'session') {
      setLoadedDetailSessionId(null);
      return;
    }
    const conversationSessionId = route.conversationSessionId;
    const sequence = ++detailSequence.current;
    let cancelled = false;
    setDetailLoading(true);
    setLoadedDetailSessionId(null);
    void backend
      .getSession(conversationSessionId)
      .then((detail) => {
        if (cancelled || sequence !== detailSequence.current) return;
        replaceSession(detail);
        setLoadedDetailSessionId(conversationSessionId);
      })
      .catch((failure) => {
        if (!cancelled && sequence === detailSequence.current)
          handleFailure(failure, '无法读取会话详情。');
      })
      .finally(() => {
        if (!cancelled && sequence === detailSequence.current) setDetailLoading(false);
      });
    return () => {
      cancelled = true;
    };
    // 会话标识变化时重新加载，状态更新不应重复发起详情请求。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [
    backend,
    route.kind,
    route.kind === 'session' ? route.conversationSessionId : null,
    detailReload,
  ]);

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

  async function createSession(projectId: string | null = null): Promise<SidebarMutationResult> {
    if (creating) return { outcome: 'rejected', refreshed: false };
    setCreating(true);
    setError(null);
    try {
      return await sidebar.mutate(async () => {
        const session = await backend.createSession('新会话', '', projectId);
        replaceSession(session);
        navigateWorkspace({
          kind: 'session',
          conversationSessionId: session.conversationSessionId,
        });
        if (narrowScreen) setHistoryOpen(false);
      });
    } finally {
      setCreating(false);
    }
  }

  async function sendPrompt() {
    if (
      sendLocked.current ||
      creating ||
      activeIsProcessing ||
      !prompt.trim() ||
      (route.kind === 'session' && !active)
    )
      return;
    const content = prompt.trim();
    let target = active;
    let targetKey = draftKey;
    sendLocked.current = true;
    setSending(true);
    setError(null);
    try {
      if (!target) {
        const created: { session?: ConversationSession } = {};
        // 复用侧栏写入代次，使创建之前的摘要请求失效。
        await sidebar.mutate(async () => {
          const session = await backend.createSession('新会话', '', null);
          created.session = session;
          targetKey = session.conversationSessionId;
          replaceSession(session);
          // 请求期间仍允许编辑，迁移最新草稿；提交成功只清理实际发送的内容。
          setDrafts((current) => ({ ...current, home: '', [targetKey]: current.home ?? content }));
          if (parseWorkspaceRoute().kind === 'home') {
            navigateWorkspace({ kind: 'session', conversationSessionId: targetKey });
          }
        });
        if (!created.session) throw new Error('会话未创建');
        target = created.session;
      }
      const session = await backend.submitMessage(target.conversationSessionId, content);
      // 先前启动的详情读取不能覆盖本次提交的最新快照。
      if (
        parseWorkspaceRoute().kind === 'session' &&
        window.location.pathname === `/sessions/${encodeURIComponent(targetKey)}`
      ) {
        detailSequence.current++;
        setLoadedDetailSessionId(targetKey);
        setDetailLoading(false);
      }
      replaceSession(session);
      setDrafts((current) =>
        current[targetKey]?.trim() === content ? { ...current, [targetKey]: '' } : current,
      );
      void sidebar.refresh();
    } catch (failure) {
      handleFailure(failure, '消息发送失败，输入内容已保留。');
    } finally {
      sendLocked.current = false;
      setSending(false);
    }
  }

  function openSession(conversationSessionId: string) {
    navigateWorkspace({ kind: 'session', conversationSessionId });
    if (window.matchMedia?.(narrowScreenQuery).matches) setHistoryOpen(false);
  }

  // 标题保存使用会话版本做乐观锁；冲突时刷新服务端最新会话，普通失败则保留编辑态。
  async function renameSession(conversationSessionId: string, title: string): Promise<boolean> {
    const session = sessions.find((item) => item.conversationSessionId === conversationSessionId);
    if (!session) return true;

    setError(null);
    try {
      replaceSession(await backend.renameSession(conversationSessionId, title, session.version));
      await sidebar.refresh();
      return true;
    } catch (failure) {
      if (failure instanceof BackendApiError && failure.status === 409) {
        try {
          replaceSession(await backend.getSession(conversationSessionId));
          await sidebar.refresh();
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
    <AppShell
      historyOpen={historyOpen}
      onToggleHistory={() => setHistoryOpen((open) => !open)}
      onOpenMemory={openMemory}
    >
      {historyOpen && narrowScreen && (
        <button
          className="sidebar-backdrop"
          type="button"
          aria-label="关闭会话列表"
          onClick={() => setHistoryOpen(false)}
        />
      )}
      {historyOpen && (
        <aside className="session-sidebar" aria-label="会话列表" ref={sidebarRef}>
          <div className="session-sidebar-heading">
            <button
              className="brand"
              type="button"
              onClick={() => navigateWorkspace({ kind: 'home' })}
            >
              Data Agent
            </button>
            <button
              className="icon-button"
              ref={sidebarCloseRef}
              type="button"
              aria-label="收起会话列表"
              onClick={() => setHistoryOpen(false)}
            >
              <PanelLeftClose size={17} />
            </button>
          </div>
          <ConversationSidebar
            key={backend.getSidebarStorageKey()}
            snapshot={sidebarSnapshot}
            loading={sidebar.loading}
            busy={sidebar.busy}
            error={sidebar.error}
            storageKey={backend.getSidebarStorageKey()}
            activeSessionId={active?.conversationSessionId}
            onOpen={openSession}
            onRename={renameSession}
            onCreateSession={createSession}
            onCreateProject={(name) => sidebar.mutate(() => backend.createProject(name))}
            onRenameProject={(id, name, version) =>
              sidebar.mutate(() => backend.renameProject(id, name, version))
            }
            onDeleteProject={(id, version) =>
              sidebar.mutate(() => backend.deleteProject(id, version))
            }
            onSetOrganization={(id, request) =>
              sidebar.mutate(() => backend.setOrganization(id, request))
            }
            onRefresh={sidebar.refresh}
          />
          <button className="sidebar-memory-button" type="button" onClick={openMemory}>
            <Brain size={17} />
            个人记忆
          </button>
        </aside>
      )}
      {route.kind === 'home' && (
        <section className="home-view">
          <div className="home-copy">
            <h1>今天想完成什么？</h1>
            <p>从一个问题开始，让任务在对话中推进。</p>
          </div>
          <MessageComposer
            value={prompt}
            onChange={setPrompt}
            onSend={() => void sendPrompt()}
            busy={sending || creating}
          />
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
              {active.projectId && (
                <span className="project-breadcrumb">
                  {
                    sidebar.snapshot.projects.find(
                      (project) => project.projectId === active.projectId,
                    )?.name
                  }
                  <span aria-hidden="true"> / </span>
                </span>
              )}
              <h1>{active.title}</h1>
            </div>
            <ConversationStatus
              status={active.execution?.status ?? 'idle'}
              onCancel={() => void cancelActiveExecution()}
            />
          </header>

          <div className="message-list" aria-label="消息列表" ref={messagesRef} onScroll={onScroll}>
            <div className="message-list-content">
              {active.messages.length === 0 && (
                <div className="empty-conversation">
                  <h2>从这里开始</h2>
                  <p>描述你的目标、背景和希望得到的结果。</p>
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
                    <div className="message-content">
                      {message.role === 'assistant' && (
                        <MessageProcessTimeline items={message.thinkingProcess} />
                      )}
                      {message.role === 'assistant' ? (
                        visibleAssistantContent ? (
                          <MarkdownContent
                            content={visibleAssistantContent}
                            uiBlocks={message.uiBlocks}
                          />
                        ) : isActiveAssistant && activeIsProcessing ? (
                          <RunStreamPanel checkpoint={active.execution?.streamCheckpoint ?? null} />
                        ) : null
                      ) : (
                        <p className="plain-message-content">{message.content}</p>
                      )}
                      {message.role === 'assistant' &&
                        visibleAssistantContent &&
                        !(isActiveAssistant && activeIsProcessing) && (
                          <CopyButton
                            label="复制回答"
                            text={parseAgentUiPlaceholders(visibleAssistantContent)
                              .map((segment) =>
                                segment.kind === 'markdown' ? segment.content : '',
                              )
                              .join('\n')}
                          />
                        )}
                    </div>
                  </article>
                );
              })}
              {!activeExecutionAnswerPersisted &&
                active.execution &&
                !activeAssistantMessage &&
                (activeIsProcessing || activeExecutionAnswer) && (
                  <RunStreamPanel checkpoint={active.execution.streamCheckpoint} />
                )}
            </div>
          </div>
          {showLatest && (
            <button className="jump-latest" type="button" onClick={jumpToLatest}>
              <ArrowDown size={14} />
              回到最新
            </button>
          )}
          <MessageComposer
            value={prompt}
            onChange={setPrompt}
            onSend={() => void sendPrompt()}
            busy={sending}
            running={activeIsProcessing}
            disabled={detailLoading}
          />
        </section>
      )}

      {!loading && route.kind === 'session' && !active && (
        <section className="not-found">
          <h1>{detailLoading ? '正在读取会话…' : '无法打开会话'}</h1>
          <button type="button" onClick={() => navigateWorkspace({ kind: 'home' })}>
            返回首页
          </button>
        </section>
      )}

      {loading && <div className="loading-state">正在读取会话…</div>}
      {(error || (!historyOpen && sidebar.error)) && (
        <div className="error-banner" role="alert">
          <span>{error ?? sidebar.error}</span>
          <button
            type="button"
            onClick={() => {
              setError(null);
              setDetailReload((current) => current + 1);
              void sidebar.refresh();
            }}
          >
            重试
          </button>
        </div>
      )}
      {memoryOpen && <PersonalMemoryPanel client={memories} onClose={closeMemory} />}
    </AppShell>
  );
}
