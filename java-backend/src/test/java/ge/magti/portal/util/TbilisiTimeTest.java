package ge.magti.portal.util;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TbilisiTimeTest {

    @Test
    void offsetIsFixedPlusFourHours() {
        assertEquals(ZoneOffset.ofHours(4), TbilisiTime.OFFSET);
    }

    @Test
    void nowCarriesTheTbilisiOffsetExplicitly() {
        OffsetDateTime now = TbilisiTime.now();

        assertEquals(ZoneOffset.ofHours(4), now.getOffset());
    }
}
