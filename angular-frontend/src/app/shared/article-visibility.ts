/** Keeps operator-facing surfaces free of drafts and archived material even
 * when the signed-in person also has content-administration privileges. */
export function isReaderVisibleArticle(article: {
  status: string;
  published_at: string | null;
  is_draft: boolean;
}): boolean {
  if (article.is_draft) {
    return false;
  }
  if (article.status === 'published') {
    return true;
  }
  return (
    article.status === 'scheduled' &&
    article.published_at != null &&
    new Date(article.published_at).getTime() <= Date.now()
  );
}
