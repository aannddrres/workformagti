import { nameInitials } from './name-initials';

describe('nameInitials', () => {
  it('takes the first letter of each of the first two words', () => {
    expect(nameInitials('ტექნიკური ოპერატორი', 'M')).toBe('ტო');
    expect(nameInitials('სისტემური ადმინი მთავარი', 'M')).toBe('სა');
  });

  it('ignores stray whitespace and keeps one letter for a one-word name', () => {
    expect(nameInitials('  ნინო   ბერიძე ', 'M')).toBe('ნბ');
    expect(nameInitials('admin', 'M')).toBe('A');
  });

  it('falls back when there is no name', () => {
    expect(nameInitials(null, 't')).toBe('T');
    expect(nameInitials('   ', 'M')).toBe('M');
  });

  it('never turns Georgian into Mtavruli capitals', () => {
    expect(nameInitials('ნინო ბერიძე', 'M')).not.toMatch(/[\u1C90-\u1CBF]/);
  });
});
