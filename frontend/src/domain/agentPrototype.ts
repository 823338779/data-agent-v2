export type MessageRole = 'user' | 'assistant' | 'system';

export interface ConversationMessage {
  id: string;
  role: MessageRole;
  author: string;
  content: string;
  timestamp: string;
}

export interface AgentRunEvent {
  id: string;
  sequence: number;
  type: string;
  occurredAt: string;
  title: string | null;
  summary: string | null;
}

export interface AgentRun {
  id: string;
  status: string;
  progress: number;
  objective: string;
  events: AgentRunEvent[];
  stream: {
    phase: string;
    thinking: string;
    answer: string;
  } | null;
}

export interface AgentSession {
  id: string;
  title: string;
  tag: string;
  createdAt: string;
  updatedAt: string;
  messages: ConversationMessage[];
  run: AgentRun | null;
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
