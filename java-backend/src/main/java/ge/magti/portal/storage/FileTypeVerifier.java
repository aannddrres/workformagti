package ge.magti.portal.storage;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Checks that an upload's bytes agree with the type it claims to be
 * (audit SEC-09).
 *
 * <h2>Why this exists</h2>
 *
 * {@code UploadController}'s javadoc stated that "the stored extension is
 * derived from the server-detected content_type, never the client-supplied
 * filename", and explained the attack it prevents: a spoofed filename
 * smuggling .html/.svg/.php for stored XSS. The filename half was true. The
 * "server-detected" half was not -- {@code MultipartFile.getContentType()}
 * returns the {@code Content-Type} header <b>the client wrote in the
 * multipart part</b>. Nothing inspected the bytes.
 *
 * <p>So the allowlist could be walked straight past: send HTML with
 * {@code Content-Type: image/png}, get {@code <uuid>.png} back, and the file
 * is served from {@code /uploads/**} -- a public path -- with
 * {@code Content-Type: image/png} taken from that same declared value.
 *
 * <p>Browsers will not render {@code image/png} as HTML, and
 * {@code UploadedFileController} sends {@code X-Content-Type-Options: nosniff}
 * as well, so this is a defence in depth rather than a live XSS. What it
 * really fixes is the gap between what the code claimed and what it did --
 * the exact class of thing this audit kept finding.
 *
 * <h2>What it verifies, and what it cannot</h2>
 *
 * Magic bytes only, for the formats that have them. That covers PNG, JPEG,
 * GIF, WebP, PDF and the ZIP-container Office formats (docx/xlsx). It
 * deliberately does <b>not</b> try to validate {@code text/plain} or
 * {@code application/msword}/{@code application/vnd.ms-excel}: plain text has
 * no signature by definition, and the legacy OLE2 formats share one
 * signature with each other. Claiming to check those would be the same kind
 * of overstatement this class exists to remove, so
 * {@link #verify(String, byte[])} returns {@link Result#UNVERIFIABLE} and
 * says so.
 */
public final class FileTypeVerifier {

    /** Outcome of a check, kept explicit so "cannot tell" is never silently read as "fine". */
    public enum Result {
        MATCHES,
        /** The declared type has a known signature and the bytes do not carry it. */
        MISMATCH,
        /** No signature exists for this type -- the declaration is accepted, knowingly. */
        UNVERIFIABLE
    }

    private record Signature(String contentType, List<byte[]> magic) {
    }

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] GIF87 = {'G', 'I', 'F', '8', '7', 'a'};
    private static final byte[] GIF89 = {'G', 'I', 'F', '8', '9', 'a'};
    private static final byte[] RIFF = {'R', 'I', 'F', 'F'};
    private static final byte[] PDF = {'%', 'P', 'D', 'F', '-'};
    /** docx/xlsx are ZIP containers. "PK\003\004" is a normal archive; the other two are empty/spanned variants. */
    private static final byte[] ZIP = {'P', 'K', 0x03, 0x04};
    private static final byte[] ZIP_EMPTY = {'P', 'K', 0x05, 0x06};
    private static final byte[] ZIP_SPANNED = {'P', 'K', 0x07, 0x08};

    private static final List<Signature> SIGNATURES = List.of(
            new Signature("image/png", List.of(PNG)),
            new Signature("image/jpeg", List.of(JPEG)),
            new Signature("image/gif", List.of(GIF87, GIF89)),
            new Signature("image/webp", List.of(RIFF)),
            new Signature("application/pdf", List.of(PDF)),
            new Signature("application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    List.of(ZIP, ZIP_EMPTY, ZIP_SPANNED)),
            new Signature("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    List.of(ZIP, ZIP_EMPTY, ZIP_SPANNED)),
            new Signature("video/mp4", List.of()));

    private FileTypeVerifier() {
    }

    public static Result verify(String declaredContentType, byte[] content) {
        if (declaredContentType == null || content == null) {
            return Result.UNVERIFIABLE;
        }
        String normalized = declaredContentType.trim().toLowerCase(Locale.ROOT);

        for (Signature signature : SIGNATURES) {
            if (!signature.contentType().equals(normalized)) {
                continue;
            }
            if (signature.magic().isEmpty()) {
                // mp4's signature sits at byte 4 ("ftyp") with a
                // brand-dependent prefix; not worth a half-right check.
                return Result.UNVERIFIABLE;
            }
            boolean matches = signature.magic().stream().anyMatch(m -> startsWith(content, m));
            if (!matches) {
                return Result.MISMATCH;
            }
            // webp is RIFF + "WEBP" at offset 8; RIFF alone is also .wav/.avi.
            if ("image/webp".equals(normalized) && !hasAt(content, 8, new byte[]{'W', 'E', 'B', 'P'})) {
                return Result.MISMATCH;
            }
            return Result.MATCHES;
        }
        return Result.UNVERIFIABLE;
    }

    private static boolean startsWith(byte[] content, byte[] prefix) {
        return hasAt(content, 0, prefix);
    }

    private static boolean hasAt(byte[] content, int offset, byte[] expected) {
        if (content.length < offset + expected.length) {
            return false;
        }
        return Arrays.equals(content, offset, offset + expected.length, expected, 0, expected.length);
    }
}
