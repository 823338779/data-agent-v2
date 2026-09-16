import { describe, expect, it, vi } from 'vitest';
import { createFetchMemoryClient, MemoryApiError } from './memoryClient';

function response(body: unknown, status = 200): Response {
  return new Response(body === undefined ? undefined : JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

describe('memoryClient', () => {
  it('lists all categories and encodes an optional category cursor', async () => {
    const view = { groups: {}, quotas: {}, revision: 3 };
    const fetchImpl = vi.fn().mockImplementation(() => Promise.resolve(response(view)));
    const client = createFetchMemoryClient({ fetchImpl });

    await expect(client.list()).resolves.toEqual(view);
    await client.list('person_profile', 'memory / 2');

    expect(fetchImpl).toHaveBeenNthCalledWith(1, '/api/user/memories', { method: 'GET' });
    expect(fetchImpl).toHaveBeenNthCalledWith(
      2,
      '/api/user/memories?kind=person_profile&cursor=memory+%2F+2',
      { method: 'GET' },
    );
  });

  it('creates a memory with the supplied idempotency key and exact payload', async () => {
    const outcome = {
      status: 'CREATED',
      memoryId: 'memory-1',
      version: 1,
      revision: 4,
      created: true,
      targetExists: false,
      quotas: {},
    };
    const fetchImpl = vi.fn().mockResolvedValue(response(outcome, 201));
    const client = createFetchMemoryClient({ fetchImpl });

    await expect(
      client.create(
        { kind: 'person_profile', content: '偏好简洁回答', restore: false },
        'create-key',
      ),
    ).resolves.toEqual(outcome);

    expect(fetchImpl).toHaveBeenCalledWith('/api/user/memories', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Idempotency-Key': 'create-key' },
      body: JSON.stringify({
        kind: 'person_profile',
        content: '偏好简洁回答',
        restore: false,
      }),
    });
  });

  it('replaces a memory using its current entry version', async () => {
    const outcome = {
      status: 'UPDATED',
      memoryId: 'memory/1',
      version: 8,
      revision: 9,
      created: false,
      targetExists: true,
      quotas: {},
    };
    const fetchImpl = vi.fn().mockResolvedValue(response(outcome));
    const client = createFetchMemoryClient({ fetchImpl });

    await client.replace('memory/1', { content: '新正文', expectedVersion: 7 }, 'replace-key');

    expect(fetchImpl).toHaveBeenCalledWith('/api/user/memories/memory%2F1', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json', 'Idempotency-Key': 'replace-key' },
      body: JSON.stringify({ content: '新正文', expectedVersion: 7 }),
    });
  });

  it('deletes with expectedVersion in the query and no request body', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(response(undefined, 204));
    const client = createFetchMemoryClient({ fetchImpl });

    await expect(client.remove('memory/1', 6, 'delete-key')).resolves.toBeUndefined();

    expect(fetchImpl).toHaveBeenCalledWith('/api/user/memories/memory%2F1?expectedVersion=6', {
      method: 'DELETE',
      headers: { 'Idempotency-Key': 'delete-key' },
    });
  });

  it('reads and updates settings with the entry-independent settings version', async () => {
    const settings = {
      settingsVersion: 5,
      readEnabled: true,
      explicitWriteEnabled: true,
      autoLearningEnabled: false,
    };
    const fetchImpl = vi.fn().mockImplementation(() => Promise.resolve(response(settings)));
    const client = createFetchMemoryClient({ fetchImpl });

    await expect(client.settings()).resolves.toEqual(settings);
    await client.updateSettings({ expectedVersion: 5, autoLearningEnabled: true }, 'settings-key');

    expect(fetchImpl).toHaveBeenNthCalledWith(1, '/api/user/memories/settings', { method: 'GET' });
    expect(fetchImpl).toHaveBeenNthCalledWith(2, '/api/user/memories/settings', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json', 'Idempotency-Key': 'settings-key' },
      body: JSON.stringify({ expectedVersion: 5, autoLearningEnabled: true }),
    });
  });

  it('exposes the stable memory error code without leaking response internals', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(
      response(
        {
          type: 'urn:data-agent:problem:memory',
          detail: '该记忆已被明确遗忘。',
          code: 'SUPPRESSED',
          ignored: 'private detail',
        },
        409,
      ),
    );

    const failure = await createFetchMemoryClient({ fetchImpl })
      .create({ kind: 'person_profile', content: '记住我' }, 'key')
      .catch((error: unknown) => error);

    expect(failure).toBeInstanceOf(MemoryApiError);
    expect(failure).toMatchObject({
      status: 409,
      code: 'SUPPRESSED',
      message: '该记忆已被明确遗忘。',
    });
  });
});
