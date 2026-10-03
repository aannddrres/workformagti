package ge.magti.portal.util;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Fixed UTC+4 offset -- a
 * hardcoded offset, not the Asia/Tbilisi IANA zone, so no DST rule applies
 * (moot in practice: Georgia has observed none since 2017, but a real zone
 * would still behave differently from this if that ever changed).
 *
 * <p>Decided 2026-07-29: the Java port keeps storing Tbilisi time rather
 * than converting to UTC. The
 * zone is explicit in the type ({@link OffsetDateTime}) instead of a
 * naive value with the offset silently assumed -- closing the exact risk
 * this class was written to flag (docs/archive/migration/JAVA_ORACLE_ANGULAR_MIGRATION.md
 * finding #17): code that mishandles an unmarked timestamp as UTC.
 */
public final class TbilisiTime {

    public static final ZoneOffset OFFSET = ZoneOffset.ofHours(4);

    private TbilisiTime() {
    }

    public static OffsetDateTime now() {
        return OffsetDateTime.now(OFFSET);
    }

    /**
     * Formats with literal backslash date separators, e.g.
     * {@code "01\08\2026 14:30"}. Almost
     * certainly an unintentional typo for {@code "%d/%m/%Y"} somewhere
     * in this codebase's past, but it is the real, live output
     * today -- kept as-is (flagged as a finding for a decision, not
     * silently changed either way) rather than "corrected" unasked. Used
     * only by the two admin list endpoints (read-receipts, views); the
     * current-user endpoints return raw datetimes with no such formatting.
     */
    public static String format(OffsetDateTime dt) {
        if (dt == null) {
            return null;
        }
        return "%02d\\%02d\\%04d %02d:%02d".formatted(
                dt.getDayOfMonth(), dt.getMonthValue(), dt.getYear(), dt.getHour(), dt.getMinute());
    }
}
