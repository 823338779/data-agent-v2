import { ScheduledTask } from '../../api/scheduledTaskClient';

interface Props {
  tasks: ScheduledTask[];
  selectedId: string | null;
  includeDeleted: boolean;
  onIncludeDeleted: (value: boolean) => void;
  onSelect: (task: ScheduledTask) => void;
  onCreate: () => void;
  onExecute: (task: ScheduledTask) => void;
  onDelete: (task: ScheduledTask) => void;
}

function time(value: string | null): string {
  return value ? new Date(value).toLocaleString('zh-CN') : '—';
}

export function ScheduledTaskList({
  tasks,
  selectedId,
  includeDeleted,
  onIncludeDeleted,
  onSelect,
  onCreate,
  onExecute,
  onDelete,
}: Props) {
  return (
    <aside className="scheduler-task-list">
      <div className="scheduler-list-heading">
        <div>
          <p className="scheduler-eyebrow">AUTOMATIONS</p>
          <h1>定时任务</h1>
        </div>
        <button className="scheduler-primary" onClick={onCreate}>
          新建任务
        </button>
      </div>
      <label className="scheduler-archive-filter">
        <input
          type="checkbox"
          checked={includeDeleted}
          onChange={(event) => onIncludeDeleted(event.target.checked)}
        />
        显示已归档任务
      </label>
      <div className="scheduler-task-scroll">
        {tasks.length === 0 && <p className="scheduler-empty">还没有任务，先创建一个。</p>}
        {tasks.map((task) => (
          <article
            key={task.id}
            className={`scheduler-task-card ${selectedId === task.id ? 'selected' : ''}`}
          >
            <button className="scheduler-task-select" onClick={() => onSelect(task)}>
              <span className="scheduler-task-title">
                <strong>{task.name}</strong>
                <i className={`scheduler-state ${task.assignmentState.toLowerCase()}`}>
                  {task.assignmentState}
                </i>
              </span>
              <span>
                {task.scheduleType === 'CRON'
                  ? `${task.cronExpression} · ${task.zoneId}`
                  : '仅手动执行'}
              </span>
              <span>节点：{task.ownerNodeId ?? '等待分配'}</span>
              <span>下次触发：{time(task.nextFireAt)}</span>
              <span>
                最近执行：
                {task.lastExecution
                  ? `${task.lastExecution.status} · ${time(task.lastExecution.occurredAt)}`
                  : '暂无'}
              </span>
            </button>
            <div className="scheduler-card-actions">
              <span>{task.deleted ? '已归档' : task.enabled ? '已启用' : '已停用'}</span>
              {!task.deleted && (
                <>
                  <button onClick={() => onExecute(task)}>立即执行</button>
                  <button className="danger-link" onClick={() => onDelete(task)}>
                    归档
                  </button>
                </>
              )}
            </div>
          </article>
        ))}
      </div>
    </aside>
  );
}
