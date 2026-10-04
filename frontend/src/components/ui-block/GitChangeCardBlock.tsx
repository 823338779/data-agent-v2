import { ReactNode, useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { ArrowRight, FileText, X } from 'lucide-react';
import { useMemoryModal } from '../memory/useMemoryModal';
import { GitBranchPreview } from './GitBranchPreview';
import {
  createGitChangeClient,
  type GitChange,
  type GitChangeAction,
  type GitChangeStatus,
} from '../../api/gitChangeClient';
const client = createGitChangeClient();
const labels: Record<GitChangeStatus, string> = {
  PENDING_CONFIRMATION: '待确认',
  SUBMITTING: '提交中',
  VERIFYING: '待核实',
  PUSHED: '已推送',
  RECONFIRM_REQUIRED: '需要重新确认',
  INVALIDATED: '已失效',
  CANCELLED: '已取消',
  FAILED: '提交失败',
};
// 执行类失败提供原确认的重试入口，是否已有效确认由服务端最终校验；完整性或基线错误要求重新准备。
const retryableErrors = new Set([
  'GIT_RETRY_REQUIRED',
  'GIT_TASK_REJECTED',
  'GIT_COMMAND_FAILED',
  'GIT_TIMEOUT',
  'GIT_INTERRUPTED',
]);

/** 审阅层只管理展示与焦点；候选状态仍由外层维护，关闭不会重置确认或轮询。 */
function GitReviewPanel({ children, onClose }: { children: ReactNode; onClose(): void }) {
  const panel = useRef<HTMLElement>(null);
  const close = useRef<HTMLButtonElement>(null);
  useMemoryModal(panel, close, onClose);
  return createPortal(
    <div
      className="review-backdrop"
      onClick={(event) => {
        if (event.target === event.currentTarget) onClose();
      }}
    >
      <section
        className="review-panel"
        ref={panel}
        role="dialog"
        aria-modal="true"
        aria-label="Git 变更审阅"
      >
        <div className="review-panel-heading">
          <h2>变更审阅</h2>
          <button
            className="icon-button"
            ref={close}
            type="button"
            aria-label="关闭变更审阅"
            onClick={onClose}
          >
            <X size={19} />
          </button>
        </div>
        <div className="git-change-details">{children}</div>
      </section>
    </div>,
    document.body,
  );
}

/** 只按服务器登记的两个 ID 加载候选；按钮是唯一提交入口，远端推送为成功终点。 */
export function GitChangeCardBlock({
  sessionId,
  changeId,
}: {
  sessionId: string;
  changeId: string;
}) {
  const [change, setChange] = useState<GitChange | null>(null);
  const [reviewOpen, setReviewOpen] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [expired, setExpired] = useState(false);
  const [diffs, setDiffs] = useState<Record<string, string>>({});
  const [opened, setOpened] = useState<string | null>(null);
  const [loadingDiff, setLoadingDiff] = useState<string | null>(null);
  const alive = useRef(false);
  const locked = useRef(false);
  const sequence = useRef(0);
  const requests = useRef(new Set<AbortController>());
  const active = change?.status === 'SUBMITTING' || change?.status === 'VERIFYING';

  function controller() {
    const value = new AbortController();
    requests.current.add(value);
    return value;
  }
  function showError(reason: unknown) {
    setError(reason instanceof Error ? reason.message : 'Git 请求失败，请刷新后重试');
  }

  useEffect(() => {
    alive.current = true;
    const abort = controller();
    const current = ++sequence.current;
    client
      .detail(sessionId, changeId, abort.signal)
      .then((value) => {
        if (alive.current && current === sequence.current) setChange(value);
      })
      .catch((reason) => {
        if (!abort.signal.aborted && alive.current) showError(reason);
      })
      .finally(() => requests.current.delete(abort));
    return () => {
      alive.current = false;
      sequence.current++;
      requests.current.forEach((value) => value.abort());
      requests.current.clear();
    };
  }, [sessionId, changeId]);

  // 一个连续的提交/核实阶段只有一个 30 秒窗口，状态刷新、rerender 和手动核实不能无限延长它。
  useEffect(() => {
    setExpired(false);
    if (!active) return;
    const timer = window.setTimeout(() => setExpired(true), 30000);
    return () => window.clearTimeout(timer);
  }, [active]);

  useEffect(() => {
    if (!active || busy || expired) return;
    const abort = controller();
    const current = sequence.current;
    const timer = window.setTimeout(() => {
      client
        .detail(sessionId, changeId, abort.signal)
        .then((value) => {
          if (!abort.signal.aborted && alive.current && current === sequence.current) {
            setChange(value);
            setError(null);
          }
        })
        .catch((reason) => {
          if (!abort.signal.aborted && alive.current) {
            showError(reason);
            setExpired(true);
          }
        })
        .finally(() => requests.current.delete(abort));
    }, 1500);
    return () => {
      window.clearTimeout(timer);
      abort.abort();
      requests.current.delete(abort);
    };
  }, [active, busy, expired, change, sessionId, changeId]);

  async function reload() {
    if (locked.current) return;
    locked.current = true;
    setBusy(true);
    setError(null);
    const abort = controller();
    const current = ++sequence.current;
    try {
      const value = await client.detail(sessionId, changeId, abort.signal);
      if (alive.current && current === sequence.current) setChange(value);
    } catch (reason) {
      if (!abort.signal.aborted && alive.current) showError(reason);
    } finally {
      requests.current.delete(abort);
      locked.current = false;
      if (alive.current) setBusy(false);
    }
  }

  async function act(action: GitChangeAction) {
    if (!change || locked.current) return;
    // ref 同步封锁双击；序号先于异步请求增加，旧轮询即便忽略 abort 也不能覆盖按钮结果。
    locked.current = true;
    setBusy(true);
    setError(null);
    const current = ++sequence.current;
    const abort = controller();
    try {
      const value = await client.act(sessionId, changeId, action, change.version, abort.signal);
      if (alive.current && current === sequence.current) setChange(value);
    } catch (reason) {
      if (!abort.signal.aborted && alive.current) {
        showError(reason);
        // 网络超时也可能已被服务端接受，先刷新状态，不盲目重发确认。
        try {
          const value = await client.detail(sessionId, changeId, abort.signal);
          if (alive.current && current === sequence.current) setChange(value);
        } catch {
          if (alive.current && !abort.signal.aborted) setChange(null);
        }
      }
    } finally {
      requests.current.delete(abort);
      locked.current = false;
      if (alive.current) setBusy(false);
    }
  }

  async function openDiff(fileId: string) {
    if (opened === fileId) {
      setOpened(null);
      return;
    }
    setOpened(fileId);
    if (diffs[fileId] !== undefined || loadingDiff !== null) return;
    setLoadingDiff(fileId);
    const abort = controller();
    try {
      const result = await client.diff(sessionId, changeId, fileId, abort.signal);
      if (alive.current) setDiffs((previous) => ({ ...previous, [fileId]: result.diff }));
    } catch (reason) {
      if (!abort.signal.aborted && alive.current) showError(reason);
    } finally {
      requests.current.delete(abort);
      if (alive.current) setLoadingDiff(null);
    }
  }

  const retryable =
    change?.reviewAvailable &&
    change.status === 'FAILED' &&
    retryableErrors.has(change.errorCode ?? '');
  return (
    <section className="git-change-card" aria-label="Git 变更确认">
      <div className="git-change-summary">
        <FileText size={19} />
        <span className="git-summary-file" title={change?.summary}>
          {change?.files?.length === 1
            ? change.files[0].path
            : change?.files
              ? `${change.files.length} 个文件`
              : 'Git 变更'}
        </span>
        {!reviewOpen && (
          <span className="git-summary-status" role="status">
            {change ? labels[change.status] : error ? '加载失败' : '加载中'}
          </span>
        )}
        <button
          type="button"
          className="text-button"
          aria-label="查看变更"
          aria-expanded={reviewOpen}
          onClick={() => setReviewOpen(true)}
        >
          查看变更
          <ArrowRight size={14} />
        </button>
      </div>
      {reviewOpen && (
        <GitReviewPanel onClose={() => setReviewOpen(false)}>
          <header>
            <strong>Git 变更</strong>
            <span role="status">
              {change ? labels[change.status] : error ? '加载失败' : '加载中'}
            </span>
          </header>
          {error && <p role="alert">{error}</p>}
          {!change && error && (
            <button type="button" disabled={busy} onClick={() => void reload()}>
              重新加载
            </button>
          )}
          {change && (
            <>
              <h4>{change.summary}</h4>
              {change.validation?.status === 'SUCCEEDED' && (
                <p>
                  <strong>定义已验证</strong>：不代表已构建或发布。验证基线{' '}
                  <code>{change.validation.baseCommitSha}</code>
                </p>
              )}
              <dl>
                <dt>目标分支</dt>
                <dd>{change.gitRef ?? change.branch}</dd>
                {change.projectId && (
                  <>
                    <dt>项目</dt>
                    <dd>{change.projectId}</dd>
                  </>
                )}
                {change.workspaceId && (
                  <>
                    <dt>工作区</dt>
                    <dd>{change.workspaceId}</dd>
                  </>
                )}
                <dt>基线</dt>
                <dd>
                  <code>{change.baseCommit}</code>
                </dd>
                <dt>提交说明</dt>
                <dd>{change.commitMessage}</dd>
                {change.commitId && (
                  <>
                    <dt>提交</dt>
                    <dd>
                      <code>{change.commitId}</code>
                    </dd>
                  </>
                )}
              </dl>
              {change.status === 'PUSHED' &&
                change.branchId &&
                change.projectId &&
                change.commitId && (
                  <GitBranchPreview
                    branchId={change.branchId}
                    projectId={change.projectId}
                    commitId={change.commitId}
                  />
                )}
              <p>
                {change.validation?.status === 'SUCCEEDED'
                  ? '已通过定义、模板、dbt parse/compile 与语义检查；物理查询仍需等待构建验证。'
                  : '冻结时基础检查已通过：变更文件 UTF-8、文件与 Git 变更完整性及大小限制；未进行指标业务口径校验。'}
              </p>
              {!change.reviewAvailable && (
                <p role="alert">
                  审阅材料不可用：冻结快照缺失或完整性校验失败，无法查看差异或批准提交。已保存的提交状态仍可查询。
                </p>
              )}
              {change.reviewAvailable && change.files && (
                <ul>
                  {change.files.map((file) => (
                    <li key={file.fileId}>
                      <div>
                        <code>{file.status}</code> <span>{file.path}</span>
                        <button
                          type="button"
                          aria-expanded={opened === file.fileId}
                          aria-label={`${opened === file.fileId ? '收起差异' : '查看差异'} ${file.path}`}
                          disabled={loadingDiff !== null}
                          onClick={() => void openDiff(file.fileId)}
                        >
                          {opened === file.fileId ? '收起差异' : '查看差异'}
                        </button>
                      </div>
                      {opened === file.fileId && (
                        <pre className="git-change-card__diff">
                          {diffs[file.fileId]?.split('\n').map((line, index) => (
                            <span
                              className={
                                line.startsWith('+') && !line.startsWith('+++')
                                  ? 'diff-added'
                                  : line.startsWith('-') && !line.startsWith('---')
                                    ? 'diff-removed'
                                    : ''
                              }
                              key={index}
                            >
                              {line}
                              {'\n'}
                            </span>
                          )) ??
                            (loadingDiff === file.fileId
                              ? '加载差异中…'
                              : '差异加载失败，请收起后重试')}
                        </pre>
                      )}
                    </li>
                  ))}
                </ul>
              )}
              {change.errorSummary && <p>{change.errorSummary}</p>}
              {(change.status === 'RECONFIRM_REQUIRED' ||
                change.status === 'INVALIDATED' ||
                (change.status === 'FAILED' && !retryable)) && (
                <p>请重新准备草稿，生成新卡片后确认。</p>
              )}
              {active && expired && <p>自动查询已暂停，可手动核实结果。</p>}
              <footer>
                <p>
                  确认后将提交并推送到 <code>{change.branch}</code>。
                </p>
                <button
                  className="primary-button"
                  type="button"
                  disabled={
                    busy || !change.reviewAvailable || change.status !== 'PENDING_CONFIRMATION'
                  }
                  onClick={() => void act('confirm')}
                >
                  确认并推送
                </button>
                {change.status === 'PENDING_CONFIRMATION' && (
                  <button type="button" disabled={busy} onClick={() => void act('cancel')}>
                    取消
                  </button>
                )}
                {retryable && (
                  <button type="button" disabled={busy} onClick={() => void act('retry')}>
                    重试原确认
                  </button>
                )}
                {active && (
                  <button type="button" disabled={busy} onClick={() => void act('reconcile')}>
                    核实结果
                  </button>
                )}
              </footer>
            </>
          )}
        </GitReviewPanel>
      )}
    </section>
  );
}
