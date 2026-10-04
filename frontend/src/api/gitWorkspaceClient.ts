import { GitChangeError } from './gitChangeClient';

/** 独立草稿；会话关联不表示独占所有权。 */
export interface GitWorkspace {
  workspaceId: string;
  projectId: string | null;
  branchId: string | null;
  gitRef: string | null;
  baseCommit: string | null;
  draftRevision: number;
  status: 'READY' | 'MUTATING' | 'BROKEN';
  activeChangeId: string | null;
}
/** dbt 服务登记的分支身份，同名重建具有不同 branchId。 */
export interface MetricBranch {
  projectId: string;
  branchId: string;
  gitRef: string;
  mode: 'PRODUCTION' | 'PREVIEW';
  status: string;
  observedHeadSha: string | null;
  baseCommitSha: string;
  activeReleaseId?: string | null;
  latestReleaseId?: string | null;
  version: number;
}
export interface GitSyncResult {
  workspaceId: string;
  baseCommitSha: string;
  remoteCommitSha: string;
  draftRevision: number;
  status: 'UNCHANGED' | 'MERGED' | 'CONFLICT';
  conflictPaths: string[];
  /** 固定三方有界正文；null 表示该侧不存在文件。 */
  conflicts?: {
    path: string;
    baseContent: string | null;
    localContent: string | null;
    remoteContent: string | null;
  }[];
}
/** 发布状态独立于 Git 推送；sourceSha 用于匹配卡片原提交。 */
export interface BranchRelease {
  projectId: string;
  releaseId: string;
  sourceSha: string;
  state: string;
  publicationSequence: number;
  validationSummary?: {
    phase: string;
    checks: { name: string; status: string; message: string | null }[];
    truncated: boolean;
  };
}
const WORKSPACES = '/api/v1/git-workspaces';
const BRANCHES = '/api/v1/metric-branches';
const SESSION_PREFIX = '/api/v1/agent-sessions/';
const GET = 'GET';
const POST = 'POST';
const PUT = 'PUT';
const CONTENT_TYPE = 'Content-Type';
const JSON_TYPE = 'application/json';
const REQUEST_FAILED = 'GIT_REQUEST_FAILED';

/** 固定同源 BFF，仅发送明确的业务字段，创建失败由调用方保留原幂等键。 */
export function createGitWorkspaceClient(options: { fetchImpl?: typeof fetch } = {}) {
  const fetchImpl = options.fetchImpl ?? ((...args: Parameters<typeof fetch>) => fetch(...args));
  async function request<T>(
    url: string,
    signal: AbortSignal,
    method = GET,
    body?: unknown,
  ): Promise<T> {
    const response = await fetchImpl(url, {
      method,
      signal,
      ...(body === undefined
        ? {}
        : { headers: { [CONTENT_TYPE]: JSON_TYPE }, body: JSON.stringify(body) }),
    });
    if (!response.ok) {
      const error = await response.json().catch(() => ({}));
      throw new GitChangeError(
        response.status,
        typeof error.code === 'string' ? error.code : REQUEST_FAILED,
        typeof error.detail === 'string'
          ? error.detail
          : '工作区服务暂不可用，请保留当前目标后重试',
      );
    }
    return response.json() as Promise<T>;
  }
  const branchPath = (id: string) => `${BRANCHES}/${encodeURIComponent(id)}`;
  return {
    list: (sessionId: string, signal: AbortSignal) =>
      request<GitWorkspace[]>(`${WORKSPACES}?sessionId=${encodeURIComponent(sessionId)}`, signal),
    get: (id: string, signal: AbortSignal) =>
      request<GitWorkspace>(`${WORKSPACES}/${encodeURIComponent(id)}`, signal),
    create: (
      input: { sessionId: string; branchId: string; baseCommitSha: string; idempotencyKey: string },
      signal: AbortSignal,
    ) =>
      request<GitWorkspace>(WORKSPACES, signal, POST, {
        sessionId: input.sessionId,
        branchId: input.branchId,
        baseCommitSha: input.baseCommitSha,
        idempotencyKey: input.idempotencyKey,
      }),
    attach: (sessionId: string, id: string, signal: AbortSignal) =>
      request<GitWorkspace>(
        `${SESSION_PREFIX}${encodeURIComponent(sessionId)}/git-workspaces/${encodeURIComponent(id)}`,
        signal,
        PUT,
      ),
    sync: (id: string, expectedRevision: number, signal: AbortSignal) =>
      request<GitSyncResult>(`${WORKSPACES}/${encodeURIComponent(id)}/sync`, signal, POST, {
        expectedRevision,
      }),
    restart: (id: string, expectedRevision: number, signal: AbortSignal) =>
      request<GitWorkspace>(`${WORKSPACES}/${encodeURIComponent(id)}/restart`, signal, POST, {
        expectedRevision,
      }),
    resolve: (
      id: string,
      expectedRevision: number,
      remoteCommitSha: string,
      resolutions: Record<string, string | null>,
      signal: AbortSignal,
    ) =>
      request<GitSyncResult>(`${WORKSPACES}/${encodeURIComponent(id)}/sync/resolve`, signal, POST, {
        expectedRevision,
        remoteCommitSha,
        resolutions,
      }),
    branches: (signal: AbortSignal) => request<MetricBranch[]>(BRANCHES, signal),
    branch: (id: string, signal: AbortSignal) => request<MetricBranch>(branchPath(id), signal),
    createBranch: (
      input: {
        name: string;
        sourceBranchId: string;
        sourceCommitSha: string;
        idempotencyKey: string;
      },
      signal: AbortSignal,
    ) =>
      request<MetricBranch>(BRANCHES, signal, POST, {
        name: input.name,
        sourceBranchId: input.sourceBranchId,
        sourceCommitSha: input.sourceCommitSha,
        idempotencyKey: input.idempotencyKey,
      }),
    registerBranch: (
      input: { name: string; baseCommitSha: string; idempotencyKey: string },
      signal: AbortSignal,
    ) =>
      request<MetricBranch>(`${BRANCHES}/register`, signal, POST, {
        name: input.name,
        baseCommitSha: input.baseCommitSha,
        idempotencyKey: input.idempotencyKey,
      }),
    releases: (id: string, signal: AbortSignal) =>
      request<BranchRelease[]>(`${branchPath(id)}/releases`, signal),
    release: (id: string, releaseId: string, signal: AbortSignal) =>
      request<BranchRelease>(`${branchPath(id)}/releases/${encodeURIComponent(releaseId)}`, signal),
  };
}
export type GitWorkspaceClient = ReturnType<typeof createGitWorkspaceClient>;
