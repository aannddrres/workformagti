package ge.magti.portal.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Fail-closed redaction for the free-form JSON details column in audit rows. */
public final class ExportSecretRedactor {

    static final String REDACTED = "[დაფარული]";
    static final String UNSTRUCTURED = "[დაფარული: არასტრუქტურირებული audit details]";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> SECRET_KEYS = Set.of(
            "password", "passwordhash", "hashedpassword", "databasepassword",
            "accesstoken", "refreshtoken", "token", "session", "sessionid",
            "credential", "credentials", "privatekey", "secret", "authorization", "cookie");

    private ExportSecretRedactor() {
    }

    public static String redactJson(String raw) {
        if (raw == null || raw.isBlank()) return raw;
        try {
            JsonNode copy = MAPPER.readTree(raw).deepCopy();
            redact(copy);
            return MAPPER.writeValueAsString(copy);
        } catch (Exception ignored) {
            return UNSTRUCTURED;
        }
    }

    private static void redact(JsonNode node) {
        if (node instanceof ObjectNode object) {
            Iterator<Map.Entry<String, JsonNode>> fields = object.properties().iterator();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (isSecretKey(field.getKey())) {
                    object.set(field.getKey(), TextNode.valueOf(REDACTED));
                } else {
                    redact(field.getValue());
                }
            }
        } else if (node instanceof ArrayNode array) {
            array.forEach(ExportSecretRedactor::redact);
        }
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static boolean isSecretKey(String key) {
        String normalized = normalize(key);
        return SECRET_KEYS.contains(normalized)
                || normalized.endsWith("password")
                || normalized.endsWith("passwordhash")
                || normalized.endsWith("token")
                || normalized.endsWith("secret")
                || normalized.endsWith("privatekey")
                || normalized.endsWith("credential")
                || normalized.equals("apikey")
                || normalized.equals("jwt")
                || normalized.equals("bearer");
    }
}
