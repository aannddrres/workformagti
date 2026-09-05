package ge.magti.portal.util;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Fixed UTC+4 offset, matching database.py's get_tbilisi_time()
 * (database.py:69-71: {@code timezone(timedelta(hours=4))}) exactly -- a
 * hardcoded offset, not the Asia/Tbilisi IANA zone, so no DST rule applies
 * (moot in practice: Georgia has observed none since 2017, but a real zone
 * would still behave differently from this if that ever changed).
 *
 * <p>Decided 2026-07-29: the Java port keeps storing Tbilisi time rather
 * than converting to UTC. The one change from the Python side is that the
 * zone is now explicit in the type ({@link OffsetDateTime}) instead of a
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
     * Mirrors database.py's format_tbilisi_date (database.py:74-75)
     * exactly, including its literal backslash date separators --
     * verified with a real Python run, not assumed from reading the
     * source: {@code "%d\%m\%Y %H:%M"} is not a valid Python escape
     * sequence, so the backslashes survive literally into the format
     * string, producing e.g. {@code "01\08\2026 14:30"}. Almost
     * certainly an unintentional typo for {@code "%d/%m/%Y"} somewhere
     * in this codebase's past, but it is the real, live, verified output
     * today -- ported as-is (flagged as a finding for a decision, not
     * silently changed either way) rather than "corrected" unasked. Used
     * only by the two admin list endpoints (read-receipts, views) that
     * explicitly call format_tbilisi_date in Python; the current-user
     * endpoints return raw datetimes with no such formatting.
     */
    public static String format(OffsetDateTime dt) {
        if (dt == null) {
            return null;
        }
        return "%02d\\%02d\\%04d %02d:%02d".formatted(
                dt.getDayOfMonth(), dt.getMonthValue(), dt.getYear(), dt.getHour(), dt.getMinute());
    }
}
