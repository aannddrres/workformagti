package ge.magti.portal.news;

import ge.magti.portal.util.TbilisiTime;

import java.time.OffsetDateTime;

/**
 * CLOB-free projection for the news list endpoint.
 *
 * <p>{@code News.content} is intentionally absent: list responses never
 * expose it, so materializing the full entity needlessly transferred every
 * selected CLOB from Oracle before discarding it during response mapping.
 */
public record NewsListItem(
        Long id,
        String title,
        String targetDepartment,
        String attachmentUrl,
        OffsetDateTime createdAt,
        int version,
        boolean visibleToTechInfo,
        boolean visibleToServiceCenter,
        OffsetDateTime expiresAt,
        boolean draft,
        Long authorId
) {
    public boolean archived() {
        return expiresAt != null && expiresAt.isBefore(TbilisiTime.now());
    }
}
