import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { UiBlockRenderer } from './UiBlockRenderer';

afterEach(cleanup);

describe('UiBlockRenderer', () => {
  it('falls back for unknown type and version', () => {
    render(
      <UiBlockRenderer
        block={{ blockId: 'one', type: 'custom', schemaVersion: 99, variant: '', payload: {} }}
      />,
    );
    expect(screen.getByText('暂不支持此内容卡片')).toBeInTheDocument();
  });

  it('does not allow payload fields to create markup', () => {
    render(
      <UiBlockRenderer
        block={{
          blockId: 'one',
          type: 'git-change-card',
          schemaVersion: 1,
          variant: '',
          payload: { changeId: 'missing', sessionId: 's1', style: '<script>alert(1)</script>' },
        }}
      />,
    );
    expect(screen.getByText('Git 卡片数据无效')).toBeInTheDocument();
    expect(document.querySelector('script')).toBeNull();
  });
});

it('rejects Git payloads with missing, empty or extra fields', () => {
  for (const payload of [
    { changeId: 'gc1' },
    { changeId: '', sessionId: 's1' },
    { changeId: 'gc1', sessionId: 's1', summary: 'untrusted' },
  ]) {
    const view = render(
      <UiBlockRenderer
        block={{ blockId: 'g1', type: 'git-change-card', schemaVersion: 1, variant: '', payload }}
      />,
    );
    expect(screen.getByText('Git 卡片数据无效')).toBeInTheDocument();
    view.unmount();
  }
});
