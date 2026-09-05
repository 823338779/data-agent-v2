import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { MessageArtifact } from '../../domain/agent';
import { ArtifactCardBlock } from './ArtifactCardBlock';

const artifact: MessageArtifact = {
  artifactId: 'artifact_1',
  artifactType: 'DOCX',
  displayName: '报告.docx',
  mediaType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  sizeBytes: 1536,
  sha256: 'a'.repeat(64),
  pageOrSheetCount: 2,
  downloadUrl: '/api/v1/agent-sessions/session_1/artifacts/artifact_1/content',
};

describe('ArtifactCardBlock', () => {
  it('uses a native accessible download link with file metadata', () => {
    render(<ArtifactCardBlock artifact={artifact} />);
    expect(screen.getByText('报告.docx')).toBeInTheDocument();
    expect(screen.getByText(/Word 文档 · 1.5 KB · 2 页/)).toBeInTheDocument();
    const download = screen.getByRole('link', { name: '下载 报告.docx' });
    expect(download).toHaveAttribute('href', artifact.downloadUrl);
    expect(download).toHaveAttribute('download', '报告.docx');
  });

  it.each([
    ['PPTX', 'PowerPoint 演示文稿', '张幻灯片'],
    ['XLSX', 'Excel 工作簿', '个工作表'],
  ] as const)('renders %s-specific metadata', (artifactType, label, unit) => {
    render(<ArtifactCardBlock artifact={{ ...artifact, artifactType }} />);
    expect(screen.getByText(new RegExp(`${label}.*2 ${unit}`))).toBeInTheDocument();
  });
});
