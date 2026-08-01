export type MessageRole = 'user' | 'assistant' | 'system';

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
  status: string;
  progress: number;
  objective: string;
  events: AgentExecutionEvent[];
  /** 用于刷新恢复的最新流式输出检查点。 */
  streamCheckpoint: {
    phase: string;
    thinking: string;
    answer: string;
  } | null;
}

/** 用户与 Agent 之间的一段连续对话。 */
export interface ConversationSession {
  conversationSessionId: string;
  title: string;
  tag: string;
  createdAt: string;
  updatedAt: string;
  messages: ConversationMessage[];
  execution: AgentExecution | null;
}

export function formatTimestamp(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return new Intl.DateTimeFormat('zh-CN', {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(date);
}
