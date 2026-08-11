/** Mirrors web.RequiredReadingRequest. */
export interface RequiredReadingRequest {
  item_type: string;
  item_id: number;
  target_department: string;
  due_date: string;
  priority: string;
}

/** Mirrors web.RequiredReadingResponse. */
export interface RequiredReadingItem {
  id: number;
  item_type: string;
  item_id: number;
  target_department: string;
  due_date: string;
  priority: string;
}
