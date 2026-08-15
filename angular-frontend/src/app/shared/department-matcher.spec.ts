import { departmentMatches, departmentPrefix, normalizeDepartment } from './department-matcher';

/**
 * FE-07. The news filter compared department strings with `!==`, against an
 * option list whose "info" entry was the truncated "საინფო" rather than the
 * canonical "საინფორმაციო" the backend writes. Two bugs in one line: the
 * info filter matched nothing at all, and every other filter silently
 * excluded sub-groups.
 *
 * These cases mirror the backend's DepartmentMatcher, which is the contract
 * this file has to keep.
 */
describe('departmentMatcher', () => {
  describe('departmentPrefix', () => {
    it('strips an em-dash sub-group', () => {
      expect(departmentPrefix('ტექნიკური — ჯგუფი 03')).toBe('ტექნიკური');
    });

    it('strips a sub-group introduced by the ჯგუფი keyword with no dash', () => {
      expect(departmentPrefix('ტექნიკური ჯგუფი 03')).toBe('ტექნიკური');
    });

    it('handles an ASCII hyphen and an en dash', () => {
      expect(departmentPrefix('ოფისი - ჯგუფი 1')).toBe('ოფისი');
      expect(departmentPrefix('ოფისი – ჯგუფი 1')).toBe('ოფისი');
    });

    it('leaves a department with no sub-group alone', () => {
      expect(departmentPrefix('ოფისი')).toBe('ოფისი');
    });

    it('is empty for empty input rather than throwing', () => {
      expect(departmentPrefix(null)).toBe('');
      expect(departmentPrefix(undefined)).toBe('');
      expect(departmentPrefix('   ')).toBe('');
    });
  });

  describe('departmentMatches', () => {
    it('matches a sub-group against its parent -- the bug FE-07 is about', () => {
      expect(departmentMatches('ტექნიკური — ჯგუფი 03', 'ტექნიკური')).toBe(true);
    });

    it('still matches exactly', () => {
      expect(departmentMatches('ოფისი', 'ოფისი')).toBe(true);
    });

    it('does not match a different department', () => {
      expect(departmentMatches('ოფისი', 'ტექნიკური')).toBe(false);
      expect(departmentMatches('ოფისი — ჯგუფი 2', 'ტექნიკური')).toBe(false);
    });

    it('treats "All" as matching everything, like the backend', () => {
      expect(departmentMatches('ტექნიკური — ჯგუფი 03', 'All')).toBe(true);
    });

    it('treats an empty target as no filter', () => {
      expect(departmentMatches('ოფისი', '')).toBe(true);
      expect(departmentMatches('ოფისი', null)).toBe(true);
    });

    it('matches the canonical info department that the old option value could never hit', () => {
      // The template offered value="საინფო"; every real row says
      // "საინფორმაციო", so the filter returned an empty list every time.
      expect(departmentMatches('საინფორმაციო', 'საინფორმაციო')).toBe(true);
      expect(departmentMatches('საინფორმაციო — ჯგუფი 1', 'საინფორმაციო')).toBe(true);
      expect(departmentMatches('საინფორმაციო', 'საინფო')).toBe(false);
    });
  });

  describe('normalizeDepartment', () => {
    it('collapses whitespace runs but leaves dashes', () => {
      expect(normalizeDepartment('  ტექნიკური   —  ჯგუფი 03 ')).toBe('ტექნიკური — ჯგუფი 03');
    });
  });
});
