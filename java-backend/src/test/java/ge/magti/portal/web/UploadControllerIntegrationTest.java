package ge.magti.portal.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import ge.magti.portal.RequiresOracle;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.repository.AuditLogRepository;
import ge.magti.portal.repository.StoredFileRepository;
import ge.magti.portal.repository.UserRepository;
import ge.magti.portal.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

/**
 * Real Oracle, real HTTP, real Spring Security filter chain -- covers
 * routers/platform.py's {@code POST /api/upload} port.
 *
 * <p>No file cleanup any more: since PR-03 the bytes go into {@code
 * stored_files}, so {@code @Transactional} rolls them back with everything
 * else. The old {@code cleanupUploadedFiles} hook existed because uploads
 * escaped the test transaction onto the real disk -- which is the same
 * property that made them survive nothing in production.
 */
@RequiresOracle
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UploadControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private AuditLogRepository auditLogRepository;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private StoredFileRepository storedFileRepository;

    private static final byte[] PNG_BYTES =
            {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x01, 0x02};

    private final ObjectMapper objectMapper = new ObjectMapper();

    private User createUser(String email, Role role) {
        User user = new User();
        user.setEmail(email);
        user.setName("ტესტ მომხმარებელი");
        user.setRole(role);
        user.setDepartment("All");
        user.setActive(true);
        user.setHashedPassword(passwordEncoder.encode("unused"));
        user.setPermissions(Permission.defaultsFor(role).stream()
                .map(Permission::value)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new)));
        return userRepository.saveAndFlush(user);
    }

    private String tokenFor(User user) {
        return jwtService.createAccessToken(Map.of("sub", user.getEmail(), "role", user.getRole().value()));
    }

    private static MockMultipartHttpServletRequestBuilder authed(
            MockMultipartHttpServletRequestBuilder builder, String token) {
        return builder.header("Authorization", "Bearer " + token);
    }

    @Test
    void noTokenIsUnauthorized() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "note.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8));

        // Supply a valid CSRF token so this test reaches the authentication
        // boundary; a browser mutation without CSRF is correctly rejected
        // earlier with 403 by Spring Security.
        mockMvc.perform(multipart("/api/upload").file(file).with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Could not validate credentials"));
    }

    @Test
    void operatorCannotUpload() throws Exception {
        User operator = createUser("up1@magti.ge", Role.OPERATOR);
        long filesBefore = storedFileRepository.count();
        long auditsBefore = auditLogRepository.count();
        MockMultipartFile file = new MockMultipartFile(
                "file", "note.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(authed(multipart("/api/upload").file(file), tokenFor(operator)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("წვდომა უარყოფილია: არასაკმარისი უფლებები"));
        assertEquals(filesBefore, storedFileRepository.count());
        assertEquals(auditsBefore, auditLogRepository.count());
    }

    @Test
    void contentAdminCanUploadAnAllowedTypeAndItIsServedBack() throws Exception {
        User admin = createUser("up2@magti.ge", Role.CONTENT_ADMIN);
        // A REAL 8-byte PNG signature. This fixture used to be a 4-byte
        // truncation, which passed only because nothing looked at the bytes
        // (SEC-09) -- FileTypeVerifier now rejects it, correctly.
        MockMultipartFile file = new MockMultipartFile(
                "file", "photo.png", "image/png", PNG_BYTES);

        String body = mockMvc.perform(authed(multipart("/api/upload").file(file), tokenFor(admin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        var json = objectMapper.readTree(body);
        String url = json.get("url").asText();
        String filename = json.get("filename").asText();
        assertTrue(url.equals("/uploads/" + filename));
        assertTrue(filename.endsWith(".png"));

        // PR-03: the bytes are in Oracle now, not on this container's disk,
        // and UploadedFileController serves them back at the same URL. The
        // round-trip is asserted byte-for-byte because "200 OK" alone would
        // also pass if the BLOB came back empty.
        mockMvc.perform(get(url))
                .andExpect(status().isUnauthorized());

        byte[] served = mockMvc.perform(get(url).header("Authorization", "Bearer " + tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsByteArray();
        assertArrayEquals(PNG_BYTES, served);
        assertTrue(storedFileRepository.findById(filename).isPresent(), "the upload must be a stored_files row");

        var uploadAudit = auditLogRepository.findAll().stream()
                .filter(a -> admin.getId().equals(a.getAdminId()) && "UPLOAD".equals(a.getAction()))
                .findFirst().orElseThrow();
        var uploadDetails = objectMapper.readTree(uploadAudit.getDetails());
        assertEquals("SUCCESS", uploadDetails.get("result").asText());
        assertEquals("image/png", uploadDetails.at("/after/content_type").asText());
        assertEquals(PNG_BYTES.length, uploadDetails.at("/after/byte_size").asInt());

        var accessAudit = auditLogRepository.findAll().stream()
                .filter(a -> admin.getId().equals(a.getAdminId()) && "FILE_ACCESS".equals(a.getAction()))
                .findFirst().orElseThrow();
        var accessDetails = objectMapper.readTree(accessAudit.getDetails());
        assertEquals("SUCCESS", accessDetails.get("result").asText());
        assertEquals(filename, accessDetails.at("/after/stored_filename").asText());
    }

    @Test
    void disallowedMimeTypeIsRejected() throws Exception {
        User admin = createUser("up3@magti.ge", Role.CONTENT_ADMIN);
        MockMultipartFile file = new MockMultipartFile(
                "file", "script.svg", "image/svg+xml", "<svg></svg>".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(authed(multipart("/api/upload").file(file), tokenFor(admin)))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void oversizedFileIsRejected() throws Exception {
        User admin = createUser("up4@magti.ge", Role.CONTENT_ADMIN);
        // Deliberately between the two caps: over UploadController's 10MB
        // app-level MAX_UPLOAD_SIZE_BYTES, but under application.yml's 11MB
        // Spring transport-level max-file-size -- so this exercises the
        // controller's own check, not Spring's multipart rejection (which
        // now lands on GlobalExceptionHandler and comes back as a generic
        // 500 with a correlation id, not this endpoint's 413).
        byte[] tooBig = new byte[10 * 1024 * 1024 + 512 * 1024];
        MockMultipartFile file = new MockMultipartFile("file", "big.png", "image/png", tooBig);

        long storedBefore = storedFileRepository.count();
        mockMvc.perform(authed(multipart("/api/upload").file(file), tokenFor(admin)))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.detail").value("ფაილის ზომა აღემატება დასაშვებ 10 MiB-ს"));
        assertEquals(storedBefore, storedFileRepository.count(), "rejected file must not create a BLOB");
    }

    /**
     * SEC-09: the MIME allowlist only knew what the client declared, so HTML
     * sent as image/png was stored as <uuid>.png and served back from the
     * public /uploads path with that declared type.
     */
    @Test
    void contentThatContradictsTheDeclaredTypeIsRejected() throws Exception {
        User admin = createUser("up5@magti.ge", Role.CONTENT_ADMIN);
        MockMultipartFile disguised = new MockMultipartFile(
                "file", "innocent.png", "image/png",
                "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(authed(multipart("/api/upload").file(disguised), tokenFor(admin)))
                .andExpect(status().isUnsupportedMediaType());
    }

    /**
     * ...but text, which has no signature, is checked only for being text
     * (no NUL byte), so a real .txt in any encoding still uploads. Until
     * 2026-09-26 the verifier did not look at it at all (ASVS V5.2.2).
     */
    @Test
    void aPlainTextUploadStillWorksBecauseTextHasNoSignature() throws Exception {
        User admin = createUser("up6@magti.ge", Role.CONTENT_ADMIN);
        MockMultipartFile note = new MockMultipartFile(
                "file", "note.txt", "text/plain", "ჩვეულებრივი ტექსტი".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(authed(multipart("/api/upload").file(note), tokenFor(admin)))
                .andExpect(status().isOk());
    }

    /**
     * A polyglot -- a byte-valid PNG with an executable payload appended --
     * passes the magic-byte check and is stored, because its header genuinely
     * is a PNG. That is fine only as long as it can never be interpreted as
     * anything but an image when served. The 2026-08-31 adversarial round
     * confirmed this defence live; this test makes it a build-time guarantee,
     * because the two headers below are the whole reason a stored polyglot is
     * inert, and nothing else asserted them.
     */
    @Test
    void aStoredPolyglotIsServedAsAnInertImage() throws Exception {
        User admin = createUser("up7@magti.ge", Role.CONTENT_ADMIN);
        byte[] script = "<script>alert(document.cookie)</script>".getBytes(StandardCharsets.UTF_8);
        byte[] polyglot = new byte[PNG_BYTES.length + script.length];
        System.arraycopy(PNG_BYTES, 0, polyglot, 0, PNG_BYTES.length);
        System.arraycopy(script, 0, polyglot, PNG_BYTES.length, script.length);
        // The client even lies about the extension; the server must ignore it.
        MockMultipartFile file = new MockMultipartFile(
                "file", "evil.php.png", "image/png", polyglot);

        String body = mockMvc.perform(authed(multipart("/api/upload").file(file), tokenFor(admin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var json = objectMapper.readTree(body);
        String url = json.get("url").asText();
        // The stored name is a server-minted UUID.png -- the ".php" is gone.
        assertTrue(json.get("filename").asText().endsWith(".png"),
                "the server, not the client filename, decides the extension");

        mockMvc.perform(get(url).header("Authorization", "Bearer " + tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Security-Policy", "default-src 'none'; sandbox"));
    }

    /** ASVS V4.1.1 through the real upload and download: a .txt goes out naming its encoding. */
    @Test
    void anUploadedTextFileIsServedWithItsCharset() throws Exception {
        User admin = createUser("up-charset@magti.ge", Role.CONTENT_ADMIN);
        MockMultipartFile file = new MockMultipartFile(
                "file", "notes.txt", "text/plain", "ინსტრუქცია".getBytes(StandardCharsets.UTF_8));

        String body = mockMvc.perform(authed(multipart("/api/upload").file(file), tokenFor(admin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        mockMvc.perform(get(objectMapper.readTree(body).get("url").asText())
                        .header("Authorization", "Bearer " + tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/plain;charset=UTF-8"));
    }
}
