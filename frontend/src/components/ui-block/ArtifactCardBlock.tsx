import { Download, FileSpreadsheet, FileText, Presentation } from 'lucide-react';
import type { MessageArtifact } from '../../domain/agent';

interface ArtifactCardBlockProps {
  artifact: MessageArtifact;
}

const typePresentation = {
  DOCX: { label: 'Word 文档', Icon: FileText, countLabel: '页' },
  PPTX: { label: 'PowerPoint 演示文稿', Icon: Presentation, countLabel: '张幻灯片' },
  XLSX: { label: 'Excel 工作簿', Icon: FileSpreadsheet, countLabel: '个工作表' },
} as const;

/** 仅消费经过协议校验的 Artifact 元数据，下载交给浏览器原生同源文件流。 */
export function ArtifactCardBlock({ artifact }: ArtifactCardBlockProps) {
  const presentation = typePresentation[artifact.artifactType];
  const Icon = presentation.Icon;
  return (
    <section className={`artifact-card artifact-card--${artifact.artifactType.toLowerCase()}`}>
      <div className="artifact-card__icon" aria-hidden="true">
        <Icon size={24} />
      </div>
      <div className="artifact-card__details">
        <strong className="artifact-card__name">{artifact.displayName}</strong>
        <span className="artifact-card__meta">
          {presentation.label} · {formatFileSize(artifact.sizeBytes)}
          {artifact.pageOrSheetCount === null
            ? ''
            : ` · ${artifact.pageOrSheetCount} ${presentation.countLabel}`}
        </span>
      </div>
      <a
        className="artifact-card__download"
        href={artifact.downloadUrl}
        download={artifact.displayName}
        aria-label={`下载 ${artifact.displayName}`}
      >
        <Download size={18} aria-hidden="true" />
        <span>下载</span>
      </a>
    </section>
  );
}

function formatFileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${formatNumber(bytes / 1024)} KB`;
  return `${formatNumber(bytes / (1024 * 1024))} MB`;
}

function formatNumber(value: number): string {
  return new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 1 }).format(value);
}
