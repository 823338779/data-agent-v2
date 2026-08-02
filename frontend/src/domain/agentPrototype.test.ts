import { describe, expect, it } from 'vitest';

import { formatTimestamp } from './agentPrototype';

describe('formatTimestamp', () => {
  it('统一按北京时间格式化绝对时间和无时区本地时间', () => {
    const absoluteTime = formatTimestamp('2026-08-01T00:00:00Z');
    const localTime = formatTimestamp('2026-08-01T08:00:00');

    expect(absoluteTime).toBe(localTime);
    expect(absoluteTime).toContain('08:00');
  });

  it('保留无法解析的时间文本', () => {
    expect(formatTimestamp('invalid-time')).toBe('invalid-time');
  });
});
