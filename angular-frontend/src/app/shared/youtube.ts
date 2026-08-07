/**
 * Port of _toYouTubeEmbed's id-extraction (app-renderers.js:596-638) --
 * handles a bare 11-char id, /embed/ID, youtu.be/ID, youtube.com?v=ID and
 * /shorts/ID. Refactored into a single id-extraction function that both
 * the embed URL and the thumbnail URL derive from, instead of duplicating
 * the same parsing twice (the original repeats it once in _toYouTubeEmbed
 * and again inline in fetchAndRenderVideos, frontend_api.js:1340-1366).
 */
export function extractYoutubeId(url: string | null | undefined): string | null {
  if (!url) {
    return null;
  }
  const trimmed = url.trim();
  if (/^[a-zA-Z0-9_-]{11}$/.test(trimmed)) {
    return trimmed;
  }

  let checkUrl = trimmed;
  if (!/^https?:\/\//i.test(checkUrl)) {
    checkUrl = 'https://' + checkUrl;
  }

  try {
    const u = new URL(checkUrl);
    if (u.pathname.startsWith('/embed/')) {
      const id = u.pathname.split('/')[2];
      return id && id.length === 11 ? id : null;
    }
    if (u.hostname.endsWith('youtu.be')) {
      const id = u.pathname.replace(/^\//, '').split('/')[0];
      return id && id.length === 11 ? id : null;
    }
    if (u.hostname.includes('youtube.com')) {
      const id = u.searchParams.get('v') || (u.pathname.match(/^\/shorts\/([^/?]+)/) || [])[1];
      return id && id.length === 11 ? id : null;
    }
  } catch {
    // fall through
  }
  return null;
}

export function toYoutubeEmbedUrl(url: string | null | undefined): string | null {
  const id = extractYoutubeId(url);
  return id ? `https://www.youtube.com/embed/${id}?rel=0` : null;
}

export function toYoutubeThumbnailUrl(url: string | null | undefined): string | null {
  const id = extractYoutubeId(url);
  return id ? `https://img.youtube.com/vi/${id}/mqdefault.jpg` : null;
}
