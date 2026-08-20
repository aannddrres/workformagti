/** Mirrors web.CategoryResponse (java-backend) field-for-field. */
export interface Category {
  id: number;
  name: string;
  parent_id: number | null;
  slug: string | null;
  icon: string | null;
  pastel_color_class: string | null;
  is_active: boolean;
}

/** Mirrors web.CategoryRequest -- shared shape for both create and update. */
export interface CategoryRequest {
  name: string;
  parent_id: number | null;
  slug: string | null;
  icon: string | null;
  pastel_color_class: string | null;
  is_active: boolean;
}
