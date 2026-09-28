package ge.magti.portal.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import ge.magti.portal.RequiresOracle;
import ge.magti.portal.domain.News;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.domain.VideoInstruction;
import ge.magti.portal.repository.NewsRepository;
import ge.magti.portal.repository.RequiredReadingRepository;
import ge.magti.portal.repository.UserRepository;
import ge.magti.portal.repository.VideoInstructionRepository;
import ge.magti.portal.security.JwtService;
import ge.magti.portal.util.TbilisiTime;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Each news/video command has an HTTP authorization and atomicity contract. */
@RequiresOracle
@SpringBootTest
@AutoConfigureMockMvc
class NewsVideoCommandIntegrationTest {
    @Autowired MockMvc mockMvc;
    @Autowired UserRepository users;
    @Autowired NewsRepository news;
    @Autowired VideoInstructionRepository videos;
    @Autowired RequiredReadingRepository readings;
    @Autowired JwtService jwtService;
    @Autowired PasswordEncoder passwordEncoder;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @ParameterizedTest
    @ValueSource(strings = {"news", "videos"})
    @Transactional
    void createAndUpdateSaveContentWithOneMandatoryAssignment(String family) throws Exception {
        User admin = user(Role.CONTENT_ADMIN);
        String title = "command-created-" + System.nanoTime();
        long id = json.readTree(mockMvc.perform(post("/api/" + family + "/command")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(command(family, title, true, true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value(title))
                .andReturn().getResponse().getContentAsByteArray()).get("id").asLong();
        String itemType = itemType(family);
        long readingId = readings.findFirstByItemTypeAndItemIdOrderByIdAsc(itemType, id).orElseThrow().getId();

        String updated = "command-updated-" + System.nanoTime();
        mockMvc.perform(put("/api/" + family + "/" + id + "/command")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(command(family, updated, true, true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value(updated));
        assertEquals(updated, titleOf(family, id));
        assertEquals(readingId, readings.findFirstByItemTypeAndItemIdOrderByIdAsc(itemType, id)
                .orElseThrow().getId(), "update must reuse the one mandatory assignment");
    }

    @ParameterizedTest
    @ValueSource(strings = {"news", "videos"})
    @Transactional
    void operatorCannotCreateACommandOrItsMandatoryAssignment(String family) throws Exception {
        User operator = user(Role.OPERATOR);
        String title = "command-denied-" + System.nanoTime();
        long before = count(family);
        long readingsBefore = readings.count();

        mockMvc.perform(post("/api/" + family + "/command")
                        .header("Authorization", bearer(operator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(command(family, title, true, true)))
                .andExpect(status().isForbidden());
        assertEquals(before, count(family));
        assertEquals(readingsBefore, readings.count());
    }

    @ParameterizedTest
    @ValueSource(strings = {"news", "videos"})
    @Transactional
    void operatorCannotUpdateACommandOrItsMandatoryAssignment(String family) throws Exception {
        User operator = user(Role.OPERATOR);
        long id = fixture(family, "original-command");
        long readingsBefore = readings.count();

        mockMvc.perform(put("/api/" + family + "/" + id + "/command")
                        .header("Authorization", bearer(operator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(command(family, "denied-update", true, true)))
                .andExpect(status().isForbidden());
        assertEquals("original-command", titleOf(family, id));
        assertEquals(readingsBefore, readings.count());
    }

    @ParameterizedTest
    @ValueSource(strings = {"news", "videos"})
    void missingDueDateRollsBackBothCreateAndUpdate(String family) throws Exception {
        User admin = user(Role.CONTENT_ADMIN);
        String uniqueTitle = "invalid-command-" + System.nanoTime();
        long existingId = fixture(family, "before-invalid-command");
        long countBefore = count(family);
        long readingsBefore = readings.count();
        try {
            mockMvc.perform(post("/api/" + family + "/command")
                            .header("Authorization", bearer(admin))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(command(family, uniqueTitle, true, false)))
                    .andExpect(status().isUnprocessableEntity());
            assertEquals(countBefore, count(family), "failed create must leave no content row");
            assertFalse(existsTitle(family, uniqueTitle));

            mockMvc.perform(put("/api/" + family + "/" + existingId + "/command")
                            .header("Authorization", bearer(admin))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(command(family, "invalid-update", true, false)))
                    .andExpect(status().isUnprocessableEntity());
            assertEquals("before-invalid-command", titleOf(family, existingId));
            assertEquals(readingsBefore, readings.count(), "neither failed command may leave an assignment");
            assertTrue(readings.findFirstByItemTypeAndItemIdOrderByIdAsc(itemType(family), existingId).isEmpty());
        } finally {
            if (family.equals("news")) news.deleteById(existingId);
            else videos.deleteById(existingId);
            users.deleteById(admin.getId());
        }
    }

    private User user(Role role) {
        User user = new User();
        user.setEmail("command-" + role.value() + "-" + System.nanoTime() + "@magti.ge");
        user.setName("ტესტ მომხმარებელი");
        user.setRole(role);
        user.setDepartment("All");
        user.setActive(true);
        user.setHashedPassword(passwordEncoder.encode("unused"));
        user.setPermissions(Permission.defaultsFor(role).stream().map(Permission::value)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new)));
        return users.saveAndFlush(user);
    }

    private long fixture(String family, String title) {
        if (family.equals("news")) {
            News row = new News();
            row.setTitle(title);
            row.setContent("შინაარსი");
            row.setTargetDepartment("All");
            row.setDraft(false);
            row.setCreatedAt(TbilisiTime.now());
            return news.saveAndFlush(row).getId();
        }
        VideoInstruction row = new VideoInstruction();
        row.setTitle(title);
        row.setVideoUrl("https://www.youtube.com/embed/dQw4w9WgXcQ");
        row.setTargetDepartment("All");
        row.setCreatedAt(TbilisiTime.now());
        return videos.saveAndFlush(row).getId();
    }

    private String command(String family, String title, boolean mandatory, boolean withDueDate) throws Exception {
        Map<String, Object> content = family.equals("news")
                ? Map.of("title", title, "content", "შინაარსი", "target_department", "All", "is_draft", false)
                : Map.of("title", title, "video_url", "https://www.youtube.com/embed/dQw4w9WgXcQ",
                        "target_department", "All");
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put(itemType(family), content);
        body.put("mandatory", mandatory);
        body.put("target_department", "All");
        if (withDueDate) body.put("due_date", OffsetDateTime.now().plusDays(3).toString());
        return json.writeValueAsString(body);
    }

    private String bearer(User user) {
        return "Bearer " + jwtService.createAccessTokenFor(user);
    }

    private static String itemType(String family) {
        return family.equals("news") ? "news" : "video";
    }

    private long count(String family) {
        return family.equals("news") ? news.count() : videos.count();
    }

    private String titleOf(String family, long id) {
        return family.equals("news") ? news.findById(id).orElseThrow().getTitle()
                : videos.findById(id).orElseThrow().getTitle();
    }

    private boolean existsTitle(String family, String title) {
        return family.equals("news") ? news.findAll().stream().anyMatch(row -> title.equals(row.getTitle()))
                : videos.findAll().stream().anyMatch(row -> title.equals(row.getTitle()));
    }
}
