package ge.magti.portal.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import ge.magti.portal.RequiresOracle;
import ge.magti.portal.domain.Category;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.RequiredReading;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.repository.ArticleReadReceiptRepository;
import ge.magti.portal.repository.AuditLogRepository;
import ge.magti.portal.repository.CategoryRepository;
import ge.magti.portal.repository.ReadStatusRepository;
import ge.magti.portal.repository.RequiredReadingRepository;
import ge.magti.portal.repository.UserRepository;
import ge.magti.portal.security.JwtService;
import ge.magti.portal.util.TbilisiTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Both acknowledgement routes must serialize on the same user across transactions. */
@RequiresOracle
@SpringBootTest
@AutoConfigureMockMvc
class ReadingAcknowledgementConcurrencyIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository users;
    @Autowired private CategoryRepository categories;
    @Autowired private RequiredReadingRepository readings;
    @Autowired private ReadStatusRepository statuses;
    @Autowired private ArticleReadReceiptRepository receipts;
    @Autowired private AuditLogRepository audits;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;

    private User user(String email, Role role, String department) {
        User user = new User();
        user.setEmail(email);
        user.setName(email);
        user.setRole(role);
        user.setDepartment(department);
        user.setActive(true);
        user.setHashedPassword(passwordEncoder.encode("unused"));
        user.setPermissions(Permission.defaultsFor(role).stream()
                .map(Permission::value).collect(java.util.stream.Collectors.toSet()));
        return users.saveAndFlush(user);
    }

    private String token(User user) {
        return jwtService.createAccessToken(Map.of("sub", user.getEmail(), "role", user.getRole().value()));
    }

    @Test
    void simultaneousArticleAndMandatoryAcknowledgementCreateOneReceiptAndStatus() throws Exception {
        String suffix = Long.toString(System.nanoTime());
        String department = "ack-race-" + suffix;
        User admin = user("ack-race-admin-" + suffix + "@magti.ge", Role.CONTENT_ADMIN, "All");
        User operator = user("ack-race-operator-" + suffix + "@magti.ge", Role.OPERATOR, department);
        Category category = new Category();
        category.setName("ack-race-" + suffix);
        category.setActive(true);
        category = categories.saveAndFlush(category);
        String body = mockMvc.perform(post("/api/articles")
                        .header("Authorization", "Bearer " + token(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Concurrent read\",\"content\":\"body\",\"category_id\":"
                                + category.getId() + ",\"target_departments\":[\"" + department + "\"],"
                                + "\"status\":\"published\",\"is_draft\":false}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long articleId = new ObjectMapper().readTree(body).get("id").asLong();
        RequiredReading reading = new RequiredReading();
        reading.setItemType("article");
        reading.setItemId(articleId);
        reading.setItemTitleSnapshot("Concurrent read");
        reading.setTargetDepartment(department);
        reading.setDueDate(TbilisiTime.now().plusDays(1));
        reading = readings.saveAndFlush(reading);
        long readingId = reading.getId();
        String operatorToken = token(operator);

        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var articleCall = executor.submit(() -> {
                start.await();
                return mockMvc.perform(post("/api/articles/" + articleId + "/read-receipt")
                                .header("Authorization", "Bearer " + operatorToken))
                        .andReturn().getResponse().getStatus();
            });
            var complianceCall = executor.submit(() -> {
                start.await();
                return mockMvc.perform(post("/api/compliance/mark-read/" + readingId)
                                .header("Authorization", "Bearer " + operatorToken))
                        .andReturn().getResponse().getStatus();
            });
            start.countDown();
            assertEquals(200, articleCall.get(20, TimeUnit.SECONDS));
            assertEquals(200, complianceCall.get(20, TimeUnit.SECONDS));
        }

        var receipt = receipts.findByArticleIdSnapshotAndArticleVersionAndOperatorId(
                articleId, 1, operator.getId()).orElseThrow();
        var status = statuses.findByUserIdAndRequiredReadingId(operator.getId(), readingId).orElseThrow();
        assertEquals(receipt.getReadAt(), status.getReadAt());
        assertEquals(1, receipts.findAll().stream().filter(row -> operator.getId().equals(row.getOperatorId())
                && Long.valueOf(articleId).equals(row.getArticleIdSnapshot())).count());
        assertEquals(1, statuses.findAll().stream().filter(row -> operator.getId().equals(row.getUserId())
                && Long.valueOf(readingId).equals(row.getRequiredReadingId())).count());
        assertTrue(audits.findAll().stream().anyMatch(row -> operator.getId().equals(row.getAdminId())
                && row.getDetails().contains("ALREADY_ACKNOWLEDGED")));
    }
}
