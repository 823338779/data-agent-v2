import { AgentExecutionStatus } from '../domain/agentPrototype';

interface ConversationStatusProps {
  status: AgentExecutionStatus;
  onCancel: () => void;
  onRetry: () => void;
}

const STATUS_LABELS: Record<AgentExecutionStatus, string> = {
  idle: '就绪',
  queued: '排队中',
  running: '处理中',
  waiting_for_user: '等待操作',
  waiting_confirmation: '等待确认',
  done: '已完成',
  cancelled: '已取消',
  failed: '失败',
  lost: '已失联',
};

/** 展示执行状态及该状态允许的最小控制集合。 */
export function ConversationStatus({ status, onCancel, onRetry }: ConversationStatusProps) {
  const cancellable = status === 'queued' || status === 'running';
  const waiting = status === 'waiting_for_user' || status === 'waiting_confirmation';
  return (
    <div className="conversation-status-block">
      <span className={`execution-status ${status}`}>{STATUS_LABELS[status]}</span>
      {cancellable && (
        <button type="button" className="run-control" onClick={onCancel}>
          取消运行
        </button>
      )}
      {status === 'failed' && (
        <button type="button" className="run-control" onClick={onRetry}>
          重试运行
        </button>
      )}
      {status === 'cancelled' && <p>运行已取消，以下内容可能不完整。</p>}
      {waiting && <p className="waiting-card">等待你的操作</p>}
    </div>
  );
}
