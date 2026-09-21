package ge.magti.portal.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ge.magti.portal.config.PortalProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The one place the portal talks to the company's authorization server:
 * a single form POST to {@code oauth/token} with the {@code ldap_auth} grant.
 *
 * <p>Shaped by the live service, measured 2026-09-21 (docs/QUESTIONS_FOR_IT.md
 * No.13), rather than by IT's sample alone. The sample's {@code GET} would put
 * the password in a URL, where proxies and access logs keep it; POST is what
 * the server accepts. Its answers are sorted into three outcomes, and the
 * sorting is the point of this class:
 *
 * <ul>
 *   <li><b>authenticated</b> -- 200 with a login or an email in it;
 *   <li><b>rejected</b> -- {@code 400 invalid_grant}, the directory's "wrong
 *       login or password". Only this counts against the person;
 *   <li><b>unavailable</b> -- everything else: the load balancer's
 *       {@code 503} HTML page, a timeout, a refused client credential. None
 *       of these says anything about the password, and treating them as a
 *       wrong password would lock people out for an outage.
 * </ul>
 *
 * <p><b>What is never logged:</b> the password, the request body, the
 * client credential and both tokens. A log line carries the HTTP status and
 * the OAuth {@code error} code, which is enough to tell an outage from a
 * misconfiguration.
 *
 * <p><b>Why the token's payload is read without checking its signature:</b>
 * the token is not presented to the portal by a browser; the portal receives
 * it directly from the authorization server, over TLS, in answer to its own
 * client-authenticated request. The channel already proves who issued it. The
 * token itself is then dropped -- see {@link CorporateIdentity}.
 */
@Component
public class CorporateAuthClient {

    private static final Logger logger = LoggerFactory.getLogger(CorporateAuthClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** What the directory's answer amounted to. */
    public sealed interface Outcome permits Authenticated, Rejected, Unavailable {
    }

    public record Authenticated(CorporateIdentity identity) implements Outcome {
    }

    public record Rejected() implements Outcome {
    }

    /** @param reason for the log only -- never shown to the person signing in */
    public record Unavailable(String reason) implements Outcome {
    }

    private final PortalProperties.Corporate settings;
    private final RestClient restClient;

    @Autowired
    public CorporateAuthClient(PortalProperties properties) {
        this(properties, RestClient.builder().requestFactory(requestFactory(properties.getSecurity().getCorporate())));
    }

    /** For tests: a builder a mock server can bind to. */
    CorporateAuthClient(PortalProperties properties, RestClient.Builder builder) {
        this.settings = properties.getSecurity().getCorporate();
        this.restClient = builder.build();
    }

    private static JdkClientHttpRequestFactory requestFactory(PortalProperties.Corporate settings) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(settings.getConnectTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(settings.getReadTimeoutSeconds()));
        return factory;
    }

    /** The token endpoint under the configured base, tolerant of a missing trailing slash. */
    String tokenUri() {
        String base = settings.getServiceUri() == null ? "" : settings.getServiceUri().trim();
        return (base.endsWith("/") ? base : base + "/") + "oauth/token";
    }

    /**
     * How the typed address travels as {@code username}. IT's sample sends the
     * full address; the one client measured so far accepted only the login.
     */
    static String usernameFor(String email, String format) {
        if ("login".equalsIgnoreCase(format)) {
            int at = email.indexOf('@');
            return at > 0 ? email.substring(0, at) : email;
        }
        return email;
    }

    public Outcome authenticate(String email, String password) {
        String body = form("grant_type", settings.getGrantType())
                + "&" + form("username", usernameFor(email, settings.getUsernameFormat()))
                + "&" + form("password", password)
                + "&" + form("client_id", settings.getClientId());
        try {
            return restClient.post()
                    .uri(tokenUri())
                    .header(HttpHeaders.AUTHORIZATION, "Basic " + settings.getClientCredential())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .exchange((request, response) -> classify(
                            response.getStatusCode().value(),
                            new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8),
                            email));
        } catch (RestClientException e) {
            logger.warn("Corporate login unreachable: {}", e.getClass().getSimpleName());
            return new Unavailable("unreachable: " + e.getClass().getSimpleName());
        }
    }

    Outcome classify(int status, String body, String typedEmail) {
        JsonNode json = parse(body);
        String error = json == null ? null : text(json, "error");

        if (status == 200 && json != null) {
            CorporateIdentity identity = identityFrom(json, typedEmail);
            if (identity == null) {
                logger.error("Corporate login answered 200 without a login or an email -- response shape changed?");
                return new Unavailable("unexpected response shape");
            }
            return new Authenticated(identity);
        }
        if (status == 400 && "invalid_grant".equals(error)) {
            return new Rejected();
        }
        if (status == 401 || status == 403) {
            // Our client credential, not the person's password: nobody can
            // sign in until the deployment is fixed, so this one is loud.
            logger.error("Corporate login refused the portal's client credential: HTTP {} {}", status, error);
            return new Unavailable("client refused: " + error);
        }
        logger.warn("Corporate login unavailable: HTTP {}{}", status, error == null ? "" : " " + error);
        return new Unavailable("HTTP " + status);
    }

    private CorporateIdentity identityFrom(JsonNode answer, String typedEmail) {
        JsonNode claims = tokenClaims(text(answer, "access_token"));
        String login = first(answer, claims, "userIdentifier", "user_name");
        String email = first(answer, claims, "email");
        if (login == null && email == null) {
            return null;
        }
        Set<String> authorities = new LinkedHashSet<>();
        collect(answer.get("authorities"), authorities);
        if (claims != null) {
            collect(claims.get("authorities"), authorities);
        }
        return new CorporateIdentity(
                login,
                (email != null ? email : typedEmail).trim().toLowerCase(Locale.ROOT),
                first(answer, claims, "userId"),
                Set.copyOf(authorities),
                first(answer, claims, settings.getDepartmentClaim()),
                first(answer, claims, settings.getNameClaim()));
    }

    /** The JWT payload, or null for an opaque token or none. The token string goes no further. */
    private static JsonNode tokenClaims(String token) {
        if (token == null) {
            return null;
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return null;
        }
        try {
            return JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
        } catch (IllegalArgumentException | IOException e) {
            return null;
        }
    }

    private static String first(JsonNode answer, JsonNode claims, String... names) {
        for (String name : names) {
            if (name == null || name.isBlank()) {
                continue;
            }
            String value = text(answer, name);
            if (value == null && claims != null) {
                value = text(claims, name);
            }
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String text(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || value.isNull() || value.isContainerNode()) {
            return null;
        }
        String text = value.asText().trim();
        return text.isEmpty() ? null : text;
    }

    private static void collect(JsonNode array, Set<String> into) {
        if (array != null && array.isArray()) {
            array.forEach(item -> {
                if (item.isTextual() && !item.asText().isBlank()) {
                    into.add(item.asText().trim());
                }
            });
        }
    }

    private static JsonNode parse(String body) {
        try {
            JsonNode node = JSON.readTree(body);
            return node != null && node.isObject() ? node : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static String form(String name, String value) {
        return name + "=" + URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
