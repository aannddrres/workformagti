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
 * this class was written to flag (docs/JAVA_ORACLE_ANGULAR_MIGRATION.md
 * finding #17): code that mishandles an unmarked timestamp as UTC.
 */
public final class TbilisiTime {

    public static final ZoneOffset OFFSET = ZoneOffset.ofHours(4);

    private TbilisiTime() {
    }

    public static OffsetDateTime now() {
        return OffsetDateTime.now(OFFSET);
    }
}
