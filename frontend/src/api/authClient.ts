export interface AuthSession {
  tenantId: string;
  subject: string;
  roles: string[];
}

export class AuthError extends Error {
  constructor(public readonly status: number) {
    super(`Authentication request failed: ${status}`);
  }
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const response = await fetch(path, {
    ...init,
    credentials: 'include',
    headers: { 'Content-Type': 'application/json', ...init.headers },
  });
  if (!response.ok) throw new AuthError(response.status);
  return response.status === 204 ? (undefined as T) : (response.json() as Promise<T>);
}

export const authClient = {
  session: () => request<AuthSession>('/api/v1/auth/session'),
  login: (username: string, password: string) =>
    request<void>('/api/v1/auth/login', {
      method: 'POST',
      body: JSON.stringify({ username, password }),
    }),
  logout: () => request<void>('/api/v1/auth/logout', { method: 'POST' }),
};
