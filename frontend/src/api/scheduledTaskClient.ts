import { BackendApiError } from './backendAgentClient';

export type ScheduleType = 'CRON' | 'MANUAL';
export type AssignmentState = 'MANUAL' | 'DISABLED' | 'PENDING_ASSIGNMENT' | 'ASSIGNED';
export type ExecutionStatus =
  'PENDING' | 'RUNNING' | 'CANCEL_REQUESTED' | 'COMPLETED' | 'FAILED' | 'CANCELLED';

export interface ScheduledTask {
  id: string;
  name: string;
  instruction: string;
  metricIdentityId: string;
  scheduleType: ScheduleType;
  cronExpression: string | null;
  zoneId: string | null;
  enabled: boolean;
  version: number;
  createdAt: string;
  updatedAt: string;
  deleted: boolean;
  deletedAt: string | null;
  assignmentState: AssignmentState;
  ownerNodeId: string | null;
  nextFireAt: string | null;
  lastExecution: { status: ExecutionStatus; occurredAt: string } | null;
}

export interface ScheduledTaskDraft {
  name: string;
  instruction: string;
  metricIdentityId: string;
  scheduleType: ScheduleType;
  cronExpression: string | null;
  zoneId: string | null;
  enabled: boolean;
}

export interface TaskExecutionSummary {
  id: string;
  taskId: string;
  triggerType: 'CRON' | 'MANUAL';
  status: ExecutionStatus;
  createdAt: string;
  startedAt: string | null;
  completedAt: string | null;
  errorCode: string | null;
  errorMessage: string | null;
}

export interface TaskExecutionDetail extends TaskExecutionSummary {
  executionEpoch: number;
  fencingToken: number;
  taskSnapshot: ScheduledTaskDraft & {
    taskId: string;
    taskVersion: number;
    scheduledFireAt: string | null;
  };
  targetNodeId: string;
  ownerNodeId: string | null;
  sessionId: string | null;
  agentRunId: string | null;
  claimedAt: string | null;
  cancelRequestedAt: string | null;
  links: { session: string | null; events: string };
}

export interface TaskTimelineEvent {
  source: 'SCHEDULER' | 'AGENT';
  sourceSequence: number;
  occurredAt: string;
  type: string;
  payload: Record<string, unknown>;
}

interface ProblemBody {
  code?: string;
  message?: string;
  detail?: string;
}

export class ScheduledTaskApiError extends BackendApiError {
  constructor(
    status: number,
    public readonly code: string,
    public readonly userMessage: string,
  ) {
    super(status);
  }
}

async function parse<T>(response: Response): Promise<T> {
  if (!response.ok) {
    const problem = (await response.json().catch(() => ({}))) as ProblemBody;
    throw new ScheduledTaskApiError(
      response.status,
      problem.code ?? 'REQUEST_FAILED',
      problem.message ?? problem.detail ?? `请求失败（${response.status}）`,
    );
  }
  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}

async function request<T>(url: string, init?: RequestInit): Promise<T> {
  return parse<T>(
    await fetch(url, {
      credentials: 'include',
      ...init,
      headers: init?.body ? { 'Content-Type': 'application/json', ...init.headers } : init?.headers,
    }),
  );
}

const root = '/api/v1/scheduled-tasks';

export const scheduledTaskClient = {
  list(includeDeleted = false) {
    return request<ScheduledTask[]>(`${root}?includeDeleted=${includeDeleted}`);
  },
  get(id: string) {
    return request<ScheduledTask>(`${root}/${id}`);
  },
  create(draft: ScheduledTaskDraft) {
    return request<ScheduledTask>(root, {
      method: 'POST',
      body: JSON.stringify(draft),
    });
  },
  update(id: string, draft: ScheduledTaskDraft, expectedVersion: number) {
    return request<ScheduledTask>(`${root}/${id}`, {
      method: 'PUT',
      body: JSON.stringify({ ...draft, expectedVersion }),
    });
  },
  remove(id: string) {
    return request<void>(`${root}/${id}`, { method: 'DELETE' });
  },
  execute(id: string, idempotencyKey: string) {
    return request<{ executionId: string }>(`${root}/${id}/executions`, {
      method: 'POST',
      headers: { 'Idempotency-Key': idempotencyKey },
    });
  },
  executionHistory(taskId: string) {
    return request<TaskExecutionSummary[]>(`${root}/${taskId}/executions`);
  },
  execution(executionId: string) {
    return request<TaskExecutionDetail>(`/api/v1/task-executions/${executionId}`);
  },
  cancel(executionId: string) {
    return request<void>(`/api/v1/task-executions/${executionId}/cancel`, {
      method: 'POST',
    });
  },
  subscribe(
    executionId: string,
    onEvent: (event: TaskTimelineEvent) => void,
    onState: (state: 'connected' | 'reconnecting') => void,
  ) {
    const source = new EventSource(`/api/v1/task-executions/${executionId}/events`);
    source.onopen = () => onState('connected');
    source.onerror = () => onState('reconnecting');
    source.addEventListener('timeline', (message) =>
      onEvent(JSON.parse((message as MessageEvent<string>).data) as TaskTimelineEvent),
    );
    return () => source.close();
  },
};
