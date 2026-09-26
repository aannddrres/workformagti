package ge.magti.portal.web;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import ge.magti.portal.RequiresOracle;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.repository.UserRepository;
import ge.magti.portal.security.JwtService;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ASVS V16.3.2: a refused request is a security event, logged with who asked
 * for what. Every handler refuses by returning 403 from its own require*
 * guard (there is no @PreAuthorize here), so the log line is written once, in
 * the security chain, from the response status rather than in each guard.
 */
@RequiresOracle
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AccessDenialLoggingIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;

    @Test
    void aRefusedRequestIsLoggedWithWhoAndWhat() throws Exception {
        User user = new User();
        user.setEmail("access-denial-operator@magti.ge");
        user.setName("Access Denial Operator");
        user.setRole(Role.OPERATOR);
        user.setDepartment("ტექნიკური");
        user.setPosition("ტესტი");
        user.setActive(true);
        user.setPermissions(Permission.defaultsFor(Role.OPERATOR).stream()
                .map(Permission::value)
                .collect(Collectors.toCollection(LinkedHashSet::new)));
        User operator = userRepository.saveAndFlush(user);

        Logger logger = (Logger) LoggerFactory.getLogger("ge.magti.portal.security.AccessDenialLoggingFilter");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            mockMvc.perform(get("/api/audit-logs/chain-health")
                            .header("Authorization", "Bearer " + jwtService.createAccessTokenFor(operator)))
                    .andExpect(status().isForbidden());
        } finally {
            logger.detachAppender(appender);
        }

        List<String> lines = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertTrue(lines.stream().anyMatch(line -> line.startsWith("ACCESS_DENIED")
                        && line.contains("user=" + operator.getId())
                        && line.contains("method=GET")
                        && line.contains("path=/api/audit-logs/chain-health")),
                lines.toString());
    }
}
