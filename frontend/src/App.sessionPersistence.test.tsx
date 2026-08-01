import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import App from './App';

afterEach(() => {
  cleanup();
  window.history.replaceState(null, '', '/');
  vi.unstubAllGlobals();
});

describe('active session persistence', () => {
  it('stores the selected session in the path and restores it after remounting', async () => {
    const sessions = [
      {
        id: 'session_first',
        title: 'First session',
        tag: 'analysis',
        createdAt: '2026-07-18T08:00:00Z',
        updatedAt: '2026-07-18T08:00:00Z',
        messages: [],
        run: null,
      },
      {
        id: 'session_second',
        title: 'Second session',
        tag: 'analysis',
        createdAt: '2026-07-18T08:01:00Z',
        updatedAt: '2026-07-18T08:01:00Z',
        messages: [],
        run: null,
      },
    ];
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => sessions }));

    const homeRender = render(<App />);
    expect(
      await screen.findByRole('heading', { name: '今天想解决什么数据问题？' }),
    ).toBeInTheDocument();
    homeRender.unmount();

    window.history.replaceState(null, '', '/sessions/session_first');
    const firstRender = render(<App />);
    await screen.findByRole('heading', { name: 'First session' });
    fireEvent.click(screen.getByRole('button', { name: '会话历史' }));
    fireEvent.click(screen.getByRole('button', { name: /Second session/ }));
    await screen.findByRole('heading', { name: 'Second session' });
    expect(window.location.pathname).toBe('/sessions/session_second');
    firstRender.unmount();

    window.history.replaceState(null, '', '/sessions/session_second');
    render(<App />);

    await waitFor(() =>
      expect(screen.getByRole('heading', { name: 'Second session' })).toBeInTheDocument(),
    );
  });
});
