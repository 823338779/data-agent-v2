import { AgentExecutionStreamCheckpoint } from '../domain/agentPrototype';
import { MarkdownContent } from './MarkdownContent';

interface RunStreamPanelProps {
  checkpoint: AgentExecutionStreamCheckpoint | null;
}

const PHASE_LABELS: Record<string, string> = {
  RECEIVED: '任务已开始处理',
  PREPARING_CONTEXT: '正在整理上下文',
  CONNECTING_MODEL: '正在连接模型',
  ANSWERING: '模型正在思考',
  PERSISTING: '正在整理最终回答',
};

/** 渲染尚未持久化为 assistant 消息的实时回答。 */
export function RunStreamPanel({ checkpoint }: RunStreamPanelProps) {
  if (!checkpoint?.answer) {
    return (
      <section className="run-stream-panel">
        <div className="stream-loading" role="status" aria-live="polite">
          <span className="stream-loading-dots" aria-hidden="true">
            <i />
            <i />
            <i />
          </span>
          <span>{PHASE_LABELS[checkpoint?.phase ?? ''] ?? '正在准备回答'}</span>
        </div>
      </section>
    );
  }
  return (
    <section className="run-stream-panel">
      <article className="stream-answer-content" aria-label="正在生成的回答" aria-live="polite">
        <MarkdownContent content={checkpoint.answer} />
      </article>
    </section>
  );
}
