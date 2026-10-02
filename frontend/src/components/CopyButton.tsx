import { Check, Copy } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';

/** 剪贴板被浏览器拒绝时提供可重试反馈；提示定时器随组件销毁清理。 */
export function CopyButton({ text, label }: { text: string | (() => string); label: string }) {
  const [feedback, setFeedback] = useState('');
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  useEffect(() => () => clearTimeout(timer.current), []);
  return (
    <span className="copy-control">
      <button
        className="icon-button"
        type="button"
        aria-label={label}
        title={label}
        onClick={async () => {
          clearTimeout(timer.current);
          try {
            await navigator.clipboard.writeText(typeof text === 'function' ? text() : text);
            setFeedback('已复制');
          } catch {
            setFeedback('复制失败，请重试');
          }
          timer.current = setTimeout(() => setFeedback(''), 2000);
        }}
      >
        {feedback === '已复制' ? <Check size={15} /> : <Copy size={15} />}
      </button>
      <span role="status">{feedback}</span>
    </span>
  );
}
