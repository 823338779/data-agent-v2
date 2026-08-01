import { useCallback, useEffect, useRef, useState } from 'react';
import {
  ScheduledTask,
  ScheduledTaskApiError,
  scheduledTaskClient,
  TaskExecutionSummary,
} from '../../api/scheduledTaskClient';
import { ScheduledTaskEditor } from './ScheduledTaskEditor';
import { ScheduledTaskList } from './ScheduledTaskList';
import { TaskExecutionDetail } from './TaskExecutionDetail';
import { TaskExecutionHistory } from './TaskExecutionHistory';

interface Props {
  onAuthenticationRequired: () => void;
  onOpenAgent: () => void;
}

export function ScheduledTaskWorkspace({ onAuthenticationRequired, onOpenAgent }: Props) {
  const [tasks, setTasks] = useState<ScheduledTask[]>([]);
  const [selected, setSelected] = useState<ScheduledTask | null>(null);
  const [editing, setEditing] = useState<ScheduledTask | null | undefined>(undefined);
  const [execution, setExecution] = useState<TaskExecutionSummary | null>(null);
  const [includeDeleted, setIncludeDeleted] = useState(false);
  const [refreshKey, setRefreshKey] = useState(0);
  const [notice, setNotice] = useState<string | null>(null);
  const executeKeys = useRef(new Map<string, string>());

  const load = useCallback(async () => {
    try {
      const values = await scheduledTaskClient.list(includeDeleted);
      setTasks(values);
      setSelected((current) => values.find((item) => item.id === current?.id) ?? values[0] ?? null);
    } catch (failure) {
      if (failure instanceof Error && 'status' in failure && failure.status === 401) {
        onAuthenticationRequired();
      } else setNotice('任务列表加载失败，请检查网络连接。');
    }
  }, [includeDeleted, onAuthenticationRequired]);

  useEffect(() => {
    void load();
  }, [load]);

  async function execute(task: ScheduledTask) {
    const key = executeKeys.current.get(task.id) ?? crypto.randomUUID();
    executeKeys.current.set(task.id, key);
    setNotice(null);
    try {
      const accepted = await scheduledTaskClient.execute(task.id, key);
      executeKeys.current.delete(task.id);
      setSelected(task);
      setExecution({
        id: accepted.executionId,
        taskId: task.id,
        triggerType: 'MANUAL',
        status: 'PENDING',
        createdAt: new Date().toISOString(),
        startedAt: null,
        completedAt: null,
        errorCode: null,
        errorMessage: null,
      });
      setRefreshKey((value) => value + 1);
      setNotice('执行已受理。');
    } catch (failure) {
      if (failure instanceof Error && 'status' in failure && failure.status === 401) {
        onAuthenticationRequired();
      } else if (failure instanceof ScheduledTaskApiError) {
        setNotice(failure.userMessage);
      } else {
        setNotice('网络失败；再次点击会复用同一个幂等键。');
      }
    }
  }

  async function remove(task: ScheduledTask) {
    if (
      !window.confirm(
        `归档“${task.name}”？任务定义会软删除，当前执行会收到取消请求，历史记录仍可查询。`,
      )
    )
      return;
    try {
      await scheduledTaskClient.remove(task.id);
      setNotice('任务已归档，历史执行仍然保留。');
      await load();
    } catch (failure) {
      if (failure instanceof Error && 'status' in failure && failure.status === 401) {
        onAuthenticationRequired();
      } else setNotice('归档失败，请稍后重试。');
    }
  }

  return (
    <div className="scheduler-workspace">
      <ScheduledTaskList
        tasks={tasks}
        selectedId={selected?.id ?? null}
        includeDeleted={includeDeleted}
        onIncludeDeleted={setIncludeDeleted}
        onSelect={(task) => {
          setSelected(task);
          setExecution(null);
          setEditing(undefined);
        }}
        onCreate={() => setEditing(null)}
        onExecute={(task) => void execute(task)}
        onDelete={(task) => void remove(task)}
      />
      <main className="scheduler-main">
        {notice && <div className="scheduler-notice">{notice}</div>}
        {editing !== undefined ? (
          <ScheduledTaskEditor
            task={editing}
            onSaved={(task) => {
              setEditing(undefined);
              setSelected(task);
              setNotice('任务定义已保存。');
              void load();
            }}
            onCancel={() => setEditing(undefined)}
            onAuthenticationRequired={onAuthenticationRequired}
            onOpenAgent={onOpenAgent}
          />
        ) : selected ? (
          <>
            <div className="scheduler-task-toolbar">
              <div>
                <strong>{selected.name}</strong>
                <span>{selected.instruction}</span>
              </div>
              {!selected.deleted && <button onClick={() => setEditing(selected)}>编辑任务</button>}
            </div>
            <div className="scheduler-detail-grid">
              <TaskExecutionHistory
                task={selected}
                refreshKey={refreshKey}
                selectedId={execution?.id ?? null}
                onSelect={setExecution}
                onAuthenticationRequired={onAuthenticationRequired}
              />
              {execution ? (
                <TaskExecutionDetail
                  executionId={execution.id}
                  onAuthenticationRequired={onAuthenticationRequired}
                  onChanged={() => setRefreshKey((value) => value + 1)}
                />
              ) : (
                <section className="scheduler-empty-detail">
                  <strong>选择一次执行</strong>
                  <span>查看 scheduler、Agent、tool 与产物的合并时间线。</span>
                </section>
              )}
            </div>
          </>
        ) : (
          <section className="scheduler-empty-detail">
            <strong>创建第一个定时任务</strong>
            <span>周期调度和独立执行指令会分别保存，运行历史不会互相覆盖。</span>
            <button className="scheduler-primary" onClick={() => setEditing(null)}>
              新建任务
            </button>
          </section>
        )}
      </main>
    </div>
  );
}
