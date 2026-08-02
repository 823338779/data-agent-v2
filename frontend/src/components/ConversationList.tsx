import { DragEvent, KeyboardEvent, useRef, useState } from 'react';
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

const DRAG_DATA_TYPE = 'text/plain';
const DROP_BEFORE = 'before';
const DROP_AFTER = 'after';
const DRAG_PREVIEW_CLASS = 'session-drag-preview';
type DropPosition = typeof DROP_BEFORE | typeof DROP_AFTER;

interface DragState {
  sourceId: string;
  targetId: string | null;
  position: DropPosition | null;
}

const IDLE_DRAG_STATE: DragState = {
  sourceId: '',
  targetId: null,
  position: null,
};

/** 单行会话导航，同时提供拖拽及 Alt+方向键重排。 */
export function ConversationList({
  sessions,
  activeSessionId,
  onOpen,
  onReorder,
}: ConversationListProps) {
  const draggedId = useRef<string | null>(null);
  const [dragState, setDragState] = useState<DragState>(IDLE_DRAG_STATE);

  function move(sessionId: string, offset: number) {
    const ids = sessions.map((session) => session.conversationSessionId);
    const from = ids.indexOf(sessionId);
    const to = Math.max(0, Math.min(ids.length - 1, from + offset));
    if (from < 0 || from === to) return;
    ids.splice(to, 0, ids.splice(from, 1)[0]);
    onReorder(ids);
  }

  function reorderFromDrag(targetId: string, position: DropPosition) {
    const sourceId = draggedId.current;
    if (!sourceId || sourceId === targetId) return;
    const ids = sessions.map((session) => session.conversationSessionId);
    const from = ids.indexOf(sourceId);
    const previousIds = [...ids];
    const [source] = ids.splice(from, 1);
    const target = ids.indexOf(targetId);
    const insertionIndex = position === DROP_AFTER ? target + 1 : target;
    ids.splice(insertionIndex, 0, source);
    if (ids.every((id, index) => id === previousIds[index])) return;
    onReorder(ids);
  }

  function handleKeyDown(event: KeyboardEvent<HTMLButtonElement>, sessionId: string) {
    if (!event.altKey || !['ArrowUp', 'ArrowDown'].includes(event.key)) return;
    event.preventDefault();
    move(sessionId, event.key === 'ArrowUp' ? -1 : 1);
  }

  function getDropPosition(event: DragEvent<HTMLButtonElement>): DropPosition {
    const bounds = event.currentTarget.getBoundingClientRect();
    return event.clientY < bounds.top + bounds.height / 2 ? DROP_BEFORE : DROP_AFTER;
  }

  // 指针越过行中点时切换插入边，避免目标位置含糊。
  function updateDropTarget(event: DragEvent<HTMLButtonElement>, targetId: string) {
    event.preventDefault();
    event.dataTransfer.dropEffect = 'move';
    const position = getDropPosition(event);
    setDragState((current) =>
      current.targetId === targetId && current.position === position
        ? current
        : { ...current, targetId, position },
    );
  }

  // 使用轻量标题浮层替代整行浏览器拖影，保持长列表拖动时的视觉焦点。
  function setDragPreview(event: DragEvent<HTMLButtonElement>, title: string) {
    const preview = document.createElement('div');
    preview.className = DRAG_PREVIEW_CLASS;
    preview.textContent = title;
    document.body.appendChild(preview);
    event.dataTransfer.setDragImage(preview, 16, 16);
    requestAnimationFrame(() => preview.remove());
  }

  function finishDrag() {
    draggedId.current = null;
    setDragState(IDLE_DRAG_STATE);
  }

  return (
    <nav>
      {sessions.map((session) => {
        const status = session.activityStatus ?? session.execution?.status;
        return (
          <button
            className={[
              'session-link',
              activeSessionId === session.conversationSessionId ? 'active' : '',
              dragState.sourceId === session.conversationSessionId ? 'dragging' : '',
              dragState.targetId === session.conversationSessionId && dragState.position
                ? `drop-${dragState.position}`
                : '',
            ]
              .filter(Boolean)
              .join(' ')}
            key={session.conversationSessionId}
            type="button"
            draggable
            onClick={() => onOpen(session.conversationSessionId)}
            onDragStart={(event) => {
              draggedId.current = session.conversationSessionId;
              setDragState({
                sourceId: session.conversationSessionId,
                targetId: null,
                position: null,
              });
              event.dataTransfer.effectAllowed = 'move';
              event.dataTransfer.setData(DRAG_DATA_TYPE, session.conversationSessionId);
              setDragPreview(event, session.title);
            }}
            onDragOver={(event) => updateDropTarget(event, session.conversationSessionId)}
            onDrop={(event) => {
              event.preventDefault();
              const position = getDropPosition(event);
              reorderFromDrag(session.conversationSessionId, position);
              finishDrag();
            }}
            onDragEnd={finishDrag}
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
