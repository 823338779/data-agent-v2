import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { DevelopmentSessionCreator } from './DevelopmentSessionCreator';
import type { DevelopmentClient } from '../api/developmentClient';
afterEach(cleanup);

it('branchSwitchCreatesNewSession，新目标只能创建新会话', async () => {
  const api = {
    projects: vi.fn().mockResolvedValue({
      items: [
        {
          projectId: 'p1',
          name: '指标项目',
          developmentConfigured: true,
          repository: 'repo',
          version: 1,
        },
      ],
      nextCursor: null,
    }),
    branches: vi.fn().mockResolvedValue({
      items: [
        { branchName: 'feature/a', headSha: 'a' },
        { branchName: 'feature/b', headSha: 'b' },
      ],
    }),
  } as unknown as DevelopmentClient;
  const create = vi.fn().mockResolvedValue(undefined);
  render(<DevelopmentSessionCreator client={api} onCreate={create} />);
  fireEvent.click(screen.getByRole('button', { name: '新建开发会话' }));
  fireEvent.change(await screen.findByLabelText('开发项目'), { target: { value: 'p1' } });
  await screen.findByRole('option', { name: 'feature/b' });
  fireEvent.change(screen.getByLabelText('Git 分支'), { target: { value: 'feature/b' } });
  fireEvent.click(screen.getByRole('button', { name: '创建固定分支会话' }));
  await waitFor(() =>
    expect(create).toHaveBeenCalledWith({ projectId: 'p1', branchName: 'feature/b' }),
  );
});
