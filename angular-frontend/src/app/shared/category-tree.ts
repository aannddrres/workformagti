import { ArticleSummary } from '../core/models/article';
import { Category } from '../core/models/category';

export function descendantCategoryIds(categories: Category[], categoryId: number): Set<number> {
  const childrenByParent = new Map<number, number[]>();
  for (const category of categories) {
    if (category.parent_id != null) {
      const children = childrenByParent.get(category.parent_id) ?? [];
      children.push(category.id);
      childrenByParent.set(category.parent_id, children);
    }
  }

  const result = new Set<number>();
  const pending = [categoryId];
  while (pending.length > 0) {
    const current = pending.pop()!;
    if (result.has(current)) {
      continue;
    }
    result.add(current);
    pending.push(...(childrenByParent.get(current) ?? []));
  }
  return result;
}

export function categoryPath(categories: Category[], categoryId: number | null): Category[] {
  if (categoryId == null) {
    return [];
  }

  const byId = new Map(categories.map((category) => [category.id, category]));
  const result: Category[] = [];
  const visited = new Set<number>();
  let current = byId.get(categoryId);
  while (current && !visited.has(current.id)) {
    visited.add(current.id);
    result.unshift(current);
    current = current.parent_id == null ? undefined : byId.get(current.parent_id);
  }
  return result;
}

/** Counts every article under a category, including all nested descendants. */
export function buildRecursiveCategoryCounts(
  categories: Category[],
  articles: ArticleSummary[],
): Map<number, number> {
  const directCounts = new Map<number, number>();
  for (const article of articles) {
    if (article.category_id != null) {
      directCounts.set(article.category_id, (directCounts.get(article.category_id) ?? 0) + 1);
    }
  }

  return new Map(
    categories.map((category) => {
      const count = [...descendantCategoryIds(categories, category.id)].reduce(
        (sum, id) => sum + (directCounts.get(id) ?? 0),
        0,
      );
      return [category.id, count];
    }),
  );
}
