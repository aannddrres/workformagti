import { AuditCategoryName, AuditLogFilter } from '../core/models/audit';
import { formatKaDate, formatKaDateTimeSeconds } from './ka-date';

export interface AuditCategoryBadge {
  label: string;
  badgeClass: string;
  dotClass: string;
}

/** Port of audit-dashboard.js's window.auditCategoryStylesMap. */
const CATEGORY_STYLES: Record<AuditCategoryName, AuditCategoryBadge> = {
  SECURITY: { label: 'უსაფრთხოება', badgeClass: 'bg-rose-50 text-rose-700 border-rose-200/60 dark:bg-rose-950/40 dark:text-rose-300 dark:border-rose-800/60', dotClass: 'bg-rose-500' },
  USER: { label: 'მომხმარებელი', badgeClass: 'bg-blue-50 text-blue-700 border-blue-200/60 dark:bg-blue-950/40 dark:text-blue-300 dark:border-blue-800/60', dotClass: 'bg-blue-500' },
  CONTENT: { label: 'კონტენტი', badgeClass: 'bg-emerald-50 text-emerald-700 border-emerald-200/60 dark:bg-emerald-950/40 dark:text-emerald-300 dark:border-emerald-800/60', dotClass: 'bg-emerald-500' },
  SYSTEM: { label: 'სისტემა', badgeClass: 'bg-amber-50 text-amber-700 border-amber-200/60 dark:bg-amber-950/40 dark:text-amber-300 dark:border-amber-800/60', dotClass: 'bg-amber-500' }
};
const CATEGORY_FALLBACK: AuditCategoryBadge = { label: '—', badgeClass: 'bg-slate-50 text-slate-600 border-slate-200/60 dark:bg-slate-800/40 dark:text-slate-400 dark:border-slate-700/60', dotClass: 'bg-slate-400' };

export function categoryBadge(category: string | null | undefined): AuditCategoryBadge {
  return (category && CATEGORY_STYLES[category as AuditCategoryName]) || CATEGORY_FALLBACK;
}

