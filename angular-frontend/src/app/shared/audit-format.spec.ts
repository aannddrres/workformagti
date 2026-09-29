import { describe, expect, it } from 'vitest';
import { formatAuditAction, parseAuditDetails } from './audit-format';
import { formatKaDateTimeSeconds } from './ka-date';

const translate = (key: string): string =>
  ({ 'users.role_operator': 'ოპერატორი', 'users.perm_articles_edit': 'სტატიების რედაქტირება' })[key] ?? key;

describe('parseAuditDetails', () => {
  it('shows only the fields that changed, in Georgian, and folds the rest away', () => {
    const view = parseAuditDetails(JSON.stringify({
      schema_version: 1,
      result: 'SUCCESS',
      reason: null,
      before: { role: 'operator', active: true, department: 'ტექნიკური', lock_version: 0 },
      after: { role: 'operator', active: false, department: 'ტექნიკური', lock_version: 1 }
    }), translate);

    expect(view.hasBefore).toBe(true);
    expect(view.changed).toEqual([{ key: 'active', label: 'აქტიურია', before: 'კი', after: 'არა' }]);
    expect(view.unchanged.map((row) => row.after)).toEqual(['ოპერატორი', 'ტექნიკური']);
    // Lock versions change on every save and say nothing to the reader.
    expect([...view.changed, ...view.unchanged].some((row) => row.key === 'lock_version')).toBe(false);
    expect(view.outcome).toBeNull();
    expect(view.technical).toContain('"lock_version": 1');
  });

  it('lists a creation as values, without a "before" column', () => {
    const view = parseAuditDetails(JSON.stringify({
      schema_version: 1,
      result: 'SUCCESS',
      reason: null,
      before: null,
      after: { priority: 'CRITICAL', published_at: '2026-09-28T03:07:38.323727998+04:00', ended_at: null }
    }));

    expect(view.hasBefore).toBe(false);
    expect(view.changed.map((row) => [row.label, row.before, row.after])).toEqual([
      ['პრიორიტეტი', null, 'კრიტიკული'],
      // Local time, whatever zone the suite runs in.
      ['გამოქვეყნდა', null, formatKaDateTimeSeconds('2026-09-28T03:07:38.323727998+04:00')],
      ['დასრულდა', null, '—']
    ]);
  });

  it('says why an event did not simply succeed', () => {
    const view = parseAuditDetails(JSON.stringify({
      schema_version: 1, result: 'FAILURE', reason: 'WORKER_LEASE_EXPIRED', before: null, after: {}
    }));

    expect(view.outcome).toBe('წარუმატებელი');
    expect(view.reason).toBe('დამმუშავებელმა დროში ვერ დაასრულა');
  });

  it('names a permission override by the permission', () => {
    const view = parseAuditDetails(JSON.stringify({
      schema_version: 1, result: 'SUCCESS', reason: null, before: {}, after: { 'articles.edit': 'ALLOW' }
    }), translate);

    expect(view.changed).toEqual([
      { key: 'articles.edit', label: 'სტატიების რედაქტირება', before: '—', after: 'დაშვებულია' }
    ]);
  });

  it('still reads the Python-era {changed: {old, new}} rows', () => {
    const view = parseAuditDetails(JSON.stringify({ changed: { title: { old: 'ა', new: 'ბ' } } }));

    expect(view.changed).toEqual([{ key: 'title', label: 'სათაური', before: 'ა', after: 'ბ' }]);
  });

  it('keeps text that is not JSON as it is, and says so when there is nothing', () => {
    expect(parseAuditDetails('plain words').text).toBe('plain words');
    const empty = parseAuditDetails(null);
    expect(empty.changed).toEqual([]);
    expect(empty.technical).toBeNull();
  });
});

describe('formatAuditAction', () => {
  it('names the actions the Java services write', () => {
    const translateKey = (key: string) => key;
    expect(formatAuditAction('MARK_REQUIRED_READING_READ', translateKey)).toBe('სავალდებულო მასალის წაკითხვა');
    expect(formatAuditAction('CORPORATE_ROLE_ACCESS_REVOKED', translateKey)).toContain('წვდომის გაუქმება');
    expect(formatAuditAction('UNKNOWN_CODE', translateKey)).toBe('UNKNOWN_CODE');
  });
});
