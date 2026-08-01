package ge.magti.portal.article;

/** The optional q/category_id/status query params of GET /api/articles. */
public record ArticleListFilter(String q, Long categoryId, String status) {
}
