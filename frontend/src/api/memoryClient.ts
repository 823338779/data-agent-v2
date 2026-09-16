import {
  CreateMemoryRequest,
  MemoryKind,
  MemoryListView,
  MemoryOutcome,
  MemorySettings,
  ReplaceMemoryRequest,
  UpdateMemorySettingsRequest,
} from '../domain/memory';

const MEMORY_PATH = '/api/user/memories';

export class MemoryApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
    message: string,
  ) {
    super(message);
  }
}

export interface MemoryClient {
  list(kind?: MemoryKind, cursor?: string): Promise<MemoryListView>;
  create(request: CreateMemoryRequest, key: string): Promise<MemoryOutcome>;
  replace(id: string, request: ReplaceMemoryRequest, key: string): Promise<MemoryOutcome>;
  remove(id: string, version: number, key: string): Promise<void>;
  settings(): Promise<MemorySettings>;
  updateSettings(request: UpdateMemorySettingsRequest, key: string): Promise<MemorySettings>;
}

/** 管理面板的固定同源 HTTP 边界；幂等键由界面按一次用户操作的生命周期提供。 */
export function createFetchMemoryClient(options: { fetchImpl?: typeof fetch } = {}): MemoryClient {
  const fetchImpl = options.fetchImpl ?? ((...args: Parameters<typeof fetch>) => fetch(...args));

  async function request<T>(url: string, init: RequestInit): Promise<T> {
    const result = await fetchImpl(url, init);
    if (!result.ok) {
      const problem = await result.json().catch(() => ({}));
      throw new MemoryApiError(
        result.status,
        typeof problem.code === 'string' ? problem.code : 'UNAVAILABLE',
        typeof problem.detail === 'string' ? problem.detail : '个人记忆请求失败。',
      );
    }
    if (result.status === 204) return undefined as T;
    return result.json() as Promise<T>;
  }

  const mutationHeaders = (key: string) => ({
    'Content-Type': 'application/json',
    'Idempotency-Key': key,
  });

  return {
    list(kind, cursor) {
      const query = new URLSearchParams();
      if (kind) query.set('kind', kind);
      if (cursor) query.set('cursor', cursor);
      const suffix = query.size > 0 ? `?${query.toString()}` : '';
      return request<MemoryListView>(`${MEMORY_PATH}${suffix}`, { method: 'GET' });
    },
    create(createRequest, key) {
      return request<MemoryOutcome>(MEMORY_PATH, {
        method: 'POST',
        headers: mutationHeaders(key),
        body: JSON.stringify(createRequest),
      });
    },
    replace(id, replaceRequest, key) {
      return request<MemoryOutcome>(`${MEMORY_PATH}/${encodeURIComponent(id)}`, {
        method: 'PUT',
        headers: mutationHeaders(key),
        body: JSON.stringify(replaceRequest),
      });
    },
    remove(id, version, key) {
      return request<void>(
        `${MEMORY_PATH}/${encodeURIComponent(id)}?expectedVersion=${encodeURIComponent(version)}`,
        { method: 'DELETE', headers: { 'Idempotency-Key': key } },
      );
    },
    settings() {
      return request<MemorySettings>(`${MEMORY_PATH}/settings`, { method: 'GET' });
    },
    updateSettings(settingsRequest, key) {
      return request<MemorySettings>(`${MEMORY_PATH}/settings`, {
        method: 'PUT',
        headers: mutationHeaders(key),
        body: JSON.stringify(settingsRequest),
      });
    },
  };
}
