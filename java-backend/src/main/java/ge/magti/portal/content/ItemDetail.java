package ge.magti.portal.content;

import java.time.OffsetDateTime;

/**
 * The (title, content) pair the reading list resolves per referenced item.
 * {@code content} is empty for article/news
 * and the video URL for a video -- the operator's reading list surfaces the
 * link inline for videos, the full body is fetched separately for the other
 * two.
 *
 * <p>{@code updatedAt} is when the item itself last changed, and exists so
 * the reading list can tell an operator that material they already
 * acknowledged has been edited since. It is null for news and videos, which
 * carry no update timestamp -- only {@code created_at}. That is not a gap
 * worth closing speculatively: every required reading in the system points
 * at an article, and adding the column to two more tables to serve a case
 * nobody creates would be work with no reader.
 */
public record ItemDetail(String title, String content, OffsetDateTime updatedAt) {
}
