import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { ConversationSession } from '../domain/agentPrototype';
import { ConversationList } from './ConversationList';

const sessions = [
  {
    conversationSessionId: 'first',
    title: '第一个会话',
    tag: '',
    createdAt: '',
    updatedAt: '',
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
    unread: false,
    lastNotifiableEventSequence: 0,
    sortOrder: 1,
    orderingVersion: 2,
    messages: [],
    execution: { status: 'running' },
  },
] as ConversationSession[];

describe('ConversationList', () => {
  it('shows compact unread and status indicators and supports keyboard reordering', () => {
    const onReorder = vi.fn();
    render(
      <ConversationList
        sessions={sessions}
        activeSessionId="first"
        onOpen={vi.fn()}
        onReorder={onReorder}
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
});
