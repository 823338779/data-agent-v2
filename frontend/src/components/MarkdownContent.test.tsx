import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { MessageUiBlock } from '../domain/agent';
import { MarkdownContent } from './MarkdownContent';

afterEach(cleanup);

vi.mock('./ui-block/GitChangeCardBlock', () => ({
  GitChangeCardBlock: ({ changeId }: { changeId: string }) => <div>{changeId}</div>,
}));

const block: MessageUiBlock = {
  blockId: 'block_1',
  type: 'git-change-card',
  schemaVersion: 1,
  variant: 'default',
  payload: { changeId: 'change_1', sessionId: 'session_1' },
};

describe('MarkdownContent', () => {
  it('renders markdown and an exact marker once even when duplicated', () => {
    render(
      <MarkdownContent
        content={
          '**done**\n<agent-ui block-id="block_1"></agent-ui>\n<agent-ui block-id="block_1"></agent-ui>'
        }
        uiBlocks={[block]}
      />,
    );
    expect(screen.getByText('done')).toBeInTheDocument();
    expect(screen.getAllByText('change_1')).toHaveLength(1);
  });

  it('never executes raw scripts', () => {
    render(<MarkdownContent content={'safe<script>window.hacked=true</script>'} />);
    expect(document.querySelector('script')).toBeNull();
  });
});
