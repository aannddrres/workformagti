package ge.magti.portal.web;

import ge.magti.portal.RequiresOracle;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Every inventoried protected action is unreachable without a credential. */
@RequiresOracle
@SpringBootTest
@AutoConfigureMockMvc
class ProtectedEndpointAnonymousMatrixIntegrationTest {

    private static final Set<String> ANONYMOUS = Set.of(
            "GET /api/health", "POST /api/auth/login", "POST /api/auth/sso/start");

    @Autowired private MockMvc mockMvc;

    static Stream<String> protectedRoutes() throws IOException {
        List<String> routes = Files.readAllLines(Path.of("..", "docs", "security",
                        "LOCAL_ENDPOINT_CASE_REVIEW_2026-09-24.csv"), StandardCharsets.UTF_8)
                .stream().skip(1).map(line -> line.substring(0, line.indexOf(','))).toList();
        assertEquals(150, routes.size(), "the anonymous matrix must not silently lose an API action");
        assertEquals(150, Set.copyOf(routes).size(), "duplicate rows would hide an untested action");
        assertEquals(3, routes.stream().filter(ANONYMOUS::contains).count());
        return routes.stream().filter(route -> !ANONYMOUS.contains(route));
    }

    @ParameterizedTest(name = "anonymous {0}")
    @MethodSource("protectedRoutes")
    void protectedActionRejectsAnonymous(String route) throws Exception {
        String[] parts = route.split(" ", 2);
        String concretePath = parts[1].replaceAll("\\{[^}]+}", "999999999");
        var request = request(HttpMethod.valueOf(parts[0]), concretePath)
                .contentType(MediaType.APPLICATION_JSON).content("{}");

        if ("GET".equals(parts[0])) {
            mockMvc.perform(request).andExpect(status().isUnauthorized());
        } else {
            mockMvc.perform(request).andExpect(status().isForbidden());
        }
    }
}
