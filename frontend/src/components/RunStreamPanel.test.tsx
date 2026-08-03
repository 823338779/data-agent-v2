import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { RunStreamPanel } from './RunStreamPanel';

describe('RunStreamPanel', () => {
  it('renders only the streamed answer', () => {
    render(
      <RunStreamPanel
        checkpoint={{
          executionAttemptNumber: 1,
          modelInvocationSequence: 1,
          phase: 'ANSWERING',
          outputDeltaSequence: 2,
          answer: '已找到结果',
          checkpointedAt: null,
        }}
      />,
    );

    expect(screen.getByLabelText('回答')).toHaveTextContent('已找到结果');
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });
});
