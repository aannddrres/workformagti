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
 * Magic bytes, for every type the upload endpoint accepts (ASVS V5.2.2,
 * pinned by UploadAllowlistTest). PNG, JPEG, GIF, WebP, PDF, the ZIP-container
 * Office formats (docx/xlsx), the OLE2 container of the legacy ones (doc/xls)
 * and the ISO media {@code ftyp} box of MP4.
 *
 * <p>Two limits, stated so the check is not read as more than it is. doc and
 * xls share the OLE2 signature, so the bytes prove the container and the
 * declared type says which of the two it is; telling them apart would mean
 * parsing the container, which this class deliberately never does. And text
 * has no signature at all, so for {@code text/plain} the check is that the
 * bytes are text: no NUL, which executables, images and archives carry near
 * their start and no 8-bit text encoding produces. The encoding itself is not
 * policed, so an old-codepage .txt still uploads.
 *
 * <p>{@link Result#UNVERIFIABLE} remains for a type outside that list; the
 * upload endpoint refuses such types before asking.
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
    /** The OLE2 compound file header shared by .doc and .xls. */
    private static final byte[] OLE2 = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};
    private static final byte[] FTYP = {'f', 't', 'y', 'p'};
    private static final byte[] UTF16_LE_BOM = {(byte) 0xFF, (byte) 0xFE};
    private static final byte[] UTF16_BE_BOM = {(byte) 0xFE, (byte) 0xFF};

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
            new Signature("application/msword", List.of(OLE2)),
            new Signature("application/vnd.ms-excel", List.of(OLE2)));

    private FileTypeVerifier() {
    }

    public static Result verify(String declaredContentType, byte[] content) {
        if (declaredContentType == null || content == null) {
            return Result.UNVERIFIABLE;
        }
        String normalized = declaredContentType.trim().toLowerCase(Locale.ROOT);
        if ("video/mp4".equals(normalized)) {
            // The box size in front of "ftyp" varies; the name does not.
            return hasAt(content, 4, FTYP) ? Result.MATCHES : Result.MISMATCH;
        }
        if ("text/plain".equals(normalized)) {
            return isText(content) ? Result.MATCHES : Result.MISMATCH;
        }

        for (Signature signature : SIGNATURES) {
            if (!signature.contentType().equals(normalized)) {
                continue;
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

    /** Text as opposed to binary: UTF-16 announces itself with a BOM; anything else may carry no NUL. */
    private static boolean isText(byte[] content) {
        if (startsWith(content, UTF16_LE_BOM) || startsWith(content, UTF16_BE_BOM)) {
            return true;
        }
        for (byte b : content) {
            if (b == 0) {
                return false;
            }
        }
        return true;
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
