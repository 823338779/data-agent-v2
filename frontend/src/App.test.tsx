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
  unread: false,
  lastNotifiableEventSequence: 0,
  sortOrder: 0,
  orderingVersion: 0,
  messages: [],
  execution: null,
};

function completedExecution(
  executionId: string,
  thinking: string,
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
      reasoning: thinking,
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
          author: 'admin',
          content: '分析订单延迟',
          timestamp: '2026-08-01T08:01:00Z',
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
    markRead: vi.fn().mockResolvedValue(session),
    reorderSessions: vi.fn().mockResolvedValue({ orderingVersion: 1, sessions: [session] }),
    subscribeActivity: vi.fn().mockReturnValue(() => undefined),
    subscribeExecutionEvents: vi.fn().mockReturnValue(() => undefined),
    subscribeEvents: vi.fn().mockReturnValue(() => undefined),
    ...overrides,
  };
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
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

  it('creates a server session without unsupported identity or mode fields', async () => {
    const backend = client();
    render(<App client={backend} />);

    const sidebar = await screen.findByRole('complementary', { name: '会话列表' });
    expect(within(sidebar).getByRole('button', { name: '收起会话列表' })).toBeInTheDocument();
    fireEvent.click(within(sidebar).getByRole('button', { name: '新会话' }));

    await waitFor(() => expect(backend.createSession).toHaveBeenCalledWith('新会话', ''));
    expect(await screen.findByRole('heading', { name: '新会话' })).toBeInTheDocument();
  });

  it('restores the session-list control in the header only while the sidebar is closed', async () => {
    render(<App client={client()} />);
    const sidebar = await screen.findByRole('complementary', { name: '会话列表' });

    fireEvent.click(within(sidebar).getByRole('button', { name: '收起会话列表' }));
    expect(screen.queryByRole('complementary', { name: '会话列表' })).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '展开会话列表' }));
    expect(await screen.findByRole('complementary', { name: '会话列表' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '展开会话列表' })).not.toBeInTheDocument();
  });

  it('starts with the session drawer closed on narrow screens', async () => {
    vi.stubGlobal(
      'matchMedia',
      vi.fn().mockReturnValue({
        matches: true,
        media: '(max-width: 820px)',
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
      }),
    );

    render(<App client={client()} />);

    await waitFor(() => expect(screen.getByRole('button', { name: '展开会话列表' })).toBeVisible());
    expect(screen.queryByRole('complementary', { name: '会话列表' })).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '展开会话列表' }));
    expect(await screen.findByRole('complementary', { name: '会话列表' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '关闭会话列表' })).toBeInTheDocument();
  });

  it('shows the matching Agent execution process below every persisted assistant answer', async () => {
    const firstExecution = completedExecution('execution_1', '第一次思考', '第一条回答');
    const secondExecution = completedExecution('execution_2', '第二次思考', '第二条回答');
    const history: ConversationSession = {
      ...session,
      messages: [
        {
          conversationMessageId: 'answer_1',
          role: 'assistant',
          author: 'assistant',
          content: '第一条回答',
          timestamp: '2026-08-01T08:01:00Z',
          execution: firstExecution,
        },
        {
          conversationMessageId: 'answer_2',
          role: 'assistant',
          author: 'assistant',
          content: '第二条回答',
          timestamp: '2026-08-01T08:02:00Z',
          execution: secondExecution,
        },
      ],
      execution: secondExecution,
    };

    render(<App client={client({ listSessions: vi.fn().mockResolvedValue([history]) })} />);
    fireEvent.click((await screen.findAllByRole('button', { name: /订单延迟分析/ }))[0]);

    expect(screen.getAllByText('查看运行过程')).toHaveLength(2);
    expect(screen.getByText('第一次思考')).toBeInTheDocument();
    expect(screen.getByText('第二次思考')).toBeInTheDocument();
    expect(screen.getAllByText('第二条回答')).toHaveLength(1);
  });

  it('places a streaming answer in the full-width message content column', async () => {
    const streaming: ConversationSession = {
      ...session,
      execution: completedExecution(
        'execution_streaming',
        '正在思考',
        '这是一条实时返回的完整回答',
      ),
    };

    const view = render(
      <App client={client({ listSessions: vi.fn().mockResolvedValue([streaming]) })} />,
    );
    fireEvent.click((await screen.findAllByRole('button', { name: /订单延迟分析/ }))[0]);

    const answer = await screen.findByText('这是一条实时返回的完整回答');
    expect(answer.closest('.stream-answer-content')).toBeInTheDocument();
    expect(view.container.querySelector('.stream-answer-content')).toContainElement(answer);
  });

  it('keeps one activity stream while switching detail streams without cancelling executions', async () => {
    const second = {
      ...session,
      conversationSessionId: 'session_2',
      title: '库存分析',
      sortOrder: 1,
    };
    const backend = client({ listSessions: vi.fn().mockResolvedValue([session, second]) });
    render(<App client={backend} />);

    fireEvent.click((await screen.findAllByRole('button', { name: /订单延迟分析/ }))[0]);
    await waitFor(() =>
      expect(backend.subscribeExecutionEvents).toHaveBeenCalledWith(
        'session_1',
        expect.any(Function),
        expect.any(Function),
        expect.any(Function),
      ),
    );
    fireEvent.click(screen.getByRole('button', { name: /库存分析/ }));

    await waitFor(() => expect(backend.subscribeExecutionEvents).toHaveBeenCalledTimes(2));
    expect(backend.subscribeActivity).toHaveBeenCalledTimes(1);
    expect(backend.cancelExecution).not.toHaveBeenCalled();
  });

  it('marks the current session read at the exact displayed notification sequence', async () => {
    let activityListener:
      | ((
          snapshot: Parameters<AgentBackendClient['subscribeActivity']>[0] extends (
            value: infer T,
          ) => void
            ? T
            : never,
        ) => void)
      | undefined;
    const backend = client({
      subscribeActivity: vi.fn((listener) => {
        activityListener = listener;
        return () => undefined;
      }),
    });
    render(<App client={backend} />);
    fireEvent.click((await screen.findAllByRole('button', { name: /订单延迟分析/ }))[0]);

    act(() =>
      activityListener?.({
        type: 'activity_snapshot',
        orderingVersion: 0,
        sessions: [
          {
            conversationSessionId: 'session_1',
            status: 'done',
            unread: true,
            lastNotifiableEventSequence: 7,
            sortOrder: 0,
            updatedAt: '2026-08-01T08:02:00Z',
          },
        ],
        snapshotAt: '2026-08-01T08:02:02Z',
      }),
    );

    await waitFor(() => expect(backend.markRead).toHaveBeenCalledWith('session_1', 7));
  });

  it('renames a session from the sidebar and updates the active heading', async () => {
    const renamed = { ...session, title: '新的会话标题', version: 5 };
    const backend = client({ renameSession: vi.fn().mockResolvedValue(renamed) });
    render(<App client={backend} />);
    const sidebar = await screen.findByRole('complementary', { name: '会话列表' });

    fireEvent.click(within(sidebar).getByRole('button', { name: /订单延迟分析/ }));
    expect(await screen.findByRole('heading', { name: '订单延迟分析' })).toBeInTheDocument();
    fireEvent.doubleClick(within(sidebar).getByRole('button', { name: /订单延迟分析/ }));
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

    fireEvent.doubleClick(within(sidebar).getByRole('button', { name: /订单延迟分析/ }));
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

  it('keeps the title draft editable after a rename request fails', async () => {
    const backend = client({
      renameSession: vi.fn().mockRejectedValue(new BackendApiError(500)),
    });
    render(<App client={backend} />);
    const sidebar = await screen.findByRole('complementary', { name: '会话列表' });

    fireEvent.doubleClick(within(sidebar).getByRole('button', { name: /订单延迟分析/ }));
    const titleInput = within(sidebar).getByRole('textbox', {
      name: '编辑“订单延迟分析”标题',
    });
    fireEvent.change(titleInput, { target: { value: '保留标题草稿' } });
    fireEvent.blur(titleInput);

    expect(await screen.findByRole('alert')).toHaveTextContent('标题保存失败，请稍后重试。');
    expect(within(sidebar).getByRole('textbox', { name: /编辑/ })).toHaveValue('保留标题草稿');
    expect(within(sidebar).getByRole('textbox', { name: /编辑/ })).not.toBeDisabled();
  });
});
