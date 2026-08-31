package ge.magti.portal.web;

import ge.magti.portal.RequiresOracle;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.repository.UserRepository;
import ge.magti.portal.security.JwtService;
import ge.magti.portal.security.PortalSessionService;
import ge.magti.portal.storage.FileStorageService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * D-4: an uploaded attachment opens only for a logged-in employee.
 *
 * <p>The decision was about a copied URL. {@code /uploads/<uuid>.<ext>} used
 * to answer anyone who had the link -- forwarded, pasted into a chat, left in
 * a browser history -- and a UUID filename made that unguessable rather than
 * protected.
 *
 * <p>The cookie case is the one worth writing down. An
 * {@code <img src="/uploads/...">} inside an article body is a browser-native
 * request: Angular's interceptor never sees it, so no bearer header is
 * attached and the request carries nothing but cookies. If that path did not
 * authenticate, every inline image in the knowledge base would break the day
 * this shipped. It is asserted here explicitly rather than assumed from the
 * cookie's configuration.
 */
@RequiresOracle
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AttachmentAccessIntegrationTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private FileStorageService fileStorageService;
    @Autowired private PortalSessionService sessionService;

    private String filename;
    private String token;
    /** Session-bound, the way login mints one -- see the cookie test below. */
    private String cookieToken;

    @BeforeEach
    void storeAnAttachmentAndAnEmployee() {
        User user = new User();
        user.setEmail("attachment-access@magti.ge");
        user.setName("Attachment access");
        user.setRole(Role.OPERATOR);
        user.setDepartment("All");
        user.setActive(true);
        user.setHashedPassword(passwordEncoder.encode("unused"));
        user = userRepository.saveAndFlush(user);
        token = jwtService.createAccessToken(Map.of("sub", user.getEmail(), "role", user.getRole().value()));
        cookieToken = jwtService.createAccessTokenFor(
                user, sessionService.create(user, "127.0.0.1", "AttachmentAccessIntegrationTest").getId());
        filename = fileStorageService.store("evidence.png", "image/png", PNG, user.getId());
    }

    @Test
    void aCopiedUrlOpensNothingWithoutALogin() throws Exception {
        MvcResult result = mockMvc.perform(get("/uploads/" + filename)).andReturn();

        assertEquals(401, result.getResponse().getStatus());
        assertEquals("{\"detail\":\"Could not validate credentials\"}",
                result.getResponse().getContentAsString(),
                "the body must be the denial, never the file");
    }

    @Test
    void anEmployeeWithABearerTokenGetsTheBytes() throws Exception {
        MvcResult result = mockMvc.perform(get("/uploads/" + filename)
                        .header("Authorization", "Bearer " + token))
                .andReturn();

        assertEquals(200, result.getResponse().getStatus());
        assertArrayEquals(PNG, result.getResponse().getContentAsByteArray());
        assertEquals("image/png", result.getResponse().getContentType());
    }

    /**
     * The path an inline {@code <img>} actually takes: cookie only, no header.
     *
     * <p>The cookie carries a SESSION-BOUND token, because that is the only
     * kind a cookie may carry: JwtAuthenticationFilter:96 rejects a cookie
     * whose token has no {@code sid} claim, deliberately, so that a token
     * minted for automation cannot be replayed out of a browser jar. Login
     * always mints one with a session, so this is what a real browser holds.
     */
    @Test
    void anInlineImageAuthenticatesByTheLoginCookieAlone() throws Exception {
        MvcResult result = mockMvc.perform(get("/uploads/" + filename)
                        .cookie(new Cookie("access_token", cookieToken)))
                .andReturn();

        assertEquals(200, result.getResponse().getStatus(),
                "an <img> in an article body sends no Authorization header; if only the header worked, every "
                        + "inline image in the knowledge base would break");
        assertArrayEquals(PNG, result.getResponse().getContentAsByteArray());
    }

    /** Caching an internal attachment in a shared proxy would undo the gate. */
    @Test
    void theResponseIsNeverCacheableBySomethingOtherThanTheBrowser() throws Exception {
        MvcResult result = mockMvc.perform(get("/uploads/" + filename)
                        .header("Authorization", "Bearer " + token))
                .andReturn();

        // no-store rather than private: this endpoint refuses to be cached at
        // all, which is the stronger of the two and what the controller
        // actually sets. Either satisfies the property being asserted -- that
        // a shared cache never keeps a copy an unauthenticated caller could
        // then be served.
        String cacheControl = result.getResponse().getHeader("Cache-Control");
        assertEquals(true,
                cacheControl != null
                        && (cacheControl.contains("no-store") || cacheControl.contains("private")),
                "expected the response to be uncacheable by a shared cache, got: " + cacheControl);
    }

    /**
     * A missing file answers 404 only once the caller is known. Anonymously it
     * is 401, so an outsider cannot use the difference to learn which
     * filenames exist.
     */
    @Test
    void aMissingFileTellsAnEmployee404AndAnOutsiderNothing() throws Exception {
        String missing = "00000000-0000-0000-0000-000000000000.png";

        assertEquals(404, mockMvc.perform(get("/uploads/" + missing)
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getStatus());
        assertEquals(401, mockMvc.perform(get("/uploads/" + missing)).andReturn().getResponse().getStatus());
    }
}
