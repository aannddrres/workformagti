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

        mockMvc.perform(multipart("/api/upload").file(file))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Could not validate credentials"));
    }

    @Test
    void operatorCannotUpload() throws Exception {
        User operator = createUser("up1@magti.ge", Role.OPERATOR);
        MockMultipartFile file = new MockMultipartFile(
                "file", "note.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(authed(multipart("/api/upload").file(file), tokenFor(operator)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Not enough permissions to perform this action"));
    }

    @Test
    void contentAdminCanUploadAnAllowedTypeAndItIsServedBack() throws Exception {
        User admin = createUser("up2@magti.ge", Role.CONTENT_ADMIN);
        MockMultipartFile file = new MockMultipartFile(
                "file", "photo.png", "image/png", new byte[]{(byte) 0x89, 'P', 'N', 'G'});

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
        byte[] served = mockMvc.perform(get(url))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andReturn().getResponse().getContentAsByteArray();
        assertArrayEquals(new byte[]{(byte) 0x89, 'P', 'N', 'G'}, served);
        assertTrue(storedFileRepository.findById(filename).isPresent(), "the upload must be a stored_files row");

        long auditCount = auditLogRepository.findAll().stream()
                .filter(a -> a.getAdminId().equals(admin.getId()) && "UPLOAD".equals(a.getAction()))
                .count();
        assertEquals(1, auditCount);
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
        // would 500, not 413, with no custom exception handler installed).
        byte[] tooBig = new byte[10 * 1024 * 1024 + 512 * 1024];
        MockMultipartFile file = new MockMultipartFile("file", "big.png", "image/png", tooBig);

        mockMvc.perform(authed(multipart("/api/upload").file(file), tokenFor(admin)))
                .andExpect(status().isPayloadTooLarge());
    }
}
