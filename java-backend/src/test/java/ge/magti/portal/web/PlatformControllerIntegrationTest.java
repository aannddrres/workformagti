package ge.magti.portal.web;

import ge.magti.portal.RequiresOracle;
import ge.magti.portal.domain.News;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.ReadStatus;
import ge.magti.portal.domain.RequiredReading;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.Tag;
import ge.magti.portal.domain.User;
import ge.magti.portal.domain.Reminder;
import ge.magti.portal.domain.ReminderType;
import ge.magti.portal.repository.ReminderRepository;
import ge.magti.portal.repository.NewsRepository;
import ge.magti.portal.repository.ReadStatusRepository;
import ge.magti.portal.repository.RequiredReadingRepository;
import ge.magti.portal.repository.TagRepository;
import ge.magti.portal.repository.UserRepository;
import ge.magti.portal.security.JwtService;
import ge.magti.portal.util.TbilisiTime;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Map;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real Oracle, real HTTP -- same infrastructure as
 * {@link FavoriteControllerIntegrationTest}. Covers the two endpoints found
 * missing (and undocumented) during the 2026-08-11 PM migration-gap audit.
 */
@RequiresOracle
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PlatformControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private TagRepository tagRepository;
    @Autowired
    private RequiredReadingRepository requiredReadingRepository;
    @Autowired
    private ReadStatusRepository readStatusRepository;
    @Autowired
    private NewsRepository newsRepository;
    @Autowired
    private ReminderRepository reminderRepository;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private User createUser(String email, Role role, String department) {
        User user = new User();
        user.setEmail(email);
        user.setName("ტესტ მომხმარებელი");
        user.setRole(role);
        user.setDepartment(department);
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

    private static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder, String token) {
        return builder.header("Authorization", "Bearer " + token);
    }

    private Tag createTag(String name) {
        Tag tag = new Tag();
        tag.setName(name);
        tag.setCreatedAt(TbilisiTime.now());
        return tagRepository.saveAndFlush(tag);
    }

    private RequiredReading createReading(String targetDepartment, OffsetDateTime dueDate) {
        RequiredReading reading = new RequiredReading();
        reading.setItemType("article");
        reading.setItemId(999999999L);
        reading.setTargetDepartment(targetDepartment);
        reading.setDueDate(dueDate);
        reading.setPriority("normal");
        return requiredReadingRepository.saveAndFlush(reading);
    }

    private News createNews(String title, String targetDepartment) {
        News news = new News();
        news.setTitle(title);
        news.setContent("შინაარსი");
        news.setTargetDepartment(targetDepartment);
        news.setCreatedAt(TbilisiTime.now());
        return newsRepository.saveAndFlush(news);
    }

    @Test
    void tagsRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/tags"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tagsAreListedAlphabetically() throws Exception {
        // The dev Oracle schema is shared across test runs, so other tags
        // may already exist -- this asserts relative order among two
        // uniquely-prefixed tags, not absolute list position.
        User operator = createUser("plat-op1@magti.ge", Role.OPERATOR, "All");
        String prefix = "zzz-platform-test-" + System.nanoTime() + "-";
        createTag(prefix + "second");
        createTag(prefix + "first");

        String body = mockMvc.perform(authed(get("/api/tags"), tokenFor(operator)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        java.util.List<String> matchingNamesInOrder = new java.util.ArrayList<>();
        for (JsonNode node : objectMapper.readTree(body)) {
            String name = node.get("name").asText();
            if (name.startsWith(prefix)) {
                matchingNamesInOrder.add(name);
            }
        }
        org.junit.jupiter.api.Assertions.assertEquals(
                java.util.List.of(prefix + "first", prefix + "second"), matchingNamesInOrder);
    }

    @Test
    void notificationsSummaryRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/notifications/summary"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void notificationsSummaryFlagsOverdueUnreadReadingsAndSkipsAlreadyReadOnes() throws Exception {
        User operator = createUser("plat-op2@magti.ge", Role.OPERATOR, "ტექნიკური");
        RequiredReading overdue = createReading("ტექნიკური", TbilisiTime.now().minusDays(1));
        RequiredReading alreadyRead = createReading("ტექნიკური", TbilisiTime.now().minusDays(1));
        ReadStatus stat = new ReadStatus();
        stat.setUserId(operator.getId());
        stat.setRequiredReadingId(alreadyRead.getId());
        stat.setStatus("read");
        stat.setReadAt(TbilisiTime.now());
        readStatusRepository.saveAndFlush(stat);

        mockMvc.perform(authed(get("/api/notifications/summary"), tokenFor(operator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unread_readings[?(@.id == " + overdue.getId() + ")].is_overdue").value(true))
                .andExpect(jsonPath("$.unread_readings[?(@.id == " + alreadyRead.getId() + ")]").isEmpty());
    }

    @Test
    void notificationsSummaryScopesRecentNewsByPrefixAwareDepartmentMatch() throws Exception {
        // Bug #315 fix, confirmed live: a sub-group operator's recent_news
        // used to miss news targeted at their parent department prefix,
        // even though GET /api/news (NewsQueryService) and this same
        // endpoint's own required-readings half both already used
        // prefix-aware matching. Now consistent across all three.
        User operator = createUser("plat-op3@magti.ge", Role.OPERATOR, "ტექნიკური — ჯგუფი 03");
        News matching = createNews("ზუსტი დეპარტამენტის სიახლე", "ტექნიკური — ჯგუფი 03");
        News allDept = createNews("ყველასთვის სიახლე", "All");
        News prefixOnly = createNews("მხოლოდ პრეფიქსის სიახლე", "ტექნიკური");
        News otherDept = createNews("სხვა დეპარტამენტის სიახლე", "ოფისი");

        mockMvc.perform(authed(get("/api/notifications/summary"), tokenFor(operator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recent_news[?(@.id == " + matching.getId() + ")]").exists())
                .andExpect(jsonPath("$.recent_news[?(@.id == " + allDept.getId() + ")]").exists())
                .andExpect(jsonPath("$.recent_news[?(@.id == " + prefixOnly.getId() + ")]").exists())
                .andExpect(jsonPath("$.recent_news[?(@.id == " + otherDept.getId() + ")]").doesNotExist());
    }

    @Test
    void notificationsSummaryCountsUnreadRemindersOnlyForTheCaller() throws Exception {
        User operator = createUser("plat-op4@magti.ge", Role.OPERATOR, "All");
        User otherOperator = createUser("plat-op4-other@magti.ge", Role.OPERATOR, "All");
        Reminder unread = reminder(operator, "წაუკითხავი შეხსენება");
        unread.setCreatedAt(TbilisiTime.now());
        reminderRepository.saveAndFlush(unread);

        Reminder read = reminder(operator, "წაკითხული შეხსენება");
        read.setCreatedAt(TbilisiTime.now());
        read.setReadAt(TbilisiTime.now());
        reminderRepository.saveAndFlush(read);

        mockMvc.perform(authed(get("/api/notifications/summary"), tokenFor(operator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unread_reminders_count").value(1));
        mockMvc.perform(authed(get("/api/notifications/summary"), tokenFor(otherOperator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unread_reminders_count").value(0));
    }

    private Reminder reminder(User recipient, String content) {
        Reminder reminder = new Reminder();
        reminder.setRecipientUserId(recipient.getId());
        reminder.setRecipientNameSnapshot(recipient.getName());
        reminder.setType(ReminderType.MANUAL);
        reminder.setContentSnapshot(content);
        reminder.setTriggeredByNameSnapshot("სისტემა");
        return reminder;
    }

    @Test
    void managementRoleGetsNoReadingsInSummary() throws Exception {
        User admin = createUser("plat-admin1@magti.ge", Role.SYSTEM_ADMIN, "All");
        createReading("All", TbilisiTime.now().minusDays(1));

        mockMvc.perform(authed(get("/api/notifications/summary"), tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unread_readings", hasSize(0)));
    }
}
