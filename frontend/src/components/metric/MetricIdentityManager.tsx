import { FormEvent, useEffect, useState } from 'react';
import { CheckCircle2, KeyRound, RefreshCw, ShieldOff, X } from 'lucide-react';
import { BackendApiError } from '../../api/backendAgentClient';
import {
  MetricIdentityClient,
  MetricIdentityStatus,
  MetricIdentitySummary,
  MetricIdentityVisibility,
  metricIdentityStatusLabel,
} from '../../api/metricIdentityClient';

interface MetricIdentityManagerProps {
  open: boolean;
  client: MetricIdentityClient;
  identities: MetricIdentitySummary[];
  onClose: () => void;
  onChanged: (identities: MetricIdentitySummary[]) => void;
  onAuthenticationRequired?: () => void;
}

function formatTime(value: string | null): string {
  if (!value) return '尚未记录';
  return new Date(value).toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  });
}

function errorMessage(error: unknown): string {
  if (!(error instanceof BackendApiError)) return '操作未完成，请稍后重试。';
  if (error.status === 403) return '当前账号没有管理该身份的权限。';
  if (error.status === 409) return '身份状态已变化，请刷新后重试。';
  if (error.status === 422 || error.status === 400) return '指标平台拒绝了该凭据，请检查后重试。';
  return '操作未完成，请稍后重试。';
}

function scopeLabel(scope: string): string {
  return scope.toLowerCase().replaceAll('_', ' ');
}

function statusClass(status: MetricIdentityStatus): string {
  return status === 'ACTIVE' ? 'active' : status === 'DISABLED' ? 'disabled' : 'warning';
}

