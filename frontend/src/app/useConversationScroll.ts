import { useLayoutEffect, useRef, useState } from 'react';

/** 跟随只由用户滚动位置决定，流式增量不能把正在阅读历史的用户拉回底部。 */
export function useConversationScroll(
  sessionId: string | undefined,
  revision: unknown,
  answer?: string,
) {
  const messagesRef = useRef<HTMLDivElement>(null);
  const following = useRef(true);
  const previousSession = useRef(sessionId);
  const [showLatest, setShowLatest] = useState(false);
  function jumpToLatest() {
    following.current = true;
    setShowLatest(false);
    const element = messagesRef.current;
    if (element) element.scrollTop = element.scrollHeight;
  }
  function onScroll() {
    const element = messagesRef.current;
    if (!element) return;
    following.current = element.scrollHeight - element.clientHeight - element.scrollTop < 48;
    setShowLatest(!following.current);
  }
  useLayoutEffect(() => {
    if (previousSession.current !== sessionId) {
      previousSession.current = sessionId;
      following.current = true;
    }
    if (following.current) jumpToLatest();
  }, [sessionId, revision, answer]);
  return { messagesRef, onScroll, showLatest, jumpToLatest };
}
