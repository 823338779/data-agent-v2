import { KeyboardEvent, useEffect, useRef, useState } from 'react';
import { ConversationSession } from '../domain/agentPrototype';

interface ConversationListProps {
  sessions: ConversationSession[];
  activeSessionId?: string;
  onOpen: (conversationSessionId: string) => void;
  onRename: (conversationSessionId: string, title: string) => Promise<boolean>;
}

const STATUS_LABELS: Record<string, string> = {
  queued: '排队中',
  running: '处理中',
  waiting_for_user: '待操作',
  waiting_confirmation: '待确认',
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
}: ConversationListProps) {
  const titleInputRef = useRef<HTMLInputElement>(null);
  const cancelTitleEdit = useRef(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [draftTitle, setDraftTitle] = useState('');
  const [savingTitle, setSavingTitle] = useState(false);

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
          <button
            className={[
              'session-link',
              activeSessionId === session.conversationSessionId ? 'active' : '',
            ]
              .filter(Boolean)
              .join(' ')}
            key={session.conversationSessionId}
            type="button"
            onClick={() => onOpen(session.conversationSessionId)}
            onDoubleClick={() => startTitleEdit(session)}
          >
            <span className="session-link-title">{session.title}</span>
            {status && STATUS_LABELS[status] && (
              <small className={`session-short-status ${status}`}>{STATUS_LABELS[status]}</small>
            )}
          </button>
        );
      })}
    </nav>
  );
}
