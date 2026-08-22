import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { useState } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ConversationSession } from '../domain/agentPrototype';
import { ConversationList } from './ConversationList';

const sessions = [
  {
    conversationSessionId: 'first',
    title: '第一个会话',
    tag: '',
    createdAt: '',
    updatedAt: '',
    version: 4,
    messages: [],
    execution: null,
  },
  {
    conversationSessionId: 'second',
    title: '第二个会话',
    tag: '',
    createdAt: '',
    updatedAt: '',
    version: 4,
    messages: [],
    execution: { status: 'running' },
  },
] as ConversationSession[];

afterEach(cleanup);

describe('ConversationList', () => {
  it('shows compact status indicators without manual reordering controls', () => {
    render(
      <ConversationList
        sessions={sessions}
        activeSessionId="first"
        onOpen={vi.fn()}
        onRename={vi.fn()}
      />,
    );

    expect(screen.getByText('处理中')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /第二个会话/ })).not.toHaveAttribute('draggable');
  });

  it('renames a title from the focused inline editor with Enter', async () => {
    function RenameHarness() {
      const [items, setItems] = useState(sessions);
      return (
        <ConversationList
          sessions={items}
          activeSessionId="first"
          onOpen={vi.fn()}
          onRename={async (conversationSessionId, title) => {
            setItems((current) =>
              current.map((item) =>
                item.conversationSessionId === conversationSessionId
                  ? { ...item, title, version: item.version + 1 }
                  : item,
              ),
            );
            return true;
          }}
        />
      );
    }
    render(<RenameHarness />);

    fireEvent.doubleClick(screen.getByRole('button', { name: /第一个会话/ }));
    const input = screen.getByRole('textbox', { name: '编辑“第一个会话”标题' });
    expect(input).toHaveFocus();
    fireEvent.change(input, { target: { value: '  重命名会话  ' } });
    fireEvent.keyDown(input, { key: 'Enter' });

    expect(await screen.findByRole('button', { name: /重命名会话/ })).toBeInTheDocument();
  });

  it('cancels or rejects invalid title edits without submitting', async () => {
    const onRename = vi.fn().mockResolvedValue(true);
    render(
      <ConversationList
        sessions={sessions}
        activeSessionId="first"
        onOpen={vi.fn()}
        onRename={onRename}
      />,
    );

    fireEvent.doubleClick(screen.getByRole('button', { name: /第一个会话/ }));
    fireEvent.keyDown(screen.getByRole('textbox'), { key: 'Escape' });
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();

    fireEvent.doubleClick(screen.getByRole('button', { name: /第一个会话/ }));
    fireEvent.change(screen.getByRole('textbox'), { target: { value: '   ' } });
    fireEvent.blur(screen.getByRole('textbox'));
    await waitFor(() => expect(screen.queryByRole('textbox')).not.toBeInTheDocument());
    expect(onRename).not.toHaveBeenCalled();
  });

  it('keeps the edited draft visible when saving fails', async () => {
    const onRename = vi.fn().mockResolvedValue(false);
    render(
      <ConversationList
        sessions={sessions}
        activeSessionId="first"
        onOpen={vi.fn()}
        onRename={onRename}
      />,
    );

    fireEvent.doubleClick(screen.getByRole('button', { name: /第一个会话/ }));
    fireEvent.change(screen.getByRole('textbox'), { target: { value: '保留的草稿' } });
    fireEvent.blur(screen.getByRole('textbox'));

    await waitFor(() => expect(onRename).toHaveBeenCalledWith('first', '保留的草稿'));
    expect(screen.getByRole('textbox')).toHaveValue('保留的草稿');
    expect(screen.getByRole('textbox')).not.toBeDisabled();
  });
});
