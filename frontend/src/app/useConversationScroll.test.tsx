import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, expect, it } from 'vitest';
import { useConversationScroll } from './useConversationScroll';

afterEach(cleanup);
function Harness({ id = 'one', text = '' }: { id?: string; text?: string }) {
  const scroll = useConversationScroll(id, text);
  return (
    <>
      <div data-testid="list" ref={scroll.messagesRef} onScroll={scroll.onScroll}>
        {text}
      </div>
      {scroll.showLatest && <button onClick={scroll.jumpToLatest}>回到最新</button>}
    </>
  );
}
it('does not pull a reader to the bottom on new text, but resumes after an explicit jump', () => {
  const view = render(<Harness />);
  const list = screen.getByTestId('list');
  Object.defineProperties(list, {
    scrollHeight: { configurable: true, value: 1000 },
    clientHeight: { value: 400 },
  });
  list.scrollTop = 100;
  fireEvent.scroll(list);
  view.rerender(<Harness text="增量回答" />);
  expect(list.scrollTop).toBe(100);
  fireEvent.click(screen.getByRole('button', { name: '回到最新' }));
  expect(list.scrollTop).toBe(1000);
  Object.defineProperty(list, 'scrollHeight', { value: 1200 });
  view.rerender(<Harness text="增量回答二" />);
  expect(list.scrollTop).toBe(1200);
  act(() => {
    list.scrollTop = 100;
    fireEvent.scroll(list);
  });
  view.rerender(<Harness id="two" text="其他会话" />);
  expect(list.scrollTop).toBe(1200);
  expect(screen.queryByRole('button', { name: '回到最新' })).not.toBeInTheDocument();
});
