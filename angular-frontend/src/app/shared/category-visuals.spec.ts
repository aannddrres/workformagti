import { DEFAULT_CATEGORY_ICON, categoryIconClass } from './category-visuals';

/**
 * The roaming category arrives from the legacy database with the icon
 * `fa_wifi`. That matched no Font Awesome rule, and because it was not empty
 * nothing fell back either -- the tile, the category page and the overview
 * all drew an empty square.
 */
describe('categoryIconClass', () => {
  it('repairs an underscore where Font Awesome has a hyphen', () => {
    expect(categoryIconClass({ icon: 'fa_wifi' })).toBe('fa-wifi');
  });

  it('keeps a valid stored icon as it is', () => {
    expect(categoryIconClass({ icon: 'fa-gift' })).toBe('fa-gift');
  });

  // Owner decision კ7: no icon is guessed from the name or a title any more.
  it('falls back to a plain folder when the stored icon is missing or not a class', () => {
    expect(categoryIconClass({ icon: null })).toBe(DEFAULT_CATEGORY_ICON);
    expect(categoryIconClass({ icon: '  ' })).toBe(DEFAULT_CATEGORY_ICON);
    expect(categoryIconClass({ icon: 'wifi' })).toBe(DEFAULT_CATEGORY_ICON);
    expect(categoryIconClass(null)).toBe(DEFAULT_CATEGORY_ICON);
  });
});
