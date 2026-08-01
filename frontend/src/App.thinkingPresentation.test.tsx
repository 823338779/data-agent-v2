import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import App from './App';

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe('thinking presentation', () => {
  it('places a compact thinking disclosure before the final assistant response', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        json: async () => [
          {
            id: 'session_done',
            title: '分析完成',
            tag: 'analysis',
            createdAt: '2026-07-18T08:00:00Z',
            updatedAt: '2026-07-18T08:01:00Z',
            messages: [
              {
                id: 'user_1',
                role: 'user',
                author: '项目负责人',
                content: '分析失败率',
                createdAt: '2026-07-18T08:00:00Z',
              },
              {
                id: 'answer_1',
                role: 'assistant',
                author: 'Data Agent',
                content: '失败率分析已经完成。',
                createdAt: '2026-07-18T08:01:00Z',
              },
            ],
            run: {
              id: 'run_done',
              status: 'done',
              progress: 100,
              objective: '分析失败率',
              events: [
                {
                  id: 'reason_1',
                  sequence: 1,
                  type: 'reasoning_delta',
                  occurredAt: '2026-07-18T08:00:10Z',
                  runId: 'run_done',
                  messageId: null,
                  title: '理解指标口径',
                  summary: '确认失败率的统计范围。',
                  toolName: null,
                  input: {},
                  output: {},
                  confirmationId: null,
                  approved: null,
                  status: 'done',
                },
                {
                  id: 'tool_1',
                  sequence: 2,
                  type: 'tool_call_started',
                  occurredAt: '2026-07-18T08:00:15Z',
                  runId: 'run_done',
                  messageId: null,
                  title: '查询任务数据',
                  summary: '读取近七天任务记录。',
                  toolName: 'metrics.query',
                  input: {},
                  output: {},
                  confirmationId: null,
                  approved: null,
                  status: 'running',
                },
                {
                  id: 'tool_2',
                  sequence: 3,
                  type: 'tool_call_completed',
                  occurredAt: '2026-07-18T08:00:20Z',
                  runId: 'run_done',
                  messageId: null,
                  title: '查询任务数据',
                  summary: '已读取近七天任务记录。',
                  toolName: 'metrics.query',
                  input: {},
                  output: {},
                  confirmationId: null,
                  approved: null,
                  status: 'done',
                },
              ],
              pendingConfirmation: null,
            },
          },
        ],
      }),
    );

    render(<App />);

    const answer = await screen.findByText('失败率分析已经完成。');
    const disclosure = screen.getByRole('button', { name: /思考过程/ });

    expect(disclosure).toHaveTextContent('思考过程');
    expect(disclosure).not.toHaveTextContent('执行过程');
    expect(
      disclosure.compareDocumentPosition(answer) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();

    fireEvent.click(disclosure);
    const thinking = screen.getByLabelText('会话内思考过程');
    expect(within(thinking).getByText('确认失败率的统计范围。')).toBeInTheDocument();
    expect(within(thinking).getByText('metrics.query')).toBeInTheDocument();
    expect(within(thinking).queryByText('已完成')).not.toBeInTheDocument();
  });
});
