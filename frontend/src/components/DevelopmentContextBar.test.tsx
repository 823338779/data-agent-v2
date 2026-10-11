import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { DevelopmentContextBar } from './DevelopmentContextBar';
import type { DevelopmentClient, DevelopmentOverview } from '../api/developmentClient';

afterEach(cleanup);
const overview: DevelopmentOverview = {
  context: {
    sessionId: 's1',
    projectId: 'p1',
    repository: 'repo',
    branchName: 'feature/revenue',
    baselineSha: 'base',
    configSnapshot: {
      repository: 'repo',
      previewBinding: 'dev',
      previewConfigVersion: 'dev-v1',
      previewSchemaName: 'preview_metrics',
      productionBinding: 'prod',
      productionConfigVersion: 'prod-v2',
      productionSchemaName: 'production_metrics',
      version: 2,
    },
  },
  local: { state: 'READY', revision: 7, pushedSha: 'source' },
  remote: {
    headSha: 'source',
    mainSha: 'main',
    observedAt: '2026-10-10T12:00:00Z',
    errorCode: null,
  },
  latestBuild: { buildId: 'b1', buildStatus: 'SUCCEEDED', phase: 'COMPLETE' },
  currentDeployment: null,
  deliveryScope: {
    expectedSourceSha: 'source',
    expectedMainSha: 'main',
    confirmedScopeDigest: 'digest',
    diff: 'diff --git a/shared.sql b/shared.sql\n+ other session change',
  },
  operations: [],
};
function client(value = overview) {
  return {
    overview: vi.fn().mockResolvedValue(value),
    deliver: vi.fn().mockResolvedValue({
      operationId: 'op1',
      type: 'DELIVER',
      phase: 'PRECHECK',
      status: 'RUNNING',
      version: 0,
      pushedSha: null,
      mergeSha: null,
      buildIds: [],
      error: null,
    }),
    operation: vi.fn().mockImplementation(() => new Promise(() => {})),
  } as unknown as DevelopmentClient;
}

describe('固定会话开发上下文', () => {
  it('四种独立状态，不把成功构建当成已部署，也不回退 main', async () => {
    render(<DevelopmentContextBar sessionId="s1" client={client()} />);
    expect(await screen.findByText('feature/revenue')).toBeInTheDocument();
    expect(screen.getByText('当前分支尚未部署')).toBeInTheDocument();
    expect(screen.getByText('SUCCEEDED')).toBeInTheDocument();
    expect(screen.queryByRole('combobox')).not.toBeInTheDocument();
  });
  it('完整共享分支 diff、两个 SHA、配置摘要必须确认后才能交付', async () => {
    const api = client();
    render(<DevelopmentContextBar sessionId="s1" client={api} />);
    fireEvent.click(await screen.findByRole('button', { name: '合并并发布' }));
    expect(await screen.findByText(/other session change/)).toBeInTheDocument();
    expect(screen.getByText('prod-v2')).toBeInTheDocument();
    expect(screen.getByText('preview_metrics')).toBeInTheDocument();
    expect(screen.getByText('production_metrics')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '确认完整范围并发布' })).toBeDisabled();
    fireEvent.click(screen.getByRole('checkbox'));
    fireEvent.click(screen.getByRole('button', { name: '确认完整范围并发布' }));
    await waitFor(() =>
      expect(api.deliver).toHaveBeenCalledWith(
        's1',
        {
          idempotencyKey: expect.any(String),
          expectedSourceSha: 'source',
          expectedMainSha: 'main',
          confirmedScopeDigest: 'digest',
        },
        expect.any(AbortSignal),
      ),
    );
  });
  it('lostLocalFilesShowsUnsavedLoss，禁止空保存和构建', async () => {
    render(
      <DevelopmentContextBar
        sessionId="s1"
        client={client({
          ...overview,
          local: { state: 'LOCAL_INPUT_LOST', revision: 7, pushedSha: null },
        })}
      />,
    );
    expect(await screen.findByText(/未推送内容无法恢复/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '保存到 Git' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '构建部署' })).toBeDisabled();
  });
  it('刷新后确认范围变化必须重新勾选，不复用旧授权', async () => {
    const api = client();
    render(<DevelopmentContextBar sessionId="s1" client={api} />);
    fireEvent.click(await screen.findByRole('button', { name: '合并并发布' }));
    fireEvent.click(await screen.findByRole('checkbox'));
    vi.mocked(api.overview).mockResolvedValue({
      ...overview,
      deliveryScope: {
        ...overview.deliveryScope!,
        expectedSourceSha: 'new-source',
        confirmedScopeDigest: 'new-digest',
      },
    });
    fireEvent.click(screen.getByRole('button', { name: '刷新开发状态' }));
    await waitFor(() => expect(screen.getByRole('checkbox')).not.toBeChecked());
  });
});
