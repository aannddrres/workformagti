package ge.magti.portal.web;

import ge.magti.portal.storage.FileTypeVerifier;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * ASVS V5.2.2: every type the upload endpoint accepts is checked against the
 * bytes. Read from UploadController's own allowlist, so a type added there
 * without a content check fails here rather than being accepted on its
 * declaration alone.
 */
class UploadAllowlistTest {

    @SuppressWarnings("unchecked")
    private static Map<String, String> allowlist() throws ReflectiveOperationException {
        Field field = UploadController.class.getDeclaredField("ALLOWED_UPLOAD_TYPES");
        field.setAccessible(true);
        return (Map<String, String>) field.get(null);
    }

    @Test
    void everyAcceptedTypeIsCheckedAgainstItsContent() throws ReflectiveOperationException {
        // Binary with no known signature: a NUL early, nothing any format starts with.
        byte[] garbage = {0x01, 0x00, 0x7F, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A};
        Map<String, String> accepted = allowlist();

        assertFalse(accepted.isEmpty());
        for (String type : accepted.keySet()) {
            assertEquals(FileTypeVerifier.Result.MISMATCH, FileTypeVerifier.verify(type, garbage), type);
        }
    }
}
