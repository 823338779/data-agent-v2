import {
  BoundedProcessPayload,
  formatTimestamp,
  ThinkingProcessItem,
} from '../domain/agentPrototype';

interface MessageProcessTimelineProps {
  items: ThinkingProcessItem[];
}

const STATUS_LABELS = {
  PREPARED: '已准备',
  RUNNING: '进行中',
  SUCCEEDED: '已完成',
  FAILED: '失败',
} as const;

function payloadText(payload: BoundedProcessPayload): string {
  if (payload.truncated) return payload.preview ?? '';
  return typeof payload.value === 'string' ? payload.value : JSON.stringify(payload.value, null, 2);
}

/** 展示数据库中的安全载荷；完整工具出入参只用于服务端审计日志，不从浏览器反查。 */
function ProcessPayload({ label, payload }: { label: string; payload: BoundedProcessPayload }) {
  return (
    <section className="process-payload">
      <strong>{label}</strong>
      {payload.truncated && <span>{label}已截断</span>}
      <pre>{payloadText(payload)}</pre>
      <small>
        {payload.originalSizeBytes} bytes · SHA-256 {payload.sha256}
      </small>
    </section>
  );
}

/** 按持久化顺序展示一条 assistant 消息自己的思考及工具执行过程。 */
export function MessageProcessTimeline({ items }: MessageProcessTimelineProps) {
  if (items.length === 0) return null;
  const running = items.some((item) => item.status === 'RUNNING');

  return (
    <details className="message-process-timeline" open={running}>
      <summary>思考与工具过程（{items.length}）</summary>
      <ol>
        {items.map((item) => (
          <li data-testid="process-item" data-id={item.processItemId} key={item.processItemId}>
            <header>
              <strong>{item.type === 'THINKING' ? '模型思考' : item.toolName}</strong>
              <span className={`process-status ${item.status.toLowerCase()}`}>
                {STATUS_LABELS[item.status]}
              </span>
              {item.durationMillis !== null && <span>{item.durationMillis} ms</span>}
            </header>
            <small>
              创建 {formatTimestamp(item.createdAt)}
              {item.startedAt ? ` · 开始 ${formatTimestamp(item.startedAt)}` : ''}
              {item.completedAt ? ` · 完成 ${formatTimestamp(item.completedAt)}` : ''}
            </small>
            {item.type === 'THINKING' ? (
              <div className="process-thinking-content">
                <p>
                  {item.modelProvider} / {item.modelName} · 调用 #{item.modelInvocationSequence}
                </p>
                <pre>{item.content}</pre>
                {item.contentTruncated && <span>思考内容已截断</span>}
              </div>
            ) : (
              <div className="process-tool-content">
                <p>
                  工具：{item.toolName} · Call ID: {item.toolCallId}
                </p>
                <ProcessPayload label="输入" payload={item.input} />
                {item.output && <ProcessPayload label="输出" payload={item.output} />}
                {item.errorMessage && (
                  <p className="process-error">
                    {item.errorCode ? `${item.errorCode}：` : ''}
                    {item.errorMessage}
                  </p>
                )}
              </div>
            )}
          </li>
        ))}
      </ol>
    </details>
  );
}
