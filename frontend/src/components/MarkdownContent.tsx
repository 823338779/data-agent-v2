import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import type { MessageUiBlock } from '../domain/agent';
import { parseAgentUiPlaceholders } from './ui-block/parseAgentUiPlaceholders';
import { UiBlockRenderer } from './ui-block/UiBlockRenderer';

interface MarkdownContentProps {
  content: string;
  uiBlocks?: MessageUiBlock[];
}

/** 使用 GitHub Flavored Markdown 渲染模型回答，不解析原始 HTML。 */
export function MarkdownContent({ content, uiBlocks = [] }: MarkdownContentProps) {
  const blocks = new Map(uiBlocks.map((block) => [block.blockId, block]));
  const renderedBlocks = new Set<string>();
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
        return <UiBlockRenderer key={`ui-${segment.blockId}`} block={block} />;
      })}
    </div>
  );
}
