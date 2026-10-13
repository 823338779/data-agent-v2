import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { useState } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { MemoryApiError, MemoryClient } from '../../api/memoryClient';
import { MemoryEntry, MemoryListView, MemoryOutcome } from '../../domain/memory';
import { PersonalMemoryPanel } from './PersonalMemoryPanel';

// 标题在加载阶段就存在，写入场景必须等表单出现后再操作。
const MEMORY_CONTENT_LABEL = '记忆内容';

function createMemoryClientStub() {
  return {
    list: vi.fn().mockResolvedValue({
      groups: {
        person_profile: { entries: [], nextCursor: null },
        collaboration_preference: { entries: [], nextCursor: null },
      },
      quotas: {
        person_profile: { used: 0, limit: 800 },
        collaboration_preference: { used: 0, limit: 1500 },
      },
      revision: 0,
    }),
    create: vi.fn(),
    replace: vi.fn(),
    remove: vi.fn().mockResolvedValue(undefined),
    settings: vi.fn().mockResolvedValue({
      readEnabled: true,
      explicitWriteEnabled: true,
      autoLearningEnabled: true,
      settingsVersion: 0,
    }),
    updateSettings: vi.fn(),
  };
}

const profile: MemoryEntry = {
  memoryId: 'profile-1',
  kind: 'person_profile',
  content: '<img src=x onerror=alert(1)>喜欢清晨工作',
  source: 'USER_UI',
  userProtected: true,
  version: 2,
  updatedAt: '2026-09-16T10:30:00+08:00',
};
const preference: MemoryEntry = {
  memoryId: 'preference-1',
  kind: 'collaboration_preference',
  content: '回答先给结论',
  source: 'LLM_ACTIVE',
  userProtected: false,
  version: 4,
  updatedAt: '2026-09-16T11:00:00+08:00',
};
const secondProfile: MemoryEntry = {
  ...profile,
  memoryId: 'profile-2',
  content: '住在杭州',
  version: 1,
};

function populatedView(): MemoryListView {
  return {
    groups: {
      person_profile: { entries: [profile], nextCursor: 'profile-1' },
      collaboration_preference: { entries: [preference], nextCursor: null },
    },
    quotas: {
      person_profile: { used: 320, limit: 800 },
      collaboration_preference: { used: 980, limit: 1500 },
    },
    revision: 8,
  };
}

function outcome(overrides: Partial<MemoryOutcome> = {}): MemoryOutcome {
  return {
    status: 'CREATED',
    memoryId: 'memory-2',
    version: 1,
    revision: 9,
    created: true,
    targetExists: true,
    quotas: populatedView().quotas,
    ...overrides,
  };
}