const ACTION_LABELS: Record<string, string> = {
  LOGIN: 'სისტემაში შესვლა',
  LOGIN_SSO: 'ერთიანი ავტორიზაციით შესვლა',
  LOGIN_FAILED: 'შესვლის წარუმატებელი მცდელობა',
  PASSWORD_CHANGE: 'პაროლის შეცვლა',
  PASSWORD_RESET: 'პაროლის აღდგენა',
  PASSWORD_RESET_REQUEST: 'პაროლის აღდგენის მოთხოვნა',
  CREATE_USER: 'მომხმარებლის შექმნა',
  UPDATE_PERMISSIONS: 'უფლებების შეცვლა',
  VIEW_AUDIT_LOG: 'აუდიტის ნახვა',
  EXPORT_AUDIT_LOG: 'აუდიტის ექსპორტი',
  VIEW: 'მასალის ნახვა',
  MARK_READ: 'წაკითხულად მონიშვნა',
  SEND_MESSAGE: 'შეტყობინების გაგზავნა',
  SEND_AUTOMATIC_REMINDER: 'ავტომატური შეხსენების გაგზავნა',
  SEND_MANUAL_REMINDER: 'ჯგუფის უფროსის შეხსენების გაგზავნა',
  READ_REMINDER: 'შეხსენების წაკითხვა',
  BROADCAST: 'საერთო განცხადების გამოქვეყნება (ძველი)',
  PUBLISH_BROADCAST: 'საერთო განცხადების გამოქვეყნება',
  END_BROADCAST_EARLY: 'საერთო განცხადების დროზე ადრე დასრულება',
  ARCHIVE: 'დაარქივება',
  UNARCHIVE: 'არქივიდან აღდგენა',
  RESTORE: 'ვერსიის აღდგენა',
  VERIFY: 'მთლიანობის შემოწმება',
  UPDATE_REQUIRED_READING: 'სავალდებულო გაცნობის განახლება',
  UPDATE_QUIZ: 'ქვიზის განახლება',
  UPLOAD: 'ფაილის ატვირთვა',
  FILE_ACCESS: 'ფაილის გახსნა',
  FILE_ACCESS_DENIED: 'ფაილზე წვდომა აიკრძალა',
  // DEC-P01 shadow mode. The label says "would have" out loud because the
  // whole point of the row is that nothing was actually refused -- and the
  // person reading this list is deciding whether to make it real.
  FILE_ACCESS_SHADOW_DENY: 'ფაილზე წვდომა აიკრძალებოდა (shadow)',
  EXPORT: 'ექსპორტი',
  EXPORT_XLSX: 'Excel-ის ექსპორტი',
  EXPORT_PDF: 'PDF-ის ექსპორტი',
  EXPORT_ADMIN_AUDIT_LEDGER: 'აუდიტის სრული ჟურნალის ექსპორტი',
  EXPORT_ADMIN_READ_EVIDENCE: 'ოფიციალური გაცნობის მტკიცებულების ექსპორტი',
  EXPORT_ADMIN_ARTICLE_VIEWS: 'სტატიის გახსნის ისტორიის ექსპორტი',
  EXPORT_ADMIN_SEARCH_HISTORY: 'ძებნის ისტორიის ექსპორტი',
  EXPORT_ADMIN_CHANGE_EVENTS: 'ცვლილებებისა და უსაფრთხოების მოვლენების ექსპორტი',
  // Written by the Java services since the port, but never given a label, so
  // the list showed the raw code for them.
  ACKNOWLEDGE_ARTICLE_READ: 'სტატიის გაცნობის დადასტურება',
  MARK_REQUIRED_READING_READ: 'სავალდებულო მასალის წაკითხვა',
  CREATE_REQUIRED_READING: 'სავალდებულო გაცნობის დანიშვნა',
  DELETE_REQUIRED_READING: 'სავალდებულო გაცნობის გაუქმება',
  SUBMIT_QUIZ_ATTEMPT: 'ქვიზის მცდელობა',
  CORPORATE_ROLE_ACCESS_REVOKED: 'წვდომის გაუქმება — AD-ში პორტალის როლი აღარ აქვს',
  UPDATE_USER_ADMIN: 'მომხმარებლის მონაცემების შეცვლა',
  UPDATE_USER_PROFILE: 'პროფილის შეცვლა',
  REVOKE_SESSION: 'სესიის გაუქმება',
  CREATE: 'შექმნა',
  UPDATE: 'განახლება',
  AUTOSAVE: 'ავტომატური შენახვა',
  DELETE: 'წაშლა',
  RESTORE_VERSION: 'ვერსიის აღდგენა',
  TRASH: 'სანაგვეში გადატანა',
  RESTORE_FROM_TRASH: 'სანაგვიდან აღდგენა',
  PURGE: 'საბოლოო წაშლა',
  SET_LEGAL_HOLD: 'იურიდიული დაცვის დაწესება',
  RELEASE_LEGAL_HOLD: 'იურიდიული დაცვის მოხსნა',
  ORG_BACKFILL_APPLY: 'სტრუქტურის შევსების გაშვება'
};

const ITEM_TYPE_LABELS: Record<string, string> = {
  article: 'სტატია',
  news: 'სიახლე',
  video: 'ვიდეო',
  category: 'კატეგორია',
  required_reading: 'სავალდებულო გაცნობა',
  user: 'მომხმარებელი',
  readings: 'გაცნობის ჩანაწერები',
  file: 'ფაილი',
  stored_file: 'ატვირთული ფაილი',
  system: 'სისტემა',
  audit_log: 'აუდიტის ჩანაწერი',
  team_stats: 'გუნდის სტატისტიკა',
  broadcast: 'საერთო განცხადება',
  reminder: 'შეხსენება',
  admin_export: 'სისტემური ადმინისტრატორის ექსპორტი',
  export_job: 'საექსპორტო დავალება'
};

