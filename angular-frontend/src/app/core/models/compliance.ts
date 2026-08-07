/** Mirrors web.RequiredReadingResponse. */
export interface RequiredReadingInfo {
  id: number;
  item_type: string;
  item_id: number;
  target_department: string;
  due_date: string | null;
  priority: string;
}

/** Mirrors web.MyReadingResponse -- one item of an operator's reading-task list. */
export interface MyReading {
  reading: RequiredReadingInfo;
  status: string;
  read_at: string | null;
  is_overdue: boolean;
  item_title: string | null;
  item_content: string | null;
}

/** Mirrors web.MyProgressResponse. */
export interface MyProgress {
  total_mandatory: number;
  read_completed: number;
  pending: number;
  percentage: number;
}

/** Mirrors web.ReadStatusResponse -- the mark-read POST response body. */
export interface ReadStatus {
  id: number;
  user_id: number;
  required_reading_id: number;
  status: string;
  read_at: string | null;
}

export type MarkReadResult =
  | { ok: true; quizRequired: false; status: ReadStatus }
  | { ok: false; quizRequired: boolean };
