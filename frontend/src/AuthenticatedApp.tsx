import { FormEvent, useEffect, useState } from 'react';
import App from './App';
import { authClient, AuthSession } from './api/authClient';

/**
 * 前端的认证边界和顶层工作区切换器。
 *
 * 登录态只从 HttpOnly cookie 对应的 `/auth/session` 读取，浏览器代码不保存 session token。
 * 任一子工作区收到 401 都回到这里清空内存态，由用户重新登录。
 */
export function AuthenticatedApp() {
  const [session, setSession] = useState<AuthSession | null>(null);
  const [loading, setLoading] = useState(true);
  const [username, setUsername] = useState('admin');
  const [password, setPassword] = useState('admin');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    void authClient
      .session()
      .then(setSession)
      .catch(() => setSession(null))
      .finally(() => setLoading(false));
  }, []);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSubmitting(true);
    setError(null);
    try {
      await authClient.login(username, password);
      setSession(await authClient.session());
    } catch {
      setError('用户名或密码不正确，请重试。');
    } finally {
      setSubmitting(false);
    }
  }

  async function logout() {
    await authClient.logout().catch(() => undefined);
    setSession(null);
  }

  if (loading)
    return (
      <main className="auth-loading" aria-live="polite">
        正在验证登录状态…
      </main>
    );
  if (!session)
    return (
      <main className="auth-page">
        <form className="auth-card" onSubmit={submit}>
          <p className="auth-eyebrow">DATA AGENT</p>
          <h1>登录数据工作区</h1>
          <p>登录后可管理数据源，或进入 Agent 对话完成分析任务。</p>
          <label>
            用户名
            <input
              value={username}
              onChange={(event) => setUsername(event.target.value)}
              autoComplete="username"
              required
            />
          </label>
          <label>
            密码
            <input
              type="password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              autoComplete="current-password"
              required
            />
          </label>
          {error && (
            <p className="auth-error" role="alert">
              {error}
            </p>
          )}
          <button className="primary" disabled={submitting}>
            {submitting ? '正在登录…' : '登录'}
          </button>
        </form>
      </main>
    );

  return (
    <App
      subject={session.subject}
      onLogout={logout}
      onAuthenticationRequired={() => setSession(null)}
    />
  );
}
