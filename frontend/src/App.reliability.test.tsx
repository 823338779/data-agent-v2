import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import App from './App';

afterEach(() => {
  cleanup();
  window.history.replaceState(null, '', '/');
  vi.unstubAllGlobals();
});

describe('Agent session reliability', () => {
  it('keeps unavailable secondary actions out of the primary workspace', () => {
    render(<App />);

    fireEvent.click(screen.getByRole('button', { name: '会话历史' }));
    expect(screen.getByRole('navigation', { name: '会话导航' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '运行历史' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '空间设置' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '暂停' })).not.toBeInTheDocument();
  });

  it('keeps the selected session visible when another session receives an SSE event', async () => {
    const firstSession = {
      id: 'session_first',
      title: '会话一',
      tag: 'debug',
      createdAt: '2026-07-08T13:00:00Z',
      updatedAt: '2026-07-08T13:00:00Z',
      messages: [],
      run: null,
    };
    const secondSession = {
      id: 'session_second',
      title: '会话二',
      tag: 'debug',
      createdAt: '2026-07-08T13:00:00Z',
      updatedAt: '2026-07-08T13:00:00Z',
      messages: [],
      run: null,
    };
    const listeners = new Map<string, EventListener>();
    vi.stubGlobal(
      'EventSource',
      class {
        onerror: (() => void) | null = null;
        addEventListener(type: string, listener: EventListener) {
          listeners.set(type, listener);
        }
        close() {}
      },
    );
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValueOnce({ ok: true, json: async () => [firstSession, secondSession] })
        .mockResolvedValueOnce({
          ok: true,
          json: async () => ({
            ...firstSession,
            run: {
              id: 'run_first',
              status: 'running',
              progress: 0,
              objective: '诊断',
              events: [],
              pendingConfirmation: null,
            },
          }),
        }),
    );

    window.history.replaceState(null, '', '/sessions/session_first');
    render(<App />);
    await screen.findByRole('heading', { name: '会话一' });
    fireEvent.change(screen.getByLabelText('Prompt'), { target: { value: '诊断' } });
    fireEvent.keyDown(screen.getByLabelText('Prompt'), { key: 'Enter' });
    await waitFor(() => expect(listeners.has('reasoning_delta')).toBe(true));
    fireEvent.click(screen.getByRole('button', { name: '会话历史' }));
    fireEvent.click(screen.getByRole('button', { name: /会话二/ }));
    listeners.get('reasoning_delta')?.(
      new MessageEvent('message', {
        data: JSON.stringify({
          id: 'event_reasoning',
          sequence: 1,
          type: 'reasoning_delta',
          occurredAt: '2026-07-08T13:01:00Z',
          runId: 'run_first',
          messageId: null,
          title: '拆解目标',
          summary: '分析中',
          toolName: null,
          input: {},
          output: {},
          confirmationId: null,
          approved: null,
          status: 'done',
        }),
      }),
    );

    await waitFor(() =>
      expect(screen.getByRole('heading', { name: '会话二' })).toBeInTheDocument(),
    );
  });

  it('returns to login when an SSE failure finds an expired session', async () => {
    const session = {
      id: 'session_backend',
      title: '认证会话',
      tag: 'debug',
      createdAt: '2026-07-08T13:00:00Z',
      updatedAt: '2026-07-08T13:00:00Z',
      messages: [],
      run: null,
    };
    let source: { onerror: (() => void) | null } | undefined;
    vi.stubGlobal(
      'EventSource',
      class {
        onerror: (() => void) | null = null;
        constructor() {
          source = this;
        }
        addEventListener() {}
        close() {}
      },
    );
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValueOnce({ ok: true, json: async () => [session] })
        .mockResolvedValueOnce({
          ok: true,
          json: async () => ({
            ...session,
            run: {
              id: 'run_backend',
              status: 'running',
              progress: 0,
              objective: '诊断',
              events: [],
              pendingConfirmation: null,
            },
          }),
        })
        .mockResolvedValueOnce({ ok: false, status: 401 }),
    );
    const onAuthenticationRequired = vi.fn();

    window.history.replaceState(null, '', '/sessions/session_backend');
    render(<App onAuthenticationRequired={onAuthenticationRequired} />);
    await screen.findByRole('heading', { name: '认证会话' });
    fireEvent.change(screen.getByLabelText('Prompt'), { target: { value: '诊断' } });
    fireEvent.keyDown(screen.getByLabelText('Prompt'), { key: 'Enter' });
    await waitFor(() => expect(source).toBeDefined());
    source?.onerror?.();

    await waitFor(() => expect(onAuthenticationRequired).toHaveBeenCalledOnce());
  });

  it('blocks prompt submission while approval is pending', async () => {
    const session = {
      id: 'session_backend',
      title: '待审批会话',
      tag: 'governance',
      createdAt: '2026-07-08T13:00:00Z',
      updatedAt: '2026-07-08T13:00:00Z',
      messages: [],
      run: {
        id: 'run_backend',
        status: 'waiting_confirmation',
        progress: 68,
        objective: '导出',
        events: [],
        pendingConfirmation: {
          id: 'confirmation_1',
          title: '确认导出',
          summary: '需要审批',
          detail: '审批后继续',
          requestedAt: '2026-07-08T13:00:00Z',
        },
      },
    };
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => [session] });
    vi.stubGlobal('fetch', fetchMock);

    window.history.replaceState(null, '', '/sessions/session_backend');
    render(<App />);
    await screen.findByRole('heading', { name: '待审批会话' });

    expect(screen.getByLabelText('Prompt')).toBeDisabled();
    expect(screen.getByRole('button', { name: '发送' })).toBeDisabled();
    fireEvent.submit(screen.getByLabelText('Prompt').closest('form')!);

    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});
