import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import App from './App';

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe('conversation-first agent prototype', () => {
  it('does not render a fixed run card before a backend run exists', () => {
    render(<App />);

    expect(screen.queryByLabelText('思考与工具过程')).not.toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: /等待你的确认|正在执行|正在理解/ }),
    ).not.toBeInTheDocument();
  });

  it('renders thinking details and approval only when the backend returns those events', async () => {
    const serverSession = {
      id: 'session_backend',
      title: '后端联调会话',
      tag: 'debug',
      createdAt: '2026-07-08T13:00:00Z',
      updatedAt: '2026-07-08T13:00:00Z',
      messages: [],
      run: null,
    };
    const runSession = {
      ...serverSession,
      messages: [
        {
          id: 'message_user',
          role: 'user',
          author: 'Project Director',
          content: '分析延迟',
          createdAt: '2026-07-08T13:01:00Z',
        },
      ],
      run: {
        id: 'run_backend',
        status: 'waiting_confirmation',
        progress: 68,
        objective: '分析延迟',
        events: [
          {
            id: 'event_reasoning',
            sequence: 1,
            type: 'reasoning_delta',
            occurredAt: '2026-07-08T13:01:01Z',
            runId: 'run_backend',
            messageId: null,
            title: '拆解目标',
            summary: '先定位上游积压和下游写入。',
            toolName: null,
            input: {},
            output: {},
            confirmationId: null,
            approved: null,
            status: 'done',
          },
          {
            id: 'event_tool',
            sequence: 2,
            type: 'tool_call_started',
            occurredAt: '2026-07-08T13:01:02Z',
            runId: 'run_backend',
            messageId: null,
            title: '查询元数据目录',
            summary: '正在搜索可用数据集。',
            toolName: 'catalog.search',
            input: {},
            output: {},
            confirmationId: null,
            approved: null,
            status: 'running',
          },
          {
            id: 'event_approval',
            sequence: 3,
            type: 'user_confirmation_requested',
            occurredAt: '2026-07-08T13:01:03Z',
            runId: 'run_backend',
            messageId: null,
            title: '确认导出日报',
            summary: '导出包含负责人字段，需要你的确认。',
            toolName: null,
            input: {},
            output: {},
            confirmationId: 'confirmation_1',
            approved: null,
            status: 'waiting',
          },
        ],
        pendingConfirmation: {
          id: 'confirmation_1',
          title: '确认导出日报',
          summary: '导出包含负责人字段，需要你的确认。',
          detail: '审批后继续。',
          requestedAt: '2026-07-08T13:01:03Z',
        },
      },
    };
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce({ ok: true, json: async () => [serverSession] })
      .mockResolvedValueOnce({ ok: true, json: async () => runSession });
    vi.stubGlobal('fetch', fetchMock);

    render(<App />);
    await screen.findByRole('heading', { name: '后端联调会话' });
    fireEvent.change(screen.getByLabelText('Prompt'), { target: { value: '分析延迟' } });
    fireEvent.keyDown(screen.getByLabelText('Prompt'), { key: 'Enter' });

    await screen.findByRole('button', { name: /等待你的确认/ });
    expect(screen.getAllByText('确认导出日报').length).toBeGreaterThanOrEqual(2);
    const thinkingFlow = await screen.findByLabelText('会话内思考过程');
    expect(within(thinkingFlow).getByText('先定位上游积压和下游写入。')).toBeInTheDocument();
    expect(within(thinkingFlow).getByText('catalog.search')).toBeInTheDocument();
  });

  it('submits prompts through the backend API when Enter is pressed', async () => {
    const serverSession = {
      id: 'session_backend',
      title: '后端联调会话',
      tag: 'debug',
      createdAt: '2026-07-08T13:00:00Z',
      updatedAt: '2026-07-08T13:00:00Z',
      messages: [
        {
          id: 'message_1',
          role: 'assistant',
          author: 'Data Agent',
          content: 'I am connected to catalog.',
          createdAt: '2026-07-08T13:00:00Z',
        },
      ],
      run: null,
    };
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce({ ok: true, json: async () => [serverSession] })
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({
          ...serverSession,
          messages: [
            ...serverSession.messages,
            {
              id: 'message_user',
              role: 'user',
              author: 'Project Director',
              content: '调试后端请求',
              createdAt: '2026-07-08T13:01:00Z',
            },
          ],
          run: {
            id: 'run_backend',
            status: 'waiting_confirmation',
            progress: 68,
            objective: '调试后端请求',
            events: [],
            pendingConfirmation: null,
          },
        }),
      });
    vi.stubGlobal('fetch', fetchMock);

    render(<App />);
    await screen.findByRole('heading', { name: '后端联调会话' });

    fireEvent.change(screen.getByLabelText('Prompt'), { target: { value: '调试后端请求' } });
    fireEvent.keyDown(screen.getByLabelText('Prompt'), { key: 'Enter', code: 'Enter' });

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/agent-sessions/session_backend/messages',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ content: '调试后端请求' }),
      }),
    );
  });

  it('keeps Shift+Enter and IME composition from submitting the prompt', async () => {
    const session = {
      id: 'session_backend',
      title: '后端联调会话',
      tag: 'debug',
      createdAt: '2026-07-08T13:00:00Z',
      updatedAt: '2026-07-08T13:00:00Z',
      messages: [],
      run: null,
    };
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => [session] });
    vi.stubGlobal('fetch', fetchMock);

    render(<App />);
    await screen.findByRole('heading', { name: '后端联调会话' });
    const input = screen.getByLabelText('Prompt');
    fireEvent.change(input, { target: { value: '保留换行' } });
    fireEvent.keyDown(input, { key: 'Enter', shiftKey: true });
    fireEvent.keyDown(input, { key: 'Enter', keyCode: 229, isComposing: true });

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(input).toHaveValue('保留换行');
  });

  it('does not show a process card until a backend run event exists', async () => {
    const session = {
      id: 'session_backend',
      title: '后端联调会话',
      tag: 'debug',
      createdAt: '2026-07-08T13:00:00Z',
      updatedAt: '2026-07-08T13:00:00Z',
      messages: [],
      run: null,
    };
    let resolveMessage: ((value: unknown) => void) | undefined;
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce({ ok: true, json: async () => [session] })
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            resolveMessage = resolve;
          }),
      );
    vi.stubGlobal('fetch', fetchMock);

    render(<App />);
    await screen.findByRole('heading', { name: '后端联调会话' });
    fireEvent.change(screen.getByLabelText('Prompt'), { target: { value: '等待真实事件' } });
    fireEvent.keyDown(screen.getByLabelText('Prompt'), { key: 'Enter' });

    expect(screen.getByText('等待真实事件')).toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: /正在理解|正在执行|等待你的确认/ }),
    ).not.toBeInTheDocument();
    resolveMessage?.({ ok: true, json: async () => session });
  });

  it('restores the failed prompt and retries the same backend request', async () => {
    const session = {
      id: 'session_backend',
      title: '后端联调会话',
      tag: 'debug',
      createdAt: '2026-07-08T13:00:00Z',
      updatedAt: '2026-07-08T13:00:00Z',
      messages: [],
      run: null,
    };
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce({ ok: true, json: async () => [session] })
      .mockRejectedValueOnce(new Error('network down'))
      .mockResolvedValueOnce({ ok: true, json: async () => session });
    vi.stubGlobal('fetch', fetchMock);

    render(<App />);
    await screen.findByRole('heading', { name: '后端联调会话' });
    fireEvent.change(screen.getByLabelText('Prompt'), { target: { value: '重试原文' } });
    fireEvent.keyDown(screen.getByLabelText('Prompt'), { key: 'Enter' });

    expect(await screen.findByRole('button', { name: '重试发送' })).toBeInTheDocument();
    expect(screen.getByLabelText('Prompt')).toHaveValue('重试原文');
    fireEvent.click(screen.getByRole('button', { name: '重试发送' }));

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(3));
  });

  it('does not take scroll position from users reading message history', async () => {
    const session = {
      id: 'session_backend',
      title: '后端联调会话',
      tag: 'debug',
      createdAt: '2026-07-08T13:00:00Z',
      updatedAt: '2026-07-08T13:00:00Z',
      messages: [],
      run: null,
    };
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce({ ok: true, json: async () => [session] })
      .mockImplementationOnce(() => new Promise(() => undefined));
    vi.stubGlobal('fetch', fetchMock);

    render(<App />);
    await screen.findByRole('heading', { name: '后端联调会话' });
    const messages = screen.getByLabelText('消息列表');
    Object.defineProperty(messages, 'scrollHeight', { configurable: true, value: 1200 });
    Object.defineProperty(messages, 'clientHeight', { configurable: true, value: 400 });
    messages.scrollTop = 0;
    fireEvent.scroll(messages);

    fireEvent.change(screen.getByLabelText('Prompt'), { target: { value: '不要抢滚动' } });
    fireEvent.keyDown(screen.getByLabelText('Prompt'), { key: 'Enter' });

    await waitFor(() => expect(screen.getByText('不要抢滚动')).toBeInTheDocument());
    expect(messages.scrollTop).toBe(0);
  });

  it('creates a new backend session from the new conversation button', async () => {
    const serverSession = {
      id: 'session_backend',
      title: '后端联调会话',
      tag: 'debug',
      createdAt: '2026-07-08T13:00:00Z',
      updatedAt: '2026-07-08T13:00:00Z',
      messages: [],
      run: null,
    };
    const createdSession = {
      ...serverSession,
      id: 'session_created',
      title: '联调会话 14:40',
      tag: 'realtime',
    };
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce({ ok: true, json: async () => [serverSession] })
      .mockResolvedValueOnce({ ok: true, json: async () => createdSession });
    vi.stubGlobal('fetch', fetchMock);

    render(<App />);
    await screen.findByRole('heading', { name: '后端联调会话' });

    fireEvent.click(screen.getByRole('button', { name: '新会话' }));

    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith(
        '/api/v1/agent-sessions',
        expect.objectContaining({
          method: 'POST',
          body: expect.stringContaining('"tag":"realtime"'),
        }),
      ),
    );
    expect(await screen.findByRole('heading', { name: '联调会话 14:40' })).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('已在服务端创建新会话');
  });

  it('makes search and mode controls visibly change local state instead of acting as no-op buttons', async () => {
    render(<App />);

    fireEvent.click(screen.getByRole('button', { name: '搜索会话' }));
    fireEvent.change(screen.getByPlaceholderText('输入会话标题或标签'), {
      target: { value: 'Kafka' },
    });

    expect(screen.getAllByText('Kafka 延迟诊断').length).toBeGreaterThan(0);
    expect(screen.queryByText('数据质量日报')).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '深度分析' }));

    expect(screen.getByRole('button', { name: '深度分析' })).toHaveClass('active');
    expect(screen.getByLabelText('Prompt')).toHaveValue(
      '深度分析近 7 天任务失败率波动，给出数据口径、异常归因和复核 SQL',
    );
    expect(screen.getByRole('status')).toHaveTextContent('已切换到深度分析模式');
  });
});
