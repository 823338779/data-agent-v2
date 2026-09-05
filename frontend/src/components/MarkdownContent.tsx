import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import type { MessageArtifact, MessageUiBlock } from '../domain/agent';
import { parseAgentUiPlaceholders } from './ui-block/parseAgentUiPlaceholders';
import { ArtifactCardBlock } from './ui-block/ArtifactCardBlock';
import { UiBlockRenderer } from './ui-block/UiBlockRenderer';

interface MarkdownContentProps {
  content: string;
  artifacts?: MessageArtifact[];
  uiBlocks?: MessageUiBlock[];
}

/** 使用 GitHub Flavored Markdown 渲染模型回答，不解析原始 HTML。 */
export function MarkdownContent({ content, artifacts = [], uiBlocks = [] }: MarkdownContentProps) {
  const blocks = new Map(uiBlocks.map((block) => [block.blockId, block]));
  const renderedBlocks = new Set<string>();
  const referencedArtifacts = new Set<string>();
  return (
    <div className="markdown-content">
      {parseAgentUiPlaceholders(content).map((segment, index) => {
        if (segment.kind === 'markdown') {
          return (
            <ReactMarkdown key={`markdown-${index}`} remarkPlugins={[remarkGfm]}>
              {segment.content}
            </ReactMarkdown>
          );
        }
        if (renderedBlocks.has(segment.blockId)) return null;
        renderedBlocks.add(segment.blockId);
        const block = blocks.get(segment.blockId);
        const artifactId = artifactIdFromBlock(block);
        if (artifactId) referencedArtifacts.add(artifactId);
        return (
          <UiBlockRenderer key={`ui-${segment.blockId}`} block={block} artifacts={artifacts} />
        );
      })}
      {artifacts
        .filter((artifact) => !referencedArtifacts.has(artifact.artifactId))
        .map((artifact) => (
          <ArtifactCardBlock key={`artifact-${artifact.artifactId}`} artifact={artifact} />
        ))}
    </div>
  );
}

function artifactIdFromBlock(block: MessageUiBlock | undefined): string | null {
  const payload = block?.payload;
  return typeof payload === 'object' && payload !== null && 'artifactId' in payload
    ? String(payload.artifactId)
    : null;
}
