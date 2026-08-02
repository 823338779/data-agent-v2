import {
  cleanup,
  createEvent,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
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
    unread: true,
    lastNotifiableEventSequence: 4,
    sortOrder: 0,
    orderingVersion: 2,
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
    unread: false,
    lastNotifiableEventSequence: 0,
    sortOrder: 1,
    orderingVersion: 2,
    messages: [],
    execution: { status: 'running' },
  },
] as ConversationSession[];

afterEach(cleanup);

describe('ConversationList', () => {
  it('shows compact unread and status indicators and supports keyboard reordering', () => {
    const onReorder = vi.fn();
    render(
      <ConversationList
        sessions={sessions}
        activeSessionId="first"
        onOpen={vi.fn()}
        onReorder={onReorder}
        onRename={vi.fn()}
      />,
    );

    expect(screen.getByLabelText('未读')).toBeInTheDocument();
    expect(screen.getByText('处理中')).toBeInTheDocument();
    fireEvent.keyDown(screen.getByRole('button', { name: /第二个会话/ }), {
      key: 'ArrowUp',
      altKey: true,
    });
    expect(onReorder).toHaveBeenCalledWith(['second', 'first']);
  });

  it('shows the insertion edge and reorders before or after the hovered row', () => {
    const onReorder = vi.fn();
    const { container } = render(
      <ConversationList
        sessions={sessions}
        activeSessionId="first"
        onOpen={vi.fn()}
        onReorder={onReorder}
        onRename={vi.fn()}
      />,
    );

    const list = within(container);
    const first = list.getByRole('button', { name: /第一个会话/ });
    const second = list.getByRole('button', { name: /第二个会话/ });
    vi.spyOn(first, 'getBoundingClientRect').mockReturnValue({
      top: 20,
      height: 40,
    } as DOMRect);
    const dataTransfer = {
      effectAllowed: '',
      dropEffect: '',
      setData: vi.fn(),
      setDragImage: vi.fn(),
    };
    const fireDragAt = (type: 'dragOver' | 'drop', element: HTMLElement, clientY: number) => {
      const event = createEvent[type](element, { dataTransfer });
      Object.defineProperty(event, 'clientY', { value: clientY });
      fireEvent(element, event);
    };

    // 拖到首项上半区时，应显示顶部插入线并插到首项之前。
    fireEvent.dragStart(second, { dataTransfer });
    fireDragAt('dragOver', first, 25);
    expect(second).toHaveClass('dragging');
    expect(first).toHaveClass('drop-before');
    fireDragAt('drop', first, 25);
    expect(onReorder).toHaveBeenCalledWith(['second', 'first']);

    // 拖到首项下半区已是原顺序，不应发出无效排序请求。
    onReorder.mockClear();
    fireEvent.dragStart(second, { dataTransfer });
    fireDragAt('dragOver', first, 55);
    expect(first).toHaveClass('drop-after');
    fireDragAt('drop', first, 55);
    expect(onReorder).not.toHaveBeenCalled();
  });

  it('renames a title from the focused inline editor with Enter', async () => {
    function RenameHarness() {
      const [items, setItems] = useState(sessions);
      return (
        <ConversationList
          sessions={items}
          activeSessionId="first"
          onOpen={vi.fn()}
          onReorder={vi.fn()}
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
        onReorder={vi.fn()}
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
        onReorder={vi.fn()}
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
