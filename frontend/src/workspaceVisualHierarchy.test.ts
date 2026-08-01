import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

function readWorkspaceStyles() {
  return ['styles.css', 'styles/shell.css', 'styles/conversation.css', 'styles/history.css']
    .map((file) => readFileSync(resolve(process.cwd(), 'src', file), 'utf8'))
    .join('\n');
}

describe('Claude-inspired workspace hierarchy', () => {
  it('uses document-style responses and a floating composer', () => {
    const styles = readWorkspaceStyles();
    const authStyles = readFileSync(resolve(process.cwd(), 'src/auth.css'), 'utf8');
    const compactAuthStyles = authStyles.replace(/\s+/g, '');

    expect(styles).toContain('max-width: 800px;');
    expect(styles).toContain('.message.assistant .message-body {\n  border: 0;');
    expect(styles).toContain(
      '.composer-surface {\n  border: 1px solid var(--color-line);\n  border-radius: 18px;',
    );
    expect(styles).toMatch(
      /font-family:\s*['"]SFMono-Regular['"],\s*Consolas,\s*['"]Liberation Mono['"],\s*monospace;/,
    );
    expect(styles).toContain('.composer-surface textarea:focus-visible {\n  outline: 0;');
    expect(styles).toContain(
      '.composer-surface:focus-within {\n  border-color: var(--color-accent);',
    );
    expect(compactAuthStyles).toContain('background:var(--color-surface)');
    expect(compactAuthStyles).toContain('color:var(--color-text)');
  });
});
