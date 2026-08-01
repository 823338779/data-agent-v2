import { DragEvent, KeyboardEvent, useRef } from 'react';
import { ConversationSession } from '../domain/agentPrototype';

interface ConversationListProps {
  sessions: ConversationSession[];
  activeSessionId?: string;
  onOpen: (conversationSessionId: string) => void;
  onReorder: (conversationSessionIds: string[]) => void;
}

const STATUS_LABELS: Record<string, string> = {
  queued: '排队中',
  running: '处理中',
  waiting_for_user: '待操作',
  waiting_confirmation: '待确认',
  failed: '失败',
  cancelled: '已取消',
};

/** 单行会话导航，同时提供拖拽及 Alt+方向键重排。 */
export function ConversationList({
  sessions,
  activeSessionId,
  onOpen,
  onReorder,
}: ConversationListProps) {
  const draggedId = useRef<string | null>(null);

  function move(sessionId: string, offset: number) {
    const ids = sessions.map((session) => session.conversationSessionId);
    const from = ids.indexOf(sessionId);
    const to = Math.max(0, Math.min(ids.length - 1, from + offset));
    if (from < 0 || from === to) return;
    ids.splice(to, 0, ids.splice(from, 1)[0]);
    onReorder(ids);
  }

  function reorderFromDrag(targetId: string) {
    const sourceId = draggedId.current;
    draggedId.current = null;
    if (!sourceId || sourceId === targetId) return;
    const ids = sessions.map((session) => session.conversationSessionId);
    const from = ids.indexOf(sourceId);
    const to = ids.indexOf(targetId);
    ids.splice(to, 0, ids.splice(from, 1)[0]);
    onReorder(ids);
  }

  function handleKeyDown(event: KeyboardEvent<HTMLButtonElement>, sessionId: string) {
    if (!event.altKey || !['ArrowUp', 'ArrowDown'].includes(event.key)) return;
    event.preventDefault();
    move(sessionId, event.key === 'ArrowUp' ? -1 : 1);
  }

  function allowDrop(event: DragEvent<HTMLButtonElement>) {
    event.preventDefault();
  }

  return (
    <nav>
      {sessions.map((session) => {
        const status = session.activityStatus ?? session.execution?.status;
        return (
          <button
            className={
              activeSessionId === session.conversationSessionId
                ? 'session-link active'
                : 'session-link'
            }
            key={session.conversationSessionId}
            type="button"
            draggable
            onClick={() => onOpen(session.conversationSessionId)}
            onDragStart={() => {
              draggedId.current = session.conversationSessionId;
            }}
            onDragOver={allowDrop}
            onDrop={() => reorderFromDrag(session.conversationSessionId)}
            onKeyDown={(event) => handleKeyDown(event, session.conversationSessionId)}
          >
            <span className="session-link-title">{session.title}</span>
            {session.unread ? (
              <span className="unread-dot" aria-label="未读" />
            ) : (
              status &&
              STATUS_LABELS[status] && (
                <small className={`session-short-status ${status}`}>{STATUS_LABELS[status]}</small>
              )
            )}
          </button>
        );
      })}
    </nav>
  );
}
