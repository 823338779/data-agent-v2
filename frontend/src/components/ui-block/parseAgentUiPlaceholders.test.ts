import { describe, expect, it } from 'vitest';
import { parseAgentUiPlaceholders } from './parseAgentUiPlaceholders';

describe('parseAgentUiPlaceholders', () => {
  it('preserves markdown around an exact marker', () => {
    expect(
      parseAgentUiPlaceholders('before\n<agent-ui block-id="block_1"></agent-ui>\nafter'),
    ).toEqual([
      { kind: 'markdown', content: 'before\n' },
      { kind: 'ui', blockId: 'block_1' },
      { kind: 'markdown', content: '\nafter' },
    ]);
  });

  it('leaves malformed or extended html inert', () => {
    const content =
      '<agent-ui block-id=\'one\'></agent-ui><agent-ui block-id="two" x="1"></agent-ui>';
    expect(parseAgentUiPlaceholders(content)).toEqual([{ kind: 'markdown', content }]);
  });
});
