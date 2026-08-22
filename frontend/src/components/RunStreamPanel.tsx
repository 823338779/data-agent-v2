import { AgentExecutionStreamCheckpoint } from '../domain/agentPrototype';
import { MarkdownContent } from './MarkdownContent';

interface RunStreamPanelProps {
  checkpoint: AgentExecutionStreamCheckpoint | null;
}

/** 渲染尚未持久化为 assistant 消息的实时回答。 */
export function RunStreamPanel({ checkpoint }: RunStreamPanelProps) {
  if (!checkpoint?.answer) return null;
  return (
    <section className="run-stream-panel">
      <article className="stream-answer-content" aria-label="回答">
        <MarkdownContent content={checkpoint.answer} />
      </article>
    </section>
  );
}
