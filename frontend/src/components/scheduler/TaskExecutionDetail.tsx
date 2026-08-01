import { useEffect, useMemo, useState } from 'react';
import {
  scheduledTaskClient,
  TaskExecutionDetail as Detail,
  TaskTimelineEvent,
} from '../../api/scheduledTaskClient';

interface Props {
  executionId: string;
  onAuthenticationRequired: () => void;
  onChanged: () => void;
}

function artifactIds(value: unknown, result = new Set<string>()): Set<string> {
  if (Array.isArray(value)) value.forEach((item) => artifactIds(item, result));
  else if (value && typeof value === 'object') {
    Object.entries(value as Record<string, unknown>).forEach(([key, item]) => {
      if (/artifact(Id|Ref)$/i.test(key) && typeof item === 'string') result.add(item);
      else artifactIds(item, result);
    });
  }
  return result;
}

export function TaskExecutionDetail({ executionId, onAuthenticationRequired, onChanged }: Props) {
  const [detail, setDetail] = useState<Detail | null>(null);
  const [events, setEvents] = useState<TaskTimelineEvent[]>([]);
  const [stream, setStream] = useState<'connected' | 'reconnecting'>('reconnecting');
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    setDetail(null);
    setEvents([]);
    void scheduledTaskClient
      .execution(executionId)
      .then(setDetail)
      .catch((failure: unknown) => {
        if (failure instanceof Error && 'status' in failure && failure.status === 401) {
          onAuthenticationRequired();
        } else setError('执行详情加载失败。');
      });
    const close = scheduledTaskClient.subscribe(
      executionId,
      (event) =>
        setEvents((current) => {
          const key = `${event.source}:${event.sourceSequence}`;
          if (current.some((item) => `${item.source}:${item.sourceSequence}` === key))
            return current;
          return [...current, event].sort(
            (left, right) =>
              left.occurredAt.localeCompare(right.occurredAt) ||
              left.source.localeCompare(right.source) ||
              left.sourceSequence - right.sourceSequence,
          );
        }),
      setStream,
    );
    return close;
  }, [executionId, onAuthenticationRequired]);

  const artifacts = useMemo(
    () => [
      ...events.reduce((result, event) => artifactIds(event.payload, result), new Set<string>()),
    ],
    [events],
  );
  const finalAnswer = [...events]
    .reverse()
    .find((event) => event.source === 'AGENT' && event.type === 'ASSISTANT_MESSAGE');

  async function cancel() {
    try {
      await scheduledTaskClient.cancel(executionId);
      setDetail((current) => (current ? { ...current, status: 'CANCEL_REQUESTED' } : current));
      onChanged();
    } catch (failure) {
      if (failure instanceof Error && 'status' in failure && failure.status === 401) {
        onAuthenticationRequired();
      } else setError('取消请求发送失败。');
    }
  }

  return (
    <section className="scheduler-execution-detail">
      <header>
        <div>
          <p className="scheduler-eyebrow">TIMELINE</p>
          <h2>执行详情</h2>
        </div>
        <span className={`scheduler-stream ${stream}`}>
          {stream === 'connected' ? '实时' : '重连中'}
        </span>
      </header>
      {detail && (
        <div className="scheduler-execution-meta">
          <span>状态：{detail.status}</span>
          <span>节点：{detail.ownerNodeId ?? detail.targetNodeId}</span>
          <span>Epoch：{detail.executionEpoch}</span>
          <span>Token：{detail.fencingToken}</span>
          {detail.links.session && (
            <a href={detail.links.session} target="_blank" rel="noreferrer">
              查看 TASK 会话
            </a>
          )}
          {['PENDING', 'RUNNING', 'CANCEL_REQUESTED'].includes(detail.status) && (
            <button onClick={() => void cancel()}>取消执行</button>
          )}
        </div>
      )}
      {error && <p className="scheduler-error">{error}</p>}
      {finalAnswer && (
        <article className="scheduler-final-answer">
          <strong>最终答案</strong>
          <p>{String(finalAnswer.payload.summary ?? 'Agent 已完成，但未返回文本摘要。')}</p>
        </article>
      )}
      {detail?.errorMessage && (
        <article className="scheduler-error">
          <strong>{detail.errorCode}</strong>
          <span>{detail.errorMessage}</span>
        </article>
      )}
      {artifacts.length > 0 && (
        <div className="scheduler-artifacts">
          <strong>产物</strong>
          {artifacts.map((id) => (
            <a key={id} href={`/api/v1/artifacts/${id}`} target="_blank" rel="noreferrer">
              {id}
            </a>
          ))}
        </div>
      )}
      <ol className="scheduler-timeline">
        {events.map((event) => (
          <li key={`${event.source}:${event.sourceSequence}`}>
            <span className={`scheduler-source ${event.source.toLowerCase()}`}>{event.source}</span>
            <div>
              <strong>{event.type}</strong>
              <time>{new Date(event.occurredAt).toLocaleString('zh-CN')}</time>
              <pre>{JSON.stringify(event.payload, null, 2)}</pre>
            </div>
          </li>
        ))}
      </ol>
    </section>
  );
}
