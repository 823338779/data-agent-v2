import { useState } from 'react';
import { PendingInteraction } from '../../domain/agentPrototype';

interface Props {
  interaction: PendingInteraction;
  busy: boolean;
  error: string | null;
  onSubmit: (value: string) => Promise<void>;
}

/** Secret state is intentionally component-local and cleared before the async request settles. */
export function SecretInputCard({ interaction, busy, error, onSubmit }: Props) {
  const [value, setValue] = useState('');

  async function submit() {
    const submitted = value;
    setValue('');
    if (!submitted) return;
    await onSubmit(submitted);
  }

  return (
    <section className="pending-interaction-card secret-input-card" aria-label="安全秘密输入">
      <strong>{interaction.prompt}</strong>
      <small>此值仅发送到安全输入接口，不会加入对话、工具参数或事件历史。</small>
      <div className="secret-input-row">
        <input
          type="password"
          value={value}
          disabled={busy}
          autoComplete="off"
          spellCheck={false}
          aria-label="连接秘密"
          onChange={(event) => setValue(event.target.value)}
        />
        <button type="button" disabled={busy || !value} onClick={() => void submit()}>
          安全提交
        </button>
      </div>
      {error && <p role="alert">{error}</p>}
    </section>
  );
}
