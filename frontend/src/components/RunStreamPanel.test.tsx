import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { RunStreamPanel } from './RunStreamPanel';

describe('RunStreamPanel', () => {
  it('separates reasoning from answer and expands reasoning while running', () => {
    render(
      <RunStreamPanel
        status="running"
        checkpoint={{
          executionAttemptNumber: 1,
          modelInvocationSequence: 1,
          phase: 'THINKING',
          outputDeltaSequence: 2,
          reasoning: '正在检查订单',
          answer: '已找到结果',
          checkpointedAt: null,
        }}
      />,
    );

    expect(screen.getByText('推理过程').closest('details')).toHaveAttribute('open');
    expect(screen.getByLabelText('回答')).toHaveTextContent('已找到结果');
  });
});
