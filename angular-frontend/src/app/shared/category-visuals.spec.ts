import { categoryIconClass, getCategoryIcon } from './category-visuals';

/**
 * The roaming category arrives from the legacy database with the icon
 * `fa_wifi`. That matched no Font Awesome rule, and because it was not empty
 * nothing fell back either -- the tile, the category page and the overview
 * all drew an empty square.
 */
describe('categoryIconClass', () => {
  it('repairs an underscore where Font Awesome has a hyphen', () => {
    expect(categoryIconClass({ name: 'როუმინგი', icon: 'fa_wifi' })).toBe('fa-wifi');
  });

  it('keeps a valid stored icon as it is', () => {
    expect(categoryIconClass({ name: 'ლოიალობა და აქციები', icon: 'fa-gift' })).toBe('fa-gift');
  });

  it('falls back to the name-based icon when the stored one is missing or not a class', () => {
    const fallback = getCategoryIcon('როუმინგი', '');
    expect(categoryIconClass({ name: 'როუმინგი', icon: null })).toBe(fallback);
    expect(categoryIconClass({ name: 'როუმინგი', icon: '  ' })).toBe(fallback);
    expect(categoryIconClass({ name: 'როუმინგი', icon: 'wifi' })).toBe(fallback);
    expect(categoryIconClass(null)).toBe(getCategoryIcon(undefined, ''));
  });
});
