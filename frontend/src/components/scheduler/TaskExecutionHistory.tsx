import { useEffect, useState } from 'react';
import {
  ScheduledTask,
  scheduledTaskClient,
  TaskExecutionSummary,
} from '../../api/scheduledTaskClient';

interface Props {
  task: ScheduledTask;
  refreshKey: number;
  selectedId: string | null;
  onSelect: (execution: TaskExecutionSummary) => void;
  onAuthenticationRequired: () => void;
}

export function TaskExecutionHistory({
  task,
  refreshKey,
  selectedId,
  onSelect,
  onAuthenticationRequired,
}: Props) {
  const [items, setItems] = useState<TaskExecutionSummary[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    void scheduledTaskClient
      .executionHistory(task.id)
      .then((values) => {
        if (active) setItems(values);
      })
      .catch((failure: unknown) => {
        if (!active) return;
        if (failure instanceof Error && 'status' in failure && failure.status === 401) {
          onAuthenticationRequired();
        } else {
          setError('执行历史加载失败。');
        }
      });
    return () => {
      active = false;
    };
  }, [onAuthenticationRequired, refreshKey, task.id]);

  return (
    <section className="scheduler-history">
      <header>
        <div>
          <p className="scheduler-eyebrow">EXECUTIONS</p>
          <h2>{task.name}</h2>
        </div>
        <span>每次触发均保留独立快照与 Agent run</span>
      </header>
      {error && <p className="scheduler-error">{error}</p>}
      <div className="scheduler-history-list">
        {items.length === 0 && <p className="scheduler-empty">暂无执行记录。</p>}
        {items.map((execution) => (
          <button
            key={execution.id}
            className={selectedId === execution.id ? 'selected' : ''}
            onClick={() => onSelect(execution)}
          >
            <strong>{execution.status}</strong>
            <span>{execution.triggerType}</span>
            <span>{new Date(execution.createdAt).toLocaleString('zh-CN')}</span>
            <code>{execution.id.slice(0, 8)}</code>
          </button>
        ))}
      </div>
    </section>
  );
}
