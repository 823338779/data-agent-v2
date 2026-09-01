import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { WorkflowRunPanel } from './WorkflowRunPanel';

describe('WorkflowRunPanel', () => {
  it('shows task progress, truncation and the complete validated SQL as plain code', () => {
    const sql =
      'SELECT region_code, SUM(paid_amount) FROM fact_orders GROUP BY region_code LIMIT 1000';
    render(
      <WorkflowRunPanel
        workflow={{
          workflowId: 'workflow_1',
          executionId: 'execution_1',
          latestSequence: 8,
          status: 'running',
          currentStep: 'execute_query',
          startedAt: '2026-08-30T12:00:00Z',
          updatedAt: '2026-08-30T12:00:01Z',
          tasks: [
            {
              task: { taskId: 'sales', purpose: '统计地区销售额', index: 1, total: 2 },
              currentStep: 'execute_query',
              sql,
              returnedRows: 1000,
              truncated: true,
              attempt: 2,
              message: '查询执行完成',
            },
          ],
        }}
      />,
    );

    expect(screen.getByText('1/2 · 统计地区销售额')).toBeInTheDocument();
    expect(screen.getByText('第 2 次尝试')).toBeInTheDocument();
    expect(screen.getByText('返回 1000 行（已按上限截断）')).toBeInTheDocument();
    expect(screen.getByText(sql)).toBeInTheDocument();
    expect(screen.getByText(sql).closest('code')).not.toBeNull();
  });
});
