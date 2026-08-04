import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { ThinkingProcessItem } from '../domain/agentPrototype';
import { MessageProcessTimeline } from './MessageProcessTimeline';

const thinkingItem: ThinkingProcessItem = {
  type: 'THINKING',
  processItemId: 'thinking_1',
  sequence: 1,
  status: 'SUCCEEDED',
  createdAt: '2026-08-04T01:00:00Z',
  startedAt: '2026-08-04T01:00:00Z',
  completedAt: '2026-08-04T01:00:00.012Z',
  durationMillis: 12,
  schemaVersion: 1,
  content: '分析数据范围',
  contentTruncated: false,
  originalContentSizeBytes: 18,
  contentSha256: 'thinking-sha',
  modelInvocationSequence: 1,
  modelProvider: 'openai',
  modelName: 'gpt-test',
};

const toolItem: ThinkingProcessItem = {
  type: 'TOOL',
  processItemId: 'tool_1',
  sequence: 2,
  status: 'FAILED',
  createdAt: '2026-08-04T01:00:01Z',
  startedAt: '2026-08-04T01:00:01Z',
  completedAt: '2026-08-04T01:00:01.020Z',
  durationMillis: 20,
  schemaVersion: 1,
  toolId: 'metric-query',
  toolName: '指标查询',
  toolExecutionId: 'tool_execution_1',
  toolCallId: 'tool_call_1',
  modelInvocationSequence: 1,
  input: {
    value: { metric: 'orders' },
    preview: null,
    truncated: false,
    originalSizeBytes: 19,
    sha256: 'a'.repeat(64),
    contentType: 'application/json',
  },
  output: {
    value: null,
    preview: '{"rows":[]}',
    truncated: true,
    originalSizeBytes: 8192,
    sha256: 'b'.repeat(64),
    contentType: 'application/json',
  },
  success: false,
  errorCode: 'TOOL_TIMEOUT',
  errorMessage: '工具执行超时',
};

describe('MessageProcessTimeline', () => {
  it('renders ordered process items with duration and truncation metadata', () => {
    render(<MessageProcessTimeline items={[thinkingItem, toolItem]} />);

    expect(screen.getAllByTestId('process-item').map((node) => node.dataset.id)).toEqual([
      'thinking_1',
      'tool_1',
    ]);
    expect(screen.getByText('12 ms')).toBeInTheDocument();
    expect(screen.getByText('输出已截断')).toBeInTheDocument();
    expect(screen.getByText('TOOL_TIMEOUT：工具执行超时')).toBeInTheDocument();
  });

  it('renders nothing for an empty process', () => {
    const { container } = render(<MessageProcessTimeline items={[]} />);

    expect(container).toBeEmptyDOMElement();
  });
});
