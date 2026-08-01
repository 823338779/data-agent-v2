export type WorkspaceRoute = { kind: 'home' } | { kind: 'session'; sessionId: string };

const routeChangeEvent = 'workspace-route-change';

export function parseWorkspaceRoute(pathname = window.location.pathname): WorkspaceRoute {
  const match = pathname.match(/^\/sessions\/([^/]+)$/);
  if (!match) return { kind: 'home' };
  try {
    const sessionId = decodeURIComponent(match[1]);
    return sessionId ? { kind: 'session', sessionId } : { kind: 'home' };
  } catch {
    return { kind: 'home' };
  }
}

export function navigateWorkspace(route: WorkspaceRoute, replace = false): void {
  const path = route.kind === 'session' ? `/sessions/${encodeURIComponent(route.sessionId)}` : '/';
  if (window.location.pathname === path) return;
  window.history[replace ? 'replaceState' : 'pushState'](null, '', path);
  window.dispatchEvent(new Event(routeChangeEvent));
}

export function subscribeWorkspaceRoute(listener: (route: WorkspaceRoute) => void): () => void {
  const update = () => listener(parseWorkspaceRoute());
  window.addEventListener('popstate', update);
  window.addEventListener(routeChangeEvent, update);
  return () => {
    window.removeEventListener('popstate', update);
    window.removeEventListener(routeChangeEvent, update);
  };
}
