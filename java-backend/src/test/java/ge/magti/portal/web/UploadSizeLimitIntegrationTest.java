package ge.magti.portal.web;

import ge.magti.portal.RequiresOracle;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.repository.UserRepository;
import ge.magti.portal.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An upload bigger than Spring's multipart limit (11MB, application.yml) is
 * the sender's mistake and gets the same answer as one over the
 * application's own 10MB check: 413, and why.
 *
 * <p>It used to fall through to the catch-all handler: 500, "an unexpected
 * error", and a stack trace logged as ERROR, so monitoring counted a
 * too-big file as a server fault. Behind the production nginx this cannot
 * happen, since nginx refuses the body at the same 11MB first; straight to
 * the backend, or behind any proxy configured otherwise, it did.
 *
 * <p>A real server, not MockMvc: MockMvc builds multipart requests itself
 * and never runs the container's size limits, so only a real HTTP request
 * reaches the exception.
 */
@RequiresOracle
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UploadSizeLimitIntegrationTest {

    private static final String BOUNDARY = "upload-size-limit-boundary";

    @LocalServerPort private int port;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private PasswordEncoder passwordEncoder;

    private Long userId;

    @Test
    void anUploadOverTheTransportLimitIsRefusedAsTheSendersErrorNotTheServers() throws Exception {
        String token = contentAdminToken();

        // Streamed with no declared length. With a Content-Length over the
        // limit Tomcat refuses the request before reading any of it, and then
        // closes the connection rather than swallow 12MB it will not use --
        // which this client reports as a broken pipe, not as the answer.
        byte[] body = multipartPdf(12 * 1024 * 1024);
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/upload"))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                        .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(413, response.statusCode(), response.body());
        assertTrue(response.body().contains("10 MiB"), response.body());
    }

    @AfterEach
    void removeUser() {
        if (userId != null) {
            userRepository.deleteById(userId);
        }
    }

    private String contentAdminToken() {
        User user = new User();
        user.setEmail("upload.limit." + System.nanoTime() + "@example.invalid");
        user.setName("ატვირთვის ზღვრის ტესტი");
        user.setRole(Role.CONTENT_ADMIN);
        user.setActive(true);
        user.setHashedPassword(passwordEncoder.encode("unused"));
        user.setPermissions(Permission.defaultsFor(Role.CONTENT_ADMIN).stream()
                .map(Permission::value)
                .collect(Collectors.toCollection(LinkedHashSet::new)));
        User saved = userRepository.saveAndFlush(user);
        userId = saved.getId();
        return jwtService.createAccessToken(Map.of("sub", saved.getEmail(), "role", Role.CONTENT_ADMIN.value()));
    }

    /** One file part holding a PDF of {@code bytes}: real enough for the magic-byte check, if it got that far. */
    private static byte[] multipartPdf(int bytes) {
        byte[] pdf = new byte[bytes];
        Arrays.fill(pdf, (byte) '0');
        byte[] head = "%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(head, 0, pdf, 0, head.length);

        ByteArrayOutputStream body = new ByteArrayOutputStream(bytes + 512);
        body.writeBytes(("--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"big.pdf\"\r\n"
                + "Content-Type: application/pdf\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        body.writeBytes(pdf);
        body.writeBytes(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        return body.toByteArray();
    }
}
