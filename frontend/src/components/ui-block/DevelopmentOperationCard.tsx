import { useEffect, useMemo, useRef, useState } from 'react';
import {
  createDevelopmentClient,
  DevelopmentError,
  type DevelopmentClient,
  type DevelopmentOperation,
} from '../../api/developmentClient';
import { createIdempotencyKey } from '../../api/idempotencyKey';

const RUNNING = 'RUNNING';
const SUCCEEDED = 'SUCCEEDED';
const UNKNOWN = 'RESULT_UNKNOWN';
const BLOCKED = 'BLOCKED';
const FAILED = 'FAILED';
const SAVE = 'SAVE';
const PREVIEW = 'PREVIEW';
const DELIVER = 'DELIVER';
const END_PHASE = {
  SAVE: 'PUSHED',
  PREVIEW: 'DEPLOYED',
  DELIVER: 'RELEASED',
  CREATE_BRANCH: 'PUSHED',
};
const LABELS = {
  saved: '已保存',
  deployed: '部署成功',
  released: '发布成功',
  created: '分支已创建',
  blocked: '操作受阻',
  unknown: '结果待核实',
  failed: '操作失败',
  running: '操作进行中',
  loading: '读取操作中',
  retry: '核对并恢复',
  refresh: '刷新操作',
  conflict: '状态版本已变化，请核对最新状态后重新恢复',
  background: '操作由后台持续推进，关闭页面不会取消。',
  recovery: '恢复沿用原输入；更换源码、配置或交付范围需要新操作。',
};
const POLL_INTERVAL = 3000;

/** 保存、构建部署和正式发布分别检查终点，未知状态绝不推断为成功。 */
function label(value: DevelopmentOperation): string {
  if (value.status === SUCCEEDED && value.phase === END_PHASE[value.type]) {
    return value.type === SAVE
      ? LABELS.saved
      : value.type === PREVIEW
        ? LABELS.deployed
        : value.type === DELIVER
          ? LABELS.released
          : LABELS.created;
  }
  return value.status === BLOCKED
    ? LABELS.blocked
    : value.status === UNKNOWN
      ? LABELS.unknown
      : value.status === FAILED
        ? LABELS.failed
        : LABELS.running;
}

/** 每次打开读取同一持久记录；轮询生命周期独立于会话 SSE 和模型执行。 */
export function DevelopmentOperationCard({
  sessionId,
  operationId,
  client,
  initial,
  onUpdated,
}: {
  sessionId: string;
  operationId: string;
  client?: DevelopmentClient;
  initial?: DevelopmentOperation;
  onUpdated?: () => void;
}) {
  const api = useMemo(() => client ?? createDevelopmentClient(), [client]);
  const [value, setValue] = useState<DevelopmentOperation | null>(initial ?? null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [refresh, setRefresh] = useState(0);
  const locked = useRef(false);
  const generation = useRef(0);
  const recovery = useRef<{ expectedVersion: number; idempotencyKey: string } | null>(null);
  const updated = useRef(onUpdated);
  updated.current = onUpdated;
  useEffect(() => {
    const abort = new AbortController();
    const token = ++generation.current;
    let timer: ReturnType<typeof setTimeout> | undefined;
    async function read() {
      try {
        const result = await api.operation(sessionId, operationId, abort.signal);
        if (abort.signal.aborted || token !== generation.current) return;
        setValue((previous) =>
          !previous || result.version >= previous.version ? result : previous,
        );
        setError(null);
        if (result.status === RUNNING || result.status === UNKNOWN)
          timer = setTimeout(() => void read(), POLL_INTERVAL);
        else updated.current?.();
      } catch (reason) {
        if (!abort.signal.aborted && token === generation.current)
          setError(reason instanceof Error ? reason.message : LABELS.unknown);
      }
    }
    void read();
    return () => {
      abort.abort();
      if (timer) clearTimeout(timer);
      generation.current++;
    };
  }, [api, sessionId, operationId, refresh]);
  async function recover() {
    if (!value || locked.current) return;
    locked.current = true;
    setBusy(true);
    const token = ++generation.current;
    const request = recovery.current ?? {
      expectedVersion: value.version,
      idempotencyKey: createIdempotencyKey(),
    };
    recovery.current = request;
    try {
      const result = await api.recover(
        sessionId,
        operationId,
        request,
        new AbortController().signal,
      );
      if (token !== generation.current) return;
      setValue(result);
      setError(null);
      recovery.current = null;
      setRefresh((current) => current + 1);
    } catch (reason) {
      if (token !== generation.current) return;
      if (reason instanceof DevelopmentError && reason.status === 409) {
        recovery.current = null;
        setError(LABELS.conflict);
      } else setError(reason instanceof Error ? reason.message : LABELS.unknown);
    } finally {
      locked.current = false;
      setBusy(false);
    }
  }
  return (
    <section className="git-change-card" aria-label="开发操作">
      <strong role="status">{value ? label(value) : LABELS.loading}</strong>
      <p>
        <code>{operationId}</code> · {value?.phase}
      </p>
      {value?.pushedSha && (
        <p>
          Git SHA：<code>{value.pushedSha}</code>
        </p>
      )}
      {value?.mergeSha && (
        <p>
          合并 SHA：<code>{value.mergeSha}</code>
        </p>
      )}
      <ul>
        {value?.buildIds.map((id) => (
          <li key={id}>
            <code>{id}</code>
          </li>
        ))}
      </ul>
      {value?.error && (
        <p role="alert">
          {value.error.code}：{value.error.message}
        </p>
      )}
      {error && <p role="alert">{error}</p>}
      <p>{LABELS.background}</p>
      {/* schema 缺失等不可恢复错误必须使用新操作，不能重放旧配置快照。 */}
      {value && value.status !== SUCCEEDED && value.error?.retryable !== false && (
        <>
          <p>{LABELS.recovery}</p>
          <button type="button" disabled={busy} onClick={() => void recover()}>
            {LABELS.retry}
          </button>
        </>
      )}
      <button type="button" disabled={busy} onClick={() => setRefresh((current) => current + 1)}>
        {LABELS.refresh}
      </button>
    </section>
  );
}
