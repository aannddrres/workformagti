package ge.magti.portal.security;

import ge.magti.portal.config.PortalProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ASVS V9 (self-contained tokens), against the service that mints and reads
 * the portal's own session token. Each refusal below is a token an attacker
 * could hand the filter: signed with another key, edited after signing,
 * unsigned, signed asymmetrically, carrying its own key, expired or not yet
 * valid. {@link JwtService#parseAndValidate} must answer every one of them
 * with "no claims", and the filter then treats the request as anonymous
 * (JwtAuthenticationFilterTest).
 */
class JwtServiceTest {

    private static final String SECRET = "jwt-service-test-secret-at-least-thirty-two-bytes";
    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();

    private JwtService service;

    @BeforeEach
    void setUp() {
        PortalProperties properties = new PortalProperties();
        properties.getSecurity().getJwt().setSecret(SECRET);
        service = new JwtService(properties);
    }

    private static String segment(String json) {
        return URL.encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aTokenThisServiceMintedIsAccepted() {
        String token = service.createAccessToken(Map.of("sub", "operator@example.ge", "role", "operator"));

        assertEquals("operator@example.ge", service.parseAndValidate(token).orElseThrow().getSubject());
    }

    @Test
    void aTokenSignedWithAnotherKeyIsRejected() {
        String forged = Jwts.builder()
                .subject("admin@example.ge")
                .claim("role", "system_admin")
                .expiration(Date.from(Instant.now().plus(Duration.ofHours(1))))
                .signWith(Keys.hmacShaKeyFor("someone-elses-secret-also-thirty-two-bytes".getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertTrue(service.parseAndValidate(forged).isEmpty());
    }

    @Test
    void aTamperedPayloadIsRejected() {
        String[] parts = service.createAccessToken(Map.of("sub", "operator@example.ge", "role", "operator")).split("\\.");
        String promoted = parts[0] + "." + segment("{\"sub\":\"operator@example.ge\",\"role\":\"system_admin\"}") + "." + parts[2];

        assertTrue(service.parseAndValidate(promoted).isEmpty());
    }

    /** alg "none": a token with no signature at all must never be read as signed. */
    @Test
    void anUnsignedTokenIsRejected() {
        String unsigned = segment("{\"alg\":\"none\"}") + "." + segment("{\"sub\":\"admin@example.ge\",\"role\":\"system_admin\"}") + ".";

        assertTrue(service.parseAndValidate(unsigned).isEmpty());
    }

    @Test
    void aTokenSignedWithAnAsymmetricAlgorithmIsRejected() {
        KeyPair attacker = Jwts.SIG.RS256.keyPair().build();
        String rsa = Jwts.builder()
                .subject("admin@example.ge")
                .expiration(Date.from(Instant.now().plus(Duration.ofHours(1))))
                .signWith(attacker.getPrivate())
                .compact();

        assertTrue(service.parseAndValidate(rsa).isEmpty());
    }

    /**
     * jku/jwk headers name a key; the verifier must use only its configured
     * one. Built by hand, as an attacker would: jjwt's own builder refuses
     * to embed a secret key.
     */
    @Test
    void aTokenCarryingItsOwnKeyIsRejected() throws Exception {
        byte[] attackerSecret = "attacker-chosen-hmac-secret-thirty-two-bytes!".getBytes(StandardCharsets.UTF_8);
        String signed = segment("{\"alg\":\"HS256\",\"jku\":\"https://attacker.example/jwks.json\","
                + "\"jwk\":{\"kty\":\"oct\",\"k\":\"" + URL.encodeToString(attackerSecret) + "\"}}")
                + "." + segment("{\"sub\":\"admin@example.ge\",\"role\":\"system_admin\",\"exp\":"
                + Instant.now().plus(Duration.ofHours(1)).getEpochSecond() + "}");
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(attackerSecret, "HmacSHA256"));
        String selfKeyed = signed + "." + URL.encodeToString(mac.doFinal(signed.getBytes(StandardCharsets.US_ASCII)));

        assertTrue(service.parseAndValidate(selfKeyed).isEmpty());
    }

    @Test
    void anExpiredTokenIsRejected() {
        String expired = service.createAccessToken(Map.of("sub", "operator@example.ge"), Duration.ofSeconds(-60));

        assertTrue(service.parseAndValidate(expired).isEmpty());
    }

    @Test
    void aTokenNotYetValidIsRejected() {
        String early = Jwts.builder()
                .subject("operator@example.ge")
                .notBefore(Date.from(Instant.now().plus(Duration.ofHours(1))))
                .expiration(Date.from(Instant.now().plus(Duration.ofHours(2))))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertTrue(service.parseAndValidate(early).isEmpty());
    }
}