export function formatAuditAction(action: string | null | undefined, translate: (key: string) => string): string {
  if (!action) return '—';
  if (action === 'LOGOUT') return translate('audit.action_logout');
  if (action === 'EXPORT_ADMIN_QUIZ_ATTEMPTS') return translate('audit.action_export_quiz');
  if (action === 'EXPORT_JOB_COMPLETED') return translate('audit.action_export_job_completed');
  if (action === 'EXPORT_JOB_FAILED') return translate('audit.action_export_job_failed');
  if (action === 'EXPORT_JOB_INTERRUPTED') return translate('audit.action_export_job_interrupted');
  if (action === 'UPDATE_STATUS_TO_TRUE') return 'მომხმარებლის გააქტიურება';
  if (action === 'UPDATE_STATUS_TO_FALSE') return 'მომხმარებლის გათიშვა';
  if (action.startsWith('BULK_ROLE_')) return 'როლის ჯგუფური შეცვლა';
  if (action === 'BULK_DEACTIVATE') return 'ანგარიშების ჯგუფური გათიშვა';
  return ACTION_LABELS[action] ?? action;
}

export function formatAuditItemType(itemType: string | null | undefined): string {
  if (!itemType) return '—';
  return ITEM_TYPE_LABELS[itemType.toLowerCase()] ?? itemType;
}

export function formatAuditActor(name: string | null | undefined, translate: (key: string) => string): string {
  if (!name) return translate('audit.unknown_user');
  if (name === 'EXPORT_WORKER') return translate('audit.actor_export_worker');
  if (name === 'EXPORT_RECOVERY') return translate('audit.actor_export_recovery');
  return name;
}

/** Localize the old system label without changing stored, hashed audit evidence. */
export function formatAuditItemName(itemType: string | null | undefined, name: string | null | undefined,
  translate: (key: string) => string): string {
  if (itemType === 'audit_log' && name === 'Audit trail') return translate('audit.item_audit_trail');
  if (itemType === 'export_job' && name === 'Export job') return translate('audit.item_export_job');
  return name ?? '';
}

/**
 * Tokenized search parser -- port of audit-dashboard.js's parseQuery +
 * buildParams. `actor:x` / `category:x` / `action:x` tokens pull out into
 * their own filter fields; everything else stays free text (`q`).
 */
export function filterFromSearch(raw: string, startDate: string | null, endDate: string | null): AuditLogFilter {
  const tokens = (raw || '').trim().split(/\s+/).filter(Boolean);
  let actor: string | null = null;
  let category: string | null = null;
  let action: string | null = null;
  const free: string[] = [];
  for (const token of tokens) {
    const match = token.match(/^(actor|category|action):(.+)$/i);
    if (!match) {
      free.push(token);
      continue;
    }
    const key = match[1].toLowerCase();
    if (key === 'actor') actor = match[2];
    else if (key === 'category') category = match[2];
    else action = match[2];
  }
  const filter: AuditLogFilter = { start_date: startDate, end_date: endDate };
  if (actor) {
    if (/^\d+$/.test(actor)) filter.user_id = Number(actor);
    else filter.user_name = actor;
  }
  if (category) filter.category = category.toUpperCase();
  if (action) filter.action = action.toUpperCase();
  if (free.length) filter.q = free.join(' ');
  return filter;
}

/** Rewrites the `category:` token in a raw search string, keeping everything else. */
export function withCategoryToken(raw: string, value: string): string {
  const withoutCategory = (raw || '').replace(/\bcategory:\S+/i, '').trim();
  return value ? `${withoutCategory} category:${value}`.trim() : withoutCategory;
}

/** One field of the audited record, as the "before / after" table shows it. */
export interface AuditFieldRow {
  key: string;
  label: string;
  /** Null when the event recorded no earlier state (a creation, a login). */
  before: string | null;
  after: string;
}

export interface AuditDetailsView {
  /** Set only when the event did not simply succeed. */
  outcome: string | null;
  reason: string | null;
  /** Whether the event recorded an earlier state, i.e. whether "before" is a column. */
  hasBefore: boolean;
  /** The fields that differ, or every field when there is nothing to compare. */
  changed: AuditFieldRow[];
  /** Fields recorded on both sides with the same value. */
  unchanged: AuditFieldRow[];
  /** Details that are not a JSON object, shown as they are. */
  text: string | null;
  /** The stored JSON, indented -- the "technical detail" for whoever needs it. */
  technical: string | null;
}

/**
 * The Georgian name of every field MutationAuditService and its callers put
 * into an audit record. A key missing here is shown as the key itself.
 */
