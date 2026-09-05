const PLACEHOLDER = /<agent-ui block-id="([a-zA-Z0-9_-]{1,80})"><\/agent-ui>/g;

export type MarkdownSegment =
  { kind: 'markdown'; content: string } | { kind: 'ui'; blockId: string };

/** 只识别服务端约定的精确占位符语法，其余 HTML 继续交给不启用 raw HTML 的 Markdown 渲染器。 */
export function parseAgentUiPlaceholders(content: string): MarkdownSegment[] {
  const segments: MarkdownSegment[] = [];
  let offset = 0;
  for (const match of content.matchAll(PLACEHOLDER)) {
    const index = match.index ?? 0;
    if (index > offset) segments.push({ kind: 'markdown', content: content.slice(offset, index) });
    segments.push({ kind: 'ui', blockId: match[1] });
    offset = index + match[0].length;
  }
  if (offset < content.length || segments.length === 0) {
    segments.push({ kind: 'markdown', content: content.slice(offset) });
  }
  return segments;
}
