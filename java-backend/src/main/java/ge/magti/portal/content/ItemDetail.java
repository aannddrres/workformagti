package ge.magti.portal.content;

/**
 * The (title, content) pair get_my_readings resolves per referenced item
 * (routers/compliance.py:68-85). {@code content} is empty for article/news
 * and the video URL for a video -- the operator's reading list surfaces the
 * link inline for videos, the full body is fetched separately for the other
 * two.
 */
public record ItemDetail(String title, String content) {
}
