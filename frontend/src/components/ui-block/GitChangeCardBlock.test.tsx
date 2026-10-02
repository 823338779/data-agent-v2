import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { UiBlockRenderer } from './UiBlockRenderer';
const pending = {
  changeId: 'gc1',
  version: 0,
  status: 'PENDING_CONFIRMATION',
  branch: 'main',
  baseCommit: 'base123',
  summary: '调整指标',
  commitMessage: 'update metrics',
  commitId: null,
  reviewAvailable: true,
  files: [{ fileId: '0', status: 'M', path: 'metrics.yml' }],
  errorCode: null,
  errorSummary: null,
};
const block = {
  blockId: 'b1',
  type: 'git-change-card',
  schemaVersion: 1,
  variant: 'default',
  payload: { sessionId: 's1', changeId: 'gc1' },
};
const response = (data: unknown, status = 200) => new Response(JSON.stringify(data), { status });
let fetchMock: ReturnType<typeof vi.fn>;
const mount = () => render(<UiBlockRenderer block={block} />);
const flush = async () => {
  await act(async () => {
    await Promise.resolve();
  });
};
beforeEach(() => {
  fetchMock = vi.fn().mockImplementation(async () => response(pending));
  vi.stubGlobal('fetch', fetchMock);
});
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.useRealTimers();
});
describe('GitChangeCardBlock', () => {
  it('loads trusted detail and fetches complete diff only on demand as text', async () => {
    fetchMock
      .mockResolvedValueOnce(response(pending))
      .mockResolvedValueOnce(
        response({ fileId: '0', diff: '+<img src=x onerror=alert(1)>\n+完整末行' }),
      );
    mount();
    expect(await screen.findByText('调整指标')).toBeInTheDocument();
    expect(screen.getByText('base123')).toBeInTheDocument();
    expect(screen.getByText('update metrics')).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByRole('button', { name: '查看差异 metrics.yml' }));
    expect(await screen.findByText(/完整末行/)).toHaveTextContent('<img src=x onerror=alert(1)>');
    expect(document.querySelector('img')).toBeNull();
    expect(fetchMock.mock.calls[1][0]).toBe(
      '/api/v1/agent-sessions/s1/git-changes/gc1/diff?fileId=0',
    );
  });
  it('synchronously blocks double confirmation and sends legal version zero', async () => {
    let resolve!: (value: Response) => void;
    fetchMock.mockResolvedValueOnce(response(pending)).mockImplementationOnce(
      () =>
        new Promise<Response>((r) => {
          resolve = r;
        }),
    );
    mount();
    const confirm = await screen.findByRole('button', { name: '确认提交' });
    fireEvent.click(confirm);
    fireEvent.click(confirm);
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(JSON.parse(fetchMock.mock.calls[1][1].body)).toEqual({ version: 0 });
    expect(confirm).toBeDisabled();
    await act(async () =>
      resolve(response({ ...pending, version: 2, status: 'PUSHED', commitId: 'commit123' })),
    );
    expect(screen.getByText('已推送')).toBeInTheDocument();
    expect(confirm).toBeDisabled();
    expect(screen.queryByText('已生效')).not.toBeInTheDocument();
  });
  it.each(['INVALIDATED', 'RECONFIRM_REQUIRED', 'SUBMITTING', 'VERIFYING', 'CANCELLED', 'PUSHED'])(
    'does not confirm %s',
    async (status) => {
      fetchMock.mockResolvedValueOnce(response({ ...pending, status }));
      mount();
      expect(await screen.findByRole('button', { name: '确认提交' })).toBeDisabled();
    },
  );
  it('cancels using the latest detail version', async () => {
    fetchMock
      .mockResolvedValueOnce(response({ ...pending, version: 7 }))
      .mockResolvedValueOnce(response({ ...pending, version: 8, status: 'CANCELLED' }));
    mount();
    fireEvent.click(await screen.findByRole('button', { name: '取消' }));
    expect(await screen.findByText('已取消')).toBeInTheDocument();
    expect(fetchMock.mock.calls[1][0]).toMatch(/\/cancel$/);
    expect(JSON.parse(fetchMock.mock.calls[1][1].body)).toEqual({ version: 7 });
  });
  it('retries a confirmed transient failure', async () => {
    fetchMock
      .mockResolvedValueOnce(
        response({ ...pending, version: 4, status: 'FAILED', errorCode: 'GIT_RETRY_REQUIRED' }),
      )
      .mockResolvedValueOnce(response({ ...pending, version: 5, status: 'SUBMITTING' }));
    mount();
    fireEvent.click(await screen.findByRole('button', { name: '重试原确认' }));
    expect(await screen.findByText('提交中')).toBeInTheDocument();
    expect(fetchMock.mock.calls[1][0]).toMatch(/\/retry$/);
    expect(JSON.parse(fetchMock.mock.calls[1][1].body)).toEqual({ version: 4 });
  });
  it.each([
    'GIT_CONFIRMATION_MISSING',
    'GIT_SNAPSHOT_CORRUPT',
    'GIT_WORKSPACE_CORRUPT',
    'GIT_CONFLICT',
  ])('requires a new draft for %s', async (errorCode) => {
    fetchMock.mockResolvedValueOnce(response({ ...pending, status: 'FAILED', errorCode }));
    mount();
    expect(await screen.findByText(/重新准备草稿/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '重试原确认' })).not.toBeInTheDocument();
  });
  it('refreshes a conflict and stops offering a stale confirmation', async () => {
    fetchMock
      .mockResolvedValueOnce(response(pending))
      .mockResolvedValueOnce(
        response({ code: 'GIT_CONFLICT', detail: '候选状态或版本已变化' }, 409),
      )
      .mockResolvedValueOnce(response({ ...pending, version: 3, status: 'INVALIDATED' }));
    mount();
    fireEvent.click(await screen.findByRole('button', { name: '确认提交' }));
    expect(await screen.findByText('已失效')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '确认提交' })).toBeDisabled();
  });
  it('bounds polling across rerenders and manually reconciles latest version', async () => {
    vi.useFakeTimers();
    let version = 5;
    fetchMock.mockImplementation(async () =>
      response({ ...pending, status: 'VERIFYING', version: version++ }),
    );
    const view = mount();
    await flush();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(20000);
    });
    view.rerender(<UiBlockRenderer block={{ ...block }} />);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(40000);
    });
    const calls = fetchMock.mock.calls.length;
    await act(async () => {
      await vi.advanceTimersByTimeAsync(60000);
    });
    expect(fetchMock).toHaveBeenCalledTimes(calls);
    expect(screen.getByText(/自动查询已暂停/)).toBeInTheDocument();
    fetchMock.mockResolvedValueOnce(response({ ...pending, status: 'PUSHED', version: 99 }));
    fireEvent.click(screen.getByRole('button', { name: '核实结果' }));
    await flush();
    expect(JSON.parse(fetchMock.mock.calls[calls][1].body)).toEqual({ version: version - 1 });
    expect(fetchMock.mock.calls[calls][0]).toMatch(/\/reconcile$/);
    expect(screen.getByText('已推送')).toBeInTheDocument();
  });
  it('stops polling after a terminal response', async () => {
    vi.useFakeTimers();
    fetchMock
      .mockResolvedValueOnce(response({ ...pending, status: 'SUBMITTING' }))
      .mockResolvedValue(response({ ...pending, status: 'PUSHED', version: 2 }));
    mount();
    await flush();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(60000);
    });
    expect(screen.getByText('已推送')).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });
  it('aborts outstanding requests and clears polling on unmount', async () => {
    vi.useFakeTimers();
    fetchMock
      .mockResolvedValueOnce(response({ ...pending, status: 'VERIFYING' }))
      .mockImplementationOnce(() => new Promise(() => {}));
    const view = mount();
    await flush();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(2000);
    });
    expect(fetchMock).toHaveBeenCalledTimes(2);
    const signal = fetchMock.mock.calls[1][1].signal as AbortSignal;
    view.unmount();
    expect(signal.aborted).toBe(true);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(60000);
    });
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });
  it('does not let an old poll overwrite manual reconciliation', async () => {
    vi.useFakeTimers();
    let resolve!: (r: Response) => void;
    fetchMock
      .mockResolvedValueOnce(response({ ...pending, status: 'VERIFYING', version: 3 }))
      .mockImplementationOnce(
        () =>
          new Promise<Response>((r) => {
            resolve = r;
          }),
      )
      .mockResolvedValueOnce(response({ ...pending, status: 'PUSHED', version: 5 }));
    mount();
    await flush();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(2000);
    });
    fireEvent.click(screen.getByRole('button', { name: '核实结果' }));
    await flush();
    await act(async () => resolve(response({ ...pending, status: 'VERIFYING', version: 4 })));
    expect(screen.getByText('已推送')).toBeInTheDocument();
  });
});

