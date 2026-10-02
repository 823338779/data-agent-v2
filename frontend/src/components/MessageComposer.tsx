import { ArrowUp, LoaderCircle } from 'lucide-react';
import { useEffect, useRef } from 'react';

interface MessageComposerProps {
  value: string;
  onChange(value: string): void;
  onSend(): void;
  busy: boolean;
  running?: boolean;
  disabled?: boolean;
}

/** 首页与会话共用输入契约；运行时仍可写草稿，但不暗示服务端支持排队。 */
export function MessageComposer({
  value,
  onChange,
  onSend,
  busy,
  running = false,
  disabled = false,
}: MessageComposerProps) {
  const input = useRef<HTMLTextAreaElement>(null);
  const canSend = Boolean(value.trim()) && !busy && !running && !disabled;
  useEffect(() => {
    if (!input.current) return;
    input.current.style.height = 'auto';
    input.current.style.height = `${Math.min(input.current.scrollHeight, 180)}px`;
  }, [value]);
  return (
    <div className="composer-area">
      <form
        className="composer"
        onSubmit={(event) => {
          event.preventDefault();
          if (canSend) onSend();
        }}
      >
        <label htmlFor="prompt" className="sr-only">
          输入消息
        </label>
        <textarea
          ref={input}
          id="prompt"
          rows={2}
          value={value}
          onChange={(event) => onChange(event.target.value)}
          onKeyDown={(event) => {
            if (event.key !== 'Enter' || event.shiftKey || event.nativeEvent.isComposing) return;
            event.preventDefault();
            if (canSend) onSend();
          }}
          placeholder="继续描述你的任务…"
        />
        <button type="submit" aria-label="发送消息" disabled={!canSend}>
          {busy ? <LoaderCircle className="spin" size={18} /> : <ArrowUp size={18} />}
        </button>
      </form>
      <small>
        {running ? '任务正在执行，你可以先写下一条消息' : 'Enter 发送 · Shift + Enter 换行'}
      </small>
    </div>
  );
}
