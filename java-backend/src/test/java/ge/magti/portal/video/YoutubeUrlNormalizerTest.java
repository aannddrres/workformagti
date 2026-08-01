package ge.magti.portal.video;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class YoutubeUrlNormalizerTest {

    private static final String EXPECTED = "https://www.youtube.com/embed/dQw4w9WgXcQ?rel=0";

    @Test
    void standardWatchUrl() {
        assertEquals(EXPECTED, YoutubeUrlNormalizer.normalize(
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ"));
    }

    @Test
    void watchUrlWithExtraQueryParamsBeforeV() {
        assertEquals(EXPECTED, YoutubeUrlNormalizer.normalize(
                "https://www.youtube.com/watch?list=PL123&v=dQw4w9WgXcQ&t=30s"));
    }

    @Test
    void shortYoutuBeLink() {
        assertEquals(EXPECTED, YoutubeUrlNormalizer.normalize("https://youtu.be/dQw4w9WgXcQ"));
    }

    @Test
    void alreadyAnEmbedUrl() {
        assertEquals(EXPECTED, YoutubeUrlNormalizer.normalize(
                "https://www.youtube.com/embed/dQw4w9WgXcQ"));
    }

    @Test
    void nocookieEmbedVariant() {
        assertEquals(EXPECTED, YoutubeUrlNormalizer.normalize(
                "https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ"));
    }

    @Test
    void bareElevenCharacterVideoId() {
        assertEquals(EXPECTED, YoutubeUrlNormalizer.normalize("dQw4w9WgXcQ"));
    }

    @Test
    void caseInsensitiveDomain() {
        assertEquals(EXPECTED, YoutubeUrlNormalizer.normalize(
                "https://WWW.YOUTUBE.COM/watch?v=dQw4w9WgXcQ"));
    }

    @Test
    void nonYoutubeUrlPassesThroughUnchanged() {
        String other = "https://vimeo.com/12345678";
        assertEquals(other, YoutubeUrlNormalizer.normalize(other));
    }

    @Test
    void tenCharacterStringIsNotTreatedAsABareId() {
        String tooShort = "dQw4w9WgXc";
        assertEquals(tooShort, YoutubeUrlNormalizer.normalize(tooShort));
    }

    @Test
    void nullPassesThroughAsNull() {
        assertNull(YoutubeUrlNormalizer.normalize(null));
    }

    @Test
    void emptyStringPassesThroughAsEmpty() {
        assertEquals("", YoutubeUrlNormalizer.normalize(""));
    }

    @Test
    void leadingAndTrailingWhitespaceIsStripped() {
        assertEquals(EXPECTED, YoutubeUrlNormalizer.normalize("  dQw4w9WgXcQ  "));
    }
}
