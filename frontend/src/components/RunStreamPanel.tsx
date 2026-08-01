import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { AgentExecutionStatus, AgentExecutionStreamCheckpoint } from '../domain/agentPrototype';

interface RunStreamPanelProps {
  status: AgentExecutionStatus;
  checkpoint: AgentExecutionStreamCheckpoint | null;
  historical?: boolean;
  hideAnswer?: boolean;
}

/** 将可折叠 reasoning 与面向用户的 answer 分区，避免两类内容混排。 */
export function RunStreamPanel({
  status,
  checkpoint,
  historical = false,
  hideAnswer = false,
}: RunStreamPanelProps) {
  if (!checkpoint || (!checkpoint.reasoning && (!checkpoint.answer || hideAnswer))) return null;
  const running = status === 'queued' || status === 'running';
  return (
    <section className={historical ? 'run-stream-panel historical' : 'run-stream-panel'}>
      {checkpoint.reasoning && (
        <details open={running} className="reasoning-panel">
          <summary>{historical ? '查看运行过程' : '推理过程'}</summary>
          <p>{checkpoint.reasoning}</p>
        </details>
      )}
      {!hideAnswer && checkpoint.answer && (
        <article className="stream-answer-content" aria-label="回答">
          <ReactMarkdown remarkPlugins={[remarkGfm]}>{checkpoint.answer}</ReactMarkdown>
        </article>
      )}
    </section>
  );
}
