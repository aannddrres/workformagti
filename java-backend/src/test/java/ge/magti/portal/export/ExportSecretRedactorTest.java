package ge.magti.portal.export;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExportSecretRedactorTest {

    @Test
    void recursivelyRedactsCredentialFieldsButKeepsIntegrityHashes() {
        String redacted = ExportSecretRedactor.redactJson("""
                {"reason":"ok","new_password":"p","nested":{"access_token":"t","row_hash":"allowed"},
                 "items":[{"privateKey":"k"}]}
                """);

        assertTrue(redacted.contains("\"reason\":\"ok\""));
        assertTrue(redacted.contains("\"row_hash\":\"allowed\""));
        assertFalse(redacted.contains("\"p\""));
        assertFalse(redacted.contains("\"t\""));
        assertFalse(redacted.contains("\"k\""));
        assertEquals(3, occurrences(redacted, ExportSecretRedactor.REDACTED));
    }

    @Test
    void malformedDetailsFailClosed() {
        assertEquals(ExportSecretRedactor.UNSTRUCTURED,
                ExportSecretRedactor.redactJson("password=should-never-leave"));
    }

    private static int occurrences(String value, String needle) {
        return (value.length() - value.replace(needle, "").length()) / needle.length();
    }
}