export function MetricIdentityManager({
  open,
  client,
  identities,
  onClose,
  onChanged,
  onAuthenticationRequired,
}: MetricIdentityManagerProps) {
  const [displayName, setDisplayName] = useState('');
  const [visibility, setVisibility] = useState<MetricIdentityVisibility>('USER');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [replacementFor, setReplacementFor] = useState<string | null>(null);
  const [replacementPassword, setReplacementPassword] = useState('');
  const [operation, setOperation] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  useEffect(() => {
    if (!open) {
      setPassword('');
      setReplacementPassword('');
      setReplacementFor(null);
      setNotice(null);
    }
  }, [open]);

  if (!open) return null;

  async function refreshIdentities() {
    const refreshed = await client.list();
    onChanged(refreshed);
  }

  function handleFailure(error: unknown) {
    if (error instanceof BackendApiError && error.status === 401) {
      onAuthenticationRequired?.();
      return;
    }
    setNotice(errorMessage(error));
  }

  async function createIdentity(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setOperation('create');
    setNotice(null);
    try {
      await client.create({ displayName, visibility, username, password });
      await refreshIdentities();
      setDisplayName('');
      setUsername('');
      setNotice('指标身份已添加并验证。');
    } catch (error) {
      handleFailure(error);
    } finally {
      setPassword('');
      setOperation(null);
    }
  }

  async function verifyIdentity(identity: MetricIdentitySummary) {
    setOperation(`verify:${identity.id}`);
    setNotice(null);
    try {
      await client.verify(identity.id);
      await refreshIdentities();
      setNotice(`“${identity.displayName}”验证成功。`);
    } catch (error) {
      handleFailure(error);
      try {
        await refreshIdentities();
      } catch {
        // Keep the original sanitized operation error visible.
      }
    } finally {
      setOperation(null);
    }
  }

  async function replacePassword(
    event: FormEvent<HTMLFormElement>,
    identity: MetricIdentitySummary,
  ) {
    event.preventDefault();
    setOperation(`replace:${identity.id}`);
    setNotice(null);
    try {
      await client.replacePassword(identity.id, { password: replacementPassword });
      await refreshIdentities();
      setReplacementFor(null);
      setNotice(`“${identity.displayName}”的密码已更新并验证。`);
    } catch (error) {
      handleFailure(error);
    } finally {
      setReplacementPassword('');
      setOperation(null);
    }
  }

  async function deactivateIdentity(identity: MetricIdentitySummary) {
    if (!window.confirm(`停用指标身份“${identity.displayName}”？现有会话将不能再用它发起查询。`)) {
      return;
    }
    setOperation(`deactivate:${identity.id}`);
    setNotice(null);
    try {
      await client.deactivate(identity.id);
      await refreshIdentities();
      setNotice(`“${identity.displayName}”已停用。`);
    } catch (error) {
      handleFailure(error);
    } finally {
      setOperation(null);
    }
  }

  return (
    <div className="metric-manager-backdrop" role="presentation" onMouseDown={onClose}>
      <section
        aria-label="指标平台身份管理"
        aria-modal="true"
        className="metric-manager"
        role="dialog"
        onMouseDown={(event) => event.stopPropagation()}
      >
        <header className="metric-manager-header">
          <div>
            <span className="metric-manager-eyebrow">Metric Platform</span>
            <h2>指标身份管理</h2>
            <p>凭据仅用于登录指标平台，保存后不会再次显示。</p>
          </div>
          <button
            aria-label="关闭身份管理"
            className="metric-icon-button"
            type="button"
            onClick={onClose}
          >
            <X size={18} />
          </button>
        </header>

        {notice && (
          <div className="metric-manager-notice" role="status">
            {notice}
          </div>
        )}

        <form className="metric-identity-form" onSubmit={createIdentity}>
          <div className="metric-form-heading">
            <KeyRound size={17} />
            <div>
              <strong>添加合法身份</strong>
              <small>提交时会立即连接指标平台验证账号。</small>
            </div>
          </div>
          <div className="metric-form-grid">
            <label>
              <span>显示名称</span>
              <input
                required
                maxLength={160}
                value={displayName}
                onChange={(event) => setDisplayName(event.target.value)}
              />
            </label>
            <label>
              <span>可见范围</span>
              <select
                value={visibility}
                onChange={(event) => setVisibility(event.target.value as MetricIdentityVisibility)}
              >
                <option value="USER">仅自己</option>
                <option value="TENANT">当前租户</option>
              </select>
            </label>
            <label>
              <span>指标平台账号</span>
              <input
                required
                maxLength={160}
                autoComplete="username"
                value={username}
                onChange={(event) => setUsername(event.target.value)}
              />
            </label>
            <label className="metric-password-field">
              <span>指标平台密码</span>
              <input
                required
                maxLength={1024}
                type="password"
                autoComplete="new-password"
                value={password}
                onChange={(event) => setPassword(event.target.value)}
              />
            </label>
          </div>
          <button className="metric-primary-button" type="submit" disabled={operation != null}>
            {operation === 'create' ? '正在验证…' : '添加并验证'}
          </button>
        </form>

        <div className="metric-identity-list-heading">
          <div>
            <h3>已保存身份</h3>
            <span>{identities.length} 个可见身份</span>
          </div>
          <button
            className="metric-secondary-button"
            type="button"
            disabled={operation != null}
            onClick={() => void refreshIdentities().catch(handleFailure)}
          >
            <RefreshCw size={14} /> 刷新
          </button>
        </div>

        <div className="metric-identity-list">
          {identities.length === 0 && (
            <div className="metric-empty-state">还没有可用的指标身份。</div>
          )}
          {identities.map((identity) => (
            <article className="metric-identity-row" key={identity.id}>
              <div className="metric-identity-row-main">
                <div className="metric-identity-title-row">
                  <strong>{identity.displayName}</strong>
                  <span className={`metric-status ${statusClass(identity.status)}`}>
                    {metricIdentityStatusLabel(identity.status)}
                  </span>
                  <span className="metric-visibility">
                    {identity.visibility === 'USER' ? '仅自己' : '租户共享'}
                  </span>
                </div>
                <p>
                  {identity.platformDisplayName || identity.platformUsername} ·{' '}
                  {identity.platformUsername}
                </p>
                <div className="metric-scopes" aria-label="授权范围">
                  {identity.grantedScopes.length > 0 ? (
                    identity.grantedScopes.map((scope) => (
                      <span key={scope}>{scopeLabel(scope)}</span>
                    ))
                  ) : (
                    <span>未授予查询范围</span>
                  )}
                </div>
                <small>
                  最近验证：{formatTime(identity.lastVerifiedAt)} · 最近登录：
                  {formatTime(identity.lastLoginAt)}
                </small>
              </div>
              <div className="metric-row-actions">
                <button
                  type="button"
                  disabled={operation != null}
                  onClick={() => void verifyIdentity(identity)}
                >
                  <CheckCircle2 size={14} /> 验证
                </button>
                <button
                  type="button"
                  disabled={operation != null}
                  onClick={() => {
                    setReplacementFor(identity.id);
                    setReplacementPassword('');
                    setNotice(null);
                  }}
                >
                  <KeyRound size={14} /> 更换密码
                </button>
                {identity.status === 'ACTIVE' && (
                  <button
                    className="danger"
                    type="button"
                    disabled={operation != null}
                    onClick={() => void deactivateIdentity(identity)}
                  >
                    <ShieldOff size={14} /> 停用
                  </button>
                )}
              </div>
              {replacementFor === identity.id && (
                <form
                  className="metric-replace-form"
                  onSubmit={(event) => void replacePassword(event, identity)}
                >
                  <label>
                    <span>新密码</span>
                    <input
                      autoFocus
                      required
                      maxLength={1024}
                      type="password"
                      autoComplete="new-password"
                      value={replacementPassword}
                      onChange={(event) => setReplacementPassword(event.target.value)}
                    />
                  </label>
                  <button
                    className="metric-primary-button"
                    type="submit"
                    disabled={operation != null}
                  >
                    保存并验证
                  </button>
                  <button
                    type="button"
                    disabled={operation != null}
                    onClick={() => {
                      setReplacementFor(null);
                      setReplacementPassword('');
                    }}
                  >
                    取消
                  </button>
                </form>
              )}
            </article>
          ))}
        </div>
      </section>
    </div>
  );
}
