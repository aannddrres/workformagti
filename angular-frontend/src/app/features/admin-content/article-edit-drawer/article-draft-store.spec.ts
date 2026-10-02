import { afterEach, describe, expect, it, vi } from 'vitest';
import { DRAFT_MAX_AGE_MS, clearDraft, draftKey, readDraft, writeDraft } from './article-draft-store';

describe('article draft store', () => {
  afterEach(() => {
    localStorage.clear();
    vi.restoreAllMocks();
  });

  it('keeps one person\'s draft apart from another\'s on a shared workstation', () => {
    writeDraft(draftKey('Nino@Magti.ge', null), { title: 'ა', content: '<p>ნინოს ტექსტი</p>', savedAt: 1000 });
    expect(readDraft(draftKey('nino@magti.ge', null), 2000)?.content).toBe('<p>ნინოს ტექსტი</p>');
    expect(readDraft(draftKey('tech@magti.ge', null), 2000)).toBeNull();
    expect(readDraft(draftKey('nino@magti.ge', 7), 2000)).toBeNull();
  });

  it('drops a draft older than a week', () => {
    const key = draftKey('nino@magti.ge', 7);
    writeDraft(key, { title: '', content: '<p>x</p>', savedAt: 0 });
    expect(readDraft(key, DRAFT_MAX_AGE_MS + 1)).toBeNull();
    expect(localStorage.getItem(key)).toBeNull();
  });

  it('forgets a draft on request and ignores garbage', () => {
    const key = draftKey('nino@magti.ge', 3);
    writeDraft(key, { title: 't', content: 'c', savedAt: 5 });
    clearDraft(key);
    expect(readDraft(key, 6)).toBeNull();
    localStorage.setItem(key, '{not json');
    expect(readDraft(key, 6)).toBeNull();
  });

  it('never breaks the editor when storage refuses', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('quota', 'QuotaExceededError');
    });
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new DOMException('blocked', 'SecurityError');
    });
    expect(() => writeDraft('k', { title: '', content: '', savedAt: 0 })).not.toThrow();
    expect(readDraft('k')).toBeNull();
  });
});
