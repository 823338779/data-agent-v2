import { useEffect, useMemo, useRef, useState } from 'react';
import {
  createDevelopmentClient,
  DevelopmentError,
  type DevelopmentClient,
  type DevelopmentOverview,
  type DevelopmentOperation,
  type SaveRequest,
  type DeliverRequest,
} from '../api/developmentClient';
import { createIdempotencyKey } from '../api/idempotencyKey';
import { DevelopmentOperationCard } from './ui-block/DevelopmentOperationCard';
import { DevelopmentFileEditor } from './DevelopmentFileEditor';

const CLEAN = 'READY';
const SAVE = 'save';
const PREVIEW = 'preview';
const DELIVER = 'deliver';
const LABELS = {
  save: '保存到 Git',
  preview: '构建部署',
  deliver: '合并并发布',
  refresh: '刷新开发状态',
  confirm: '确认完整范围并发布',
  consent: '我已核对整个共享分支的差异、源 SHA、main SHA 和配置',
  lost: '本地文件已丢失，未推送内容无法恢复；不能用空修改完成保存或构建。',
  undeployed: '当前分支尚未部署',
  pending: '已推送不等于已部署，构建状态与有效部署分别展示。',
  conflict: '输入或版本已变化，请刷新状态并重新确认。',
  unknown: '请求结果待核实，请重试原请求以找回原操作。',
  retry: '重试原请求',
  commit: '提交说明',
  scope: '完整共享分支交付范围',
  config: '固定配置摘要',
  previewSchema: '预览目标 schema',
  productionSchema: '正式目标 schema',
  edit: '编辑工程文件',
  close: '关闭文件编辑',
  changed: '当前有效部署已更新；已有操作卡片仍固定原构建版本。',
};

