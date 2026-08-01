import { FormEvent, useEffect, useMemo, useState } from 'react';
import { BackendApiError } from '../../api/backendAgentClient';
import {
  createMetricIdentityClient,
  isSelectableMetricIdentity,
  MetricIdentitySummary,
} from '../../api/metricIdentityClient';
import {
  ScheduledTask,
  ScheduledTaskApiError,
  ScheduledTaskDraft,
  scheduledTaskClient,
  ScheduleType,
} from '../../api/scheduledTaskClient';

interface Props {
  task: ScheduledTask | null;
  onSaved: (task: ScheduledTask) => void;
  onCancel: () => void;
  onAuthenticationRequired: () => void;
  onOpenAgent: () => void;
}

const presets = [
  { label: '每小时', value: '0 0 * * * *' },
  { label: '每天 09:00', value: '0 0 9 * * *' },
  { label: '工作日 09:00', value: '0 0 9 * * MON-FRI' },
];

export function ScheduledTaskEditor({
  task,
  onSaved,
  onCancel,
  onAuthenticationRequired,
  onOpenAgent,
}: Props) {
  const identitiesClient = useMemo(() => createMetricIdentityClient(), []);
  const [identities, setIdentities] = useState<MetricIdentitySummary[]>([]);
  const [name, setName] = useState('');
  const [instruction, setInstruction] = useState('');
  const [metricIdentityId, setMetricIdentityId] = useState('');
  const [scheduleType, setScheduleType] = useState<ScheduleType>('CRON');
  const [cronExpression, setCronExpression] = useState(presets[1].value);
  const [zoneId, setZoneId] = useState('Asia/Shanghai');
  const [enabled, setEnabled] = useState(true);
  const [advanced, setAdvanced] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [conflict, setConflict] = useState(false);

  const activeIdentities = identities.filter(isSelectableMetricIdentity);

  useEffect(() => {
    void identitiesClient
      .list()
      .then((values) => {
        setIdentities(values);
        if (!task && !metricIdentityId) {
          setMetricIdentityId(values.find(isSelectableMetricIdentity)?.id ?? '');
        }
      })
      .catch((failure) => {
        if (failure instanceof BackendApiError && failure.status === 401) {
          onAuthenticationRequired();
        }
      });
  }, [identitiesClient, onAuthenticationRequired, task]);

  useEffect(() => {
    setName(task?.name ?? '');
    setInstruction(task?.instruction ?? '');
    setMetricIdentityId(task?.metricIdentityId ?? activeIdentities[0]?.id ?? '');
    setScheduleType(task?.scheduleType ?? 'CRON');
    setCronExpression(task?.cronExpression ?? presets[1].value);
    setZoneId(task?.zoneId ?? 'Asia/Shanghai');
    setEnabled(task?.enabled ?? true);
    setAdvanced(
      Boolean(task?.cronExpression && !presets.some((p) => p.value === task.cronExpression)),
    );
    setError(null);
    setConflict(false);
  }, [task]);

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!name.trim() || !instruction.trim() || !metricIdentityId) {
      setError('请填写名称、独立执行指令并选择可用的指标身份。');
      return;
    }
    if (scheduleType === 'CRON' && cronExpression.trim().split(/\s+/).length !== 6) {
      setError('Cron 需要六段：秒 分 时 日 月 星期。');
      return;
    }
    const draft: ScheduledTaskDraft = {
      name: name.trim(),
      instruction: instruction.trim(),
      metricIdentityId,
      scheduleType,
      cronExpression: scheduleType === 'CRON' ? cronExpression.trim() : null,
      zoneId: scheduleType === 'CRON' ? zoneId : null,
      enabled,
    };
    setSaving(true);
    setError(null);
    setConflict(false);
    try {
      const saved = task
        ? await scheduledTaskClient.update(task.id, draft, task.version)
        : await scheduledTaskClient.create(draft);
      onSaved(saved);
    } catch (failure) {
      if (failure instanceof BackendApiError && failure.status === 401) {
        onAuthenticationRequired();
      } else if (failure instanceof ScheduledTaskApiError) {
        setConflict(failure.status === 409);
        setError(failure.userMessage);
      } else {
        setError('网络连接失败，输入内容已保留，请稍后重试。');
      }
    } finally {
      setSaving(false);
    }
  }

  return (
    <form className="scheduler-editor" onSubmit={submit}>
      <header>
        <div>
          <p className="scheduler-eyebrow">{task ? 'EDIT TASK' : 'NEW TASK'}</p>
          <h2>{task ? '编辑任务' : '创建任务'}</h2>
        </div>
        <button type="button" onClick={onCancel}>
          关闭
        </button>
      </header>
      <label>
        任务名称
        <input value={name} onChange={(event) => setName(event.target.value)} maxLength={160} />
      </label>
      <label>
        独立执行指令
        <textarea
          value={instruction}
          onChange={(event) => setInstruction(event.target.value)}
          rows={6}
          placeholder="例如：汇总昨天的订单金额，按区域拆分并说明异常。"
        />
        <small>该指令会脱离当前聊天，作为一个独立 TASK 会话执行。</small>
      </label>
      <label>
        指标身份
        <select
          value={metricIdentityId}
          onChange={(event) => setMetricIdentityId(event.target.value)}
          disabled={activeIdentities.length === 0}
        >
          <option value="">请选择 ACTIVE 身份</option>
          {activeIdentities.map((identity) => (
            <option key={identity.id} value={identity.id}>
              {identity.displayName} · {identity.platformUsername}
            </option>
          ))}
        </select>
      </label>
      {activeIdentities.length === 0 && (
        <div className="scheduler-callout">
          <strong>没有可用的指标身份</strong>
          <span>请先在 Agent 对话的身份管理中创建并验证身份。</span>
          <button type="button" onClick={onOpenAgent}>
            前往 Agent 对话
          </button>
        </div>
      )}
      <fieldset>
        <legend>触发方式</legend>
        <label className="scheduler-radio">
          <input
            type="radio"
            checked={scheduleType === 'CRON'}
            onChange={() => setScheduleType('CRON')}
          />
          周期执行
        </label>
        <label className="scheduler-radio">
          <input
            type="radio"
            checked={scheduleType === 'MANUAL'}
            onChange={() => setScheduleType('MANUAL')}
          />
          仅手动执行
        </label>
      </fieldset>
      {scheduleType === 'CRON' && (
        <>
          <label>
            常用频率
            <select
              value={
                presets.some((preset) => preset.value === cronExpression) ? cronExpression : ''
              }
              onChange={(event) => {
                if (event.target.value) setCronExpression(event.target.value);
              }}
            >
              <option value="">自定义</option>
              {presets.map((preset) => (
                <option key={preset.value} value={preset.value}>
                  {preset.label}
                </option>
              ))}
            </select>
          </label>
          <button
            className="scheduler-advanced-toggle"
            type="button"
            onClick={() => setAdvanced((value) => !value)}
          >
            {advanced ? '收起高级 Cron' : '高级 Cron 编辑'}
          </button>
          {advanced && (
            <label>
              六段 Cron
              <input
                value={cronExpression}
                onChange={(event) => setCronExpression(event.target.value)}
                placeholder="秒 分 时 日 月 星期"
              />
            </label>
          )}
          <label>
            时区
            <select value={zoneId} onChange={(event) => setZoneId(event.target.value)}>
              {['Asia/Shanghai', 'UTC', 'Asia/Tokyo', 'Europe/London', 'America/New_York'].map(
                (zone) => (
                  <option key={zone}>{zone}</option>
                ),
              )}
            </select>
          </label>
        </>
      )}
      <label className="scheduler-check">
        <input
          type="checkbox"
          checked={enabled}
          onChange={(event) => setEnabled(event.target.checked)}
        />
        启用任务
      </label>
      {!enabled && (
        <p className="scheduler-help">停用只阻止未来周期，不会取消正在运行的 execution。</p>
      )}
      {error && (
        <div className="scheduler-error" role="alert">
          <span>{error}</span>
          {conflict && (
            <strong>服务器版本已变化；请刷新后再决定如何合并，当前输入不会被覆盖。</strong>
          )}
        </div>
      )}
      <footer>
        <button type="button" onClick={onCancel}>
          取消
        </button>
        <button className="scheduler-primary" disabled={saving || activeIdentities.length === 0}>
          {saving ? '正在保存…' : task ? '保存修改' : '创建任务'}
        </button>
      </footer>
    </form>
  );
}
