import { AgentEvent, AgentEventStreamAdapter } from '../api/agentEvents';

export const mockAgentEvents: AgentEvent[] = [
  {
    eventId: 'event-001',
    type: 'run.started',
    sessionId: 'session-kafka',
    runId: 'run-kafka-001',
    sequence: 1,
    createdAt: '2026-07-08T13:12:00Z',
    payload: {
      objective: '定位 Kafka 订单链路从 13:00 开始延迟升高的原因',
    },
  },
  {
    eventId: 'event-002',
    type: 'message.created',
    sessionId: 'session-kafka',
    runId: 'run-kafka-001',
    sequence: 2,
    createdAt: '2026-07-08T13:12:01Z',
    payload: {
      messageId: 'message-run-started',
      role: 'assistant',
      author: 'Data Agent',
      content: '我会先检查 Topic 积压、Flink checkpoint、下游写入和最近发布记录。',
      timestamp: '13:12',
    },
  },
  {
    eventId: 'event-003',
    type: 'reasoning.delta',
    sessionId: 'session-kafka',
    runId: 'run-kafka-001',
    sequence: 3,
    createdAt: '2026-07-08T13:12:03Z',
    payload: {
      stepId: 'step-plan',
      title: '拆解目标与约束',
      summary: '先限定 13:00 后订单链路的延迟窗口，再核对上游 Topic、Flink checkpoint 和下游写入。',
    },
  },
  {
    eventId: 'event-004',
    type: 'tool.call.started',
    sessionId: 'session-kafka',
    runId: 'run-kafka-001',
    sequence: 4,
    createdAt: '2026-07-08T13:12:06Z',
    payload: {
      toolCallId: 'tool-catalog',
      toolName: 'catalog.search',
      title: '查询元数据目录',
      summary: '定位订单链路近 7 天可用的数据集、血缘关系和质量告警。',
      input: { keyword: 'order_latency', range: '7d' },
    },
  },
  {
    eventId: 'event-005',
    type: 'tool.call.completed',
    sessionId: 'session-kafka',
    runId: 'run-kafka-001',
    sequence: 5,
    createdAt: '2026-07-08T13:12:09Z',
    payload: {
      toolCallId: 'tool-catalog',
      output: '命中 3 个表，主表 freshness 正常，维表存在 1 条延迟告警。',
    },
  },
  {
    eventId: 'event-006',
    type: 'tool.call.started',
    sessionId: 'session-kafka',
    runId: 'run-kafka-001',
    sequence: 6,
    createdAt: '2026-07-08T13:12:11Z',
    payload: {
      toolCallId: 'tool-spark',
      toolName: 'spark.sql',
      title: '执行分析 SQL',
      summary: '在隔离会话中生成并运行聚合查询，返回异常分布。',
      input: 'SELECT dt, owner, fail_rate FROM ads_task_quality WHERE dt >= current_date - 7',
    },
  },
  {
    eventId: 'event-007',
    type: 'approval.requested',
    sessionId: 'session-kafka',
    runId: 'run-kafka-001',
    sequence: 7,
    createdAt: '2026-07-08T13:12:18Z',
    payload: {
      approvalId: 'approval-export-daily',
      title: '确认导出日报',
      description: '日报会包含负责人字段、任务链接和近 7 天失败率明细。确认后我再生成可分享链接。',
      riskLevel: 'medium',
    },
  },
];

export function createMockAgentEventStream(
  options: { intervalMs?: number } = {},
): AgentEventStreamAdapter {
  const intervalMs = options.intervalMs ?? 180;

  return {
    subscribe(sessionId, onEvent) {
      const timers = mockAgentEvents
        .map((event) => ({
          ...event,
          sessionId,
          runId: `mock-${sessionId}`,
        }))
        .map((event, index) => window.setTimeout(() => onEvent(event), index * intervalMs));

      return {
        unsubscribe() {
          timers.forEach((timer) => window.clearTimeout(timer));
        },
      };
    },
  };
}
