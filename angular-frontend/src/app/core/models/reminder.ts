export type ReminderType = 'ASSIGNMENT' | 'DUE_SOON' | 'OVERDUE' | 'MANUAL';

export interface ReminderEntry {
  id: number;
  recipient_name: string;
  type: ReminderType;
  content: string;
  required_reading_id: number | null;
  item_type: string | null;
  item_id: number | null;
  item_title: string | null;
  due_at: string | null;
  triggered_by_name: string;
  created_at: string;
  read_at: string | null;
  lock_version: number;
}

export interface ReminderPage {
  items: ReminderEntry[];
  page: number;
  size: number;
  total_elements: number;
  total_pages: number;
}
