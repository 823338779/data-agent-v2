/** 开发会话只在创建时选择目标；后续所有动作由服务端固定上下文。 */
export interface DevelopmentSelection {
  /** Agent 项目身份。 */
  projectId: string;
  /** 真实 Git 分支名，不是服务侧登记 ID。 */
  branchName: string;
}
/** 受理时冻结的执行目标，不包含凭据值。 */
export interface ExecutionSnapshot {
  /** 外部环境稳定身份。 */ environmentId: string;
  /** 外部环境配置版本。 */ configVersion: number;
  /** 非敏感物理连接信息。 */ connection: {
    type: 'starrocks' | 'postgres' | 'duckdb';
    instanceId: string;
    host: string | null;
    port: number | null;
    path: string | null;
  };
  /** 执行服务允许的 secret 引用。 */ credentialRef: string;
  /** 目标 catalog 或数据库。 */ catalog: string;
  /** 物理写入 schema。 */ schema: string;
  /** dbt 并行度。 */ threads: number;
  /** 查询业务时区。 */ businessTimezone: string;
}
export interface DevelopmentConfig {
  /** 受控源码仓库。 */ repository: string;
  /** 预览动作固定执行快照。 */ previewExecution: ExecutionSnapshot;
  /** 交付动作固定执行快照。 */ productionExecution: ExecutionSnapshot;
  /** Agent 项目配置乐观锁版本。 */ version: number;
}
export interface DevelopmentOperation {
  /** 可在重新打开会话后继续查询的持久操作身份。 */
  operationId: string;
  type: 'SAVE' | 'PREVIEW' | 'DELIVER' | 'CREATE_BRANCH';
  phase: string;
  status: string;
  /** 恢复请求的 CAS 版本，不是本地编辑修订。 */
  version: number;
  /** 仅在外部事实得到核实时填写 SHA。 */
  pushedSha: string | null;
  mergeSha: string | null;
  /** 原操作所有构建尝试，历史链接永远固定这些 ID。 */
  buildIds: string[];
  error: { code: string; message: string; retryable: boolean } | null;
}
export interface DevelopmentOverview {
  /** 会话创建时冻结的项目、仓库及分支绑定。 */
  context: DevelopmentSelection & {
    sessionId: string;
    repository: string;
    baselineSha: string;
    configSnapshot: DevelopmentConfig;
  };
  /** 本机临时输入状态；丢失时不能声称草稿已恢复。 */
  local: { state: string; revision: number; pushedSha: string | null };
  /** 本次远端观察，与当前有效部署独立。 */
  remote: {
    headSha: string | null;
    mainSha: string | null;
    observedAt: string | null;
    errorCode: string | null;
  };
  latestBuild: { buildId: string; buildStatus: string; phase: string } | null;
  currentDeployment: {
    activeBuildId: string | null;
    activeCommitSha: string | null;
    sourceState: string;
  } | null;
  /** 摘要覆盖整个共享分支差异和固定配置，任何变化必须重新确认。 */
  deliveryScope: {
    expectedSourceSha: string;
    expectedMainSha: string;
    confirmedScopeDigest: string;
    diff: string;
  } | null;
  /** 用于会话重连后的独立操作恢复。 */
  operations: DevelopmentOperation[];
}
export interface DevelopmentProject {
  projectId: string;
  name: string;
  version: number;
  developmentConfigured: boolean;
  repository: string | null;
}
export interface SaveRequest {
  /** 网络结果未知时重用同一键与完整输入。 */
  idempotencyKey: string;
  expectedRevision: number;
  commitMessage: string;
}
export interface DeliverRequest {
  idempotencyKey: string;
  expectedSourceSha: string;
  expectedMainSha: string;
  confirmedScopeDigest: string;
}
export interface FileMutation {
  type: 'CREATE' | 'UPDATE' | 'DELETE';
  path: string;
  /** UPDATE/DELETE 的原始字节摘要；CREATE 为空。 */
  expectedSha256?: string;
  /** 完整源码正文，不解析并重新序列化 YAML。 */
  content?: string;
}

const PROJECTS = '/api/v1/agent-projects';
const SESSIONS = '/api/v1/agent-sessions';
const GET = 'GET';
const POST = 'POST';
const PUT = 'PUT';
const JSON_TYPE = 'application/json';
const CONTENT_TYPE = 'Content-Type';
const REQUEST_FAILED = 'DEVELOPMENT_REQUEST_FAILED';

