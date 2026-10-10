import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { GitChangeCardBlock } from './GitChangeCardBlock';
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

it('历史 Git 卡片只读，不再查询或执行已退役接口', () => {
  const fetchMock = vi.fn();
  vi.stubGlobal('fetch', fetchMock);
  render(<GitChangeCardBlock sessionId="s1" changeId="gc1" />);
  expect(screen.getByText('历史 Git 审阅记录')).toBeInTheDocument();
  expect(screen.getByText('gc1')).toBeInTheDocument();
  expect(screen.queryByRole('button')).not.toBeInTheDocument();
  expect(fetchMock).not.toHaveBeenCalled();
});
