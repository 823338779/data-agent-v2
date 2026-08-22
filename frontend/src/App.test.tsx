import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AgentBackendClient, BackendApiError } from './api/backendAgentClient';
import App from './App';
import { ConversationSession } from './domain/agentPrototype';

const session: ConversationSession = {
  conversationSessionId: 'session_1',
  title: '订单延迟分析',
  tag: 'debug',
  createdAt: '2026-08-01T08:00:00Z',
  updatedAt: '2026-08-01T08:00:00Z',
  version: 4,
  messages: [],
  execution: null,
};

function completedExecution(
  executionId: string,
  answer: string,
): NonNullable<ConversationSession['execution']> {
  return {
    executionId,
    status: 'done',
    progress: 100,
    objective: answer,
    cancellationRequestedAt: null,
    events: [],
    streamCheckpoint: {
      executionAttemptNumber: 1,
      modelInvocationSequence: 1,
      phase: 'COMPLETED',
      outputDeltaSequence: 1,
      answer,
      checkpointedAt: '2026-08-01T08:01:00Z',
    },
  };
}

function client(overrides: Partial<AgentBackendClient> = {}): AgentBackendClient {
  return {
    listSessions: vi.fn().mockResolvedValue([session]),
    createSession: vi
      .fn()
      .mockResolvedValue({ ...session, conversationSessionId: 'session_2', title: '新会话' }),
    getSession: vi.fn().mockResolvedValue(session),
    renameSession: vi.fn().mockResolvedValue({ ...session, version: session.version + 1 }),
    submitMessage: vi.fn().mockResolvedValue({
      ...session,
      messages: [
        {
          conversationMessageId: 'message_1',
          role: 'user',
          author: 'user_admin',
          content: '分析订单延迟',
          timestamp: '2026-08-01T08:01:00Z',
          version: 0,
          thinkingProcess: [],
          execution: null,
        },
      ],
      execution: {
        executionId: 'execution_1',
        status: 'running',
        progress: 5,
        objective: '分析订单延迟',
        cancellationRequestedAt: null,
        events: [],
        streamCheckpoint: null,
      },
    }),
    cancelExecution: vi.fn(),
    retryExecution: vi.fn(),
    subscribeSessionEvents: vi.fn().mockReturnValue(() => undefined),
    ...overrides,
  };
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.useRealTimers();
  window.history.replaceState(null, '', '/');
});

