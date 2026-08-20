import { AuditCategoryName, AuditLogFilter } from '../core/models/audit';

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
const CATEGORY_FALLBACK: AuditCategoryBadge = { label: '—', badgeClass: 'bg-gray-50 text-gray-500 border-gray-200/60 dark:bg-gray-800/40 dark:text-gray-400 dark:border-gray-700/60', dotClass: 'bg-gray-400' };

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
  BROADCAST: 'საერთო შეტყობინების გაგზავნა',
  ARCHIVE: 'დაარქივება',
  UNARCHIVE: 'არქივიდან აღდგენა',
  RESTORE: 'ვერსიის აღდგენა',
  VERIFY: 'მთლიანობის შემოწმება',
  UPDATE_REQUIRED_READING: 'სავალდებულო გაცნობის განახლება',
  UPDATE_QUIZ: 'ქვიზის განახლება',
  UPLOAD: 'ფაილის ატვირთვა',
  EXPORT: 'ექსპორტი',
  EXPORT_XLSX: 'Excel-ის ექსპორტი',
  EXPORT_PDF: 'PDF-ის ექსპორტი'
};

const ITEM_TYPE_LABELS: Record<string, string> = {
  article: 'სტატია',
  news: 'სიახლე',
  video: 'ვიდეო',
  category: 'კატეგორია',
  feedback: 'უკუკავშირი',
  required_reading: 'სავალდებულო გაცნობა',
  user: 'მომხმარებელი',
  readings: 'გაცნობის ჩანაწერები',
  file: 'ფაილი',
  system: 'სისტემა',
  audit_log: 'აუდიტის ჩანაწერი',
  team_stats: 'გუნდის სტატისტიკა'
};

export function formatAuditAction(action: string | null | undefined): string {
  if (!action) return '—';
  if (action === 'UPDATE_STATUS_TO_TRUE') return 'მომხმარებლის გააქტიურება';
  if (action === 'UPDATE_STATUS_TO_FALSE') return 'მომხმარებლის გაუქმება';
  if (action.startsWith('BULK_ROLE_')) return 'როლის ჯგუფური შეცვლა';
  return ACTION_LABELS[action] ?? action;
}

export function formatAuditItemType(itemType: string | null | undefined): string {
  if (!itemType) return '—';
  return ITEM_TYPE_LABELS[itemType.toLowerCase()] ?? itemType;
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

export interface AuditDetailChange {
  key: string;
  oldValue: string;
  newValue: string;
}

export type AuditDetailsView =
  | { kind: 'empty' }
  | { kind: 'changes'; changes: AuditDetailChange[] }
  | { kind: 'json'; json: string }
  | { kind: 'raw'; raw: string };

/** Port of audit-dashboard.js's formatAuditDetails, restructured as data instead of an HTML string. */
export function parseAuditDetails(detailsStr: string | null): AuditDetailsView {
  if (!detailsStr) {
    return { kind: 'empty' };
  }
  try {
    const data = JSON.parse(detailsStr);
    if (data && typeof data === 'object' && data.changed) {
      const changes: AuditDetailChange[] = Object.entries(data.changed as Record<string, { old?: unknown; new?: unknown }>).map(
        ([key, val]) => ({
          key,
          oldValue: val?.old !== null && val?.old !== undefined ? String(val.old) : 'NULL',
          newValue: val?.new !== null && val?.new !== undefined ? String(val.new) : 'NULL'
        })
      );
      return { kind: 'changes', changes };
    }
    return { kind: 'json', json: JSON.stringify(data, null, 2) };
  } catch {
    return { kind: 'raw', raw: detailsStr };
  }
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
