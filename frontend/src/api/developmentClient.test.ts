import { describe, expect, it, vi } from 'vitest';
import { createDevelopmentClient } from './developmentClient';

describe('固定会话开发 HTTP 契约', () => {
  it('所有动作只发送新接口字段且保留完整确认范围', async () => {
    const fetchImpl = vi.fn().mockImplementation(async () => new Response('{}'));
    const api = createDevelopmentClient({ fetchImpl });
    const signal = new AbortController().signal;
    const save = { idempotencyKey: 'save-key', expectedRevision: 0, commitMessage: '修改指标' };
    await api.save('s/1', save, signal);
    await api.preview('s/1', save, signal);
    const deliver = {
      idempotencyKey: 'deliver-key',
      expectedSourceSha: 'a'.repeat(40),
      expectedMainSha: 'b'.repeat(40),
      confirmedScopeDigest: 'c'.repeat(64),
    };
    await api.deliver('s/1', deliver, signal);
    await api.recover('s/1', 'op/1', { expectedVersion: 0, idempotencyKey: 'recover-key' }, signal);
    expect(fetchImpl.mock.calls.map(([url]) => url)).toEqual([
      '/api/v1/agent-sessions/s%2F1/development/save',
      '/api/v1/agent-sessions/s%2F1/development/preview',
      '/api/v1/agent-sessions/s%2F1/development/deliver',
      '/api/v1/agent-sessions/s%2F1/development/operations/op%2F1/recover',
    ]);
    expect(JSON.parse(fetchImpl.mock.calls[2][1].body)).toEqual(deliver);
  });

  it('noLegacyRequestOnFailure', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ code: 'REVISION_CONFLICT', detail: '修订已变化' }), {
        status: 409,
      }),
    );
    const api = createDevelopmentClient({ fetchImpl });
    await expect(
      api.save(
        's1',
        { idempotencyKey: 'key', expectedRevision: 3, commitMessage: 'save' },
        new AbortController().signal,
      ),
    ).rejects.toMatchObject({ status: 409, code: 'REVISION_CONFLICT' });
    expect(fetchImpl).toHaveBeenCalledTimes(1);
    expect(fetchImpl.mock.calls[0][0]).toBe('/api/v1/agent-sessions/s1/development/save');
  });

  it('文件正文和未知 YAML 字段完整往返，不绑定旧工作区身份', async () => {
    const source =
      '# comment\nmetrics:\n  - name: revenue\n    vendor_extension: &custom {enabled: true}\nunknown: *custom\n';
    const fetchImpl = vi
      .fn()
      .mockImplementation(
        async () =>
          new Response(
            JSON.stringify({ path: 'models/schema.yml', content: source, sha256: 'digest' }),
          ),
      );
    const api = createDevelopmentClient({ fetchImpl });
    const signal = new AbortController().signal;
    const file = await api.file('s1', 'models/schema.yml', signal);
    await api.writeFiles(
      's1',
      {
        expectedRevision: 7,
        operations: [
          { type: 'UPDATE', path: file.path, content: file.content, expectedSha256: file.sha256 },
        ],
      },
      signal,
    );
    expect(JSON.parse(fetchImpl.mock.calls[1][1].body)).toEqual({
      expectedRevision: 7,
      operations: [
        { type: 'UPDATE', path: 'models/schema.yml', content: source, expectedSha256: 'digest' },
      ],
    });
  });
});
