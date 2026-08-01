import { LogOut, MessageSquarePlus, PanelLeft } from 'lucide-react';
import { ReactNode, useState } from 'react';

interface AppShellProps {
  subject: string;
  historyOpen: boolean;
  onNewSession: () => void;
  onToggleHistory: () => void;
  onLogout: () => Promise<void>;
  children: ReactNode;
}

export function AppShell({
  subject,
  historyOpen,
  onNewSession,
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
        </div>
        <nav aria-label="工作区操作">
          <button
            className="icon-button"
            type="button"
            aria-label="会话历史"
            aria-pressed={historyOpen}
            onClick={onToggleHistory}
          >
            <PanelLeft size={18} />
          </button>
          <button className="new-session-button" type="button" onClick={onNewSession}>
            <MessageSquarePlus size={17} />
            新会话
          </button>
        </nav>
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
