import { ComponentProps, useMemo } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { CitationSummary } from '../../domain/agentPrototype';

const citationPattern = /\[M([1-9][0-9]*)\]/g;
const citationHrefPrefix = '#citation-';

/**
 * 将后端附带的引用标记转换成 Markdown 链接，再由链接组件渲染为可点击的引用按钮。
 *
 * 只有当前消息实际携带该引用时才转换，避免模型输出的普通 `[M1]` 文本被错误地赋予交互能力。
 */
function addCitationLinks(content: string, citations: CitationSummary[]) {
  const availableTokens = new Set(citations.map((citation) => citation.token));
  return content.replace(citationPattern, (token, number: string) =>
    availableTokens.has(token) ? `[${token}](${citationHrefPrefix}${number})` : token,
  );
}

/**
 * Agent 消息的安全 Markdown 渲染器。
 *
 * ReactMarkdown 默认不会执行消息中的原始 HTML；因此即使回复内容来自模型或外部数据源，仍以普通文本
 * 呈现。GFM 插件补全表格、删除线、任务列表等分析回复中常用的语法。
 */
export function MarkdownMessage({
  content,
  citations,
  onCitation,
}: {
  content: string;
  citations: CitationSummary[];
  onCitation: (citation: CitationSummary) => void;
}) {
  const citationsByToken = useMemo(
    () => new Map(citations.map((citation) => [citation.token, citation])),
    [citations],
  );
  const markdown = useMemo(() => addCitationLinks(content, citations), [content, citations]);

  function renderLink({
    href,
    children,
    // ReactMarkdown 额外传入的语法树节点不应透传到原生 <a>，避免产生无效 DOM 属性。
    node: _node,
    ...props
  }: ComponentProps<'a'> & { node?: unknown }) {
    if (href?.startsWith(citationHrefPrefix)) {
      const citation = citationsByToken.get(`[M${href.slice(citationHrefPrefix.length)}]`);
      if (citation) {
        return (
          <button
            className="citation-token"
            type="button"
            title={citation.label}
            onClick={() => onCitation(citation)}
          >
            {children}
          </button>
        );
      }
    }

    return (
      <a {...props} href={href} target="_blank" rel="noreferrer">
        {children}
      </a>
    );
  }

  return (
    <div className="markdown-message">
      <ReactMarkdown remarkPlugins={[remarkGfm]} components={{ a: renderLink }}>
        {markdown}
      </ReactMarkdown>
    </div>
  );
}
