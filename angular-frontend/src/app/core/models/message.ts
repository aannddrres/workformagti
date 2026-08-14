/** Mirrors web.MessageResponse -- one row of GET /api/messages or /api/messages/sent. */
export interface MessageEntry {
  id: number;
  user_id: number;
  sender_id: number | null;
  content: string;
  is_read: boolean;
  created_at: string;
  sender_name: string | null;
  recipient_name: string | null;
}

/** Mirrors web.MessageRequest -- POST /api/messages. */
export interface SendMessageRequest {
  user_id: number;
  content: string;
}

/** Mirrors web.BroadcastRequest -- POST /api/broadcast. Blank/omitted department or role defaults to "All" server-side. */
export interface BroadcastRequest {
  message: string;
  target_department?: string | null;
  target_role?: string | null;
}

/** {@code recipients} is how many Message rows were actually created (2026-08-14 fix). */
export interface BroadcastResult {
  status: string;
  recipients: number;
}