function profilePage(entries: MemoryEntry[], nextCursor: string | null): MemoryListView {
  return {
    ...populatedView(),
    groups: {
      person_profile: { entries, nextCursor },
      collaboration_preference: { entries: [], nextCursor: null },
    },
  };
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe('PersonalMemoryPanel', () => {
  it('ignores a late page after deletion and uses only the refreshed cursor and quota', async () => {
    let finishPage!: (page: MemoryListView) => void;
    let finishFreshPage!: (page: MemoryListView) => void;
    const oldPage = new Promise<MemoryListView>((resolve) => {
      finishPage = resolve;
    });
    const freshPage = new Promise<MemoryListView>((resolve) => {
      finishFreshPage = resolve;
    });
    const client = createMemoryClientStub();
    client.list
      .mockResolvedValueOnce(populatedView())
      .mockReturnValueOnce(oldPage)
      .mockResolvedValueOnce({
        ...profilePage([secondProfile], 'fresh-cursor'),
        revision: 10,
        quotas: { ...populatedView().quotas, person_profile: { used: 4, limit: 800 } },
      })
      .mockReturnValueOnce(freshPage);
    render(<PersonalMemoryPanel client={client} onClose={() => {}} />);
    const region = await screen.findByRole('region', { name: '个人画像' });
    fireEvent.click(within(region).getByRole('button', { name: '加载更多个人画像' }));
    fireEvent.click(within(region).getByRole('button', { name: '删除' }));
    fireEvent.click(within(region).getByRole('button', { name: '确认删除' }));
    await screen.findByText('已用 4 / 800 字符');
    fireEvent.click(within(region).getByRole('button', { name: '加载更多个人画像' }));
    await waitFor(() =>
      expect(client.list).toHaveBeenLastCalledWith('person_profile', 'fresh-cursor'),
    );
    await act(async () => finishPage(profilePage([secondProfile], 'stale-cursor')));
    expect(screen.getAllByText(secondProfile.content)).toHaveLength(1);
    expect(screen.getByText('已用 4 / 800 字符')).toBeInTheDocument();
    expect(within(region).getByRole('button', { name: '加载更多个人画像' })).toBeDisabled();
    await act(async () =>
      finishFreshPage({
        ...profilePage([], null),
        revision: 10,
        quotas: { ...populatedView().quotas, person_profile: { used: 4, limit: 800 } },
      }),
    );
    expect(screen.getAllByText(secondProfile.content)).toHaveLength(1);
    expect(
      within(region).queryByRole('button', { name: '加载更多个人画像' }),
    ).not.toBeInTheDocument();
  });

  it.each(['CREATED', 'ALREADY_EXISTS'] as const)(
    'preserves a missing historical %s draft until explicit restore',
    async (status) => {
      const client = createMemoryClientStub();
      client.create
        .mockRejectedValueOnce(new TypeError('lost response'))
        .mockResolvedValueOnce(
          outcome({ status, created: status === 'CREATED', targetExists: false }),
        )
        .mockRejectedValueOnce(new MemoryApiError(503, 'UNAVAILABLE', 'unknown commit'))
        .mockResolvedValueOnce(outcome());
      render(<PersonalMemoryPanel client={client} onClose={() => {}} />);
      await screen.findByLabelText(MEMORY_CONTENT_LABEL);
      fireEvent.change(screen.getByLabelText('记忆内容'), { target: { value: '住在杭州' } });
      fireEvent.click(screen.getByRole('button', { name: '保存记忆' }));
      await screen.findByText('保存失败，请重试。');
      fireEvent.click(screen.getByRole('button', { name: '保存记忆' }));
      await screen.findByText('该操作曾成功，但记忆现已删除。内容已保留，可明确重新记住。');
      expect(client.create.mock.calls[1][1]).toBe(client.create.mock.calls[0][1]);
      expect(screen.getByLabelText('记忆内容')).toHaveValue('住在杭州');
      expect(screen.getByRole('button', { name: '保存记忆' })).toBeDisabled();
      expect(client.create).toHaveBeenCalledTimes(2);
      fireEvent.click(screen.getByRole('button', { name: '明确重新记住' }));
      await screen.findByText('保存失败，请重试。');
      fireEvent.click(screen.getByRole('button', { name: '明确重新记住' }));
      await screen.findByText('已重新记住。');
      expect(client.create.mock.calls[2][1]).not.toBe(client.create.mock.calls[1][1]);
      expect(client.create.mock.calls[3][1]).toBe(client.create.mock.calls[2][1]);
      expect(client.create.mock.calls[2][0].restore).toBe(true);
    },
  );

  it('preserves an edit whose same-key historical success target was deleted', async () => {
    const client = createMemoryClientStub();
    client.list.mockResolvedValueOnce(populatedView());
    client.replace
      .mockRejectedValueOnce(new TypeError('lost response'))
      .mockResolvedValueOnce(outcome({ status: 'UPDATED', created: false, targetExists: false }));
    render(<PersonalMemoryPanel client={client} onClose={() => {}} />);
    const region = await screen.findByRole('region', { name: '个人画像' });
    fireEvent.click(within(region).getByRole('button', { name: '编辑' }));
    fireEvent.change(screen.getByLabelText('编辑记忆内容'), {
      target: { value: '保留的修改正文' },
    });
    fireEvent.click(screen.getByRole('button', { name: '保存修改' }));
    await screen.findByText('修改失败，请重试。');
    fireEvent.click(screen.getByRole('button', { name: '保存修改' }));
    await screen.findByText('该操作曾成功，但记忆现已删除。修改草稿已保留。');
    expect(client.replace.mock.calls[1][2]).toBe(client.replace.mock.calls[0][2]);
    expect(screen.queryByText('记忆已更新。')).not.toBeInTheDocument();
    expect(screen.getByLabelText('已删除记忆草稿')).toHaveValue('保留的修改正文');
    expect(client.create).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: '使用草稿重新记住' }));
    expect(screen.getByLabelText('记忆内容')).toHaveValue('保留的修改正文');
    expect(client.create).not.toHaveBeenCalled();
    client.create.mockResolvedValueOnce(outcome());
    fireEvent.click(screen.getByRole('button', { name: '明确重新记住' }));
    await screen.findByText('已重新记住。');
    expect(client.create.mock.calls[0][0]).toEqual({
      kind: 'person_profile',
      content: '保留的修改正文',
      restore: true,
    });
    expect(client.create.mock.calls[0][1]).not.toBe(client.replace.mock.calls[1][2]);
  });

  it('shows both empty categories, server quotas, settings guidance, and closes explicitly', async () => {
    const client = createMemoryClientStub();
    const onClose = vi.fn();
    render(<PersonalMemoryPanel client={client} onClose={onClose} />);

    expect(await screen.findByRole('heading', { name: '个人记忆' })).toBeInTheDocument();
    expect(await screen.findByText('还没有个人画像记忆。')).toBeInTheDocument();
    expect(screen.getByText('还没有协作偏好记忆。')).toBeInTheDocument();
    expect(screen.getByText('已用 0 / 800 字符')).toBeInTheDocument();
    expect(screen.getByText('已用 0 / 1500 字符')).toBeInTheDocument();
    expect(screen.getByText(/关闭后停止主动提炼和后台学习/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '关闭个人记忆' }));
    expect(onClose).toHaveBeenCalledOnce();
  });

  it('renders two category lists as plain text and loads the next category page', async () => {
    const client = createMemoryClientStub();
    client.list.mockResolvedValueOnce(populatedView()).mockResolvedValueOnce({
      groups: {
        person_profile: {
          entries: [{ ...profile, memoryId: 'profile-2', content: '住在杭州', version: 1 }],
          nextCursor: null,
        },
        collaboration_preference: { entries: [], nextCursor: null },
      },
      quotas: {
        person_profile: { used: 321, limit: 800 },
        collaboration_preference: { used: 980, limit: 1500 },
      },
      revision: 9,
    });
    render(<PersonalMemoryPanel client={client} onClose={() => {}} />);

    expect(await screen.findByText(profile.content)).toBeInTheDocument();
    expect(screen.queryByRole('img')).not.toBeInTheDocument();
    expect(screen.getByText(preference.content)).toBeInTheDocument();
    expect(screen.getByText('已用 320 / 800 字符')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '加载更多个人画像' }));

    expect(await screen.findByText('住在杭州')).toBeInTheDocument();
    expect(screen.getByText('已用 321 / 800 字符')).toBeInTheDocument();
    expect(client.list).toHaveBeenLastCalledWith('person_profile', 'profile-1');
  });

  it('reuses a key after network, unknown 5xx, and timeout failures, then changes it with content', async () => {
    vi.stubGlobal('crypto', {
      randomUUID: vi
        .fn()
        .mockReturnValueOnce('key-1')
        .mockReturnValueOnce('key-2')
        .mockReturnValueOnce('key-3'),
    });
    const client = createMemoryClientStub();
    client.create
      .mockRejectedValueOnce(new TypeError('network'))
      .mockRejectedValueOnce(new MemoryApiError(500, 'INTERNAL_ERROR', 'response lost'))
      .mockRejectedValueOnce(new MemoryApiError(408, 'REQUEST_TIMEOUT', 'timed out'))
      .mockRejectedValueOnce(new TypeError('network'))
      .mockResolvedValueOnce(outcome());
    render(<PersonalMemoryPanel client={client} onClose={() => {}} />);
    await screen.findByLabelText(MEMORY_CONTENT_LABEL);

    fireEvent.change(screen.getByLabelText('记忆内容'), { target: { value: '偏好短回答' } });
    fireEvent.click(screen.getByRole('button', { name: '保存记忆' }));
    await screen.findByText('保存失败，请重试。');
    fireEvent.click(screen.getByRole('button', { name: '保存记忆' }));
    await waitFor(() => expect(client.create).toHaveBeenCalledTimes(2));
    expect(client.create.mock.calls[0][1]).toBe('key-1');
    expect(client.create.mock.calls[1][1]).toBe('key-1');

    fireEvent.click(screen.getByRole('button', { name: '保存记忆' }));
    await waitFor(() => expect(client.create).toHaveBeenCalledTimes(3));
    expect(client.create.mock.calls[2][1]).toBe('key-1');

    fireEvent.click(screen.getByRole('button', { name: '保存记忆' }));
    await waitFor(() => expect(client.create).toHaveBeenCalledTimes(4));
    expect(client.create.mock.calls[3][1]).toBe('key-1');

    fireEvent.change(screen.getByLabelText('记忆内容'), { target: { value: '偏好先给结论' } });
    fireEvent.click(screen.getByRole('button', { name: '保存记忆' }));
    await waitFor(() => expect(client.create).toHaveBeenCalledTimes(5));
    expect(client.create.mock.calls[4][1]).toBe('key-2');
  });

  it('uses a new key for explicit restore and does not claim ALREADY_EXISTS created a memory', async () => {
    vi.stubGlobal('crypto', {
      randomUUID: vi.fn().mockReturnValueOnce('normal-key').mockReturnValueOnce('restore-key'),
    });
    const client = createMemoryClientStub();
    client.create
      .mockRejectedValueOnce(new MemoryApiError(409, 'SUPPRESSED', '已遗忘'))
      .mockResolvedValueOnce(
        outcome({ status: 'ALREADY_EXISTS', created: false, targetExists: true }),
      );
    render(<PersonalMemoryPanel client={client} onClose={() => {}} />);
    await screen.findByLabelText(MEMORY_CONTENT_LABEL);

    fireEvent.change(screen.getByLabelText('记忆内容'), { target: { value: '住在杭州' } });
    fireEvent.click(screen.getByRole('button', { name: '保存记忆' }));
    fireEvent.click(await screen.findByRole('button', { name: '明确重新记住' }));

    expect(await screen.findByText('已有相同记忆，已确认现有条目。')).toBeInTheDocument();
    expect(screen.queryByText('已重新记住。')).not.toBeInTheDocument();
    expect(client.create.mock.calls[0]).toEqual([
      { kind: 'person_profile', content: '住在杭州', restore: false },
      'normal-key',
    ]);
    expect(client.create.mock.calls[1]).toEqual([
      { kind: 'person_profile', content: '住在杭州', restore: true },
      'restore-key',
    ]);
  });

  it('clearly reports that quota rejection did not save the draft', async () => {
    vi.stubGlobal('crypto', {
      randomUUID: vi.fn().mockReturnValueOnce('quota-key-1').mockReturnValueOnce('quota-key-2'),
    });
    const client = createMemoryClientStub();
    client.create
      .mockRejectedValueOnce(new MemoryApiError(422, 'QUOTA_EXCEEDED', '个人画像容量不足'))
      .mockResolvedValueOnce(outcome());
    render(<PersonalMemoryPanel client={client} onClose={() => {}} />);
    await screen.findByLabelText(MEMORY_CONTENT_LABEL);

    fireEvent.change(screen.getByLabelText('记忆内容'), { target: { value: '仍需保留的草稿' } });
    fireEvent.click(screen.getByRole('button', { name: '保存记忆' }));

    expect(
      await screen.findByText('容量不足，记忆未保存。请删除或精简该分类的内容。'),
    ).toBeInTheDocument();
    expect(screen.getByLabelText('记忆内容')).toHaveValue('仍需保留的草稿');

    // 用户释放服务端容量后再次提交是新的业务操作，不能重放旧拒绝结果。
    fireEvent.click(screen.getByRole('button', { name: '保存记忆' }));
    await waitFor(() => expect(client.create).toHaveBeenCalledTimes(2));
    expect(client.create.mock.calls[0][1]).toBe('quota-key-1');
    expect(client.create.mock.calls[1][1]).toBe('quota-key-2');
  });

  it('clearly reports that unsafe content was rejected', async () => {
    const client = createMemoryClientStub();
    client.create.mockRejectedValue(
      new MemoryApiError(400, 'UNSAFE_CONTENT', '内容不符合安全要求'),
    );
    render(<PersonalMemoryPanel client={client} onClose={() => {}} />);
    await screen.findByLabelText(MEMORY_CONTENT_LABEL);

    fireEvent.change(screen.getByLabelText('记忆内容'), { target: { value: '待检查内容' } });
    fireEvent.click(screen.getByRole('button', { name: '保存记忆' }));

    expect(await screen.findByText('内容不符合保存要求，记忆未保存。')).toBeInTheDocument();
    expect(screen.getByLabelText('记忆内容')).toHaveValue('待检查内容');
  });

  it('edits and deletes entries using their current versions', async () => {
    const client = createMemoryClientStub();
    client.list.mockResolvedValue(populatedView());
    client.replace.mockResolvedValue(outcome({ status: 'UPDATED', memoryId: profile.memoryId }));
    render(<PersonalMemoryPanel client={client} onClose={() => {}} />);

    const profileRegion = await screen.findByRole('region', { name: '个人画像' });
    fireEvent.click(within(profileRegion).getByRole('button', { name: '编辑' }));
    fireEvent.change(screen.getByLabelText('编辑记忆内容'), {
      target: { value: '喜欢下午工作' },
    });
    fireEvent.click(screen.getByRole('button', { name: '保存修改' }));
    await waitFor(() =>
      expect(client.replace).toHaveBeenCalledWith(
        profile.memoryId,
        { content: '喜欢下午工作', expectedVersion: 2 },
        expect.any(String),
      ),
    );

    fireEvent.click(within(profileRegion).getByRole('button', { name: '删除' }));
    fireEvent.click(within(profileRegion).getByRole('button', { name: '确认删除' }));
    await waitFor(() =>
      expect(client.remove).toHaveBeenCalledWith(profile.memoryId, 2, expect.any(String)),
    );
  });

  it('refreshes a conflicted entry, preserves the draft, and requires reconfirmation', async () => {
    const latest = { ...profile, version: 3, content: '服务器内容' };
    const client = createMemoryClientStub();
    client.list.mockResolvedValueOnce(populatedView()).mockResolvedValue({
      ...populatedView(),
      groups: {
        ...populatedView().groups,
        person_profile: { entries: [latest], nextCursor: null },
      },
    });
    client.replace.mockRejectedValueOnce(new MemoryApiError(409, 'CONFLICT', '版本冲突'));
    render(<PersonalMemoryPanel client={client} onClose={() => {}} />);

    const profileRegion = await screen.findByRole('region', { name: '个人画像' });
    fireEvent.click(within(profileRegion).getByRole('button', { name: '编辑' }));
    fireEvent.change(screen.getByLabelText('编辑记忆内容'), {
      target: { value: '本地草稿仍要保留' },
    });
    fireEvent.click(screen.getByRole('button', { name: '保存修改' }));

    expect(
      await screen.findByText('版本已变化，已刷新服务器内容。请确认后重新保存。'),
    ).toBeInTheDocument();
    expect(screen.getByLabelText('编辑记忆内容')).toHaveValue('本地草稿仍要保留');
    expect(screen.getByRole('button', { name: '保存修改' })).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: '使用保留内容重新编辑' }));
    expect(screen.getByRole('button', { name: '保存修改' })).toBeEnabled();
    fireEvent.click(screen.getByRole('button', { name: '保存修改' }));
    await waitFor(() =>
      expect(client.replace).toHaveBeenLastCalledWith(
        profile.memoryId,
        { content: '本地草稿仍要保留', expectedVersion: 3 },
        expect.any(String),
      ),
    );
  });

  it('finds a conflicted second-page edit through cursors and uses its refreshed version', async () => {
    vi.stubGlobal('crypto', {
      randomUUID: vi.fn().mockReturnValueOnce('edit-key-1').mockReturnValueOnce('edit-key-2'),
    });
    const latestSecond = { ...secondProfile, content: '服务器杭州资料', version: 2 };
    const client = createMemoryClientStub();
    client.list
      .mockResolvedValueOnce(populatedView())
      .mockResolvedValueOnce(profilePage([secondProfile], null))
      .mockResolvedValueOnce(profilePage([profile], 'server-page-1'))
      .mockResolvedValueOnce(profilePage([latestSecond], null))
      .mockResolvedValue(populatedView());
    client.replace
      .mockRejectedValueOnce(new MemoryApiError(409, 'CONFLICT', '版本冲突'))
      .mockResolvedValueOnce(outcome({ status: 'UPDATED', memoryId: secondProfile.memoryId }));
    render(<PersonalMemoryPanel client={client} onClose={() => {}} />);

    const profileRegion = await screen.findByRole('region', { name: '个人画像' });
    fireEvent.click(within(profileRegion).getByRole('button', { name: '加载更多个人画像' }));
    await screen.findByText(secondProfile.content);
    fireEvent.click(within(profileRegion).getAllByRole('button', { name: '编辑' })[1]);
    fireEvent.change(screen.getByLabelText('编辑记忆内容'), {
      target: { value: '本地杭州草稿' },
    });
    fireEvent.click(screen.getByRole('button', { name: '保存修改' }));

    expect(
      await screen.findByText('版本已变化，已刷新服务器内容。请确认后重新保存。'),
    ).toBeInTheDocument();
    expect(client.list).toHaveBeenCalledWith('person_profile', 'server-page-1');
    expect(screen.getByLabelText('编辑记忆内容')).toHaveValue('本地杭州草稿');
    fireEvent.click(screen.getByRole('button', { name: '使用保留内容重新编辑' }));
    fireEvent.click(screen.getByRole('button', { name: '保存修改' }));

    await waitFor(() =>
      expect(client.replace).toHaveBeenLastCalledWith(
        secondProfile.memoryId,
        { content: '本地杭州草稿', expectedVersion: 2 },
        'edit-key-2',
      ),
    );
  });

  it('keeps a deleted conflict draft while replacing the category with authoritative pages', async () => {
    const authoritativeFirst = {
      ...secondProfile,
      memoryId: 'profile-server-1',
      content: '服务器保留的第一条画像',
      version: 5,
    };
    const authoritativeSecond = {
      ...secondProfile,
      memoryId: 'profile-server-2',
      content: '服务器保留的第二条画像',
      version: 3,
    };
    const client = createMemoryClientStub();
    client.list
      .mockResolvedValueOnce(populatedView())
      .mockResolvedValueOnce(profilePage([authoritativeFirst], 'server-page-1'))
      .mockResolvedValueOnce({
        ...profilePage([authoritativeSecond], null),
        quotas: {
          ...populatedView().quotas,
          person_profile: { used: 188, limit: 800 },
        },
        revision: 12,
      });
    client.replace.mockRejectedValueOnce(new MemoryApiError(409, 'CONFLICT', '版本冲突'));
    render(<PersonalMemoryPanel client={client} onClose={() => {}} />);

    const profileRegion = await screen.findByRole('region', { name: '个人画像' });
    fireEvent.click(within(profileRegion).getByRole('button', { name: '编辑' }));
    fireEvent.change(screen.getByLabelText('编辑记忆内容'), {
      target: { value: '服务器已删除但仍需查看的本地草稿' },
    });
    fireEvent.click(screen.getByRole('button', { name: '保存修改' }));

    expect(
      await screen.findByText('该记忆已在服务器删除，草稿已保留但不能覆盖；可取消后新增。'),
    ).toBeInTheDocument();
    expect(screen.getByLabelText('已删除记忆草稿')).toHaveValue('服务器已删除但仍需查看的本地草稿');
    expect(screen.getByText(authoritativeFirst.content)).toBeInTheDocument();
    expect(screen.getByText(authoritativeSecond.content)).toBeInTheDocument();
    expect(within(profileRegion).getByText('已用 188 / 800 字符')).toBeInTheDocument();
    expect(screen.queryByText(profile.content)).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '取消保留草稿' }));

    expect(screen.queryByLabelText('已删除记忆草稿')).not.toBeInTheDocument();
    expect(screen.queryByText(profile.content)).not.toBeInTheDocument();
    expect(within(profileRegion).getAllByRole('button', { name: '编辑' })).toHaveLength(2);
    expect(within(profileRegion).getAllByRole('button', { name: '删除' })).toHaveLength(2);
  });

  it('keeps a second-page delete target after conflict and retries with its refreshed version', async () => {
    vi.stubGlobal('crypto', {
      randomUUID: vi.fn().mockReturnValueOnce('delete-key-1').mockReturnValueOnce('delete-key-2'),
    });
    const latestSecond = { ...secondProfile, version: 2 };
    const client = createMemoryClientStub();
    client.list
      .mockResolvedValueOnce(populatedView())
      .mockResolvedValueOnce(profilePage([secondProfile], null))
      .mockResolvedValueOnce(profilePage([profile], 'server-page-1'))
      .mockResolvedValueOnce(profilePage([latestSecond], null))
      .mockResolvedValue(populatedView());
    client.remove
      .mockRejectedValueOnce(new MemoryApiError(409, 'CONFLICT', '版本冲突'))
      .mockResolvedValueOnce(undefined);
    render(<PersonalMemoryPanel client={client} onClose={() => {}} />);

    const profileRegion = await screen.findByRole('region', { name: '个人画像' });
    fireEvent.click(within(profileRegion).getByRole('button', { name: '加载更多个人画像' }));
    await screen.findByText(secondProfile.content);
    fireEvent.click(within(profileRegion).getAllByRole('button', { name: '删除' })[1]);
    fireEvent.click(within(profileRegion).getByRole('button', { name: '确认删除' }));

    expect(
      await screen.findByText('版本已变化，已刷新服务器内容，请重新删除。'),
    ).toBeInTheDocument();
    expect(client.list).toHaveBeenCalledWith('person_profile', 'server-page-1');
    expect(screen.getByText(secondProfile.content)).toBeInTheDocument();
    fireEvent.click(within(profileRegion).getAllByRole('button', { name: '删除' })[1]);
    fireEvent.click(within(profileRegion).getByRole('button', { name: '确认删除' }));

    await waitFor(() =>
      expect(client.remove).toHaveBeenLastCalledWith(secondProfile.memoryId, 2, 'delete-key-2'),
    );
  });

  it('ends the rejected edit key before refresh and recovers a preserved draft after refresh failure', async () => {
    vi.stubGlobal('crypto', {
      randomUUID: vi.fn().mockReturnValueOnce('conflict-key').mockReturnValueOnce('recovered-key'),
    });
    const latest = { ...profile, content: '服务器新内容', version: 3 };
    const client = createMemoryClientStub();
    client.list
      .mockResolvedValueOnce(populatedView())
      .mockRejectedValueOnce(new TypeError('refresh failed'))
      .mockResolvedValueOnce(profilePage([latest], null))
      .mockResolvedValue(populatedView());
    client.replace
      .mockRejectedValueOnce(new MemoryApiError(409, 'CONFLICT', '版本冲突'))
      .mockResolvedValueOnce(outcome({ status: 'UPDATED', memoryId: profile.memoryId }));
    render(<PersonalMemoryPanel client={client} onClose={() => {}} />);

    const profileRegion = await screen.findByRole('region', { name: '个人画像' });
    fireEvent.click(within(profileRegion).getByRole('button', { name: '编辑' }));
    fireEvent.change(screen.getByLabelText('编辑记忆内容'), {
      target: { value: '刷新失败也保留的草稿' },
    });
    fireEvent.click(screen.getByRole('button', { name: '保存修改' }));

    expect(
      await screen.findByText('版本冲突，但刷新失败。请先重试刷新服务器版本。'),
    ).toBeInTheDocument();
    expect(screen.getByLabelText('编辑记忆内容')).toHaveValue('刷新失败也保留的草稿');
    expect(screen.getByRole('button', { name: '保存修改' })).toBeDisabled();
    expect(screen.queryByRole('button', { name: '使用保留内容重新编辑' })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '重试刷新服务器版本' }));
    expect(await screen.findByRole('button', { name: '使用保留内容重新编辑' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '使用保留内容重新编辑' }));
    fireEvent.click(screen.getByRole('button', { name: '保存修改' }));

    await waitFor(() =>
      expect(client.replace).toHaveBeenLastCalledWith(
        profile.memoryId,
        { content: '刷新失败也保留的草稿', expectedVersion: 3 },
        'recovered-key',
      ),
    );
    expect(client.replace.mock.calls[0][2]).toBe('conflict-key');
  });

  it('keeps keyboard focus inside the modal, closes with Escape, and restores the opener', async () => {
    const client = createMemoryClientStub();

    function Harness() {
      const [open, setOpen] = useState(false);
      const [, setRenderVersion] = useState(0);
      return (
        <>
          <button type="button" onClick={() => setOpen(true)}>
            打开记忆
          </button>
          <button type="button" onClick={() => setRenderVersion((version) => version + 1)}>
            刷新父级
          </button>
          {open && (
            <PersonalMemoryPanel
              client={client}
              onClose={() => {
                setOpen(false);
                setRenderVersion((version) => version + 1);
              }}
            />
          )}
        </>
      );
    }

    render(<Harness />);
    const opener = screen.getByRole('button', { name: '打开记忆' });
    opener.focus();
    fireEvent.click(opener);
    const close = screen.getByRole('button', { name: '关闭个人记忆' });
    await waitFor(() => expect(close).toHaveFocus());
    await screen.findByLabelText('记忆内容');

    fireEvent.keyDown(document, { key: 'Tab', shiftKey: true });
    expect(screen.getByLabelText('记忆内容')).toHaveFocus();
    fireEvent.keyDown(document, { key: 'Tab' });
    expect(close).toHaveFocus();

    screen.getByLabelText('记忆内容').focus();
    fireEvent.change(screen.getByLabelText('记忆内容'), { target: { value: '输入中' } });
    fireEvent.click(screen.getByRole('button', { name: '刷新父级' }));
    expect(screen.getByLabelText('记忆内容')).toHaveFocus();
    fireEvent.keyDown(document, { key: 'Escape' });

    expect(screen.queryByRole('dialog', { name: '个人记忆' })).not.toBeInTheDocument();
    expect(opener).toHaveFocus();
  });

  it('设置保存失败后仍显示服务端的开启状态', async () => {
    const client = createMemoryClientStub();
    client.updateSettings.mockRejectedValue(new Error('不可用'));
    render(<PersonalMemoryPanel client={client} onClose={() => {}} />);
    const toggle = await screen.findByRole('checkbox', { name: '自动学习' });
    fireEvent.click(toggle);
    await screen.findByText('设置保存失败，请重试。');
    expect(toggle).toBeChecked();
  });
});