const FIELD_LABELS: Record<string, string> = {
  title: 'სათაური',
  name: 'სახელი',
  slug: 'ბმულის სახელი',
  status: 'სტატუსი',
  lifecycle_state: 'მდგომარეობა',
  is_draft: 'პირადი მონახაზი',
  version: 'ვერსია',
  category: 'კატეგორია',
  category_id: 'კატეგორია (ID)',
  parent_id: 'მშობელი კატეგორია (ID)',
  target_department: 'აუდიტორია',
  target_departments: 'აუდიტორია',
  audience: 'აუდიტორია',
  tags: 'თეგები',
  attachment_present: 'დანართი აქვს',
  archived: 'დაარქივებულია',
  trashed: 'სანაგვეშია',
  purge_after: 'საბოლოო წაშლა შესაძლებელია',
  legal_hold: 'იურიდიულად დაცულია',
  last_verified_at: 'ბოლოს გადამოწმდა',
  expires_at: 'ვადა',
  due_at: 'ვადა',
  due_date: 'ვადა',
  created_at: 'შეიქმნა',
  published_at: 'გამოქვეყნდა',
  started_at: 'დაიწყო',
  ends_at: 'დასრულების დრო',
  ended_at: 'დასრულდა',
  read_at: 'წაკითხვის დრო',
  role: 'როლი',
  active: 'აქტიურია',
  department: 'დეპარტამენტი',
  department_id: 'დეპარტამენტი (ID)',
  position: 'თანამდებობა',
  phone_present: 'ტელეფონი მითითებულია',
  team_id: 'ჯგუფი (ID)',
  manager_id: 'ხელმძღვანელი (ID)',
  compliance_override: 'გაცნობის გამონაკლისი',
  card_style: 'ბარათის სტილი',
  directory_role_change: 'როლი შეიცვალა AD-ში',
  account_created: 'ანგარიში შეიქმნა',
  user_id: 'მომხმარებელი (ID)',
  user_name: 'მომხმარებელი',
  leader_user_id: 'ლიდერი (ID)',
  assignment_type: 'დანიშვნის ტიპი',
  source: 'წყარო',
  recipient_user_id: 'მიმღები (ID)',
  reminder_type: 'შეხსენების ტიპი',
  required_reading_id: 'სავალდებულო გაცნობა (ID)',
  item_type: 'მასალის ტიპი',
  item_id: 'მასალა (ID)',
  item_title: 'მასალა',
  article_id: 'სტატია (ID)',
  article_title: 'სტატია',
  article_version: 'სტატიის ვერსია',
  operator_id: 'ოპერატორი (ID)',
  operator_name: 'ოპერატორი',
  operator_email: 'ოპერატორის ელფოსტა',
  operator_department: 'ოპერატორის დეპარტამენტი',
  attempt_number: 'მცდელობის ნომერი',
  score: 'ქულა',
  total_questions: 'კითხვების რაოდენობა',
  question_count: 'კითხვების რაოდენობა',
  answer_count: 'პასუხების რაოდენობა',
  correct_answer_count: 'სწორი პასუხები',
  passed: 'ჩააბარა',
  priority: 'პრიორიტეტი',
  message_length: 'ტექსტის სიგრძე (სიმბოლო)',
  published_by_user_id: 'გამოაქვეყნა (ID)',
  ended_by_user_id: 'დაასრულა (ID)',
  authenticated: 'ავტორიზაცია გაიარა',
  auth_channel: 'შესვლის გზა',
  session_created: 'სესია შეიქმნა',
  session_present: 'სესია არსებობდა',
  session_active: 'სესია აქტიურია',
  session_revoked: 'სესია გაუქმდა',
  job_id: 'ექსპორტის დავალება',
  owner_user_id: 'მფლობელი (ID)',
  export_family: 'ექსპორტის სახე',
  export_format: 'ფორმატი',
  scope_department: 'დეპარტამენტი',
  date_from: 'პერიოდის დასაწყისი',
  date_through: 'პერიოდის ბოლო',
  start_date: 'პერიოდის დასაწყისი',
  end_date: 'პერიოდის ბოლო',
  row_count: 'ჩანაწერების რაოდენობა',
  byte_size: 'ზომა (ბაიტი)',
  stored_filename: 'ფაილი',
  content_type: 'ფაილის ტიპი',
  q: 'საძიებო ტექსტი',
  offset: 'გვერდის დასაწყისი',
  limit: 'გვერდის ზომა',
  result_count: 'ნაპოვნი ჩანაწერები',
  pending_count: 'მოლოდინში'
};

