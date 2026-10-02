export type GitChangeStatus =
  | 'PENDING_CONFIRMATION'
  | 'SUBMITTING'
  | 'VERIFYING'
  | 'PUSHED'
  | 'RECONFIRM_REQUIRED'
  | 'INVALIDATED'
  | 'CANCELLED'
  | 'FAILED';
export type GitChangeAction = 'confirm' | 'cancel' | 'retry' | 'reconcile';
export interface GitFileSummary {
  fileId: string;
  status: string;
  path: string;
}
/** 服务端冻结候选；version 是按钮 CAS 版本，0 为合法初始值。 */
export interface GitChange {
  changeId: string;
  version: number;
  status: GitChangeStatus;
  branch: string;
  baseCommit: string;
  summary: string;
  commitMessage: string;
  commitId: string | null;
  /** false 表示冻结审阅材料丢失或损坏，禁止确认和重试；不改变已保存的提交阶段。 */
  reviewAvailable: boolean;
  /** 不可审阅时为 null，不能解释为空变更。 */
  files: GitFileSummary[] | null;
  errorCode: string | null;
  errorSummary: string | null;
  /** 固定候选的定义检查凭证；旧候选为空，不代表已构建或发布。 */
  validation?: {
    status: 'SUCCEEDED';
    baseCommitSha: string;
    validatedAt: string;
    checkedLevels: string[];
  } | null;
}
export class GitChangeError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
    message: string,
  ) {
    super(message);
  }
}
/** 固定同源 HTTP 边界，不接受模型提供的远端或路径，不自动重试按钮请求。 */
export function createGitChangeClient(options: { fetchImpl?: typeof fetch } = {}) {
  const fetchImpl = options.fetchImpl ?? ((...args: Parameters<typeof fetch>) => fetch(...args));
  const path = (sessionId: string, changeId: string) =>
    `/api/v1/agent-sessions/${encodeURIComponent(sessionId)}/git-changes/${encodeURIComponent(changeId)}`;
  async function request<T>(
    url: string,
    signal: AbortSignal,
    action?: { version: number },
  ): Promise<T> {
    const result = await fetchImpl(url, {
      method: action ? 'POST' : 'GET',
      signal,
      ...(action
        ? { headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(action) }
        : {}),
    });
    if (!result.ok) {
      const problem = await result.json().catch(() => ({}));
      throw new GitChangeError(
        result.status,
        typeof problem.code === 'string' ? problem.code : 'GIT_REQUEST_FAILED',
        typeof problem.detail === 'string' ? problem.detail : 'Git 请求失败，请刷新后重试',
      );
    }
    return result.json() as Promise<T>;
  }
  return {
    detail: (sessionId: string, changeId: string, signal: AbortSignal) =>
      request<GitChange>(path(sessionId, changeId), signal),
    files: (sessionId: string, changeId: string, signal: AbortSignal) =>
      request<GitFileSummary[]>(`${path(sessionId, changeId)}/files`, signal),
    diff: (sessionId: string, changeId: string, fileId: string, signal: AbortSignal) =>
      request<{ fileId: string; diff: string }>(
        `${path(sessionId, changeId)}/diff?fileId=${encodeURIComponent(fileId)}`,
        signal,
      ),
    act: (
      sessionId: string,
      changeId: string,
      action: GitChangeAction,
      version: number,
      signal: AbortSignal,
    ) => request<GitChange>(`${path(sessionId, changeId)}/${action}`, signal, { version }),
  };
}
