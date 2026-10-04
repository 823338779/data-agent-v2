import { useEffect, useMemo, useRef, useState } from 'react';
import {
  createGitWorkspaceClient,
  type GitWorkspace,
  type GitWorkspaceClient,
  type MetricBranch,
  type GitSyncResult,
} from '../api/gitWorkspaceClient';
import { createIdempotencyKey } from '../api/idempotencyKey';

interface Props {
  sessionId: string;
  /** 只决定下次受理；执行及卡片不读取此选择。 */
  workspaceId: string | null;
  onChange(value: string | null): void;
  onUsableChange?(usable: boolean): void;
  client?: GitWorkspaceClient;
}
const READY = 'READY';
const ACTIVE = 'ACTIVE';
const PREVIEW = 'PREVIEW';
const EMPTY = '';
const SHA = /^[0-9a-f]{40}$/;

/** 独立草稿选择与显式创建；异步结果按会话代次隔离。 */
export function GitWorkspaceSelector({
  sessionId,
  workspaceId,
  onChange,
  onUsableChange,
  client,
}: Props) {
  const api = useMemo(() => client ?? createGitWorkspaceClient(), [client]);
  const [open, setOpen] = useState(false);
  const [workspaces, setWorkspaces] = useState<GitWorkspace[]>([]);
  const [branches, setBranches] = useState<MetricBranch[]>([]);
  const [branchId, setBranchId] = useState(EMPTY);
  const [base, setBase] = useState(EMPTY);
  const [name, setName] = useState(EMPTY);
  const [attachId, setAttachId] = useState(EMPTY);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [conflict, setConflict] = useState<GitSyncResult | null>(null);
  const [resolutions, setResolutions] = useState<Record<string, string | null>>({});
  const [reload, setReload] = useState(0);
  const generation = useRef(0);
  const operation = useRef<AbortController | null>(null);
  const pendingKeys = useRef(new Map<string, string>());
  const callbacks = useRef({ onChange, onUsableChange });
  callbacks.current = { onChange, onUsableChange };
  const selected = workspaces.find((item) => item.workspaceId === workspaceId);
  const selectedBranch = branches.find((item) => item.branchId === selected?.branchId);
  const source = branches.find((item) => item.branchId === branchId);
  const usable = (item: GitWorkspace) =>
    item.status === READY &&
    branches.some(
      (branch) =>
        branch.branchId === item.branchId && branch.mode === PREVIEW && branch.status === ACTIVE,
    );

  // 人工决议始终属于原工作区；切换会话或目标立即丢弃页面上的旧决议输入。
  useEffect(() => {
    setConflict(null);
    setResolutions({});
  }, [sessionId, workspaceId, reload]);

  useEffect(() => {
    const current = ++generation.current;
    operation.current?.abort();
    setWorkspaces([]);
    setBranches([]);
    setBranchId(EMPTY);
    setBase(EMPTY);
    setError(null);
    setNotice(null);
    setBusy(false);
    if (!open && !workspaceId) return;
    const abort = new AbortController();
    Promise.all([api.list(sessionId, abort.signal), api.branches(abort.signal)])
      .then(([drafts, available]) => {
        if (abort.signal.aborted || generation.current !== current) return;
        setWorkspaces(drafts);
        setBranches(available);
        const initial =
          available.find((branch) => branch.mode === PREVIEW && branch.status === ACTIVE) ??
          available.find((branch) => branch.status === ACTIVE);
        setBranchId(initial?.branchId ?? EMPTY);
        setBase(initial?.observedHeadSha ?? initial?.baseCommitSha ?? EMPTY);
      })
      .catch((reason: unknown) => {
        if (!abort.signal.aborted && generation.current === current)
          setError(reason instanceof Error ? reason.message : '无法读取工作区');
      });
    return () => {
      abort.abort();
      operation.current?.abort();
      generation.current++;
    };
  }, [sessionId, open, api, reload]);

  useEffect(() => {
    callbacks.current.onUsableChange?.(!workspaceId || Boolean(selected && usable(selected)));
  }, [workspaceId, selected, branches]);

  async function perform(action: (signal: AbortSignal) => Promise<void>) {
    if (operation.current && !operation.current.signal.aborted) return;
    const current = generation.current;
    const abort = new AbortController();
    operation.current = abort;
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      await action(abort.signal);
    } catch (reason) {
      if (!abort.signal.aborted && current === generation.current)
        setError(reason instanceof Error ? reason.message : '操作未完成，请重试');
    } finally {
      if (current === generation.current) {
        setBusy(false);
        operation.current = null;
      }
    }
  }
  function key(identity: string) {
    if (!pendingKeys.current.has(identity))
      pendingKeys.current.set(identity, createIdempotencyKey());
    return pendingKeys.current.get(identity)!;
  }
  function accepted(workspace: GitWorkspace, signal: AbortSignal) {
    if (signal.aborted) return;
    if (workspace.status === READY) callbacks.current.onChange(workspace.workspaceId);
    else setNotice('草稿尚未就绪，已保留其身份；请刷新或创建新的独立草稿。');
    setReload((value) => value + 1);
  }
  async function create(newBranch: boolean, signal: AbortSignal) {
    if (!source || !SHA.test(base)) return;
    const identity = JSON.stringify([sessionId, branchId, base, newBranch ? name : null]);
    const requestKey = key(identity);
    const target = newBranch
      ? await api.createBranch(
          {
            name: name.trim(),
            sourceBranchId: branchId,
            sourceCommitSha: base,
            idempotencyKey: requestKey,
          },
          signal,
        )
      : source;
    if (signal.aborted) return;
    if (target.mode !== PREVIEW || target.status !== ACTIVE)
      throw new Error('分支尚未就绪，请刷新状态后重试');
    const workspace = await api.create(
      { sessionId, branchId: target.branchId, baseCommitSha: base, idempotencyKey: requestKey },
      signal,
    );
    if (!signal.aborted) {
      pendingKeys.current.delete(identity);
      accepted(workspace, signal);
    }
  }

  return (
    <section className="git-workspace-selector">
      <button
        type="button"
        className="text-button"
        aria-expanded={open}
        onClick={() => setOpen(!open)}
      >
        开发工作区
      </button>
      <small>
        {selected
          ? `${selected.gitRef} · 草稿 ${selected.draftRevision}`
          : '普通聊天 · 发送开发指令前请选择工作区'}
      </small>
      {open && (
        <div className="git-workspace-controls">
          <label>
            当前工作区
            <select
              aria-label="当前工作区"
              value={workspaceId ?? EMPTY}
              onChange={(event) => onChange(event.target.value || null)}
            >
              <option value={EMPTY}>普通聊天（未选择工作区）</option>
              {workspaces.map((item) => (
                <option key={item.workspaceId} value={item.workspaceId} disabled={!usable(item)}>
                  {item.gitRef} · {item.workspaceId} · {item.status}
                </option>
              ))}
            </select>
          </label>
          {selected && (
            <p>
              基线 <code>{selected.baseCommit}</code> · 草稿修订 {selected.draftRevision}
              {selectedBranch?.observedHeadSha &&
              selectedBranch.observedHeadSha !== selected.baseCommit
                ? ' · 远端已有更新'
                : ''}
              {selectedBranch?.status !== ACTIVE ? ' · 分支不可用' : ''}
            </p>
          )}
          <button type="button" disabled={busy} onClick={() => setReload((value) => value + 1)}>
            刷新工作区
          </button>
          <button
            type="button"
            disabled={busy || !selected || !usable(selected)}
            onClick={() =>
              void perform(async (signal) => {
                if (!selected) return;
                const result = await api.sync(selected.workspaceId, selected.draftRevision, signal);
                if (signal.aborted) return;
                if (result.status === 'CONFLICT') {
                  setConflict(result);
                  setResolutions(
                    Object.fromEntries(
                      (result.conflicts ?? []).map((item) => [item.path, item.localContent]),
                    ),
                  );
                  setNotice(`同步冲突，原草稿已保留：${result.conflictPaths.join('、')}`);
                } else {
                  setConflict(null);
                  setWorkspaces((values) =>
                    values.map((item) =>
                      item.workspaceId === result.workspaceId
                        ? {
                            ...item,
                            baseCommit: result.baseCommitSha,
                            draftRevision: result.draftRevision,
                          }
                        : item,
                    ),
                  );
                  setNotice(
                    result.status === 'UNCHANGED'
                      ? '远端没有变化'
                      : '同步完成，请重新验证并生成确认卡片',
                  );
                }
              })
            }
          >
            同步当前草稿
          </button>
          {workspaces
            .filter(
              (item) =>
                item.status !== READY &&
                branches.some(
                  (branch) => branch.branchId === item.branchId && branch.status === ACTIVE,
                ),
            )
            .map((item) => (
              <div key={item.workspaceId}>
                <p>恢复会从原基线建立新草稿，原中断材料仍保留在服务端。</p>
                <button
                  type="button"
                  disabled={busy}
                  onClick={() =>
                    void perform(async (signal) =>
                      accepted(
                        await api.restart(item.workspaceId, item.draftRevision, signal),
                        signal,
                      ),
                    )
                  }
                >
                  恢复草稿 {item.workspaceId}
                </button>
              </div>
            ))}
          {conflict?.workspaceId === workspaceId && (conflict.conflicts?.length ?? 0) > 0 && (
            <fieldset disabled={busy}>
              <legend>人工解决冲突 · 固定远端 {conflict.remoteCommitSha}</legend>
              {conflict.conflicts?.map((item) => (
                <div key={item.path}>
                  <h4>{item.path}</h4>
                  <p>原基线</p>
                  <pre>{item.baseContent ?? '文件不存在'}</pre>
                  <p>本地草稿</p>
                  <pre>{item.localContent ?? '文件不存在'}</pre>
                  <p>远端版本</p>
                  <pre>{item.remoteContent ?? '文件不存在'}</pre>
                  <label>
                    删除文件 {item.path}
                    <input
                      type="checkbox"
                      checked={resolutions[item.path] === null}
                      onChange={(event) =>
                        setResolutions((values) => ({
                          ...values,
                          [item.path]: event.target.checked ? null : (item.localContent ?? EMPTY),
                        }))
                      }
                    />
                  </label>
                  <label>
                    合并结果 {item.path}
                    <textarea
                      disabled={resolutions[item.path] === null}
                      value={resolutions[item.path] ?? EMPTY}
                      onChange={(event) =>
                        setResolutions((values) => ({ ...values, [item.path]: event.target.value }))
                      }
                    />
                  </label>
                </div>
              ))}
              <button
                type="button"
                onClick={() =>
                  void perform(async (signal) => {
                    await api.resolve(
                      conflict.workspaceId,
                      conflict.draftRevision,
                      conflict.remoteCommitSha,
                      resolutions,
                      signal,
                    );
                    if (!signal.aborted) {
                      setConflict(null);
                      setReload((value) => value + 1);
                    }
                  })
                }
              >
                接受人工解决结果
              </button>
              <p>接受后必须重新验证并生成确认卡片，才可推送。</p>
            </fieldset>
          )}
          <fieldset disabled={busy}>
            <legend>创建独立草稿</legend>
            <label>
              来源分支
              <select
                value={branchId}
                onChange={(event) => {
                  const branch = branches.find((item) => item.branchId === event.target.value);
                  setBranchId(event.target.value);
                  setBase(branch?.observedHeadSha ?? branch?.baseCommitSha ?? EMPTY);
                }}
              >
                <option value={EMPTY}>选择分支</option>
                {branches.map((branch) => (
                  <option
                    key={branch.branchId}
                    value={branch.branchId}
                    disabled={branch.status !== ACTIVE}
                  >
                    {branch.gitRef} · {branch.mode}
                  </option>
                ))}
              </select>
            </label>
            <label>
              基线提交
              <input value={base} onChange={(event) => setBase(event.target.value)} />
            </label>
            <button
              type="button"
              disabled={source?.mode !== PREVIEW || source.status !== ACTIVE || !SHA.test(base)}
              onClick={() => void perform((signal) => create(false, signal))}
            >
              在此分支创建草稿
            </button>
            <label>
              新分支名称
              <input
                value={name}
                onChange={(event) => setName(event.target.value)}
                placeholder="dev/example"
              />
            </label>
            <button
              type="button"
              disabled={!source || source.status !== ACTIVE || !name.trim() || !SHA.test(base)}
              onClick={() => void perform((signal) => create(true, signal))}
            >
              创建分支并建立草稿
            </button>
          </fieldset>
          <fieldset disabled={busy}>
            <legend>关联已有工作区</legend>
            <label>
              工作区标识
              <input value={attachId} onChange={(event) => setAttachId(event.target.value)} />
            </label>
            <button
              type="button"
              disabled={!attachId.trim()}
              onClick={() =>
                void perform(async (signal) =>
                  accepted(await api.attach(sessionId, attachId.trim(), signal), signal),
                )
              }
            >
              关联工作区
            </button>
          </fieldset>
          {notice && <p role="status">{notice}</p>}
          {error && <p role="alert">{error}</p>}
        </div>
      )}
    </section>
  );
}
