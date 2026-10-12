import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { DevelopmentOperationCard } from './DevelopmentOperationCard';
import type { DevelopmentClient, DevelopmentOperation } from '../../api/developmentClient';

afterEach(() => {
  cleanup();
  vi.unstubAllEnvs();
});
const saved: DevelopmentOperation = {
  operationId: 'op1',
  type: 'SAVE',
  phase: 'PUSHED',
  status: 'SUCCEEDED',
  version: 2,
  pushedSha: 'sha1',
  mergeSha: null,
  buildIds: [],
  error: null,
};
describe('持久开发操作卡片', () => {
  it('缺少 schema 的旧操作提示补全配置并且不提供原操作恢复', async () => {
    const api = {
      operation: vi.fn().mockResolvedValue({
        ...saved,
        status: 'FAILED',
        error: {
          code: 'DEVELOPMENT_SCHEMA_REQUIRED',
          message: '请补全项目开发配置后发起新操作',
          retryable: false,
        },
      }),
    } as unknown as DevelopmentClient;
    render(<DevelopmentOperationCard sessionId="s1" operationId="op1" client={api} />);
    expect(await screen.findByRole('alert')).toHaveTextContent('请补全项目开发配置后发起新操作');
    expect(screen.queryByRole('button', { name: '核对并恢复' })).not.toBeInTheDocument();
  });
  it('saveIsNotDeploymentSuccess', async () => {
    const api = { operation: vi.fn().mockResolvedValue(saved) } as unknown as DevelopmentClient;
    render(<DevelopmentOperationCard sessionId="s1" operationId="op1" client={api} />);
    expect(await screen.findByText('已保存')).toBeInTheDocument();
    expect(screen.queryByText('部署成功')).not.toBeInTheDocument();
  });
  it('operationSurvivesReconnect，恢复携带版本且不重新创建动作', async () => {
    const operation = vi
      .fn()
      .mockResolvedValue({ ...saved, status: 'RESULT_UNKNOWN', phase: 'VERIFYING' });
    const recover = vi.fn().mockResolvedValue(saved);
    const api = { operation, recover } as unknown as DevelopmentClient;
    const view = render(<DevelopmentOperationCard sessionId="s1" operationId="op1" client={api} />);
    fireEvent.click(await screen.findByRole('button', { name: '核对并恢复' }));
    await screen.findByText('已保存');
    expect(recover).toHaveBeenCalledWith(
      's1',
      'op1',
      expect.objectContaining({ expectedVersion: 2, idempotencyKey: expect.any(String) }),
      expect.any(AbortSignal),
    );
    view.unmount();
    operation.mockClear();
    render(<DevelopmentOperationCard sessionId="s1" operationId="op1" client={api} />);
    await waitFor(() => expect(operation).toHaveBeenCalledTimes(1));
  });
  it('构建成功但部署未完成不显示成功，固定 buildId 只作文本展示，不连接指标平台', async () => {
    vi.stubEnv('VITE_METRIC_PLATFORM_URL', 'https://metrics.example');
    const api = {
      operation: vi.fn().mockResolvedValue({
        ...saved,
        type: 'PREVIEW',
        phase: 'DEPLOYMENT_PENDING',
        status: 'BLOCKED',
        buildIds: ['build-original'],
        error: { code: 'STALE', message: '分支已前进', retryable: false },
      }),
    } as unknown as DevelopmentClient;
    render(<DevelopmentOperationCard sessionId="s1" operationId="op1" client={api} />);
    expect(await screen.findByText('操作受阻')).toBeInTheDocument();
    expect(screen.getByText('build-original')).toBeInTheDocument();
    expect(screen.queryByRole('link')).not.toBeInTheDocument();
    expect(screen.queryByText('部署成功')).not.toBeInTheDocument();
  });
});
