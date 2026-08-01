import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { ConversationStatus } from './ConversationStatus';

describe('ConversationStatus', () => {
  it('offers cancel, retry, partial, and waiting affordances by execution status', () => {
    const onCancel = vi.fn();
    const onRetry = vi.fn();
    const view = render(
      <ConversationStatus status="running" onCancel={onCancel} onRetry={onRetry} />,
    );
    fireEvent.click(screen.getByRole('button', { name: '取消运行' }));
    expect(onCancel).toHaveBeenCalledOnce();

    view.rerender(<ConversationStatus status="failed" onCancel={onCancel} onRetry={onRetry} />);
    fireEvent.click(screen.getByRole('button', { name: '重试运行' }));
    expect(onRetry).toHaveBeenCalledOnce();

    view.rerender(<ConversationStatus status="cancelled" onCancel={onCancel} onRetry={onRetry} />);
    expect(screen.getByText('运行已取消，以下内容可能不完整。')).toBeInTheDocument();

    view.rerender(
      <ConversationStatus status="waiting_for_user" onCancel={onCancel} onRetry={onRetry} />,
    );
    expect(screen.getByText('等待你的操作')).toBeInTheDocument();
  });
});
