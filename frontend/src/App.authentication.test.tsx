import { cleanup, render, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import App from './App';

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe('Agent authentication handling', () => {
  it('returns control to the login shell when session loading receives 401', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 401 }));
    const onAuthenticationRequired = vi.fn();

    render(
      <App
        subject="admin"
        onLogout={vi.fn().mockResolvedValue(undefined)}
        onAuthenticationRequired={onAuthenticationRequired}
      />,
    );

    await waitFor(() => expect(onAuthenticationRequired).toHaveBeenCalledOnce());
  });
});
