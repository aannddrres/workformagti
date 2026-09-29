import { describe, expect, it } from 'vitest';
import { completionBarClass, completionIcon, completionTextClass, completionTier } from './completion-tier';

describe('completionTier', () => {
  it('splits at 80 and 30', () => {
    expect(completionTier(100)).toBe('good');
    expect(completionTier(80)).toBe('good');
    expect(completionTier(79.9)).toBe('middle');
    expect(completionTier(30)).toBe('middle');
    expect(completionTier(29)).toBe('low');
    expect(completionTier(0)).toBe('low');
  });

  it('reads the "85%" labels the statistics endpoints return', () => {
    expect(completionTier('85%')).toBe('good');
    expect(completionTier('12.5%')).toBe('low');
  });

  it('has no tier when nothing is required or the value is missing', () => {
    expect(completionTier(0, false)).toBe('none');
    expect(completionTier(null)).toBe('none');
    expect(completionTier('—')).toBe('none');
  });
});

describe('completion classes', () => {
  it('never paints a status in the brand red', () => {
    for (const tier of ['good', 'middle', 'low', 'none'] as const) {
      expect(completionTextClass(tier)).not.toMatch(/brand/);
      expect(completionBarClass(tier)).not.toMatch(/brand/);
    }
  });

  it('pairs every text colour with a dark one', () => {
    for (const tier of ['good', 'middle', 'low', 'none'] as const) {
      expect(completionTextClass(tier)).toMatch(/dark:text-/);
    }
  });

  it('marks only the lowest tier with an icon', () => {
    expect(completionIcon('low')).toBe('fa-triangle-exclamation');
    expect(completionIcon('middle')).toBeNull();
    expect(completionIcon('good')).toBeNull();
  });
});