/** 冻结会话目标，独立展示本地、远端、构建、部署；仅显式确认后发起动作。 */
export function DevelopmentContextBar({
  sessionId,
  client,
}: {
  sessionId: string;
  client?: DevelopmentClient;
}) {
  const api = useMemo(() => client ?? createDevelopmentClient(), [client]);
  const [overview, setOverview] = useState<DevelopmentOverview | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [refresh, setRefresh] = useState(0);
  const [busy, setBusy] = useState(false);
  const [commitMessage, setCommitMessage] = useState('');
  const [review, setReview] = useState(false);
  const [confirmed, setConfirmed] = useState(false);
  const [editing, setEditing] = useState(false);
  // 浏览器尚未写入临时文件的输入不能参与 Git 保存或交付。
  const [editorDirty, setEditorDirty] = useState(false);
  const [deploymentChanged, setDeploymentChanged] = useState(false);
  const locked = useRef(false);
  const pending = useRef<
    | { action: typeof SAVE | typeof PREVIEW; input: SaveRequest }
    | { action: typeof DELIVER; input: DeliverRequest }
    | null
  >(null);
  const [pendingRetry, setPendingRetry] = useState(false);
  useEffect(() => {
    const abort = new AbortController();
    void api
      .overview(sessionId, abort.signal)
      .then((value) => {
        if (abort.signal.aborted) return;
        setOverview((previous) => {
          if (
            previous?.deliveryScope?.confirmedScopeDigest !==
            value.deliveryScope?.confirmedScopeDigest
          )
            setConfirmed(false);
          if (
            previous &&
            previous.currentDeployment?.activeBuildId !== value.currentDeployment?.activeBuildId
          )
            setDeploymentChanged(true);
          return value;
        });
        setError(null);
      })
      .catch((reason) => {
        if (abort.signal.aborted) return;
        if (reason instanceof DevelopmentError && reason.status === 404) {
          setOverview(null);
          return;
        }
        setError(reason instanceof Error ? reason.message : LABELS.unknown);
      });
    return () => abort.abort();
  }, [api, sessionId, refresh]);
  function accepted(operation: DevelopmentOperation) {
    setOverview((current) =>
      current
        ? {
            ...current,
            operations: [
              operation,
              ...current.operations.filter((item) => item.operationId !== operation.operationId),
            ],
          }
        : current,
    );
  }
  async function act(action: typeof SAVE | typeof PREVIEW | typeof DELIVER, retry = false) {
    if (!overview || locked.current) return;
    if (!retry) {
      if (action === DELIVER) {
        if (!confirmed || !overview.deliveryScope) return;
        const { expectedSourceSha, expectedMainSha, confirmedScopeDigest } = overview.deliveryScope;
        pending.current = {
          action,
          input: {
            idempotencyKey: createIdempotencyKey(),
            expectedSourceSha,
            expectedMainSha,
            confirmedScopeDigest,
          },
        };
      } else
        pending.current = {
          action,
          input: {
            idempotencyKey: createIdempotencyKey(),
            expectedRevision: overview.local.revision,
            commitMessage,
          },
        };
    }
    const request = pending.current;
    if (!request) return;
    locked.current = true;
    setBusy(true);
    setError(null);
    try {
      const signal = new AbortController().signal;
      const operation =
        request.action === DELIVER
          ? await api.deliver(sessionId, request.input, signal)
          : await api[request.action](sessionId, request.input, signal);
      accepted(operation);
      pending.current = null;
      setPendingRetry(false);
      setReview(false);
      setConfirmed(false);
      setRefresh((value) => value + 1);
    } catch (reason) {
      if (reason instanceof DevelopmentError && reason.status < 500) {
        setError(reason.status === 409 ? LABELS.conflict : reason.message);
        pending.current = null;
        setPendingRetry(false);
        setConfirmed(false);
      } else {
        setError(LABELS.unknown);
        setPendingRetry(true);
      }
    } finally {
      locked.current = false;
      setBusy(false);
    }
  }
  if (!overview)
    return error ? (
      <p role="alert">
        {error}
        <button type="button" onClick={() => setRefresh((value) => value + 1)}>
          {LABELS.refresh}
        </button>
      </p>
    ) : null;
  const { context, local, remote, latestBuild, currentDeployment, deliveryScope } = overview;
  const lost = local.state.includes('LOST') || local.state.includes('MISSING');
  const unavailable = lost || local.state === 'MUTATING' || editorDirty;
  const config = context.configSnapshot;
  return (
    <section className="development-context" aria-label="开发上下文">
      <header>
        <strong>{context.projectId}</strong> · <span>{context.repository}</span> ·{' '}
        <strong>{context.branchName}</strong>
      </header>
      <dl className="development-states">
        <div>
          <dt>本地编辑</dt>
          <dd>
            {local.state} · revision {local.revision}
          </dd>
          <dd>{local.pushedSha}</dd>
        </div>
        <div>
          <dt>远端分支</dt>
          <dd>{remote.headSha ?? remote.errorCode}</dd>
          <dd>{remote.observedAt}</dd>
        </div>
        <div>
          <dt>最新构建</dt>
          <dd>{latestBuild?.buildStatus ?? '暂无构建'}</dd>
          <dd>{latestBuild?.buildId}</dd>
        </div>
        <div>
          <dt>当前有效部署</dt>
          <dd>{currentDeployment?.activeBuildId ?? LABELS.undeployed}</dd>
          <dd>{currentDeployment?.sourceState}</dd>
        </div>
      </dl>
      <p>{LABELS.pending}</p>
      {lost && <p role="alert">{LABELS.lost}</p>}
      {deploymentChanged && <p role="status">{LABELS.changed}</p>}
      {error && <p role="alert">{error}</p>}
      <label>
        {LABELS.commit}
        <input
          value={commitMessage}
          onChange={(event) => setCommitMessage(event.target.value)}
          disabled={busy || pendingRetry}
        />
      </label>
      <div className="development-actions">
        <button
          type="button"
          disabled={busy || unavailable || pendingRetry || !commitMessage.trim()}
          onClick={() => void act(SAVE)}
        >
          {LABELS.save}
        </button>
        <button
          type="button"
          disabled={
            busy || unavailable || pendingRetry || (local.state !== CLEAN && !commitMessage.trim())
          }
          onClick={() => void act(PREVIEW)}
        >
          {LABELS.preview}
        </button>
        <button
          type="button"
          disabled={busy || unavailable || pendingRetry || local.state !== CLEAN || !deliveryScope}
          onClick={() => {
            setReview(true);
            setConfirmed(false);
            setRefresh((value) => value + 1);
          }}
        >
          {LABELS.deliver}
        </button>
        <button type="button" disabled={busy} onClick={() => setRefresh((value) => value + 1)}>
          {LABELS.refresh}
        </button>
        <button
          type="button"
          disabled={busy || unavailable}
          onClick={() => setEditing((value) => !value)}
        >
          {editing ? LABELS.close : LABELS.edit}
        </button>
        {pendingRetry && (
          <button
            type="button"
            disabled={busy}
            onClick={() => void act(pending.current!.action, true)}
          >
            {LABELS.retry}
          </button>
        )}
      </div>
      {review && deliveryScope && (
        <section aria-label={LABELS.scope} className="development-review">
          <h3>{LABELS.scope}</h3>
          <dl>
            <dt>源分支 SHA</dt>
            <dd>{deliveryScope.expectedSourceSha}</dd>
            <dt>main SHA</dt>
            <dd>{deliveryScope.expectedMainSha}</dd>
            <dt>{LABELS.config}</dt>
            <dd>{config.repository}</dd>
            <dd>{config.previewExecution.environmentId}</dd>
            <dd>{config.previewExecution.configVersion}</dd>
            <dt>{LABELS.previewSchema}</dt>
            <dd>{config.previewExecution.schema}</dd>
            <dd>{config.productionExecution.environmentId}</dd>
            <dd>{config.productionExecution.configVersion}</dd>
            <dt>{LABELS.productionSchema}</dt>
            <dd>{config.productionExecution.schema}</dd>
          </dl>
          <pre>{deliveryScope.diff}</pre>
          <code>{deliveryScope.confirmedScopeDigest}</code>
          <label>
            <input
              type="checkbox"
              checked={confirmed}
              onChange={(event) => setConfirmed(event.target.checked)}
            />
            {LABELS.consent}
          </label>
          <button
            type="button"
            disabled={!confirmed || busy || pendingRetry || local.state !== CLEAN}
            onClick={() => void act(DELIVER)}
          >
            {LABELS.confirm}
          </button>
        </section>
      )}
      {editing && (
        <DevelopmentFileEditor
          sessionId={sessionId}
          client={api}
          onChanged={() => setRefresh((value) => value + 1)}
          onDirtyChange={setEditorDirty}
        />
      )}
      {overview.operations.map((operation) => (
        <DevelopmentOperationCard
          key={operation.operationId}
          sessionId={sessionId}
          operationId={operation.operationId}
          initial={operation}
          client={api}
          onUpdated={() => setRefresh((value) => value + 1)}
        />
      ))}
    </section>
  );
}
