import { MandatoryAddressees } from '../core/models/required-reading';
import { articleReach, lossLines, mandatoryLoss } from './mandatory-reach';

const NOW = new Date('2026-09-27T12:00:00+04:00');

function audience(): MandatoryAddressees {
  return {
    in_force_total: 5,
    pending_total: 0,
    departments: [
      { department: 'ტექნიკური — ჯგუფი 03', in_force: 3, pending: 0, read: 1 },
      { department: 'ოფისი', in_force: 2, pending: 0, read: 0 }
    ],
    addressees: [
      { user_id: 1, user_name: 'ნინო', department: 'ტექნიკური — ჯგუფი 03', read: true, pending: false },
      { user_id: 2, user_name: 'გიორგი', department: 'ოფისი', read: false, pending: false }
    ]
  };
}

describe('articleReach', () => {
  it('is now for published, and for a schedule already passed', () => {
    expect(articleReach('published', null, NOW)).toBe('now');
    expect(articleReach('scheduled', '2026-09-26T11:00', NOW)).toBe('now');
  });

  it('is later for a schedule still ahead and never for a draft', () => {
    expect(articleReach('scheduled', '2026-10-01T09:00', NOW)).toBe('later');
    expect(articleReach('draft', null, NOW)).toBe('never');
    expect(articleReach('scheduled', null, NOW)).toBe('never');
  });
});

describe('mandatoryLoss', () => {
  it('loses nobody while the article stays readable for the same departments', () => {
    const loss = mandatoryLoss(audience(), { reach: 'now', departments: ['ტექნიკური', 'ოფისი'] });
    expect(loss.total).toBe(0);
    expect(loss.names).toEqual([]);
  });

  it('loses the department the new audience drops, group members included', () => {
    const loss = mandatoryLoss(audience(), { reach: 'now', departments: ['ოფისი'] });
    expect(loss.total).toBe(3);
    expect(loss.confirmed).toBe(1);
    expect(loss.departments).toEqual([{ department: 'ტექნიკური — ჯგუფი 03', count: 3 }]);
    expect(loss.names).toEqual(['ნინო']);
  });

  it('loses everyone when the article stops being readable', () => {
    const loss = mandatoryLoss(audience(), { reach: 'never', departments: ['ტექნიკური', 'ოფისი'] });
    expect(loss.total).toBe(5);
    expect(loss.names).toEqual(['ნინო', 'გიორგი']);
  });

  it('keeps people bound at publication when the article is only rescheduled', () => {
    const scheduled: MandatoryAddressees = {
      in_force_total: 0,
      pending_total: 2,
      departments: [{ department: 'ოფისი', in_force: 0, pending: 2, read: 0 }],
      addressees: []
    };
    expect(mandatoryLoss(scheduled, { reach: 'later', departments: ['ოფისი'] }).total).toBe(0);
    expect(mandatoryLoss(scheduled, { reach: 'never', departments: ['ოფისი'] }).total).toBe(2);
  });
});

describe('lossLines', () => {
  it('lists names, capped with how many more', () => {
    const loss = { total: 12, confirmed: 0, departments: [], names: ['ა', 'ბ', 'გ'] };
    expect(lossLines(loss, (n) => `+${n}`, 2)).toEqual(['ა', 'ბ', '+10']);
  });

  it('falls back to counts by department when no names may be shown', () => {
    const loss = { total: 4, confirmed: 0, departments: [{ department: 'ოფისი', count: 4 }], names: [] };
    expect(lossLines(loss, (n) => `+${n}`)).toEqual(['ოფისი — 4']);
  });
});