it('can reload detail after a network failure without resending any mutation', async () => {
  fetchMock
    .mockRejectedValueOnce(new TypeError('网络不可用'))
    .mockResolvedValueOnce(response(pending));
  mount();
  expect(await screen.findByRole('alert')).toHaveTextContent('网络不可用');
  fireEvent.click(screen.getByRole('button', { name: '重新加载' }));
  expect(await screen.findByRole('button', { name: '确认提交' })).toBeEnabled();
  expect(fetchMock.mock.calls.every((call) => call[1].method === 'GET')).toBe(true);
});
it.each(['GIT_TIMEOUT', 'GIT_INTERRUPTED'])(
  'allows retrying confirmed %s failures',
  async (errorCode) => {
    fetchMock.mockResolvedValueOnce(response({ ...pending, status: 'FAILED', errorCode }));
    mount();
    expect(await screen.findByRole('button', { name: '重试原确认' })).toBeEnabled();
  },
);

it('retries a failed diff only when reopened, not when collapsed', async () => {
  fetchMock
    .mockResolvedValueOnce(response(pending))
    .mockRejectedValueOnce(new TypeError('差异读取失败'))
    .mockResolvedValueOnce(response({ fileId: '0', diff: '+恢复内容' }));
  mount();
  fireEvent.click(await screen.findByRole('button', { name: '查看差异 metrics.yml' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('差异读取失败');
  fireEvent.click(screen.getByRole('button', { name: '收起差异 metrics.yml' }));
  expect(fetchMock).toHaveBeenCalledTimes(2);
  fireEvent.click(screen.getByRole('button', { name: '查看差异 metrics.yml' }));
  expect(await screen.findByText('+恢复内容')).toBeInTheDocument();
});

it('disables approval when pending review material is unavailable', async () => {
  fetchMock.mockResolvedValueOnce(response({ ...pending, reviewAvailable: false, files: null }));
  mount();
  expect(await screen.findByText(/审阅材料不可用/)).toBeInTheDocument();
  expect(screen.getByRole('button', { name: '确认提交' })).toBeDisabled();
  expect(screen.getByRole('button', { name: '取消' })).toBeEnabled();
  expect(screen.queryByRole('button', { name: /查看差异/ })).not.toBeInTheDocument();
});

it.each(['FAILED', 'PUSHED'])(
  'recovers stored %s after action failure with unavailable review',
  async (status) => {
    fetchMock
      .mockResolvedValueOnce(response(pending))
      .mockResolvedValueOnce(
        response({ code: 'GIT_SNAPSHOT_CORRUPT', detail: '候选快照不可用' }, 409),
      )
      .mockResolvedValueOnce(
        response({
          ...pending,
          status,
          version: 2,
          commitId: 'saved-commit',
          errorCode: status === 'FAILED' ? 'GIT_SNAPSHOT_CORRUPT' : null,
          reviewAvailable: false,
          files: null,
        }),
      );
    mount();
    fireEvent.click(await screen.findByRole('button', { name: '确认提交' }));
    expect(
      await screen.findByText(status === 'FAILED' ? '提交失败' : '已推送'),
    ).toBeInTheDocument();
    expect(screen.getByText('saved-commit')).toBeInTheDocument();
    expect(screen.getByText(/审阅材料不可用/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '确认提交' })).toBeDisabled();
    expect(screen.queryByText('加载失败')).not.toBeInTheDocument();
  },
);

it('shows frozen file checks and explicitly excludes metric business validation', async () => {
  mount();
  expect(await screen.findByText(/冻结时基础检查已通过/)).toHaveTextContent('UTF-8');
  expect(screen.getByText(/冻结时基础检查已通过/)).toHaveTextContent('文件与 Git 变更完整性');
  expect(screen.getByText(/未进行指标业务口径校验/)).toBeInTheDocument();
});
