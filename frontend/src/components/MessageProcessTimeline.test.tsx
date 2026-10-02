import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, expect, it } from 'vitest';
import { MessageProcessTimeline } from './MessageProcessTimeline';
import { ThinkingProcessItem } from '../domain/agent';

afterEach(cleanup);
const item: ThinkingProcessItem = {
  processItemId: 'p1',
  type: 'THINKING',
  sequence: 1,
  status: 'RUNNING',
  createdAt: '2026-10-02T10:00:00',
  startedAt: null,
  completedAt: null,
  durationMillis: null,
  schemaVersion: 1,
  content: '正在整理输入',
  contentTruncated: false,
  originalContentSizeBytes: 0,
  contentSha256: '',
  modelInvocationSequence: 1,
  modelProvider: 'test',
  modelName: 'test',
};

it('starts collapsed while running and keeps the user expansion across completion', () => {
  const view = render(<MessageProcessTimeline items={[item]} />);
  const detail = view.container.querySelector('details')!;
  expect(detail.open).toBe(false);
  fireEvent.click(screen.getByText(/查看执行过程/));
  expect(detail.open).toBe(true);
  view.rerender(<MessageProcessTimeline items={[{ ...item, status: 'SUCCEEDED' }]} />);
  expect(detail.open).toBe(true);
  expect(screen.getByText(/已完成 · 查看执行过程/)).toBeInTheDocument();
});
