/**
 * Unsaved article text kept in this browser, so a session that ends while
 * someone is writing does not take the text with it (owner, 2026-10-02).
 *
 * QA round 4 found the loss: an editor left open for 30 minutes, or past the
 * 8-hour session limit, met the sign-in screen on "save", and after signing
 * in again the drawer was gone and the text with it. Autosave to the server
 * was deliberately not ported (article-edit-drawer.ts), and could not have
 * helped -- the session it needs is the thing that ended.
 *
 * Only the title and the body are kept: they are the work; the rest of the
 * form is a few clicks. Keyed by the signed-in address so a shared
 * call-centre workstation never offers one person's draft to another, and
 * dropped after a week. Storage can be missing or refuse a write (private
 * window, full quota, blocked site data), so every access is guarded and a
 * failure only means no draft is kept -- never a broken editor.
 */
export interface ArticleDraft {
  title: string;
  content: string;
  savedAt: number;
}

const PREFIX = 'magti_article_draft:';
export const DRAFT_MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000;

export function draftKey(email: string, articleId: number | null): string {
  return `${PREFIX}${email.trim().toLowerCase()}:${articleId ?? 'new'}`;
}

export function readDraft(key: string, now = Date.now()): ArticleDraft | null {
  try {
    const raw = localStorage.getItem(key);
    if (!raw) return null;
    const draft = JSON.parse(raw) as ArticleDraft;
    if (typeof draft?.content !== 'string' || typeof draft.savedAt !== 'number' || now - draft.savedAt > DRAFT_MAX_AGE_MS) {
      localStorage.removeItem(key);
      return null;
    }
    return { title: typeof draft.title === 'string' ? draft.title : '', content: draft.content, savedAt: draft.savedAt };
  } catch {
    return null;
  }
}

export function writeDraft(key: string, draft: ArticleDraft): void {
  try {
    localStorage.setItem(key, JSON.stringify(draft));
  } catch {
    // Quota or blocked storage: the editor keeps working, only without a safety copy.
  }
}

export function clearDraft(key: string): void {
  try {
    localStorage.removeItem(key);
  } catch {
    // Nothing to clear if storage is unavailable.
  }
}

/**
 * Every draft this person kept, gone: called when they sign out on purpose
 * (owner, 2026-10-03, after QA round 5's ASVS review). A session that ends by
 * itself -- 30 idle minutes, the 8-hour limit -- keeps them, because that is
 * the case PO-50 exists for; choosing to sign out is choosing to leave the
 * desk, and on a shared workstation the next person should find nothing.
 */
export function clearDraftsFor(email: string): void {
  try {
    const prefix = `${PREFIX}${email.trim().toLowerCase()}:`;
    const doomed: string[] = [];
    for (let i = 0; i < localStorage.length; i++) {
      const key = localStorage.key(i);
      if (key?.startsWith(prefix)) doomed.push(key);
    }
    doomed.forEach((key) => localStorage.removeItem(key));
  } catch {
    // No storage, nothing kept.
  }
}
