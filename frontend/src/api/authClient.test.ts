import { afterEach, describe, expect, it, vi } from 'vitest';
import { authClient, AuthError } from './authClient';

afterEach(() => vi.unstubAllGlobals());

describe('authClient', () => {
  it('includes credentials for login and session requests', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce({ ok: true, status: 204 })
      .mockResolvedValueOnce({
        ok: true,
        status: 200,
        json: async () => ({ tenantId: 'tenant', subject: 'admin', roles: ['TENANT_ADMIN'] }),
      });
    vi.stubGlobal('fetch', fetchMock);

    await authClient.login('admin', 'admin');
    await authClient.session();

    expect(fetchMock).toHaveBeenNthCalledWith(
      1,
      '/api/v1/auth/login',
      expect.objectContaining({
        credentials: 'include',
        method: 'POST',
        body: JSON.stringify({ username: 'admin', password: 'admin' }),
      }),
    );
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      '/api/v1/auth/session',
      expect.objectContaining({ credentials: 'include' }),
    );
  });

  it('exposes a 401 so the application can show the login view', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 401 }));
    await expect(authClient.session()).rejects.toEqual(
      expect.objectContaining<AuthError>({ status: 401 }),
    );
  });
});