/**
 * Bookkeeping for optimistic locking and the record format. They change on
 * every save, so in the table they would be the loudest row and mean nothing
 * to the reader; the technical detail still has them.
 */
const TECHNICAL_FIELDS = new Set(['schema_version', 'lock_version', 'token_version']);

const VALUE_LABELS: Record<string, string> = {
  SUCCESS: 'წარმატებული',
  FAILURE: 'წარუმატებელი',
  DENIED: 'უარყოფილი',
  ALREADY_ACKNOWLEDGED: 'უკვე დადასტურებული იყო',
  ALLOW: 'დაშვებულია',
  DENY: 'აკრძალულია',
  INHERIT: 'როლიდან',
  PRIMARY: 'ძირითადი',
  ACTING: 'მოვალეობის შემსრულებელი',
  MANUAL: 'ხელით',
  BACKFILL: 'საწყისი შევსება',
  AD_SYNC: 'კომპანიის AD',
  NORMAL: 'ჩვეულებრივი',
  IMPORTANT: 'მნიშვნელოვანი',
  CRITICAL: 'კრიტიკული',
  OVERDUE: 'ვადაგადაცილება',
  ALL_AUTHENTICATED: 'ყველა თანამშრომელი',
  CORPORATE_OAUTH: 'კომპანიის ანგარიში',
  LOCAL_DEVELOPMENT_ONLY: 'სატესტო შესვლა (მხოლოდ დეველოპმენტში)',
  ACTIVE: 'აქტიური',
  TRASHED: 'სანაგვეში',
  PURGED: 'საბოლოოდ წაშლილი',
  published: 'გამოქვეყნებული',
  draft: 'მონახაზი',
  scheduled: 'დაგეგმილი',
  archived: 'დაარქივებული',
  read: 'წაკითხული',
  unread: 'წასაკითხი'
};

const REASON_LABELS: Record<string, string> = {
  NO_PORTAL_ROLE: 'AD-ში პორტალის როლი აღარ აქვს',
  EXPORT_BUILD_FAILED: 'ფაილის მომზადება ვერ მოხერხდა',
  WORKER_LEASE_EXPIRED: 'დამმუშავებელმა დროში ვერ დაასრულა',
  CARDINALITY_LIMIT: 'ჩანაწერების რაოდენობის ზღვარი გადაჭარბდა'
};

type Translate = (key: string) => string;

function fieldLabel(key: string, translate: Translate): string {
  if (FIELD_LABELS[key]) return FIELD_LABELS[key];
  // A permission-override record is keyed by the permission itself.
  if (/^[a-z]+\.[a-z_]+$/.test(key)) {
    const translationKey = `users.perm_${key.replace('.', '_')}`;
    const label = translate(translationKey);
    if (label && label !== translationKey) return label;
  }
  return key;
}

