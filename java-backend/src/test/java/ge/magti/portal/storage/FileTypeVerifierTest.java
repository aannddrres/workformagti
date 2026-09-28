package ge.magti.portal.storage;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * SEC-09. The MIME allowlist only ever knew what the client SAID a file was,
 * despite a javadoc claiming server-side detection. These tests pin both the
 * detection and -- just as importantly -- the honesty about what it cannot
 * detect.
 */
class FileTypeVerifierTest {

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    private static final byte[] REAL_PNG = bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x01);
    private static final byte[] HTML = "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8);

    /** The attack the old javadoc claimed to prevent, and did not. */
    @Test
    void htmlDeclaredAsAPngIsRejected() {
        assertEquals(FileTypeVerifier.Result.MISMATCH, FileTypeVerifier.verify("image/png", HTML));
    }

    @Test
    void svgDeclaredAsAJpegIsRejected() {
        byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script/></svg>".getBytes(StandardCharsets.UTF_8);

        assertEquals(FileTypeVerifier.Result.MISMATCH, FileTypeVerifier.verify("image/jpeg", svg));
    }

    @Test
    void aRealPngPasses() {
        assertEquals(FileTypeVerifier.Result.MATCHES, FileTypeVerifier.verify("image/png", REAL_PNG));
    }

    @Test
    void aRealPdfPasses() {
        assertEquals(FileTypeVerifier.Result.MATCHES,
                FileTypeVerifier.verify("application/pdf", "%PDF-1.7\nrest".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void bothGifVersionsPass() {
        assertEquals(FileTypeVerifier.Result.MATCHES,
                FileTypeVerifier.verify("image/gif", "GIF87a...".getBytes(StandardCharsets.UTF_8)));
        assertEquals(FileTypeVerifier.Result.MATCHES,
                FileTypeVerifier.verify("image/gif", "GIF89a...".getBytes(StandardCharsets.UTF_8)));
    }

    /** docx/xlsx are ZIP containers, so the ZIP signature is what there is to check. */
    @Test
    void anOfficeOpenXmlFileIsCheckedAsAZipContainer() {
        byte[] zip = bytes('P', 'K', 0x03, 0x04, 0x14, 0x00);

        assertEquals(FileTypeVerifier.Result.MATCHES, FileTypeVerifier.verify(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", zip));
        assertEquals(FileTypeVerifier.Result.MISMATCH, FileTypeVerifier.verify(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", HTML));
    }

    /**
     * RIFF alone is also .wav and .avi, so accepting it for webp would be a
     * check that looks stricter than it is.
     */
    @Test
    void aRiffFileThatIsNotWebpIsRejected() {
        byte[] wav = bytes('R', 'I', 'F', 'F', 0x24, 0x08, 0x00, 0x00, 'W', 'A', 'V', 'E');

        assertEquals(FileTypeVerifier.Result.MISMATCH, FileTypeVerifier.verify("image/webp", wav));
    }

    @Test
    void arealWebpPasses() {
        byte[] webp = bytes('R', 'I', 'F', 'F', 0x24, 0x08, 0x00, 0x00, 'W', 'E', 'B', 'P');

        assertEquals(FileTypeVerifier.Result.MATCHES, FileTypeVerifier.verify("image/webp", webp));
    }

    /**
     * The honesty half. Plain text has no signature by definition, and the
     * legacy OLE2 Word/Excel formats share one with each other, so these
     * report UNVERIFIABLE rather than a reassuring MATCHES.
     */
    @Test
    void theLegacyOfficeFormatsMustBeOle2Containers() {
        byte[] ole2 = bytes(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1, 0, 0, 0, 0);
        for (String type : new String[] {"application/msword", "application/vnd.ms-excel"}) {
            assertEquals(FileTypeVerifier.Result.MISMATCH, FileTypeVerifier.verify(type, HTML), type);
            // doc and xls share the container; the declared type says which.
            assertEquals(FileTypeVerifier.Result.MATCHES, FileTypeVerifier.verify(type, ole2), type);
        }
    }

    @Test
    void anMp4MustCarryItsFtypBox() {
        assertEquals(FileTypeVerifier.Result.MISMATCH, FileTypeVerifier.verify("video/mp4", HTML));
        assertEquals(FileTypeVerifier.Result.MATCHES,
                FileTypeVerifier.verify("video/mp4", bytes(0, 0, 0, 0x20, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm')));
    }

    /**
     * Text has no signature, so the check is that it is text: no NUL byte,
     * which every executable, image and archive has near its start and no
     * 8-bit text encoding produces. The encoding itself is not policed, so an
     * old-codepage .txt still uploads. Markup declared as text/plain is
     * served as text/plain with nosniff, which a browser shows as text.
     */
    @Test
    void plainTextMustBeText() {
        assertEquals(FileTypeVerifier.Result.MATCHES,
                FileTypeVerifier.verify("text/plain", "ქართული ტექსტი".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals(FileTypeVerifier.Result.MATCHES, FileTypeVerifier.verify("text/plain", HTML));
        assertEquals(FileTypeVerifier.Result.MATCHES,
                FileTypeVerifier.verify("text/plain", bytes(0xFF, 0xFE, 'a', 0, 'b', 0)), "UTF-16 with its BOM");
        assertEquals(FileTypeVerifier.Result.MISMATCH, FileTypeVerifier.verify("text/plain", bytes('M', 'Z', 0x90, 0, 3, 0)));
        assertEquals(FileTypeVerifier.Result.MISMATCH, FileTypeVerifier.verify("text/plain", bytes('a', 'b', 0, 'c')));
    }

    /** A truncated file must not read past the end of the array. */
    @Test
    void contentShorterThanTheSignatureIsAMismatchNotACrash() {
        assertEquals(FileTypeVerifier.Result.MISMATCH, FileTypeVerifier.verify("image/png", bytes(0x89, 'P')));
        assertEquals(FileTypeVerifier.Result.MISMATCH, FileTypeVerifier.verify("image/png", new byte[0]));
    }

    @Test
    void nullsAreUnverifiableRatherThanThrowing() {
        assertEquals(FileTypeVerifier.Result.UNVERIFIABLE, FileTypeVerifier.verify(null, REAL_PNG));
        assertEquals(FileTypeVerifier.Result.UNVERIFIABLE, FileTypeVerifier.verify("image/png", null));
    }

    /** An unknown type never reaches here (the allowlist runs first), but must not be silently trusted-as-matching either. */
    @Test
    void anUnknownContentTypeIsUnverifiable() {
        assertEquals(FileTypeVerifier.Result.UNVERIFIABLE, FileTypeVerifier.verify("application/x-made-up", REAL_PNG));
    }
}
