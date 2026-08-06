/** Mirrors web.CategoryResponse (java-backend) field-for-field. */
export interface Category {
  id: number;
  name: string;
  parent_id: number | null;
  slug: string;
  icon: string | null;
  pastel_color_class: string | null;
  is_active: boolean;
}