export class DevelopmentError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
    message: string,
  ) {
    super(message);
  }
}

/** 仅同源 Agent BFF；失败不探测旧路由，不自动更换目标或请求键。 */
export function createDevelopmentClient(options: { fetchImpl?: typeof fetch } = {}) {
  const fetchImpl = options.fetchImpl ?? ((...args: Parameters<typeof fetch>) => fetch(...args));
  const project = (id: string) => `${PROJECTS}/${encodeURIComponent(id)}`;
  const session = (id: string) => `${SESSIONS}/${encodeURIComponent(id)}/development`;
  const operation = (sid: string, id: string) =>
    `${session(sid)}/operations/${encodeURIComponent(id)}`;
  async function request<T>(
    url: string,
    signal: AbortSignal,
    method = GET,
    body?: unknown,
  ): Promise<T> {
    const result = await fetchImpl(url, {
      method,
      signal,
      ...(body === undefined
        ? {}
        : { headers: { [CONTENT_TYPE]: JSON_TYPE }, body: JSON.stringify(body) }),
    });
    if (!result.ok) {
      const problem = await result.json().catch(() => ({}));
      throw new DevelopmentError(
        result.status,
        problem.code ?? REQUEST_FAILED,
        problem.detail ?? problem.message ?? '开发请求失败，请刷新状态后重试',
      );
    }
    return result.json() as Promise<T>;
  }
  return {
    projects: (signal: AbortSignal, cursor?: string) =>
      request<{ items: DevelopmentProject[]; nextCursor: string | null }>(
        `${PROJECTS}?limit=50${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`,
        signal,
      ),
    config: (id: string, signal: AbortSignal) =>
      request<DevelopmentConfig>(`${project(id)}/development-config`, signal),
    configure: (
      id: string,
      input: Omit<DevelopmentConfig, 'version'> & { expectedVersion: number },
      signal: AbortSignal,
    ) => request<DevelopmentConfig>(`${project(id)}/development-config`, signal, PUT, input),
    branches: (id: string, signal: AbortSignal) =>
      request<{ items: { branchName: string; headSha: string }[] }>(
        `${project(id)}/branches`,
        signal,
      ),
    createBranch: (
      id: string,
      input: { branchName: string; idempotencyKey: string },
      signal: AbortSignal,
    ) => request<DevelopmentOperation>(`${project(id)}/branches`, signal, POST, input),
    overview: (id: string, signal: AbortSignal) =>
      request<DevelopmentOverview>(session(id), signal),
    save: (id: string, input: SaveRequest, signal: AbortSignal) =>
      request<DevelopmentOperation>(`${session(id)}/save`, signal, POST, input),
    preview: (id: string, input: SaveRequest, signal: AbortSignal) =>
      request<DevelopmentOperation>(`${session(id)}/preview`, signal, POST, input),
    deliver: (id: string, input: DeliverRequest, signal: AbortSignal) =>
      request<DevelopmentOperation>(`${session(id)}/deliver`, signal, POST, input),
    operation: (sid: string, id: string, signal: AbortSignal) =>
      request<DevelopmentOperation>(operation(sid, id), signal),
    recover: (
      sid: string,
      id: string,
      input: { expectedVersion: number; idempotencyKey: string },
      signal: AbortSignal,
    ) => request<DevelopmentOperation>(`${operation(sid, id)}/recover`, signal, POST, input),
    files: (id: string, signal: AbortSignal, directory = '.') =>
      request<{ files: string[]; revision: number }>(
        `${session(id)}/files?directory=${encodeURIComponent(directory)}`,
        signal,
      ),
    file: (id: string, path: string, signal: AbortSignal) =>
      request<{ path: string; content: string; sha256: string }>(
        `${session(id)}/file?path=${encodeURIComponent(path)}`,
        signal,
      ),
    writeFiles: (
      id: string,
      input: { expectedRevision: number; operations: FileMutation[] },
      signal: AbortSignal,
    ) => request<{ revision: number }>(`${session(id)}/files`, signal, POST, input),
    diff: (id: string, signal: AbortSignal) =>
      request<{ diff: string; revision: number }>(`${session(id)}/diff`, signal),
  };
}
export type DevelopmentClient = ReturnType<typeof createDevelopmentClient>;
