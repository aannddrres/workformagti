import { describe, expect, it } from 'vitest';
import { auditDatePreset } from './audit-date-presets';

describe('audit date presets', () => {
  it('uses the Tbilisi calendar across the UTC midnight boundary', () => {
    expect(auditDatePreset(0, new Date('2026-09-22T19:59:00Z'))).toEqual({
      start: '2026-09-22', end: '2026-09-22'
    });
    expect(auditDatePreset(0, new Date('2026-09-22T20:01:00Z'))).toEqual({
      start: '2026-09-23', end: '2026-09-23'
    });
    expect(auditDatePreset(30, new Date('2026-09-22T22:51:00Z'))).toEqual({
      start: '2026-08-24', end: '2026-09-23'
    });
  });

  it('subtracts calendar days across a leap day', () => {
    expect(auditDatePreset(1, new Date('2028-03-01T12:00:00Z'))).toEqual({
      start: '2028-02-29', end: '2028-03-01'
    });
  });
});
