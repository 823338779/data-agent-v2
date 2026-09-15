import { KeyboardEvent, useEffect, useRef, useState } from 'react';
import { ConversationSession } from '../domain/agent';
import { MoreHorizontal } from 'lucide-react';
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
  const [destination, setDestination] = useState('');
  const menuTrigger = useRef<HTMLButtonElement | null>(null);

  function closeMenu() {
    setMenuId(null);
    menuTrigger.current?.focus();
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
          <div
            className="session-row"
            key={session.conversationSessionId}
            onKeyDown={(event) => {
              if (event.key === ESCAPE_KEY) {
                event.preventDefault();
                event.stopPropagation();
                closeMenu();
              }
            }}
          >
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
                aria-expanded={menuId === session.conversationSessionId}
                onClick={(event) => {
                  menuTrigger.current = event.currentTarget;
                  setMenuId(
                    menuId === session.conversationSessionId ? null : session.conversationSessionId,
                  );
                  setDestination(session.projectId ?? '');
                }}
              >
                <MoreHorizontal size={16} />
              </button>
            )}
            {menuId === session.conversationSessionId && (
              <div className="sidebar-menu">
                <button
                  type="button"
                  disabled={busy}
                  onClick={() =>
                    void organize(session, session.projectId, session.pinnedAt === null)
                  }
                >
                  {session.pinnedAt === null ? '置顶会话' : '取消置顶'}
                </button>
                <label>
                  移动到
                  <select
                    aria-label="目标项目"
                    disabled={busy}
                    value={destination}
                    onChange={(event) => setDestination(event.target.value)}
                  >
                    <option value="">未分组</option>
                    {projects.map((project) => (
                      <option key={project.projectId} value={project.projectId}>
                        {project.name}
                      </option>
                    ))}
                  </select>
                </label>
                <button
                  type="button"
                  disabled={busy || destination === (session.projectId ?? '')}
                  onClick={() =>
                    void organize(session, destination || null, session.pinnedAt !== null)
                  }
                >
                  确认移动
                </button>
                <button
                  type="button"
                  disabled={busy}
                  onClick={() => {
                    closeMenu();
                    startTitleEdit(session);
                  }}
                >
                  重命名会话
                </button>
                <button type="button" onClick={closeMenu}>
                  关闭菜单
                </button>
              </div>
            )}
          </div>
        );
      })}
    </nav>
  );
}
