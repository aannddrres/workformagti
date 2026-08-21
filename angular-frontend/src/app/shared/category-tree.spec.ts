import { describe, expect, it } from 'vitest';
import { ArticleSummary } from '../core/models/article';
import { Category } from '../core/models/category';
import { buildRecursiveCategoryCounts, categoryPath, descendantCategoryIds } from './category-tree';

const categories: Category[] = [
  {
    id: 1,
    name: 'მშობელი',
    parent_id: null,
    slug: 'parent',
    icon: null,
    pastel_color_class: null,
    is_active: true,
  },
  {
    id: 2,
    name: 'შვილი',
    parent_id: 1,
    slug: 'child',
    icon: null,
    pastel_color_class: null,
    is_active: true,
  },
  {
    id: 3,
    name: 'შვილიშვილი',
    parent_id: 2,
    slug: 'grandchild',
    icon: null,
    pastel_color_class: null,
    is_active: true,
  },
];

function article(id: number, categoryId: number): ArticleSummary {
  return {
    id,
    title: `A${id}`,
    category_id: categoryId,
    category_name: null,
    tags: null,
    target_departments: ['All'],
    status: 'published',
    published_at: null,
    created_at: '2026-01-01T00:00:00Z',
    read_time: 1,
    audience_profile: 'all',
    visible_to_tech_info: true,
    visible_to_service_center: true,
    is_draft: false,
  };
}

describe('category tree helpers', () => {
  it('collects descendants without looping on malformed cycles', () => {
    expect([...descendantCategoryIds(categories, 1)]).toEqual([1, 2, 3]);
  });

  it('builds a root-to-leaf breadcrumb', () => {
    expect(categoryPath(categories, 3).map((item) => item.id)).toEqual([1, 2, 3]);
  });

  it('rolls child article counts into every ancestor', () => {
    const counts = buildRecursiveCategoryCounts(categories, [
      article(1, 1),
      article(2, 2),
      article(3, 3),
    ]);
    expect(counts.get(1)).toBe(3);
    expect(counts.get(2)).toBe(2);
    expect(counts.get(3)).toBe(1);
  });
});
