import { BackendApiError } from './backendAgentClient';

export type MetricIdentityVisibility = 'USER' | 'TENANT';

export type MetricIdentityStatus =
  'PENDING_VERIFICATION' | 'ACTIVE' | 'INVALID_CREDENTIALS' | 'LOCKED' | 'EXPIRED' | 'DISABLED';

export interface MetricIdentitySummary {
  id: string;
  displayName: string;
  visibility: MetricIdentityVisibility;
  ownerSubject: string;
  platformTenantKey: string;
  platformUsername: string;
  platformDisplayName: string;
  grantedScopes: string[];
  status: MetricIdentityStatus;
  credentialVersion: number;
  lastVerifiedAt: string | null;
  lastLoginAt: string | null;
  lastLoginErrorCode: string | null;
  version: number;
}

export interface CreateMetricIdentityRequest {
  displayName: string;
  visibility: MetricIdentityVisibility;
  username: string;
  password: string;
}

export interface ReplaceMetricIdentityPasswordRequest {
  password: string;
}

export interface MetricIdentityClient {
  list: () => Promise<MetricIdentitySummary[]>;
  get: (id: string) => Promise<MetricIdentitySummary>;
  create: (request: CreateMetricIdentityRequest) => Promise<MetricIdentitySummary>;
  verify: (id: string) => Promise<MetricIdentitySummary>;
  replacePassword: (
    id: string,
    request: ReplaceMetricIdentityPasswordRequest,
  ) => Promise<MetricIdentitySummary>;
  deactivate: (id: string) => Promise<MetricIdentitySummary>;
}

export function isSelectableMetricIdentity(identity: MetricIdentitySummary): boolean {
  return identity.status === 'ACTIVE';
}

export function metricIdentityStatusLabel(status: MetricIdentityStatus): string {
  const labels: Record<MetricIdentityStatus, string> = {
    PENDING_VERIFICATION: '待验证',
    ACTIVE: '可用',
    INVALID_CREDENTIALS: '凭据无效',
    LOCKED: '账号锁定',
    EXPIRED: '凭据过期',
    DISABLED: '已停用',
  };
  return labels[status];
}

async function parseJson<T>(response: Response): Promise<T> {
  if (!response.ok) throw new BackendApiError(response.status);
  return response.json() as Promise<T>;
}

export function createMetricIdentityClient(
  baseUrl = '/api/v1/metric-identities',
  fetchImpl: typeof fetch = fetch,
): MetricIdentityClient {
  async function request<T>(url: string, method: 'POST' = 'POST', body?: unknown): Promise<T> {
    const response = await fetchImpl(url, {
      method,
      credentials: 'include',
      headers: body == null ? undefined : { 'Content-Type': 'application/json' },
      body: body == null ? undefined : JSON.stringify(body),
    });
    return parseJson<T>(response);
  }

  return {
    async list() {
      return parseJson<MetricIdentitySummary[]>(
        await fetchImpl(baseUrl, { credentials: 'include' }),
      );
    },
    async get(id) {
      return parseJson<MetricIdentitySummary>(
        await fetchImpl(`${baseUrl}/${id}`, { credentials: 'include' }),
      );
    },
    create(requestBody) {
      return request<MetricIdentitySummary>(baseUrl, 'POST', requestBody);
    },
    verify(id) {
      return request<MetricIdentitySummary>(`${baseUrl}/${id}:verify`);
    },
    replacePassword(id, requestBody) {
      return request<MetricIdentitySummary>(
        `${baseUrl}/${id}:replace-password`,
        'POST',
        requestBody,
      );
    },
    deactivate(id) {
      return request<MetricIdentitySummary>(`${baseUrl}/${id}:deactivate`);
    },
  };
}
