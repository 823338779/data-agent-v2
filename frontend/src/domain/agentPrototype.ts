export type MessageRole = 'user' | 'assistant' | 'system';
export type AgentExecutionStatus =
  | 'idle'
  | 'queued'
  | 'running'
  | 'waiting_for_user'
  | 'waiting_confirmation'
  | 'done'
  | 'cancelled'
  | 'failed';

/** 可恢复的推理与回答流检查点，三个序号共同标识严格顺序。 */
export interface AgentExecutionStreamCheckpoint {
  executionAttemptNumber: number;
  modelInvocationSequence: number;
  phase: string;
  outputDeltaSequence: number;
  reasoning: string;
  answer: string;
  checkpointedAt: string | null;
}

export interface ConversationMessage {
  conversationMessageId: string;
  role: MessageRole;
  author: string;
  content: string;
  timestamp: string;
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
  title: string | null;
  summary: string | null;
}

/** 一条用户消息触发的完整 Agent 执行及其最新输出检查点。 */
export interface AgentExecution {
  executionId: string;
  status: AgentExecutionStatus;
  progress: number;
  objective: string;
  cancellationRequestedAt: string | null;
  events: AgentExecutionEvent[];
  /** 用于刷新恢复的最新流式输出检查点。 */
  streamCheckpoint: AgentExecutionStreamCheckpoint | null;
}

/** 用户与 Agent 之间的一段连续对话。 */
export interface ConversationSession {
  conversationSessionId: string;
  title: string;
  tag: string;
  createdAt: string;
  updatedAt: string;
  /** 会话共享字段更新使用的服务端乐观锁版本。 */
  version: number;
  unread: boolean;
  lastNotifiableEventSequence: number;
  sortOrder: number;
  orderingVersion: number;
  /** activity SSE 提供的轻量状态，可早于详细会话刷新到达。 */
  activityStatus?: AgentExecutionStatus;
  messages: ConversationMessage[];
  execution: AgentExecution | null;
}

/** 用户级活动流中的轻量会话投影。 */
export interface ConversationActivityItem {
  conversationSessionId: string;
  status: AgentExecutionStatus;
  unread: boolean;
  lastNotifiableEventSequence: number;
  sortOrder: number;
  updatedAt: string;
}

export interface ConversationActivitySnapshot {
  type: 'activity_snapshot';
  orderingVersion: number;
  sessions: ConversationActivityItem[];
  snapshotAt: string;
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
