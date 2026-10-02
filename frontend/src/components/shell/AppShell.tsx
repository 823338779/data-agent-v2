import { Brain, PanelLeftOpen } from 'lucide-react';
import { ReactNode } from 'react';

interface AppShellProps {
  historyOpen: boolean;
  onToggleHistory: () => void;
  onOpenMemory: () => void;
  children: ReactNode;
}

export function AppShell({ historyOpen, onToggleHistory, onOpenMemory, children }: AppShellProps) {
  return (
    <main className={`app-shell ${historyOpen ? 'sidebar-expanded' : 'sidebar-collapsed'}`}>
      {!historyOpen && (
        <div className="collapsed-sidebar-controls">
          <button
            className="icon-button header-sidebar-toggle"
            type="button"
            aria-label="展开会话列表"
            onClick={onToggleHistory}
          >
            <PanelLeftOpen size={18} />
          </button>
          <button className="header-memory-button" type="button" onClick={onOpenMemory}>
            <Brain size={16} />
            个人记忆
          </button>
        </div>
      )}
      <div className="workspace">{children}</div>
    </main>
  );
}
