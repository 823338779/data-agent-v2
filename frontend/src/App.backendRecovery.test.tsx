import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import App from './App';

afterEach(() => {
  cleanup();
  window.history.replaceState(null, '', '/');
  vi.unstubAllGlobals();
});

describe('Agent session recovery', () => {
  it('creates a backend session before submitting when startup used the local prototype', async () => {
    const createdSession = {
      id: 'server-session-1',
      title: 'Kafka 延迟诊断',
      tag: '实时',
      createdAt: '2026-07-08T13:00:00Z',
      updatedAt: '2026-07-08T13:00:00Z',
      messages: [],
      run: null,
    };
    const fetchMock = vi
      .fn()
      .mockRejectedValueOnce(new Error('backend unavailable during startup'))
      .mockResolvedValueOnce({ ok: true, json: async () => createdSession })
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({
          ...createdSession,
          messages: [
            {
              id: 'm1',
              role: 'user',
              author: 'Project Director',
              content: '检查延迟',
              createdAt: '2026-07-08T13:01:00Z',
            },
          ],
        }),
      });
    vi.stubGlobal('fetch', fetchMock);

    window.history.replaceState(null, '', '/sessions/session_local');
    render(<App />);
    fireEvent.change(screen.getByLabelText('Prompt'), { target: { value: '检查延迟' } });
    fireEvent.keyDown(screen.getByLabelText('Prompt'), { key: 'Enter' });

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(3));
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      '/api/v1/agent-sessions',
      expect.objectContaining({ method: 'POST' }),
    );
    expect(fetchMock).toHaveBeenNthCalledWith(
      3,
      '/api/v1/agent-sessions/server-session-1/messages',
      expect.objectContaining({ method: 'POST' }),
    );
  });
});
