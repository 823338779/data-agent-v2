import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ConversationSidebar } from './ConversationSidebar';
import { ConversationSidebarSnapshot } from '../domain/sidebar';

const snapshot: ConversationSidebarSnapshot = {
  projects: [
    {
      projectId: 'p1',
      name: '经营分析',
      version: 0,
      createdAt: '2026-09-15T01:00:00',
      updatedAt: '2026-09-15T01:00:00',
    },
  ],
  sessions: [
    {
      conversationSessionId: 's1',
      title: '每周经营数据复盘',
      tag: 'debug',
      projectId: 'p1',
      pinnedAt: '2026-09-15T01:00:00Z',
      version: 4,
      createdAt: '2026-09-15T01:00:00',
      updatedAt: '2026-09-15T01:00:00',
      messages: [],
      execution: null,
    },
  ],
};
function props() {
  const saved = { outcome: 'saved', refreshed: true } as const;
  return {
    snapshot,
    activeSessionId: 's1',
    loading: false,
    busy: false,
    error: null,
    storageKey: 'sidebar-test',
    onOpen: vi.fn(),
    onRename: vi.fn().mockResolvedValue(true),
    onCreateSession: vi.fn().mockResolvedValue(saved),
    onCreateProject: vi.fn().mockResolvedValue(saved),
    onRenameProject: vi.fn().mockResolvedValue(saved),
    onDeleteProject: vi.fn().mockResolvedValue(saved),
    onSetOrganization: vi.fn().mockResolvedValue(saved),
    onRefresh: vi.fn(),
  };
}
afterEach(() => {
  cleanup();
  localStorage.clear();
});
describe('conversation sidebar', () => {
  it('shows each pinned session once and unpins without discarding its project', async () => {
    const inputs = props();
    render(<ConversationSidebar {...inputs} />);
    expect(
      within(screen.getByRole('region', { name: '置顶' })).getByText('每周经营数据复盘'),
    ).toBeInTheDocument();
    expect(screen.getAllByText('每周经营数据复盘')).toHaveLength(1);
    fireEvent.click(screen.getByRole('button', { name: '每周经营数据复盘的更多操作' }));
    fireEvent.click(screen.getByRole('menuitem', { name: '取消置顶' }));
    await waitFor(() =>
      expect(inputs.onSetOrganization).toHaveBeenCalledWith('s1', {
        projectId: 'p1',
        pinned: false,
        expectedVersion: 4,
      }),
    );
    expect(inputs.onOpen).not.toHaveBeenCalled();
  });
  it('creates in the explicit destination and searches the existing tag', () => {
    const inputs = props();
    render(<ConversationSidebar {...inputs} />);
    fireEvent.click(screen.getByRole('button', { name: '新会话' }));
    expect(inputs.onCreateSession).toHaveBeenCalledWith(null);
    fireEvent.click(screen.getByRole('button', { name: '在经营分析中新建会话' }));
    expect(inputs.onCreateSession).toHaveBeenCalledWith('p1');
    fireEvent.change(screen.getByRole('textbox', { name: '搜索会话' }), {
      target: { value: 'DEBUG' },
    });
    expect(screen.getByText('每周经营数据复盘')).toBeInTheDocument();
    fireEvent.change(screen.getByRole('textbox', { name: '搜索会话' }), {
      target: { value: 'missing' },
    });
    expect(screen.getByText('没有找到会话')).toBeInTheDocument();
  });
  it('deletes only the project after explicit confirmation', async () => {
    const inputs = props();
    render(<ConversationSidebar {...inputs} />);
    fireEvent.click(screen.getByRole('button', { name: '经营分析的项目操作' }));
    fireEvent.click(screen.getByRole('button', { name: '删除项目' }));
    expect(screen.getByText(/项目内会话将移至未分组/)).toBeInTheDocument();
    expect(inputs.onDeleteProject).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: '确认删除项目' }));
    await waitFor(() => expect(inputs.onDeleteProject).toHaveBeenCalledWith('p1', 0));
  });
  it('temporarily expands search results without replacing collapse preferences', () => {
    const inputs = props();
    inputs.snapshot = { ...snapshot, sessions: [{ ...snapshot.sessions[0], pinnedAt: null }] };
    render(<ConversationSidebar {...inputs} />);
    fireEvent.click(screen.getByRole('button', { name: '经营分析' }));
    expect(screen.queryByText('每周经营数据复盘')).not.toBeInTheDocument();
    fireEvent.change(screen.getByRole('textbox', { name: '搜索会话' }), {
      target: { value: 'debug' },
    });
    expect(screen.getByText('每周经营数据复盘')).toBeInTheDocument();
    fireEvent.change(screen.getByRole('textbox', { name: '搜索会话' }), { target: { value: '' } });
    expect(screen.queryByText('每周经营数据复盘')).not.toBeInTheDocument();
  });
  it('keeps a pin when moving and restores focus when Escape closes a menu', async () => {
    const inputs = props();
    render(<ConversationSidebar {...inputs} />);
    fireEvent.click(screen.getByRole('button', { name: '经营分析的项目操作' }));
    fireEvent.keyDown(screen.getByRole('button', { name: '删除项目' }), { key: 'Escape' });
    const trigger = screen.getByRole('button', { name: '每周经营数据复盘的更多操作' });
    fireEvent.click(trigger);
    fireEvent.click(screen.getByRole('menuitem', { name: '移动到项目' }));
    expect(screen.getByRole('menuitemradio', { name: '经营分析 当前项目' })).toHaveAttribute(
      'aria-checked',
      'true',
    );
    fireEvent.click(screen.getByRole('menuitemradio', { name: '未分组' }));
    await waitFor(() =>
      expect(inputs.onSetOrganization).toHaveBeenCalledWith('s1', {
        projectId: null,
        pinned: true,
        expectedVersion: 4,
      }),
    );
    await waitFor(() => expect(screen.queryByRole('menu')).not.toBeInTheDocument());
    fireEvent.click(trigger);
    fireEvent.keyDown(screen.getByRole('menu'), { key: 'Escape' });
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
    expect(trigger).toHaveFocus();
  });
  it('navigates menu actions with the keyboard and returns from destinations without moving', () => {
    const inputs = props();
    render(<ConversationSidebar {...inputs} />);
    fireEvent.click(screen.getByRole('button', { name: '每周经营数据复盘的更多操作' }));
    expect(screen.getByRole('menuitem', { name: '取消置顶' })).toHaveFocus();
    fireEvent.keyDown(document.activeElement!, { key: 'ArrowDown' });
    expect(screen.getByRole('menuitem', { name: '重命名会话' })).toHaveFocus();
    fireEvent.keyDown(document.activeElement!, { key: 'End' });
    const move = screen.getByRole('menuitem', { name: '移动到项目' });
    expect(move).toHaveFocus();
    fireEvent.click(move);
    fireEvent.keyDown(screen.getByRole('menu'), { key: 'Escape' });
    expect(screen.getByRole('menuitem', { name: '移动到项目' })).toHaveFocus();
    expect(inputs.onSetOrganization).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('menuitem', { name: '重命名会话' }));
    expect(screen.getByRole('textbox', { name: '编辑“每周经营数据复盘”标题' })).toHaveFocus();
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
  });
  it('dismisses on outside interaction and keeps only the newly opened session menu', () => {
    const inputs = props();
    inputs.snapshot = {
      ...snapshot,
      sessions: [
        ...snapshot.sessions,
        {
          ...snapshot.sessions[0],
          conversationSessionId: 's2',
          title: '另一个会话',
          pinnedAt: null,
          projectId: null,
        },
      ],
    };
    render(<ConversationSidebar {...inputs} />);
    const first = screen.getByRole('button', { name: '每周经营数据复盘的更多操作' });
    fireEvent.click(first);
    const second = screen.getByRole('button', { name: '另一个会话的更多操作' });
    fireEvent.pointerDown(second);
    fireEvent.click(second);
    expect(screen.getAllByRole('menu')).toHaveLength(1);
    expect(first).toHaveAttribute('aria-expanded', 'false');
    fireEvent.pointerDown(screen.getByRole('textbox', { name: '搜索会话' }));
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
  });
  it('keeps destinations open after rejection and retries with the refreshed version', async () => {
    const inputs = props();
    inputs.onSetOrganization.mockResolvedValueOnce({ outcome: 'rejected', refreshed: true });
    const { rerender } = render(<ConversationSidebar {...inputs} />);
    fireEvent.click(screen.getByRole('button', { name: '每周经营数据复盘的更多操作' }));
    fireEvent.click(screen.getByRole('menuitem', { name: '移动到项目' }));
    fireEvent.click(screen.getByRole('menuitemradio', { name: '经营分析 当前项目' }));
    expect(inputs.onSetOrganization).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('menuitemradio', { name: '未分组' }));
    await waitFor(() => expect(inputs.onSetOrganization).toHaveBeenCalledTimes(1));
    expect(screen.getByRole('menu')).toBeInTheDocument();
    rerender(
      <ConversationSidebar
        {...inputs}
        snapshot={{ ...snapshot, sessions: [{ ...snapshot.sessions[0], version: 5 }] }}
      />,
    );
    fireEvent.click(screen.getByRole('menuitemradio', { name: '未分组' }));
    await waitFor(() =>
      expect(inputs.onSetOrganization).toHaveBeenLastCalledWith('s1', {
        projectId: null,
        pinned: true,
        expectedVersion: 5,
      }),
    );
    await waitFor(() => expect(screen.queryByRole('menu')).not.toBeInTheDocument());
  });
  it('preserves a rejected name draft and retries using the refreshed project version', async () => {
    const inputs = props();
    inputs.onRenameProject.mockResolvedValueOnce({ outcome: 'rejected', refreshed: true });
    const { rerender } = render(<ConversationSidebar {...inputs} />);
    fireEvent.click(screen.getByRole('button', { name: '经营分析的项目操作' }));
    fireEvent.click(screen.getByRole('button', { name: '重命名项目' }));
    const input = screen.getByRole('textbox', { name: '项目名称' });
    fireEvent.change(input, { target: { value: ' 新名称 ' } });
    fireEvent.keyDown(input, { key: 'Enter', isComposing: true });
    expect(inputs.onRenameProject).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: '保存项目' }));
    await waitFor(() => expect(inputs.onRenameProject).toHaveBeenCalledTimes(1));
    expect(input).toHaveValue(' 新名称 ');
    rerender(
      <ConversationSidebar
        {...inputs}
        snapshot={{ ...snapshot, projects: [{ ...snapshot.projects[0], version: 2 }] }}
      />,
    );
    fireEvent.click(screen.getByRole('button', { name: '保存项目' }));
    await waitFor(() => expect(inputs.onRenameProject).toHaveBeenLastCalledWith('p1', '新名称', 2));
  });
});
