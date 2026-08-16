import { ArticleSummary } from './article';
import { NewsSummary } from './news';
import { VideoInstruction } from './video';

/**
 * Mirrors web.GlobalSearchResponse — the shape returned by
 * GET /api/search/global.
 *
 * Reuses the three existing summary models rather than declaring new ones:
 * the backend builds this response from ArticleSummaryResponse /
 * NewsSummaryResponse / VideoInstructionResponse, the same records the list
 * endpoints return, so a separate "search result" type would be the same
 * fields under a second name and could drift from them.
 */
export interface GlobalSearchResponse {
  articles: ArticleSummary[];
  news: NewsSummary[];
  videos: VideoInstruction[];
}

/** One flattened, render-ready hit — what the palette actually lists. */
export interface SearchHit {
  itemType: 'article' | 'news' | 'video';
  id: number;
  title: string;
  /** Category, department or similar — the one line of context under the title. */
  context: string | null;
}
