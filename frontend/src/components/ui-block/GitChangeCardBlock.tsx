const TITLE = '历史 Git 审阅记录';
const NOTE = '此历史卡片仅用于审计。保存、构建部署和合并发布请使用会话开发上下文。';
/** 保留历史消息身份，不调用已退役端点或恢复旧授权按钮。 */
export function GitChangeCardBlock({
  sessionId,
  changeId,
}: {
  sessionId: string;
  changeId: string;
}) {
  return (
    <section className="git-change-card" aria-label={TITLE}>
      <strong>{TITLE}</strong>
      <p>{NOTE}</p>
      <code>{sessionId}</code> · <code>{changeId}</code>
    </section>
  );
}
