export type MessageRole = 'user' | 'assistant' | 'system';
export type AgentExecutionStatus = 'idle' | 'running' | 'done' | 'cancelled' | 'failed';

const PROCESSING_STATUSES = new Set<AgentExecutionStatus>(['running']);

/** 判断会话是否仍需要实时详情更新。 */
export function isProcessingStatus(status: AgentExecutionStatus): boolean {
  return PROCESSING_STATUSES.has(status);
}

export type ThinkingProcessStatus = 'PREPARED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED';

/** 数据库内保存的工具入参或出参；超限时只包含安全预览和完整内容摘要。 */
export interface BoundedProcessPayload {
  value: unknown | null;
  preview: string | null;
  truncated: boolean;
  originalSizeBytes: number;
  sha256: string;
  contentType: string;
}

/** 每种过程节点都具备的身份、顺序和耗时字段。 */
interface ProcessItemBase {
  processItemId: string;
  sequence: number;
  status: ThinkingProcessStatus;
  createdAt: string;
  startedAt: string | null;
  completedAt: string | null;
  durationMillis: number | null;
  schemaVersion: 1;
}

export interface ModelThinkingProcessItem extends ProcessItemBase {
  type: 'THINKING';
  content: string;
  contentTruncated: boolean;
  originalContentSizeBytes: number;
  contentSha256: string;
  modelInvocationSequence: number;
  modelProvider: string;
  modelName: string;
}

export interface ToolThinkingProcessItem extends ProcessItemBase {
  type: 'TOOL';
  toolName: string;
  toolCallId: string;
  modelInvocationSequence: number;
  input: BoundedProcessPayload;
  output: BoundedProcessPayload | null;
  errorCode: string | null;
  errorMessage: string | null;
}

/** 与一条 assistant 正文绑定、按 sequence 有序展示的过程节点。 */
export type ThinkingProcessItem = ModelThinkingProcessItem | ToolThinkingProcessItem;

export type ArtifactType = 'DOCX' | 'PPTX' | 'XLSX';

/** 服务端完成完整性校验后公开给消息卡片的不可变文件元数据。 */
export interface MessageArtifact {
  artifactId: string;
  artifactType: ArtifactType;
  displayName: string;
  mediaType: string;
  sizeBytes: number;
  sha256: string;
  pageOrSheetCount: number | null;
  downloadUrl: string;
}

/** Markdown 占位符引用的受控 UI 描述；只有前端注册过的 type@version 才会渲染。 */
export interface MessageUiBlock {
  blockId: string;
  type: string;
  schemaVersion: number;
  variant: string;
  payload: unknown;
}

/** 可恢复的回答流检查点。 */
export interface AgentExecutionStreamCheckpoint {
  modelInvocationSequence: number;
  phase: string;
  outputDeltaSequence: number;
  answer: string;
}

export interface ConversationMessage {
  conversationMessageId: string;
  /** 消息关联的 Agent 执行标识，用于把 SSE 增量关联回对应回答。 */
  executionId?: string | null;
  role: MessageRole;
  author: string;
  content: string;
  timestamp: string;
  /** 消息完整替换事件的幂等版本。 */
  version: number;
  /** 与当前消息正文一同持久化的有序思考和工具过程。 */
  thinkingProcess: ThinkingProcessItem[];
  artifacts: MessageArtifact[];
  uiBlocks: MessageUiBlock[];
  /** 回答对应的持久化 Agent 执行，用于恢复该条消息自己的执行过程。 */
  execution: AgentExecution | null;
}

/** Agent 执行时间线中供界面展示的持久事件。 */
export interface AgentExecutionEvent {
  executionEventId: string;
  /** 事件在会话级执行时间线中的严格递增业务序号。 */
  executionEventSequence: number;
  type: string;
  occurredAt: string;
  executionId: string;
  status: Exclude<AgentExecutionStatus, 'idle'>;
}

/** 一条用户消息触发的完整 Agent 执行及其最新输出检查点。 */
export interface AgentExecution {
  executionId: string;
  status: AgentExecutionStatus;
  progress: number;
  objective: string;
  events: AgentExecutionEvent[];
  /** 用于刷新恢复的最新流式输出检查点。 */
  streamCheckpoint: AgentExecutionStreamCheckpoint | null;
}

export type WorkflowStatus = 'running' | 'succeeded' | 'failed' | 'cancelled' | 'timed_out';
export type WorkflowStep =
  | 'discover_semantic_resources'
  | 'build_query_plan'
  | 'validate_query_plan'
  | 'generate_sql'
  | 'validate_sql'
  | 'explain_sql'
  | 'execute_query'
  | 'verify_results'
  | 'analyze_results'
  | 'finalize_response';

export interface WorkflowTaskRef {
  taskId: string;
  purpose: string;
  /** 从 1 开始的任务位置。 */
  index: number;
  total: number;
}

export interface WorkflowTaskSnapshot {
  task: WorkflowTaskRef;
  currentStep: WorkflowStep;
  /** 仅在本地策略和 StarRocks EXPLAIN 均成功后出现的完整 SQL。 */
  sql: string | null;
  returnedRows: number | null;
  truncated: boolean | null;
  attempt: number | null;
  message: string | null;
}

/** 当前页面内的瞬时 Workflow 状态；REST 刷新不会恢复。 */
export interface WorkflowSnapshot {
  workflowId: string;
  executionId: string;
  latestSequence: number;
  status: WorkflowStatus;
  currentStep: WorkflowStep;
  tasks: WorkflowTaskSnapshot[];
  startedAt: string;
  updatedAt: string;
}

/** 用户与 Agent 之间的一段连续对话。 */
export interface ConversationSession {
  conversationSessionId: string;
  title: string;
  tag: string;
  /** 侧栏归属；null 表示未分组，不参与会话上下文。 */
  projectId: string | null;
  /** 置顶时间；null 表示未置顶，与归属独立。 */
  pinnedAt: string | null;
  createdAt: string;
  updatedAt: string;
  /** 会话共享字段更新使用的服务端乐观锁版本。 */
  version: number;
  /** 摘要轮询提供的轻量状态，可早于详细会话刷新到达。 */
  latestExecutionStatus?: AgentExecutionStatus;
  messages: ConversationMessage[];
  execution: AgentExecution | null;
  workflow?: WorkflowSnapshot | null;
}

const BEIJING_TIME_ZONE = 'Asia/Shanghai';
const BEIJING_UTC_OFFSET = '+08:00';
const EXPLICIT_TIME_ZONE_SUFFIX = /(?:Z|[+-]\d{2}:\d{2})$/i;

export function formatTimestamp(value: string): string {
  // 后端 LocalDateTime 不携带偏移量，按北京时间解释后再统一格式化。
  const normalizedValue = EXPLICIT_TIME_ZONE_SUFFIX.test(value)
    ? value
    : `${value}${BEIJING_UTC_OFFSET}`;
  const date = new Date(normalizedValue);
  if (Number.isNaN(date.getTime())) return value;
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: BEIJING_TIME_ZONE,
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(date);
}
