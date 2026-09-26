package ge.magti.portal.security;

import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Validates the supplied Basic value without rewriting it or retaining decoded credentials. */
public final class CorporateClientCredential {
    private CorporateClientCredential() {
    }

    public static void validate(String credential, String expectedClientId) {
        if (credential.regionMatches(true, 0, "Basic ", 0, 6)) {
            throw invalid("must omit the Basic prefix");
        }
        String decoded;
        try {
            byte[] bytes = Base64.getDecoder().decode(credential);
            decoded = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (IllegalArgumentException | CharacterCodingException e) {
            // Decoder exception messages may include input; never retain the cause.
            throw invalid("must be valid base64 containing a UTF-8 client:secret value");
        }
        int separator = decoded.indexOf(':');
        if (separator <= 0 || separator == decoded.length() - 1) {
            throw invalid("must contain a nonempty client and secret separated by a colon");
        }
        String client;
        String secret;
        try {
            // RFC 6749 section 2.3.1: form encoding is applied before Basic encoding.
            client = URLDecoder.decode(decoded.substring(0, separator), StandardCharsets.UTF_8);
            secret = URLDecoder.decode(decoded.substring(separator + 1), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw invalid("contains invalid form encoding");
        }
        if (client.codePoints().anyMatch(Character::isISOControl)
                || secret.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid("must not contain control characters");
        }
        if (!client.equals(expectedClientId)) {
            throw invalid("client does not match OAUTH_CLIENT_ID");
        }
    }

    private static IllegalStateException invalid(String reason) {
        return new IllegalStateException("OAUTH_SECRET " + reason + ".");
    }
}
