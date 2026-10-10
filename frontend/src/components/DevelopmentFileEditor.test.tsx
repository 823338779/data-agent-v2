import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { DevelopmentFileEditor } from './DevelopmentFileEditor';
import type { DevelopmentClient } from '../api/developmentClient';
afterEach(cleanup);

it('源码编辑保存临时文件时保留未知 YAML，且不声称已推送', async () => {
  const source = '# retained comment\nunknown: &alias {key: value}\nmetric: *alias\n';
  const api = {
    files: vi.fn().mockResolvedValue({ files: ['models/schema.yml'], revision: 7 }),
    file: vi
      .fn()
      .mockResolvedValue({ path: 'models/schema.yml', content: source, sha256: 'original' }),
    writeFiles: vi.fn().mockResolvedValue({ revision: 8 }),
  } as unknown as DevelopmentClient;
  render(<DevelopmentFileEditor sessionId="s1" client={api} onChanged={vi.fn()} />);
  fireEvent.click(await screen.findByRole('button', { name: 'models/schema.yml' }));
  const editor = await screen.findByRole('textbox', { name: '文件源码' });
  fireEvent.change(editor, { target: { value: source + 'new_field: 1\n' } });
  fireEvent.click(screen.getByRole('button', { name: '写入本地文件' }));
  await waitFor(() =>
    expect(api.writeFiles).toHaveBeenCalledWith(
      's1',
      {
        expectedRevision: 7,
        operations: [
          {
            type: 'UPDATE',
            path: 'models/schema.yml',
            expectedSha256: 'original',
            content: source + 'new_field: 1\n',
          },
        ],
      },
      expect.any(AbortSignal),
    ),
  );
  expect(await screen.findByText('已写入本地临时文件，尚未保存到 Git。')).toBeInTheDocument();
});
