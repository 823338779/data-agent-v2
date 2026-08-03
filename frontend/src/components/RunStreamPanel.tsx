import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { AgentExecutionStreamCheckpoint } from '../domain/agentPrototype';

interface RunStreamPanelProps {
  checkpoint: AgentExecutionStreamCheckpoint | null;
}

/** 渲染尚未持久化为 assistant 消息的实时回答。 */
export function RunStreamPanel({ checkpoint }: RunStreamPanelProps) {
  if (!checkpoint?.answer) return null;
  return (
    <section className="run-stream-panel">
      <article className="stream-answer-content" aria-label="回答">
        <ReactMarkdown remarkPlugins={[remarkGfm]}>{checkpoint.answer}</ReactMarkdown>
      </article>
    </section>
  );
}
