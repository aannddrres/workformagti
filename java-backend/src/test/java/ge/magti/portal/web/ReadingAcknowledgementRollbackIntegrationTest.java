package ge.magti.portal.web;

import ge.magti.portal.RequiresOracle;
import ge.magti.portal.audit.MutationAuditService;
import ge.magti.portal.domain.Article;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.RequiredReading;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.repository.ArticleReadReceiptRepository;
import ge.magti.portal.repository.ArticleRepository;
import ge.magti.portal.repository.ReadStatusRepository;
import ge.magti.portal.repository.RequiredReadingRepository;
import ge.magti.portal.repository.UserRepository;
import ge.magti.portal.security.JwtService;
import ge.magti.portal.util.TbilisiTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A failed audit write cannot leave either half of the acknowledgement behind. */
@RequiresOracle
@SpringBootTest
@AutoConfigureMockMvc
class ReadingAcknowledgementRollbackIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository users;
    @Autowired private ArticleRepository articles;
    @Autowired private RequiredReadingRepository readings;
    @Autowired private ArticleReadReceiptRepository receipts;
    @Autowired private ReadStatusRepository statuses;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;
    @MockitoBean private MutationAuditService audit;

    @Test
    void auditFailureRollsBackReadStatusAndArticleReceipt() throws Exception {
        String suffix = Long.toString(System.nanoTime());
        String department = "ack-rollback-" + suffix;
        User user = new User();
        user.setEmail("ack-rollback-" + suffix + "@magti.ge");
        user.setName("Rollback reader");
        user.setRole(Role.OPERATOR);
        user.setDepartment(department);
        user.setActive(true);
        user.setHashedPassword(passwordEncoder.encode("unused"));
        user.setPermissions(Permission.defaultsFor(Role.OPERATOR).stream()
                .map(Permission::value).collect(java.util.stream.Collectors.toSet()));
        user = users.saveAndFlush(user);

        Article article = new Article();
        article.setTitle("Rollback article");
        article.setContent("body");
        article.setVersion(1);
        article = articles.saveAndFlush(article);
        RequiredReading reading = new RequiredReading();
        reading.setItemType("article");
        reading.setItemId(article.getId());
        reading.setItemTitleSnapshot(article.getTitle());
        reading.setTargetDepartment(department);
        reading.setDueDate(TbilisiTime.now().plusDays(1));
        reading = readings.saveAndFlush(reading);

        doThrow(new IllegalStateException("synthetic audit failure"))
                .when(audit).recordSuccess(any(), eq("MARK_REQUIRED_READING_READ"),
                        eq("article_read_receipt"), any(), any(), isNull(), any());
        String token = jwtService.createAccessToken(Map.of("sub", user.getEmail(), "role", "operator"));
        long readingId = reading.getId();
        long articleId = article.getId();
        long userId = user.getId();
        mockMvc.perform(
                post("/api/compliance/mark-read/" + readingId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isInternalServerError());

        assertTrue(statuses.findByUserIdAndRequiredReadingId(userId, readingId).isEmpty());
        assertTrue(receipts.findByArticleIdSnapshotAndArticleVersionAndOperatorId(articleId, 1, userId).isEmpty());
    }
}
