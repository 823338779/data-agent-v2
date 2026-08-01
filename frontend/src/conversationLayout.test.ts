import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

describe('conversation layout', () => {
  it('keeps the composer in the right-hand conversation grid', () => {
    const styles = readFileSync(resolve(process.cwd(), 'src/styles.css'), 'utf8');
    const conversation = styles.match(/\.conversation-view\s*\{[^}]+\}/)?.[0];
    const composer = styles.match(/\.composer\s*\{[^}]+\}/)?.[0];

    expect(conversation).toContain('grid-template-rows: auto minmax(0, 1fr) auto');
    expect(composer).toContain('justify-self: center');
    expect(composer).not.toContain('position: absolute');
    expect(composer).not.toContain('left: 50%');
  });
});
