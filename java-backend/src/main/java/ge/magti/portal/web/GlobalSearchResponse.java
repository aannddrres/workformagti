package ge.magti.portal.web;

import java.util.List;

/** The /api/search/global response shape. */
public record GlobalSearchResponse(
        List<ArticleSummaryResponse> articles,
        List<NewsSummaryResponse> news,
        List<VideoInstructionResponse> videos) {
}
