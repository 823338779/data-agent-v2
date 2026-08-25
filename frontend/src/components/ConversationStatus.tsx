import { AgentExecutionStatus } from '../domain/agentPrototype';

interface ConversationStatusProps {
  status: AgentExecutionStatus;
  onCancel: () => void;
}

const STATUS_LABELS: Record<AgentExecutionStatus, string> = {
  idle: '就绪',
  running: '处理中',
  done: '已完成',
  cancelled: '已取消',
  failed: '失败',
};

/** 展示执行状态及该状态允许的最小控制集合。 */
export function ConversationStatus({ status, onCancel }: ConversationStatusProps) {
  const cancellable = status === 'running';
  return (
    <div className="conversation-status-block">
      <span className={`execution-status ${status}`}>{STATUS_LABELS[status]}</span>
      {cancellable && (
        <button type="button" className="run-control" onClick={onCancel}>
          取消运行
        </button>
      )}
      {status === 'cancelled' && <p>运行已取消，以下内容可能不完整。</p>}
    </div>
  );
}
