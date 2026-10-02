import {
  KeyboardEvent,
  useCallback,
  useEffect,
  useId,
  useLayoutEffect,
  useRef,
  useState,
} from 'react';
import { ConversationSession } from '../domain/agent';
import {
  ArrowLeft,
  Check,
  ChevronRight,
  Folder,
  FolderInput,
  Inbox,
  MoreHorizontal,
  Pencil,
  Pin,
  PinOff,
} from 'lucide-react';
import { ConversationProject, SetConversationOrganization } from '../domain/sidebar';
import { SidebarMutationResult } from '../app/useConversationSidebar';

interface ConversationListProps {
  sessions: ConversationSession[];
  activeSessionId?: string;
  onOpen: (conversationSessionId: string) => void;
  onRename: (conversationSessionId: string, title: string) => Promise<boolean>;
  projects?: ConversationProject[];
  busy?: boolean;
  showOrigin?: boolean;
  onSetOrganization?: (
    id: string,
    request: SetConversationOrganization,
  ) => Promise<SidebarMutationResult>;
}

const STATUS_LABELS: Record<string, string> = {
  running: '处理中',
  failed: '失败',
  cancelled: '已取消',
};

const ENTER_KEY = 'Enter';
const ESCAPE_KEY = 'Escape';
const MAX_TITLE_LENGTH = 120;
const EDIT_TITLE_LABEL_PREFIX = '编辑“';
const EDIT_TITLE_LABEL_SUFFIX = '”标题';
/** 单行会话导航，列表顺序由服务端会话更新时间决定。 */
export function ConversationList({
  sessions,
  activeSessionId,
  onOpen,
  onRename,
  projects = [],
  busy = false,
  showOrigin = false,
  onSetOrganization,
}: ConversationListProps) {
  const titleInputRef = useRef<HTMLInputElement>(null);
  const cancelTitleEdit = useRef(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [draftTitle, setDraftTitle] = useState('');
  const [savingTitle, setSavingTitle] = useState(false);
  const [menuId, setMenuId] = useState<string | null>(null);
  const [choosingProject, setChoosingProject] = useState(false);
  const [menuPosition, setMenuPosition] = useState({ top: 0, left: 0 });
  const menuRef = useRef<HTMLDivElement>(null);
  const menuTrigger = useRef<HTMLButtonElement | null>(null);
  const moveAction = useRef<HTMLButtonElement>(null);
  const returningToActions = useRef(false);
  const menuElementId = useId();

  const closeMenu = useCallback((restoreFocus = true) => {
    setMenuId(null);
    if (restoreFocus) menuTrigger.current?.focus({ preventScroll: true });
  }, []);

  // 浮层脱离列表布局但留在侧栏 DOM 内，避免撑开列表，也兼容窄屏抽屉的焦点约束。
  useLayoutEffect(() => {
    const menu = menuRef.current;
    const anchor = menuTrigger.current;
    if (!menuId || !menu || !anchor) return;
    const rect = anchor.getBoundingClientRect();
    const bounds = menu.getBoundingClientRect();
    const gap = 6;
    setMenuPosition({
      left: Math.max(8, Math.min(rect.right - bounds.width, window.innerWidth - bounds.width - 8)),
      top: Math.max(
        8,
        Math.min(
          rect.bottom + gap + bounds.height <= window.innerHeight - 8
            ? rect.bottom + gap
            : rect.top - bounds.height - gap,
          window.innerHeight - bounds.height - 8,
        ),
      ),
    });
    const initialFocus = returningToActions.current
      ? moveAction.current
      : menu.querySelector<HTMLButtonElement>('button:not(:disabled)');
    initialFocus?.focus({ preventScroll: true });
    returningToActions.current = false;
  }, [menuId, choosingProject, projects.length]);

  useEffect(() => {
    if (!menuId) return;
    function dismissOutside(event: Event) {
      const target = event.target as Node;
      if (!menuRef.current?.contains(target) && !menuTrigger.current?.contains(target))
        closeMenu(false);
    }
    function dismissOnScroll(event: Event) {
      if (!menuRef.current?.contains(event.target as Node)) closeMenu();
    }
    // 侧栏滚动或窗口变化后收起，防止菜单脱离原会话；浮层自己的项目列表仍可滚动。
    document.addEventListener('pointerdown', dismissOutside);
    document.addEventListener('focusin', dismissOutside);
    document.addEventListener('scroll', dismissOnScroll, true);
    window.addEventListener('resize', closeOnResize);
    function closeOnResize() {
      closeMenu();
    }
    return () => {
      document.removeEventListener('pointerdown', dismissOutside);
      document.removeEventListener('focusin', dismissOutside);
      document.removeEventListener('scroll', dismissOnScroll, true);
      window.removeEventListener('resize', closeOnResize);
    };
  }, [menuId, closeMenu]);

  function returnToActions() {
    returningToActions.current = true;
    setChoosingProject(false);
  }

  function handleMenuKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    if (event.nativeEvent.isComposing) return;
    if (event.key === ESCAPE_KEY || (event.key === 'ArrowLeft' && choosingProject)) {
      event.preventDefault();
      event.stopPropagation();
      if (choosingProject) returnToActions();
      else closeMenu();
      return;
    }
    if (event.key === 'Tab') {
      closeMenu();
      return;
    }
    const items = Array.from(
      event.currentTarget.querySelectorAll<HTMLButtonElement>('button:not(:disabled)'),
    );
    const index = items.indexOf(document.activeElement as HTMLButtonElement);
    const next =
      event.key === 'Home'
        ? 0
        : event.key === 'End'
          ? items.length - 1
          : event.key === 'ArrowDown'
            ? (index + 1) % items.length
            : event.key === 'ArrowUp'
              ? (index - 1 + items.length) % items.length
              : null;
    if (next !== null) {
      event.preventDefault();
      items[next]?.focus();
    }
  }

  async function organize(session: ConversationSession, projectId: string | null, pinned: boolean) {
    const result = await onSetOrganization?.(session.conversationSessionId, {
      projectId,
      pinned,
      expectedVersion: session.version,
    });
    if (result?.outcome === 'saved') closeMenu();
  }

  // 新输入框挂载后聚焦并全选原标题，便于直接覆盖输入。
  useEffect(() => {
    if (!editingId) return;
    titleInputRef.current?.focus();
    titleInputRef.current?.select();
  }, [editingId]);

  function startTitleEdit(session: ConversationSession) {
    cancelTitleEdit.current = false;
    setDraftTitle(session.title);
    setEditingId(session.conversationSessionId);
  }

  // 统一由失焦路径保存，确保 Enter 不会和 blur 各提交一次。
  async function finishTitleEdit(session: ConversationSession) {
    if (savingTitle) return;
    if (cancelTitleEdit.current) {
      cancelTitleEdit.current = false;
      setEditingId(null);
      return;
    }
    const title = draftTitle.trim();
    if (!title || title === session.title) {
      setEditingId(null);
      return;
    }

    // 上层返回 false 时保留草稿输入态，允许用户修正后重试。
    setSavingTitle(true);
    const shouldClose = await onRename(session.conversationSessionId, title);
    setSavingTitle(false);
    if (shouldClose) setEditingId(null);
  }

  function handleTitleKeyDown(event: KeyboardEvent<HTMLInputElement>) {
    if (event.nativeEvent.isComposing) return;
    if (event.key === ENTER_KEY) {
      event.preventDefault();
      event.currentTarget.blur();
    } else if (event.key === ESCAPE_KEY) {
      event.preventDefault();
      cancelTitleEdit.current = true;
      event.currentTarget.blur();
    }
  }

  return (
    <nav>
      {sessions.map((session) => {
        const status = session.latestExecutionStatus ?? session.execution?.status;
        if (editingId === session.conversationSessionId) {
          return (
            <input
              ref={titleInputRef}
              className="session-title-input"
              key={session.conversationSessionId}
              aria-label={`${EDIT_TITLE_LABEL_PREFIX}${session.title}${EDIT_TITLE_LABEL_SUFFIX}`}
              value={draftTitle}
              maxLength={MAX_TITLE_LENGTH}
              disabled={savingTitle}
              onChange={(event) => setDraftTitle(event.target.value)}
              onKeyDown={handleTitleKeyDown}
              onBlur={() => void finishTitleEdit(session)}
            />
          );
        }
        return (
          <div className="session-row" key={session.conversationSessionId}>
            <button
              className={[
                'session-link',
                activeSessionId === session.conversationSessionId ? 'active' : '',
              ]
                .filter(Boolean)
                .join(' ')}
              key={session.conversationSessionId}
              type="button"
              title={session.title}
              aria-current={activeSessionId === session.conversationSessionId ? 'page' : undefined}
              onClick={() => onOpen(session.conversationSessionId)}
              onDoubleClick={() => {
                if (!busy) startTitleEdit(session);
              }}
            >
              <span className="session-link-copy">
                <span className="session-link-title">{session.title}</span>
                {showOrigin && (
                  <small className="session-origin">
                    {projects.find((p) => p.projectId === session.projectId)?.name ?? '未分组'}
                  </small>
                )}
              </span>
              {status && STATUS_LABELS[status] && (
                <small className={`session-short-status ${status}`}>{STATUS_LABELS[status]}</small>
              )}
            </button>
            {onSetOrganization && (
              <button
                className="icon-button session-actions"
                type="button"
                disabled={busy}
                aria-label={`${session.title}的更多操作`}
                aria-haspopup="menu"
                aria-expanded={menuId === session.conversationSessionId}
                aria-controls={menuId === session.conversationSessionId ? menuElementId : undefined}
                onClick={(event) => {
                  menuTrigger.current = event.currentTarget;
                  setMenuId(
                    menuId === session.conversationSessionId ? null : session.conversationSessionId,
                  );
                  setChoosingProject(false);
                }}
                onKeyDown={(event) => {
                  if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
                    event.preventDefault();
                    menuTrigger.current = event.currentTarget;
                    setChoosingProject(false);
                    setMenuId(session.conversationSessionId);
                  }
                }}
              >
                <MoreHorizontal size={16} />
              </button>
            )}
            {menuId === session.conversationSessionId && (
              <div
                ref={menuRef}
                id={menuElementId}
                className="session-menu"
                role="menu"
                aria-label={choosingProject ? '移动到项目' : `${session.title}的会话操作`}
                aria-busy={busy}
                style={menuPosition}
                onKeyDown={handleMenuKeyDown}
              >
                {choosingProject ? (
                  <>
                    <button
                      className="session-menu-back"
                      type="button"
                      role="menuitem"
                      tabIndex={-1}
                      onClick={returnToActions}
                    >
                      <ArrowLeft size={16} aria-hidden="true" />
                      <span>移动到项目</span>
                    </button>
                    <div className="session-menu-separator" role="separator" />
                    <div className="session-menu-destinations" role="group" aria-label="目标项目">
                      {[{ projectId: null, name: '未分组' }, ...projects].map((project) => {
                        const current = project.projectId === session.projectId;
                        return (
                          <button
                            key={project.projectId ?? 'ungrouped'}
                            type="button"
                            role="menuitemradio"
                            tabIndex={-1}
                            aria-checked={current}
                            aria-disabled={current || busy}
                            disabled={busy}
                            title={project.name}
                            onClick={() => {
                              if (!current)
                                void organize(
                                  session,
                                  project.projectId,
                                  session.pinnedAt !== null,
                                );
                            }}
                          >
                            {project.projectId ? (
                              <Folder size={16} aria-hidden="true" />
                            ) : (
                              <Inbox size={16} aria-hidden="true" />
                            )}
                            <span>{project.name}</span>
                            {current && (
                              <>
                                <Check size={15} aria-hidden="true" />
                                <span className="sr-only">当前项目</span>
                              </>
                            )}
                          </button>
                        );
                      })}
                    </div>
                    {!projects.length && (
                      <p className="session-menu-hint">暂无项目，可在侧栏「项目」旁新建</p>
                    )}
                  </>
                ) : (
                  <>
                    <button
                      type="button"
                      role="menuitem"
                      tabIndex={-1}
                      disabled={busy}
                      onClick={() =>
                        void organize(session, session.projectId, session.pinnedAt === null)
                      }
                    >
                      {session.pinnedAt === null ? (
                        <Pin size={16} aria-hidden="true" />
                      ) : (
                        <PinOff size={16} aria-hidden="true" />
                      )}
                      {session.pinnedAt === null ? '置顶会话' : '取消置顶'}
                    </button>
                    <button
                      type="button"
                      role="menuitem"
                      tabIndex={-1}
                      disabled={busy}
                      onClick={() => {
                        closeMenu();
                        startTitleEdit(session);
                      }}
                    >
                      <Pencil size={16} aria-hidden="true" />
                      重命名会话
                    </button>
                    <div className="session-menu-separator" role="separator" />
                    <button
                      ref={moveAction}
                      type="button"
                      role="menuitem"
                      tabIndex={-1}
                      disabled={busy}
                      onClick={() => setChoosingProject(true)}
                    >
                      <FolderInput size={16} aria-hidden="true" />
                      <span>移动到项目</span>
                      <ChevronRight size={15} aria-hidden="true" />
                    </button>
                  </>
                )}
              </div>
            )}
          </div>
        );
      })}
    </nav>
  );
}
