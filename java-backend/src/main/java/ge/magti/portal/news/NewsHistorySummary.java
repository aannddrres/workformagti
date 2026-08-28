package ge.magti.portal.news;

import java.time.OffsetDateTime;

/** CLOB-free news revision metadata for history list screens. */
public record NewsHistorySummary(
        Long id,
        String title,
        String attachmentUrl,
        OffsetDateTime updatedAt,
        Long updatedBy
) {
}
