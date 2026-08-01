import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

function readWorkspaceStyles() {
  return ['styles.css', 'styles/shell.css', 'styles/conversation.css', 'styles/history.css']
    .map((file) => readFileSync(resolve(process.cwd(), 'src', file), 'utf8'))
    .join('\n');
}

describe('workspace scroll containment', () => {
  it('keeps the page shell, history drawer, and composer inside the viewport', () => {
    const styles = readWorkspaceStyles();
    const authStyles = readFileSync(resolve(process.cwd(), 'src/auth.css'), 'utf8');
    const appSource = readFileSync(resolve(process.cwd(), 'src/App.tsx'), 'utf8');

    expect(styles).toContain('html,\nbody,\n#root {\n  height: 100%;\n  overflow: hidden;\n}');
    expect(styles).toContain('height: 100%;\n  min-height: 0;\n  overflow: hidden;');
    expect(styles).toContain(
      '.history-drawer {\n  position: absolute;\n  inset: 0 auto 0 0;\n  width: min(280px, 30vw);\n  min-width: 230px;',
    );
    expect(styles).toContain('.conversation-messages {\n  min-height: 0;\n  overflow-y: auto;');
    expect(styles).toContain(
      '.conversation-pane {\n  grid-template-rows: auto minmax(0, 1fr) auto;\n  min-height: 0;\n  overflow: hidden;',
    );
    expect(appSource).toContain('<div className="conversation-notices">');
    expect(authStyles.replace(/\s+/g, '')).toContain('height:100dvh;overflow:hidden');
  });
});
