import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { GitWorkspaceSelector } from './GitWorkspaceSelector';
import type { GitWorkspaceClient } from '../api/gitWorkspaceClient';

afterEach(cleanup);
const workspace = (id: string) => ({
  workspaceId: id,
  projectId: 'metrics',
  branchId: 'branch',
  gitRef: 'refs/heads/dev',
  baseCommit: 'a'.repeat(40),
  draftRevision: 2,
  status: 'READY' as const,
  activeChangeId: null,
});
const branch = {
  projectId: 'metrics',
  branchId: 'branch',
  gitRef: 'refs/heads/dev',
  mode: 'PREVIEW' as const,
  status: 'ACTIVE',
  observedHeadSha: 'a'.repeat(40),
  baseCommitSha: 'a'.repeat(40),
  version: 1,
};

describe('工作区选择', () => {
  it('损坏草稿可以显式恢复，不需要先受理执行', async () => {
    const restart = vi.fn().mockResolvedValue(workspace('w1'));
    const client = {
      list: vi.fn().mockResolvedValue([{ ...workspace('w1'), status: 'BROKEN' }]),
      branches: vi.fn().mockResolvedValue([branch]),
      restart,
    } as unknown as GitWorkspaceClient;
    render(
      <GitWorkspaceSelector sessionId="s1" workspaceId={null} onChange={vi.fn()} client={client} />,
    );
    fireEvent.click(screen.getByRole('button', { name: '开发工作区' }));
    fireEvent.click(await screen.findByRole('button', { name: /恢复草稿 w1/ }));
    await waitFor(() => expect(restart).toHaveBeenCalledWith('w1', 2, expect.any(AbortSignal)));
  });

  it('展示固定三方冲突，按原修订与远端提交人工解决', async () => {
    const resolve = vi.fn().mockResolvedValue({
      workspaceId: 'w1',
      status: 'MERGED',
      draftRevision: 3,
      baseCommitSha: 'remote',
    });
    const client = {
      list: vi.fn().mockResolvedValue([workspace('w1')]),
      branches: vi.fn().mockResolvedValue([branch]),
      sync: vi.fn().mockResolvedValue({
        workspaceId: 'w1',
        status: 'CONFLICT',
        draftRevision: 2,
        remoteCommitSha: 'remote',
        conflictPaths: ['x.yml'],
        conflicts: [
          {
            path: 'x.yml',
            baseContent: 'base',
            localContent: 'local',
            remoteContent: 'remote-value',
          },
        ],
      }),
      resolve,
    } as unknown as GitWorkspaceClient;
    render(
      <GitWorkspaceSelector sessionId="s1" workspaceId="w1" onChange={vi.fn()} client={client} />,
    );
    fireEvent.click(screen.getByRole('button', { name: '开发工作区' }));
    await screen.findByRole('option', { name: /w1/ });
    fireEvent.click(screen.getByRole('button', { name: '同步当前草稿' }));
    expect(await screen.findByText('remote-value')).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('合并结果 x.yml'), { target: { value: 'combined' } });
    fireEvent.click(screen.getByRole('button', { name: '接受人工解决结果' }));
    await waitFor(() =>
      expect(resolve).toHaveBeenCalledWith(
        'w1',
        2,
        'remote',
        { 'x.yml': 'combined' },
        expect.any(AbortSignal),
      ),
    );
  });

  it('切换会话后忽略旧请求返回的工作区', async () => {
    let finishOld!: (value: ReturnType<typeof workspace>[]) => void;
    const list = vi
      .fn()
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            finishOld = resolve;
          }),
      )
      .mockResolvedValue([workspace('w2')]);
    const client = {
      list,
      branches: vi.fn().mockResolvedValue([branch]),
    } as unknown as GitWorkspaceClient;
    const onChange = vi.fn();
    const view = render(
      <GitWorkspaceSelector
        sessionId="s1"
        workspaceId={null}
        onChange={onChange}
        client={client}
      />,
    );
    fireEvent.click(screen.getByRole('button', { name: '开发工作区' }));
    view.rerender(
      <GitWorkspaceSelector
        sessionId="s2"
        workspaceId={null}
        onChange={onChange}
        client={client}
      />,
    );
    await screen.findByRole('option', { name: /w2/ });
    await act(async () => finishOld([workspace('w1')]));
    expect(screen.queryByRole('option', { name: /w1/ })).not.toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('当前工作区'), { target: { value: 'w2' } });
    expect(onChange).toHaveBeenLastCalledWith('w2');
  });

  it('分支删除后禁用其草稿，同时允许普通聊天', async () => {
    const client = {
      list: vi.fn().mockResolvedValue([workspace('w1')]),
      branches: vi.fn().mockResolvedValue([{ ...branch, status: 'DELETED' }]),
    } as unknown as GitWorkspaceClient;
    render(
      <GitWorkspaceSelector sessionId="s1" workspaceId="w1" onChange={vi.fn()} client={client} />,
    );
    fireEvent.click(screen.getByRole('button', { name: '开发工作区' }));
    expect(await screen.findByRole('option', { name: /w1/ })).toBeDisabled();
    expect(screen.getByRole('option', { name: '普通聊天（未选择工作区）' })).toBeEnabled();
  });
});
