import { describe, expect, it } from 'vitest';
import { formatKaDateTime } from './ka-date';

describe('formatKaDateTime', () => {
  it('uses a deterministic Georgian month label', () => {
    const localAugust = new Date(2026, 7, 21, 9, 5, 7).toISOString();

    expect(formatKaDateTime(localAugust)).toBe('21 აგვ. 2026, 09:05:07');
  });

  it('renders an invalid value safely', () => {
    expect(formatKaDateTime('not-a-date')).toBe('—');
  });
});
