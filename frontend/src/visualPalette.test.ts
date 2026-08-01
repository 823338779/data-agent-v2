import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

function readWorkspaceStyles() {
  return ['styles.css', 'styles/shell.css', 'styles/conversation.css', 'styles/history.css']
    .map((file) => readFileSync(resolve(process.cwd(), 'src', file), 'utf8'))
    .join('\n');
}

describe('visual palette tokens', () => {
  it('uses a warm, document-first palette without decorative effects', () => {
    const css = readWorkspaceStyles();

    expect(css).toContain('--color-canvas: #faf8f2;');
    expect(css).toContain('--color-surface: #fffefa;');
    expect(css).toContain('--color-navigation: #f4eedf;');
    expect(css).toContain('--color-primary-action: #d8c89a;');
    expect(css).toContain('--color-accent: #536779;');
    expect(css).toContain('--color-text: #343732;');
    expect(css).not.toContain('#1f7a68');
    expect(css).not.toContain('linear-gradient(');
    expect(css).not.toContain('backdrop-filter:');
  });
});
