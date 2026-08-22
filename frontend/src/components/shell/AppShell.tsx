import { PanelLeftOpen } from 'lucide-react';
import { ReactNode } from 'react';

interface AppShellProps {
  historyOpen: boolean;
  onToggleHistory: () => void;
  children: ReactNode;
}

export function AppShell({ historyOpen, onToggleHistory, children }: AppShellProps) {
  return (
    <main className="app-shell">
      <header className="app-header">
        <div className="brand">
          <span className="brand-mark">D</span>
          <span>Data Agent</span>
          {!historyOpen && (
            <button
              className="icon-button header-sidebar-toggle"
              type="button"
              aria-label="展开会话列表"
              onClick={onToggleHistory}
            >
              <PanelLeftOpen size={18} />
            </button>
          )}
        </div>
      </header>
      <div className="workspace">{children}</div>
    </main>
  );
}
