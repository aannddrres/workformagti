package ge.magti.portal.web;

import java.util.List;

/** Mirrors schemas.py's GlobalSearchResponse -- the /api/search/global response shape. */
public record GlobalSearchResponse(
        List<ArticleSummaryResponse> articles,
        List<NewsSummaryResponse> news,
        List<VideoInstructionResponse> videos) {
}
