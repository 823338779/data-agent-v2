import { useCallback, useEffect, useRef, useState } from 'react';
import { AgentBackendClient, BackendApiError } from '../api/backendAgentClient';
import { ConversationSidebarSnapshot } from '../domain/sidebar';

export interface SidebarMutationResult {
  outcome: 'saved' | 'rejected' | 'unknown';
  refreshed: boolean;
}

/** 侧栏元数据的唯一读取与写入编排；不持有消息流或执行生命周期。 */
export function useConversationSidebar(client: AgentBackendClient) {
  const [snapshot, setSnapshot] = useState<ConversationSidebarSnapshot>({
    projects: [],
    sessions: [],
  });
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const generation = useRef(0);
  const scope = useRef(0);
  const mounted = useRef(false);
  const writing = useRef(false);
  const controller = useRef<AbortController | null>(null);
  const inFlight = useRef<Promise<boolean> | null>(null);

  const read = useCallback(
    (afterWrite = false): Promise<boolean> => {
      if (!mounted.current || (writing.current && !afterWrite)) return Promise.resolve(false);
      if (inFlight.current && !afterWrite) return inFlight.current;
      const ticket = ++generation.current;
      controller.current?.abort();
      const abort = new AbortController();
      controller.current = abort;
      const request = (async () => {
        try {
          const next = await client.getSidebar(abort.signal);
          // 取消请求并不保证服务器停止响应，代次才是旧读取不能恢复已删除项目的边界。
          if (!mounted.current || ticket !== generation.current) return false;
          setSnapshot(next);
          setError(null);
          return true;
        } catch {
          if (mounted.current && ticket === generation.current)
            setError('侧栏同步失败，点击重试。');
          return false;
        } finally {
          if (mounted.current && ticket === generation.current) {
            setLoading(false);
            inFlight.current = null;
          }
        }
      })();
      inFlight.current = request;
      return request;
    },
    [client],
  );

  const refresh = useCallback(() => read(), [read]);

  const mutate = useCallback(
    async (operation: () => Promise<unknown>): Promise<SidebarMutationResult> => {
      if (!mounted.current || writing.current) return { outcome: 'rejected', refreshed: false };
      const owner = scope.current;
      writing.current = true;
      setBusy(true);
      setError(null);
      // 同页组织写入串行，写开始时使旧读取失效；读失败不能导致重复创建已保存项目。
      ++generation.current;
      controller.current?.abort();
      inFlight.current = null;
      let outcome: SidebarMutationResult['outcome'] = 'saved';
      let failure: unknown;
      try {
        await operation();
      } catch (error) {
        failure = error;
        outcome = error instanceof BackendApiError && error.status < 500 ? 'rejected' : 'unknown';
      }
      if (!mounted.current || owner !== scope.current) return { outcome, refreshed: false };
      const refreshed = await read(true);
      if (!mounted.current || owner !== scope.current) return { outcome, refreshed: false };
      if (outcome === 'saved' && !refreshed) setError('更改已保存，侧栏刷新失败，请重试刷新。');
      if (outcome === 'unknown') setError('操作结果尚未确认，请核对列表后再重试。');
      if (outcome === 'rejected')
        setError(
          failure instanceof BackendApiError && failure.status === 409
            ? '数据已在其他位置更新，请核对后重新提交。'
            : '操作未完成，请检查输入或刷新后重试。',
        );
      writing.current = false;
      setBusy(false);
      return { outcome, refreshed };
    },
    [read],
  );

  useEffect(() => {
    mounted.current = true;
    ++scope.current;
    writing.current = false;
    setBusy(false);
    setLoading(true);
    setSnapshot({ projects: [], sessions: [] });
    let timer: number | undefined;
    const startTimer = () => {
      if (timer === undefined) timer = window.setInterval(() => void refresh(), 60_000);
    };
    const onFocus = () => {
      if (document.visibilityState !== 'hidden') void refresh();
    };
    const onVisibility = () => {
      if (document.visibilityState === 'hidden') {
        window.clearInterval(timer);
        timer = undefined;
      } else {
        void refresh();
        startTimer();
      }
    };
    void refresh();
    if (document.visibilityState !== 'hidden') startTimer();
    window.addEventListener('focus', onFocus);
    document.addEventListener('visibilitychange', onVisibility);
    return () => {
      mounted.current = false;
      ++scope.current;
      ++generation.current;
      controller.current?.abort();
      inFlight.current = null;
      window.clearInterval(timer);
      window.removeEventListener('focus', onFocus);
      document.removeEventListener('visibilitychange', onVisibility);
    };
  }, [refresh]);

  return { snapshot, loading, busy, error, refresh, mutate };
}
