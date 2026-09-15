import { act, cleanup, renderHook } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { createFetchAgentBackendClient, BackendApiError } from '../api/backendAgentClient';
import { ConversationSidebarSnapshot } from '../domain/sidebar';
import { useConversationSidebar } from './useConversationSidebar';

const empty: ConversationSidebarSnapshot = { projects: [], sessions: [] };
const populated: ConversationSidebarSnapshot = {
  sessions: [],
  projects: [
    {
      projectId: 'p1',
      name: 'Project',
      version: 0,
      createdAt: '2026-09-15T01:00:00',
      updatedAt: '2026-09-15T01:00:00',
    },
  ],
};
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((yes) => {
    resolve = yes;
  });
  return { promise, resolve };
}
function client() {
  return { ...createFetchAgentBackendClient(), getSidebar: vi.fn().mockResolvedValue(empty) };
}
afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe('sidebar lifecycle', () => {
  it('ignores a pre-mutation read that arrives after deletion', async () => {
    const old = deferred<ConversationSidebarSnapshot>();
    const backend = client();
    backend.getSidebar.mockReturnValueOnce(old.promise);
    const { result } = renderHook(() => useConversationSidebar(backend));
    await act(async () => {
      await result.current.mutate(async () => undefined);
    });
    await act(async () => {
      old.resolve(populated);
    });
    expect(result.current.snapshot.projects).toEqual([]);
    expect(result.current.busy).toBe(false);
  });
  it('retains prior data and distinguishes saved from failed refresh', async () => {
    const backend = client();
    backend.getSidebar.mockResolvedValueOnce(populated).mockRejectedValueOnce(new Error('offline'));
    const { result } = renderHook(() => useConversationSidebar(backend));
    await act(async () => {});
    let outcome;
    await act(async () => {
      outcome = await result.current.mutate(async () => undefined);
    });
    expect(outcome).toEqual({ outcome: 'saved', refreshed: false });
    expect(result.current.snapshot.projects).toHaveLength(1);
    expect(result.current.error).toContain('已保存');
  });
  it('serializes writes and coalesces refresh triggers while writing', async () => {
    const backend = client();
    const write = deferred<void>();
    const { result } = renderHook(() => useConversationSidebar(backend));
    await act(async () => {});
    let completion!: ReturnType<typeof result.current.mutate>;
    act(() => {
      completion = result.current.mutate(() => write.promise);
    });
    await act(async () => {
      await result.current.refresh();
      window.dispatchEvent(new Event('focus'));
    });
    const second = vi.fn();
    await act(async () => {
      await result.current.mutate(second);
    });
    expect(second).not.toHaveBeenCalled();
    expect(backend.getSidebar).toHaveBeenCalledTimes(1);
    await act(async () => {
      write.resolve();
      await completion;
    });
    expect(backend.getSidebar).toHaveBeenCalledTimes(2);
  });
  it('polls idle visible pages, pauses when hidden, and cleans up', async () => {
    vi.useFakeTimers();
    let visibility = 'visible';
    vi.spyOn(document, 'visibilityState', 'get').mockImplementation(
      () => visibility as DocumentVisibilityState,
    );
    const backend = client();
    const { unmount } = renderHook(() => useConversationSidebar(backend));
    await act(async () => {});
    await act(async () => {
      await vi.advanceTimersByTimeAsync(60_000);
    });
    expect(backend.getSidebar).toHaveBeenCalledTimes(2);
    visibility = 'hidden';
    act(() => {
      document.dispatchEvent(new Event('visibilitychange'));
    });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(120_000);
    });
    expect(backend.getSidebar).toHaveBeenCalledTimes(2);
    visibility = 'visible';
    await act(async () => {
      document.dispatchEvent(new Event('visibilitychange'));
      window.dispatchEvent(new Event('focus'));
    });
    expect(backend.getSidebar).toHaveBeenCalledTimes(3);
    unmount();
    await vi.advanceTimersByTimeAsync(60_000);
    expect(backend.getSidebar).toHaveBeenCalledTimes(3);
  });
  it('reports version conflicts and unknown network results without retrying writes', async () => {
    const backend = client();
    const { result } = renderHook(() => useConversationSidebar(backend));
    await act(async () => {});
    await act(async () => {
      expect(
        await result.current.mutate(async () => {
          throw new BackendApiError(409);
        }),
      ).toEqual({ outcome: 'rejected', refreshed: true });
    });
    expect(result.current.error).toContain('更新');
    await act(async () => {
      expect(
        await result.current.mutate(async () => {
          throw new TypeError('offline');
        }),
      ).toEqual({ outcome: 'unknown', refreshed: true });
    });
    expect(result.current.error).toContain('尚未确认');
  });
});
