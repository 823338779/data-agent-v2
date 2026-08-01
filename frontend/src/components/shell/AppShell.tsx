import { LogOut, PanelLeftOpen } from 'lucide-react';
import { ReactNode, useState } from 'react';

interface AppShellProps {
  subject: string;
  historyOpen: boolean;
  onToggleHistory: () => void;
  onLogout: () => Promise<void>;
  children: ReactNode;
}

export function AppShell({
  subject,
  historyOpen,
  onToggleHistory,
  onLogout,
  children,
}: AppShellProps) {
  const [accountOpen, setAccountOpen] = useState(false);

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
        <div className="account">
          <button
            className="account-trigger"
            type="button"
            aria-label="账户菜单"
            onClick={() => setAccountOpen((open) => !open)}
          >
            {subject.slice(0, 1).toUpperCase()}
          </button>
          {accountOpen && (
            <div className="account-menu" role="menu">
              <span>{subject}</span>
              <button type="button" role="menuitem" onClick={() => void onLogout()}>
                <LogOut size={15} />
                退出登录
              </button>
            </div>
          )}
        </div>
      </header>
      <div className="workspace">{children}</div>
    </main>
  );
}
