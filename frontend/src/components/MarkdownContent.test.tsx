import { render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { MessageArtifact, MessageUiBlock } from '../domain/agent';
import { MarkdownContent } from './MarkdownContent';

const artifact: MessageArtifact = {
  artifactId: 'artifact_1',
  artifactType: 'DOCX',
  displayName: 'report.docx',
  mediaType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  sizeBytes: 3,
  sha256: 'a'.repeat(64),
  pageOrSheetCount: null,
  downloadUrl: '/api/v1/agent-sessions/session_1/artifacts/artifact_1/content',
};
const block: MessageUiBlock = {
  blockId: 'block_1',
  type: 'artifact-card',
  schemaVersion: 1,
  variant: 'default',
  payload: { artifactId: 'artifact_1' },
};

describe('MarkdownContent', () => {
  it('renders markdown and an exact marker once even when duplicated', () => {
    render(
      <MarkdownContent
        content={
          '**done**\n<agent-ui block-id="block_1"></agent-ui>\n<agent-ui block-id="block_1"></agent-ui>'
        }
        artifacts={[artifact]}
        uiBlocks={[block]}
      />,
    );
    expect(screen.getByText('done')).toBeInTheDocument();
    expect(screen.getAllByText('report.docx')).toHaveLength(1);
  });

  it('appends unreferenced artifacts and never executes raw scripts', () => {
    const { container } = render(
      <MarkdownContent
        content={'safe<script>window.hacked=true</script>'}
        artifacts={[artifact]}
      />,
    );
    expect(within(container).getByText('report.docx')).toBeInTheDocument();
    expect(document.querySelector('script')).toBeNull();
  });
});