function formatValue(key: string, value: unknown, translate: Translate): string {
  if (value === null || value === undefined || value === '') return '—';
  if (typeof value === 'boolean') return value ? 'კი' : 'არა';
  if (Array.isArray(value)) {
    return value.length ? value.map((item) => formatValue(key, item, translate)).join(', ') : '—';
  }
  if (typeof value === 'object') return JSON.stringify(value);
  const text = String(value);
  if (key === 'role') {
    const translationKey = `users.role_${text}`;
    const label = translate(translationKey);
    if (label && label !== translationKey) return label;
  }
  if (/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/.test(text)) return formatKaDateTimeSeconds(text);
  if (/^\d{4}-\d{2}-\d{2}$/.test(text)) return formatKaDate(text);
  return VALUE_LABELS[text] ?? text;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function sameValue(left: unknown, right: unknown): boolean {
  return JSON.stringify(left) === JSON.stringify(right);
}

const NO_DETAILS: AuditDetailsView = {
  outcome: null, reason: null, hasBefore: false, changed: [], unchanged: [], text: null, technical: null
};

/**
 * Turns the stored details into a "before / after" table (UI audit bug 21).
 *
 * Every record MutationAuditService writes is an envelope --
 * `{schema_version, result, reason, before, after}` -- and the drawer used to
 * print it as indented JSON, because the only shape it understood was the
 * Python original's `{changed: {field: {old, new}}}`. The JSON took the whole
 * drawer and the one field that changed was somewhere inside it. The JSON is
 * still there, under "technical detail".
 */
export function parseAuditDetails(detailsStr: string | null, translate: Translate = (key) => key): AuditDetailsView {
  if (!detailsStr) {
    return NO_DETAILS;
  }
  let data: unknown;
  try {
    data = JSON.parse(detailsStr);
  } catch {
    return { ...NO_DETAILS, text: detailsStr };
  }
  if (!isRecord(data)) {
    return { ...NO_DETAILS, text: JSON.stringify(data) };
  }
  const technical = JSON.stringify(data, null, 2);
  const row = (key: string, before: unknown, after: unknown, hasBefore: boolean): AuditFieldRow => ({
    key,
    label: fieldLabel(key, translate),
    before: hasBefore ? formatValue(key, before, translate) : null,
    after: formatValue(key, after, translate)
  });

  // The Python original's shape, which older rows still carry.
  if (isRecord(data['changed'])) {
    const changed = Object.entries(data['changed']).map(([key, value]) => {
      const pair = isRecord(value) ? value : {};
      return row(key, pair['old'], pair['new'], true);
    });
    return { ...NO_DETAILS, hasBefore: true, changed, technical };
  }

  if ('before' in data || 'after' in data) {
    const before = isRecord(data['before']) ? data['before'] : null;
    const after = isRecord(data['after']) ? data['after'] : null;
    const hasBefore = before !== null && after !== null;
    const keys = [...new Set([...Object.keys(after ?? {}), ...Object.keys(before ?? {})])]
      .filter((key) => !TECHNICAL_FIELDS.has(key));
    const changed: AuditFieldRow[] = [];
    const unchanged: AuditFieldRow[] = [];
    for (const key of keys) {
      // A deletion recorded only what was there; show it as the value.
      const value = after ? after[key] : before?.[key];
      if (hasBefore && sameValue(before[key], after[key])) {
        unchanged.push(row(key, before[key], after[key], true));
      } else {
        changed.push(row(key, before?.[key], value, hasBefore));
      }
    }
    const result = typeof data['result'] === 'string' ? data['result'] : null;
    const reason = typeof data['reason'] === 'string' ? data['reason'] : null;
    return {
      outcome: result && result !== 'SUCCESS' ? (VALUE_LABELS[result] ?? result) : null,
      reason: reason ? (REASON_LABELS[reason] ?? reason) : null,
      hasBefore,
      changed,
      unchanged,
      text: null,
      technical
    };
  }

  // Anything else is a flat record of facts.
  const changed = Object.entries(data)
    .filter(([key]) => !TECHNICAL_FIELDS.has(key))
    .map(([key, value]) => row(key, undefined, value, false));
  return { ...NO_DETAILS, changed, technical };
}

export interface ParsedUserAgent {
  browser: string;
  os: string;
}

/** Coarse substring-based UA sniff -- port of audit-dashboard.js's parseUserAgent. */
export function parseUserAgent(ua: string | null | undefined): ParsedUserAgent | null {
  if (!ua) return null;
  let browser = 'უცნობი ბრაუზერი';
  if (/Edg\//.test(ua)) browser = 'Edge';
  else if (/Chrome\//.test(ua) && !/Chromium/.test(ua)) browser = 'Chrome';
  else if (/Firefox\//.test(ua)) browser = 'Firefox';
  else if (/Safari\//.test(ua) && !/Chrome/.test(ua)) browser = 'Safari';
  let os = 'უცნობი OS';
  if (/Windows/.test(ua)) os = 'Windows';
  else if (/Mac OS X/.test(ua)) os = 'macOS';
  else if (/Android/.test(ua)) os = 'Android';
  else if (/iPhone|iPad/.test(ua)) os = 'iOS';
  else if (/Linux/.test(ua)) os = 'Linux';
  return { browser, os };
}
