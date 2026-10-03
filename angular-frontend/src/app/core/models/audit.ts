/** Mirrors AuditCategory.java's 4 wire-value constants. */
export type AuditCategoryName = 'SECURITY' | 'USER' | 'CONTENT' | 'SYSTEM';

/** Mirrors web.AuditLogResponse -- one row of GET /api/audit-logs. */
export interface AuditLogEntry {
  id: number;
  admin_id: number | null;
  admin_name: string;
  action: string;
  item_type: string;
  item_id: number | null;
  item_name: string | null;
  timestamp: string;
  category: AuditCategoryName;
  details: string | null;
  prev_hash: string | null;
  row_hash: string | null;
  ip_address: string | null;
  user_agent: string | null;
}

/** Mirrors AuditLogFilter.java -- the shared query params of list + export. */
export interface AuditLogFilter {
  start_date?: string | null;
  end_date?: string | null;
  user_id?: number | null;
  user_name?: string | null;
  action?: string | null;
  category?: string | null;
  q?: string | null;
}

export interface AuditLogPage {
  rows: AuditLogEntry[];
  total: number;
}

/** Mirrors web.AuditVerifyResponse. status is "ok" | "tampered" | "unchained". */
export interface AuditVerifyResult {
  status: 'ok' | 'tampered' | 'unchained' | 'error';
  hash_match: boolean | null;
  chain_match: boolean | null;
  row_hash: string | null;
  recomputed_hash: string | null;
}

/** Mirrors web.AuditChainHealthResponse. status is "ok" | "tampered". */
/** One batch of the whole-ledger check; loop on next_after_id until it is null. */
export interface AuditChainFullCheck {
  status: 'ok' | 'tampered';
  checked: number;
  total: number;
  hash_mismatches: number;
  link_breaks: number;
  bad_ids: number[];
  tail_state_mismatch: boolean;
  next_after_id: number | null;
}

export interface AuditChainHealth {
  status: 'ok' | 'tampered';
  checked: number;
  window: number;
  hash_mismatches: number;
  link_breaks: number;
  bad_ids: number[];
  unchained_total: number;
  tail_state_mismatch: boolean;
}
