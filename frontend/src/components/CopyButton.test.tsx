import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { CopyButton } from './CopyButton';
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});
it('copies the supplied content and lets a failed clipboard operation be retried', async () => {
  const writeText = vi.fn().mockRejectedValueOnce(new Error('denied')).mockResolvedValue(undefined);
  vi.stubGlobal('navigator', { clipboard: { writeText } });
  render(<CopyButton text="原始代码" label="复制代码" />);
  fireEvent.click(screen.getByRole('button', { name: '复制代码' }));
  await screen.findByText('复制失败，请重试');
  fireEvent.click(screen.getByRole('button', { name: '复制代码' }));
  await screen.findByText('已复制');
  expect(writeText).toHaveBeenLastCalledWith('原始代码');
});
