import { useEffect, useMemo, useRef, useState } from 'react';
import {
  createDevelopmentClient,
  type DevelopmentClient,
  type DevelopmentProject,
  type DevelopmentSelection,
} from '../api/developmentClient';
import { createIdempotencyKey } from '../api/idempotencyKey';

const LABELS = {
  open: '新建开发会话',
  project: '开发项目',
  branch: 'Git 分支',
  create: '创建固定分支会话',
  newBranch: '新分支名称',
  createBranch: '从当前 main 创建分支',
  choose: '请选择',
  fixed: '项目、仓库和分支在新会话中固定；切换分支需要创建另一个会话。',
  error: '创建失败，请重试原请求。',
};
/** 选择器只服务于创建新会话，不修改任何已绑定会话的开发目标。 */
export function DevelopmentSessionCreator({
  client,
  onCreate,
}: {
  client?: DevelopmentClient;
  onCreate: (selection: DevelopmentSelection) => Promise<void>;
}) {
  const api = useMemo(() => client ?? createDevelopmentClient(), [client]);
  const [open, setOpen] = useState(false);
  const [projects, setProjects] = useState<DevelopmentProject[]>([]);
  const [projectId, setProjectId] = useState('');
  const [branches, setBranches] = useState<{ branchName: string; headSha: string }[]>([]);
  const [branchName, setBranchName] = useState('');
  const [newBranch, setNewBranch] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const locked = useRef(false);
  const branchRequest = useRef<{
    projectId: string;
    branchName: string;
    idempotencyKey: string;
  } | null>(null);
  useEffect(() => {
    if (!open) return;
    const abort = new AbortController();
    async function load() {
      const found: DevelopmentProject[] = [];
      let cursor: string | undefined;
      do {
        const page = await api.projects(abort.signal, cursor);
        found.push(...page.items);
        cursor = page.nextCursor ?? undefined;
      } while (cursor && !abort.signal.aborted);
      if (!abort.signal.aborted) setProjects(found.filter((item) => item.developmentConfigured));
    }
    void load().catch((reason) => {
      if (!abort.signal.aborted) setError(reason instanceof Error ? reason.message : LABELS.error);
    });
    return () => abort.abort();
  }, [open, api]);
  useEffect(() => {
    setBranchName('');
    setBranches([]);
    if (!projectId) return;
    const abort = new AbortController();
    void api
      .branches(projectId, abort.signal)
      .then((value) => {
        if (!abort.signal.aborted) setBranches(value.items);
      })
      .catch((reason) => {
        if (!abort.signal.aborted)
          setError(reason instanceof Error ? reason.message : LABELS.error);
      });
    return () => abort.abort();
  }, [api, projectId]);
  async function run(action: () => Promise<void>) {
    if (locked.current) return;
    locked.current = true;
    setBusy(true);
    setError(null);
    try {
      await action();
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : LABELS.error);
    } finally {
      locked.current = false;
      setBusy(false);
    }
  }
  async function createBranch() {
    if (
      branchRequest.current?.projectId !== projectId ||
      branchRequest.current.branchName !== newBranch
    )
      branchRequest.current = {
        projectId,
        branchName: newBranch,
        idempotencyKey: createIdempotencyKey(),
      };
    const request = branchRequest.current;
    const result = await api.createBranch(
      projectId,
      { branchName: request.branchName, idempotencyKey: request.idempotencyKey },
      new AbortController().signal,
    );
    if (result.status !== 'SUCCEEDED')
      throw new Error(result.error?.message ?? '分支创建尚未完成，请重试原请求核实结果');
    const value = await api.branches(projectId, new AbortController().signal);
    setBranches(value.items);
    setBranchName(request.branchName);
    branchRequest.current = null;
    setNewBranch('');
  }
  return (
    <section className="development-creator">
      <button type="button" onClick={() => setOpen((value) => !value)}>
        {LABELS.open}
      </button>
      {open && (
        <div>
          <p>{LABELS.fixed}</p>
          <label>
            {LABELS.project}
            <select
              disabled={busy}
              value={projectId}
              onChange={(event) => setProjectId(event.target.value)}
            >
              <option value="">{LABELS.choose}</option>
              {projects.map((item) => (
                <option key={item.projectId} value={item.projectId}>
                  {item.name}
                </option>
              ))}
            </select>
          </label>
          <label>
            {LABELS.branch}
            <select
              disabled={busy || !projectId}
              value={branchName}
              onChange={(event) => setBranchName(event.target.value)}
            >
              <option value="">{LABELS.choose}</option>
              {branches.map((item) => (
                <option key={item.branchName} value={item.branchName}>
                  {item.branchName}
                </option>
              ))}
            </select>
          </label>
          <button
            type="button"
            disabled={busy || !projectId || !branchName}
            onClick={() =>
              void run(async () => {
                await onCreate({ projectId, branchName });
                setOpen(false);
              })
            }
          >
            {LABELS.create}
          </button>
          <label>
            {LABELS.newBranch}
            <input
              value={newBranch}
              disabled={busy || !projectId}
              onChange={(event) => setNewBranch(event.target.value)}
            />
          </label>
          <button
            type="button"
            disabled={busy || !projectId || !newBranch.trim()}
            onClick={() => void run(createBranch)}
          >
            {LABELS.createBranch}
          </button>
          {error && <p role="alert">{error}</p>}
        </div>
      )}
    </section>
  );
}
