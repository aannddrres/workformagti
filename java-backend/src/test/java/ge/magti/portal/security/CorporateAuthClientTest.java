package ge.magti.portal.security;

import ge.magti.portal.config.PortalProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The corporate token endpoint, as measured on 2026-09-21
 * (docs/QUESTIONS_FOR_IT.md No.13). Every response below is shaped after a
 * real one -- field names, status codes and the load balancer's HTML page --
 * with invented values; no real identity or credential is in this file.
 */
class CorporateAuthClientTest {

    private static final String TOKEN_URI = "https://oauth.example.test/auth/oauth/token";

    private PortalProperties properties;
    private MockRestServiceServer server;
    private CorporateAuthClient client;

    @BeforeEach
    void setUp() {
        properties = new PortalProperties();
        PortalProperties.Corporate corporate = properties.getSecurity().getCorporate();
        corporate.setEnabled(true);
        corporate.setServiceUri("https://oauth.example.test/auth/");
        corporate.setClientId("InfoPortal");
        corporate.setClientCredential(Base64.getEncoder().encodeToString(
                "InfoPortal:fixture:with-colon".getBytes(StandardCharsets.UTF_8)));
        rebuild();
    }

    private void rebuild() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new CorporateAuthClient(properties, builder);
    }

    /** A token whose payload is the given JSON; header and signature are placeholders. */
    private static String jwt(String payloadJson) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return encoder.encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8))
                + "." + encoder.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8))
                + ".c2lnbmF0dXJl";
    }

    private static String successBody(String claimsJson) {
        return "{\"access_token\":\"" + jwt(claimsJson) + "\",\"token_type\":\"bearer\","
                + "\"refresh_token\":\"" + jwt("{}") + "\",\"expires_in\":43199,"
                + "\"scope\":\"user_info read write trust\",\"userIdentifier\":\"test.user\","
                + "\"userId\":1001,\"email\":\"Test.User@example.ge\"}";
    }

    @Test
    void sendsTheSampleRequestAsAFormPostAndReadsWhoSignedIn() {
        server.expect(requestTo(TOKEN_URI))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Basic " + properties.getSecurity().getCorporate().getClientCredential()))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().string(
                        "grant_type=ldap_auth&username=test.user%40example.ge&password=p%40ss+w%26rd&client_id=InfoPortal"))
                .andRespond(withSuccess(successBody(
                        "{\"user_name\":\"test.user\",\"authorities\":[\"INFOPORTAL_MANAGER\",\"SOMETHING_ELSE\"]}"),
                        MediaType.APPLICATION_JSON));

        CorporateAuthClient.Outcome outcome = client.authenticate("test.user@example.ge", "p@ss w&rd");

        CorporateIdentity identity = assertInstanceOf(CorporateAuthClient.Authenticated.class, outcome).identity();
        assertEquals("test.user", identity.login());
        assertEquals("test.user@example.ge", identity.email(), "the directory's address, lower-cased");
        assertEquals("1001", identity.directoryUserId());
        assertEquals(Set.of("INFOPORTAL_MANAGER", "SOMETHING_ELSE"), identity.authorities());
        assertNull(identity.department(), "the live directory sends none today");
        assertNull(identity.name());
        server.verify();
    }

    /** The only client measured so far accepted the bare login, not the address. */
    @Test
    void theLoginFormatSendsTheUsernameWithoutItsDomain() {
        properties.getSecurity().getCorporate().setUsernameFormat("login");
        rebuild();
        server.expect(requestTo(TOKEN_URI))
                .andExpect(content().string(
                        "grant_type=ldap_auth&username=test.user&password=x&client_id=InfoPortal"))
                .andRespond(withSuccess(successBody("{}"), MediaType.APPLICATION_JSON));

        assertInstanceOf(CorporateAuthClient.Authenticated.class, client.authenticate("test.user@example.ge", "x"));
        server.verify();
    }

    @Test
    void departmentAndNameAreTakenWhenTheDirectoryStartsSendingThem() {
        server.expect(requestTo(TOKEN_URI)).andRespond(withSuccess(successBody(
                "{\"department\":\"ტექნიკური — ჯგუფი 03\",\"full_name\":\"ტესტ მომხმარებელი\"}"),
                MediaType.APPLICATION_JSON));

        CorporateIdentity identity = assertInstanceOf(CorporateAuthClient.Authenticated.class,
                client.authenticate("test.user@example.ge", "x")).identity();

        assertEquals("ტექნიკური — ჯგუფი 03", identity.department());
        assertEquals("ტესტ მომხმარებელი", identity.name());
    }

    /** The directory's own "wrong login or password" -- the one answer that counts against the person. */
    @Test
    void invalidGrantIsARejection() {
        server.expect(requestTo(TOKEN_URI)).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":\"invalid_grant\",\"error_description\":\"Invalid Credentials\"}"));

        assertInstanceOf(CorporateAuthClient.Rejected.class, client.authenticate("test.user@example.ge", "wrong"));
    }

    /**
     * Seen live when the request named a client its credential did not
     * belong to. Says nothing about the person's password, so it must not
     * be answered as "wrong password".
     */
    @Test
    void aRefusedClientCredentialIsUnavailableNotARejection() {
        server.expect(requestTo(TOKEN_URI)).andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":\"invalid_client\",\"error_description\":\"Given client ID does not match authenticated client\"}"));

        assertInstanceOf(CorporateAuthClient.Unavailable.class, client.authenticate("test.user@example.ge", "x"));
    }

    /** The load balancer's page, verbatim in shape, as returned during the 2026-09-21 outage from outside. */
    @Test
    void theLoadBalancersHtml503IsUnavailable() {
        server.expect(requestTo(TOKEN_URI)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.TEXT_HTML)
                .body("<html><body><h1>503 Service Unavailable</h1>\nNo server is available to handle this request.\n</body></html>"));

        assertInstanceOf(CorporateAuthClient.Unavailable.class, client.authenticate("test.user@example.ge", "x"));
    }

    @Test
    void anUnreachableServerIsUnavailable() {
        server.expect(requestTo(TOKEN_URI)).andRespond(withException(new IOException("connect timed out")));

        assertInstanceOf(CorporateAuthClient.Unavailable.class, client.authenticate("test.user@example.ge", "x"));
    }

    /** A 200 that no longer says who signed in must not sign anybody in. */
    @Test
    void aSuccessWithoutAnIdentityIsUnavailable() {
        server.expect(requestTo(TOKEN_URI)).andRespond(withSuccess(
                "{\"access_token\":\"opaque\",\"token_type\":\"bearer\"}", MediaType.APPLICATION_JSON));

        assertInstanceOf(CorporateAuthClient.Unavailable.class, client.authenticate("test.user@example.ge", "x"));
    }

    @Test
    void theTokenEndpointToleratesABaseWithoutATrailingSlash() {
        properties.getSecurity().getCorporate().setServiceUri("https://oauth.example.test/auth");
        rebuild();
        assertEquals(TOKEN_URI, client.tokenUri());
    }
}
