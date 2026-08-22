export type BroadcastPriority = 'NORMAL' | 'IMPORTANT' | 'CRITICAL';
export type BroadcastStatus = 'active' | 'expired' | 'ended';

export interface BroadcastAnnouncement {
  id: number;
  message: string;
  priority: BroadcastPriority;
  published_at: string;
  ends_at: string;
  ended_at: string | null;
  publisher_name: string;
  ended_by_name: string | null;
  status: BroadcastStatus;
  can_end_early: boolean;
  lock_version: number;
}

export interface PublishBroadcastRequest {
  message: string;
  priority: BroadcastPriority;
  ends_at: string;
}

export interface BroadcastHistory {
  items: BroadcastAnnouncement[];
  page: number;
  size: number;
  total_items: number;
  total_pages: number;
}
