package ge.magti.portal.docs;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiContractCredentialHygieneTest {
    private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");

    @Test
    void goldenMasterIsValidJsonWithoutSignedTokenExamples() throws Exception {
        String json = Files.readString(RepoRoot.path("docs/api-contract/golden_master_v1.json"));
        assertTrue(JsonMapper.builder().build().readTree(json).isArray(), "contract must remain a JSON array");
        // Report counts, never captured credentials or the containing JSON.
        assertEquals(0, JWT.matcher(json).results().count(), "contract contains JWT-shaped credentials");
    }

    @Test
    void detectsSyntheticTokenButAcceptsExplicitNonCredentialMarker() {
        var encoder = Base64.getUrlEncoder().withoutPadding();
        String header = encoder.encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = encoder.encodeToString("{\"sub\":\"fixture-only\"}".getBytes(StandardCharsets.UTF_8));
        String token = header + "." + payload + "." + encoder.encodeToString(new byte[32]);
        assertTrue(JWT.matcher(token).find(), "synthetic JWT must be detected");
        assertFalse(JWT.matcher("NOT_A_CREDENTIAL_API_EXAMPLE").find());
    }
}
