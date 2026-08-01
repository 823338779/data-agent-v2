import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AgentBackendClient } from './api/backendAgentClient';
import App from './App';
import { ConversationSession } from './domain/agentPrototype';

const session: ConversationSession = {
  conversationSessionId: 'session_1',
  title: '订单延迟分析',
  tag: 'debug',
  createdAt: '2026-08-01T08:00:00Z',
  updatedAt: '2026-08-01T08:00:00Z',
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
    events: [],
    streamCheckpoint: {
      phase: 'COMPLETED',
      thinking,
      answer,
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
        events: [],
        streamCheckpoint: null,
      },
    }),
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
    expect(screen.getByText('处理中')).toBeInTheDocument();
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
    expect(answer.closest('.message-content')).toBeInTheDocument();
    expect(view.container.querySelector('.stream-answer > .message-content')).toContainElement(
      answer,
    );
  });
});