describe('server-backed conversation workspace', () => {
  it('shows persisted sessions and submits a message through the backend', async () => {
    const backend = client();
    render(<App client={backend} />);

    fireEvent.click((await screen.findAllByRole('button', { name: /订单延迟分析/ }))[0]);
    const input = screen.getByLabelText('输入消息');
    fireEvent.change(input, { target: { value: '分析订单延迟' } });
    fireEvent.keyDown(input, { key: 'Enter' });

    await waitFor(() =>
      expect(backend.submitMessage).toHaveBeenCalledWith('session_1', '分析订单延迟'),
    );
    expect(await screen.findByText('分析订单延迟')).toBeInTheDocument();
    expect(screen.getAllByText('处理中')).not.toHaveLength(0);
  });

  it('does not duplicate persisted assistant answers with execution checkpoints', async () => {
    const firstExecution = completedExecution('execution_1', '第一条回答');
    const secondExecution = completedExecution('execution_2', '第二条回答');
    const history: ConversationSession = {
      ...session,
      messages: [
        {
          conversationMessageId: 'answer_1',
          role: 'assistant',
          author: 'assistant',
          content: '第一条回答',
          timestamp: '2026-08-01T08:01:00Z',
          version: 1,
          thinkingProcess: [],
          execution: firstExecution,
        },
        {
          conversationMessageId: 'answer_2',
          role: 'assistant',
          author: 'assistant',
          content: '第二条回答',
          timestamp: '2026-08-01T08:02:00Z',
          version: 1,
          thinkingProcess: [],
          execution: secondExecution,
        },
      ],
      execution: secondExecution,
    };

    render(<App client={client({ listSessions: vi.fn().mockResolvedValue([history]) })} />);
    fireEvent.click((await screen.findAllByRole('button', { name: /订单延迟分析/ }))[0]);

    expect(screen.queryByText('推理过程')).not.toBeInTheDocument();
    expect(screen.queryByText('查看运行过程')).not.toBeInTheDocument();
    expect(screen.getAllByText('第一条回答')).toHaveLength(1);
    expect(screen.getAllByText('第二条回答')).toHaveLength(1);
  });

  it('renders assistant answers as GFM markdown while keeping user messages as plain text', async () => {
    const history: ConversationSession = {
      ...session,
      messages: [
        {
          conversationMessageId: 'question_1',
          role: 'user',
          author: 'user_admin',
          content: '**不要解析**',
          timestamp: '2026-08-01T08:01:00Z',
          version: 1,
          thinkingProcess: [],
          execution: null,
        },
        {
          conversationMessageId: 'answer_1',
          role: 'assistant',
          author: 'assistant',
          content: '# 期末试卷\n\n- 第一题\n\n| 题目 | 分值 |\n| --- | ---: |\n| 计算 | 10 |',
          timestamp: '2026-08-01T08:02:00Z',
          version: 1,
          thinkingProcess: [],
          execution: null,
        },
      ],
    };

    render(<App client={client({ listSessions: vi.fn().mockResolvedValue([history]) })} />);
    fireEvent.click((await screen.findAllByRole('button', { name: /订单延迟分析/ }))[0]);

    expect(screen.getByRole('heading', { name: '期末试卷' })).toBeInTheDocument();
    expect(screen.getByRole('table')).toBeInTheDocument();
    expect(screen.getByText('**不要解析**').tagName).toBe('P');
    expect(screen.queryByText('不要解析')).not.toBeInTheDocument();
  });

  it('closes the previous detail stream before opening the viewed processing session', async () => {
    const running = {
      ...session,
      latestExecutionStatus: 'running' as const,
      execution: {
        ...completedExecution('execution_1', ''),
        status: 'running' as const,
        progress: 25,
        streamCheckpoint: null,
      },
    };
    const second = {
      ...running,
      conversationSessionId: 'session_2',
      title: '库存分析',
    };
    const closeFirst = vi.fn();
    const closeSecond = vi.fn();
    const backend = client({
      listSessions: vi.fn().mockResolvedValue([running, second]),
      getSession: vi.fn((sessionId) =>
        Promise.resolve(sessionId === 'session_1' ? running : second),
      ),
      subscribeSessionEvents: vi
        .fn()
        .mockReturnValueOnce(closeFirst)
        .mockReturnValueOnce(closeSecond),
    });
    render(<App client={backend} />);

    fireEvent.click((await screen.findAllByRole('button', { name: /订单延迟分析/ }))[0]);
    await waitFor(() =>
      expect(backend.subscribeSessionEvents).toHaveBeenCalledWith(
        'session_1',
        expect.any(Function),
        expect.any(Function),
      ),
    );
    fireEvent.click(screen.getByRole('button', { name: /库存分析/ }));

    await waitFor(() => expect(backend.subscribeSessionEvents).toHaveBeenCalledTimes(2));
    expect(closeFirst).toHaveBeenCalledOnce();
    expect(backend.cancelExecution).not.toHaveBeenCalled();
  });

  it('applies execution updates from SSE without refetching the full session', async () => {
    const running = {
      ...session,
      latestExecutionStatus: 'running' as const,
      execution: {
        ...completedExecution('execution_1', ''),
        status: 'running' as const,
        streamCheckpoint: null,
      },
    };
    const backend = client({
      listSessions: vi.fn().mockResolvedValue([running]),
      getSession: vi.fn().mockResolvedValue(running),
    });
    render(<App client={backend} />);

    fireEvent.click((await screen.findAllByRole('button', { name: /订单延迟分析/ }))[0]);
    await waitFor(() => expect(backend.subscribeSessionEvents).toHaveBeenCalledOnce());
    const onEvent = vi.mocked(backend.subscribeSessionEvents).mock.calls[0][1];

    act(() =>
      onEvent({
        type: 'execution_update',
        conversationSessionId: 'session_1',
        execution: {
          executionId: 'execution_1',
          status: 'running',
          progress: 25,
          objective: '分析订单延迟',
          cancellationRequestedAt: null,
          streamCheckpoint: null,
        },
        event: {
          executionEventId: 'event_1',
          executionEventSequence: 1,
          type: 'execution_started',
          occurredAt: '2026-08-01T08:01:00Z',
          title: '开始执行',
          summary: null,
        },
        messages: [],
      }),
    );

    expect(screen.getAllByText('处理中')).not.toHaveLength(0);
    expect(backend.getSession).toHaveBeenCalledTimes(1);
  });

  it('keeps one detail stream while execution moves between processing states', async () => {
    const queued = {
      ...session,
      latestExecutionStatus: 'queued' as const,
      execution: {
        ...completedExecution('execution_1', ''),
        status: 'queued' as const,
        progress: 0,
        streamCheckpoint: null,
      },
    };
    const closeDetail = vi.fn();
    let listener: Parameters<AgentBackendClient['subscribeSessionEvents']>[1] | undefined;
    const backend = client({
      listSessions: vi.fn().mockResolvedValue([queued]),
      getSession: vi.fn().mockResolvedValue(queued),
      subscribeSessionEvents: vi.fn((_sessionId, onEvent) => {
        listener = onEvent;
        return closeDetail;
      }),
    });
    render(<App client={backend} />);
    fireEvent.click((await screen.findAllByRole('button', { name: /订单延迟分析/ }))[0]);
    await waitFor(() => expect(listener).toBeDefined());

    act(() =>
      listener?.({
        type: 'execution_update',
        conversationSessionId: 'session_1',
        execution: {
          executionId: 'execution_1',
          status: 'running',
          progress: 25,
          objective: '分析订单延迟',
          cancellationRequestedAt: null,
          streamCheckpoint: null,
        },
        event: {
          executionEventId: 'event_running',
          executionEventSequence: 1,
          type: 'execution_started',
          occurredAt: '2026-08-01T08:01:00Z',
          title: '开始执行',
          summary: null,
        },
        messages: [],
      }),
    );

    expect(backend.subscribeSessionEvents).toHaveBeenCalledOnce();
    expect(closeDetail).not.toHaveBeenCalled();
  });

  it('shows no notice or connection warning after a completed execution', async () => {
    const running = {
      ...session,
      latestExecutionStatus: 'running' as const,
      execution: {
        ...completedExecution('execution_1', ''),
        status: 'running' as const,
        streamCheckpoint: null,
      },
    };
    const closeDetail = vi.fn();
    let listener: Parameters<AgentBackendClient['subscribeSessionEvents']>[1] | undefined;
    let errorListener: Parameters<AgentBackendClient['subscribeSessionEvents']>[2] | undefined;
    const backend = client({
      listSessions: vi.fn().mockResolvedValue([running]),
      getSession: vi.fn().mockResolvedValue(running),
      subscribeSessionEvents: vi.fn((_sessionId, onEvent, onError) => {
        listener = onEvent;
        errorListener = onError;
        return closeDetail;
      }),
    });
    render(<App client={backend} />);
    fireEvent.click((await screen.findAllByRole('button', { name: /订单延迟分析/ }))[0]);
    await waitFor(() => expect(listener).toBeDefined());

    act(() =>
      listener?.({
        type: 'execution_update',
        conversationSessionId: 'session_1',
        execution: {
          executionId: 'execution_1',
          status: 'done',
          progress: 100,
          objective: '分析订单延迟',
          cancellationRequestedAt: null,
          streamCheckpoint: null,
        },
        event: {
          executionEventId: 'event_done',
          executionEventSequence: 2,
          type: 'execution_completed',
          occurredAt: '2026-08-01T08:02:00Z',
          title: '执行完成',
          summary: null,
        },
        messages: [],
      }),
    );

    await waitFor(() => expect(closeDetail).toHaveBeenCalledOnce());
    act(() => errorListener?.());
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(backend.cancelExecution).not.toHaveBeenCalled();
  });

  it('opens no SSE for a terminal current session', async () => {
    const terminal = { ...session, execution: completedExecution('execution_done', '完成') };
    const backend = client({ getSession: vi.fn().mockResolvedValue(terminal) });
    render(<App client={backend} />);

    fireEvent.click((await screen.findAllByRole('button', { name: /订单延迟分析/ }))[0]);

    await waitFor(() => expect(backend.getSession).toHaveBeenCalledWith('session_1'));
    expect(backend.subscribeSessionEvents).not.toHaveBeenCalled();
  });

  it('polls processing background summaries every minute and preserves viewed details', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const viewed = {
      ...session,
      messages: [
        {
          conversationMessageId: 'assistant_1',
          executionId: 'execution_viewed',
          role: 'assistant' as const,
          author: 'assistant',
          content: '当前详情回答',
          timestamp: '2026-08-01T08:01:00Z',
          version: 1,
          thinkingProcess: [],
          execution: completedExecution('execution_viewed', '当前详情回答'),
        },
      ],
      execution: completedExecution('execution_viewed', '当前详情回答'),
    };
    const background = {
      ...session,
      conversationSessionId: 'session_2',
      title: '后台分析',
      latestExecutionStatus: 'running' as const,
    };
    const listSessions = vi.fn().mockResolvedValue([viewed, background]);
    const backend = client({ listSessions, getSession: vi.fn().mockResolvedValue(viewed) });
    render(<App client={backend} />);
    fireEvent.click((await screen.findAllByRole('button', { name: /订单延迟分析/ }))[0]);
    await screen.findByText('当前详情回答');

    await act(async () => vi.advanceTimersByTimeAsync(60_000));

    expect(listSessions).toHaveBeenCalledTimes(2);
    expect(screen.getAllByText('当前详情回答')).toHaveLength(1);
    expect(backend.subscribeSessionEvents).not.toHaveBeenCalled();
  });

  it('renames a session from the sidebar and updates the active heading', async () => {
    const renamed = { ...session, title: '新的会话标题', version: 5 };
    const backend = client({ renameSession: vi.fn().mockResolvedValue(renamed) });
    render(<App client={backend} />);
    const sidebar = await screen.findByRole('complementary', { name: '会话列表' });

    const sessionButton = await within(sidebar).findByRole('button', { name: /订单延迟分析/ });
    fireEvent.click(sessionButton);
    expect(await screen.findByRole('heading', { name: '订单延迟分析' })).toBeInTheDocument();
    fireEvent.doubleClick(sessionButton);
    const titleInput = within(sidebar).getByRole('textbox', {
      name: '编辑“订单延迟分析”标题',
    });
    fireEvent.change(titleInput, {
      target: { value: '  新的会话标题  ' },
    });
    fireEvent.keyDown(titleInput, { key: 'Enter' });

    await waitFor(() =>
      expect(backend.renameSession).toHaveBeenCalledWith('session_1', '新的会话标题', 4),
    );
    expect(await screen.findByRole('heading', { name: '新的会话标题' })).toBeInTheDocument();
  });

  it('reloads the server title when a rename conflicts', async () => {
    const latest = { ...session, title: '其他位置的标题', version: 5 };
    const backend = client({
      renameSession: vi.fn().mockRejectedValue(new BackendApiError(409)),
      getSession: vi.fn().mockResolvedValue(latest),
    });
    render(<App client={backend} />);
    const sidebar = await screen.findByRole('complementary', { name: '会话列表' });

    fireEvent.doubleClick(await within(sidebar).findByRole('button', { name: /订单延迟分析/ }));
    const titleInput = within(sidebar).getByRole('textbox', {
      name: '编辑“订单延迟分析”标题',
    });
    fireEvent.change(titleInput, {
      target: { value: '本地冲突标题' },
    });
    fireEvent.blur(titleInput);

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '标题已在其他位置更新，已加载最新标题。',
    );
    expect(within(sidebar).getByRole('button', { name: /其他位置的标题/ })).toBeInTheDocument();
  });
});
