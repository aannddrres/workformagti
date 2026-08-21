package ge.magti.portal.web;

import ge.magti.portal.RequiresOracle;
import ge.magti.portal.domain.Favorite;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.domain.VideoInstruction;
import ge.magti.portal.repository.AuditLogRepository;
import ge.magti.portal.repository.FavoriteRepository;
import ge.magti.portal.repository.TagMappingRepository;
import ge.magti.portal.repository.UserRepository;
import ge.magti.portal.repository.VideoInstructionRepository;
import ge.magti.portal.security.JwtService;
import ge.magti.portal.util.TbilisiTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real Oracle, real HTTP, real Spring Security filter chain. JIT-provisioned
 * test emails always default to department "Support" (AuthenticationService),
 * so department-visibility scenarios need a specific sub-group department --
 * those users are created directly via the repository and given a token
 * minted directly via JwtService, bypassing the login endpoint entirely
 * (equally real from the filter chain's perspective: it only ever inspects
 * the token and re-reads the user row, never how the token was minted).
 *
 * <p>{@code @Transactional} rolls back every user/video/tag row this test
 * creates.
 */
@RequiresOracle
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class VideoControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private VideoInstructionRepository videoRepository;
    @Autowired
    private TagMappingRepository tagMappingRepository;
    @Autowired
    private FavoriteRepository favoriteRepository;
    @Autowired
    private AuditLogRepository auditLogRepository;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private User createUser(String email, Role role, String department) {
        User user = new User();
        user.setEmail(email);
        user.setName("ტესტ მომხმარებელი");
        user.setRole(role);
        user.setDepartment(department);
        user.setActive(true);
        user.setHashedPassword(passwordEncoder.encode("unused"));
        // Real JIT provisioning (AuthenticationService.jitProvision) populates
        // permissions from the role's defaults at creation time -- permissions
        // are a per-user assigned list, never derived live from role, so a
        // bare User row here would otherwise fail every permission check.
        user.setPermissions(Permission.defaultsFor(role).stream()
                .map(Permission::value)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new)));
        return userRepository.saveAndFlush(user);
    }

    private String tokenFor(User user) {
        return jwtService.createAccessToken(Map.of("sub", user.getEmail(), "role", user.getRole().value()));
    }

    private VideoInstruction createVideo(String title, String targetDepartment, boolean archived) {
        VideoInstruction video = new VideoInstruction();
        video.setTitle(title);
        video.setVideoUrl("https://www.youtube.com/embed/dQw4w9WgXcQ?rel=0");
        video.setTargetDepartment(targetDepartment);
        video.setCreatedAt(TbilisiTime.now());
        video.setArchived(archived);
        return videoRepository.saveAndFlush(video);
    }

    private static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder, String token) {
        return builder.header("Authorization", "Bearer " + token);
    }

    @Test
    void contentAdminSeesEveryVideoIncludingArchived() throws Exception {
        User admin = createUser("va1@magti.ge", Role.CONTENT_ADMIN, "Content Creation");
        createVideo("ტექნიკური ვიდეო", "ტექნიკური", false);
        createVideo("დაარქივებული ვიდეო", "All", true);

        mockMvc.perform(authed(get("/api/videos"), tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void operatorInASubgroupSeesTheParentDepartmentsVideo() throws Exception {
        // The fix: "ტექნიკური — ჯგუფი 01" must see a video targeted at the
        // parent "ტექნიკური", not just an exact string match.
        User operator = createUser("va2@magti.ge", Role.OPERATOR, "ტექნიკური — ჯგუფი 01");
        createVideo("ტექნიკური ვიდეო", "ტექნიკური", false);
        createVideo("ზოგადი ვიდეო", "All", false);
        createVideo("სხვა განყოფილების ვიდეო", "საინფორმაციო", false);
        createVideo("დაარქივებული ტექნიკური ვიდეო", "ტექნიკური", true);

        mockMvc.perform(authed(get("/api/videos"), tokenFor(operator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[*].title").value(
                        org.hamcrest.Matchers.containsInAnyOrder("ტექნიკური ვიდეო", "ზოგადი ვიდეო")));
    }

    @Test
    void operatorInAnUnrelatedDepartmentDoesNotSeeIt() throws Exception {
        User operator = createUser("va3@magti.ge", Role.OPERATOR, "საინფორმაციო");
        createVideo("ტექნიკური ვიდეო", "ტექნიკური", false);

        mockMvc.perform(authed(get("/api/videos"), tokenFor(operator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void noTokenIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/videos"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Could not validate credentials"));
    }

    @Test
    void viewingAVideoIncrementsItsCount() throws Exception {
        User operator = createUser("va4@magti.ge", Role.OPERATOR, "All");
        VideoInstruction video = createVideo("სანახავი ვიდეო", "All", false);

        mockMvc.perform(authed(post("/api/videos/" + video.getId() + "/view"), tokenFor(operator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.views_count").value(1));

        assertEquals(1, videoRepository.findById(video.getId()).orElseThrow().getViewsCount());
    }

    @Test
    void operatorCannotCreateAVideo() throws Exception {
        User operator = createUser("va5@magti.ge", Role.OPERATOR, "All");

        mockMvc.perform(authed(post("/api/videos"), tokenFor(operator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"x\",\"video_url\":\"https://youtu.be/dQw4w9WgXcQ\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("წვდომა უარყოფილია: არასაკმარისი უფლებები"));
    }

    @Test
    void contentAdminCreatesAVideoWithNormalizedUrlAndSyncedTags() throws Exception {
        User admin = createUser("va6@magti.ge", Role.CONTENT_ADMIN, "Content Creation");

        String body = mockMvc.perform(authed(post("/api/videos"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"ახალი ვიდეო\",\"video_url\":\"https://youtu.be/dQw4w9WgXcQ\","
                                + "\"tags\":\"ინტერნეტი, პაროლი\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.video_url").value("https://www.youtube.com/embed/dQw4w9WgXcQ?rel=0"))
                .andExpect(jsonPath("$.target_department").value("All"))
                .andReturn().getResponse().getContentAsString();

        long id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).get("id").asLong();
        assertEquals(2, tagMappingRepository.findAll().stream()
                .filter(m -> "video".equals(m.getItemType()) && m.getItemId().equals(id)).count());
    }

    @Test
    void updatingAVideoAndKeepingOneOfItsExistingTagsDoesNotViolateTheUniqueTagMappingConstraint() throws Exception {
        // Regression test for a real 500: TagSyncService.sync() deletes the
        // item's existing tag_mappings then re-inserts the current tag set in
        // the same transaction. Hibernate's default flush ordering runs
        // INSERTs before DELETEs, so re-adding a tag the item already had
        // (very common on an edit that only tweaks the tag list) used to hit
        // uq_tag_mapping_item's UNIQUE(tag_id, item_type, item_id) constraint
        // before the fix flushed the delete first.
        User admin = createUser("va8@magti.ge", Role.CONTENT_ADMIN, "Content Creation");

        String createBody = mockMvc.perform(authed(post("/api/videos"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"ვიდეო\",\"video_url\":\"https://youtu.be/dQw4w9WgXcQ\","
                                + "\"tags\":\"ინტერნეტი, პაროლი\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(createBody).get("id").asLong();

        mockMvc.perform(authed(put("/api/videos/" + id), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"ვიდეო\",\"video_url\":\"https://youtu.be/dQw4w9WgXcQ\","
                                + "\"tags\":\"ინტერნეტი, ვიდეო\"}"))
                .andExpect(status().isOk());

        List<String> remainingTagItemTypes = tagMappingRepository.findAll().stream()
                .filter(m -> "video".equals(m.getItemType()) && m.getItemId().equals(id))
                .map(m -> m.getItemType())
                .toList();
        assertEquals(2, remainingTagItemTypes.size());
    }

    @Test
    void contentAdminUpdatesAVideo() throws Exception {
        User admin = createUser("va7@magti.ge", Role.CONTENT_ADMIN, "Content Creation");
        VideoInstruction video = createVideo("ძველი სათაური", "All", false);

        mockMvc.perform(authed(put("/api/videos/" + video.getId()), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"ახალი სათაური\",\"video_url\":\"dQw4w9WgXcQ\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("ახალი სათაური"))
                .andExpect(jsonPath("$.video_url").value("https://www.youtube.com/embed/dQw4w9WgXcQ?rel=0"));
    }

    @Test
    void contentAdminDeletesAVideo() throws Exception {
        User admin = createUser("va8@magti.ge", Role.CONTENT_ADMIN, "Content Creation");
        VideoInstruction video = createVideo("წასაშლელი ვიდეო", "All", false);

        mockMvc.perform(authed(delete("/api/videos/" + video.getId()), tokenFor(admin)))
                .andExpect(status().isNoContent());

        assertTrue(videoRepository.findById(video.getId()).isEmpty());
    }

    /**
     * BL-10: deleting a video never cleared its tags_mapping or favorites
     * rows -- both address the item by (item_type, item_id) with no FK, so
     * Oracle's cascade cannot reach either. Confirmed via ContentDeletionService.
     */
    @Test
    void deletingAVideoRemovesOrphanedTagsAndFavorites() throws Exception {
        User admin = createUser("va10@magti.ge", Role.CONTENT_ADMIN, "Content Creation");
        User operator = createUser("va10-op@magti.ge", Role.OPERATOR, "Content Creation");

        String createBody = mockMvc.perform(authed(post("/api/videos"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"წასაშლელი ვიდეო\",\"video_url\":\"https://youtu.be/dQw4w9WgXcQ\","
                                + "\"tags\":\"წასაშლელი\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(createBody).get("id").asLong();

        Favorite favorite = new Favorite();
        favorite.setUserId(operator.getId());
        favorite.setItemType("video");
        favorite.setItemId(id);
        favoriteRepository.saveAndFlush(favorite);

        mockMvc.perform(authed(delete("/api/videos/" + id), tokenFor(admin)))
                .andExpect(status().isNoContent());

        assertTrue(tagMappingRepository.findAll().stream()
                        .noneMatch(m -> "video".equals(m.getItemType()) && m.getItemId().equals(id)),
                "tags_mapping must not keep pointing at a deleted video");
        assertTrue(favoriteRepository.findByUserIdAndItemTypeAndItemId(operator.getId(), "video", id).isEmpty(),
                "a favourite of a deleted video must be removed");
    }

    @Test
    void archivingWritesAnAuditRowAndIsIdempotent() throws Exception {
        User admin = createUser("va9@magti.ge", Role.CONTENT_ADMIN, "Content Creation");
        VideoInstruction video = createVideo("დასაარქივებელი ვიდეო", "All", false);

        mockMvc.perform(authed(post("/api/videos/" + video.getId() + "/archive"), tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.is_archived").value(true));

        assertTrue(auditLogRepository.findAll().stream()
                .anyMatch(a -> "ARCHIVE".equals(a.getAction()) && "video".equals(a.getItemType())
                        && video.getId().equals(a.getItemId())));

        // Second archive call: idempotent, no error, no duplicate meaning.
        mockMvc.perform(authed(post("/api/videos/" + video.getId() + "/archive"), tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.is_archived").value(true));
    }

    @Test
    void unarchivingANonArchivedVideoIsRejected() throws Exception {
        User admin = createUser("va10@magti.ge", Role.CONTENT_ADMIN, "Content Creation");
        VideoInstruction video = createVideo("ბანალური ვიდეო", "All", false);

        mockMvc.perform(authed(post("/api/videos/" + video.getId() + "/unarchive"), tokenFor(admin)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("ვიდეო არ არის არქივში"));
    }

    @Test
    void operatorCannotArchiveEvenThoughTheyCanView() throws Exception {
        User operator = createUser("va11@magti.ge", Role.OPERATOR, "All");
        VideoInstruction video = createVideo("ვიდეო", "All", false);

        mockMvc.perform(authed(post("/api/videos/" + video.getId() + "/archive"), tokenFor(operator)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("წვდომა უარყოფილია: არასაკმარისი უფლებები"));
    }

    @Test
    void viewingAMissingVideoIs404() throws Exception {
        User operator = createUser("va12@magti.ge", Role.OPERATOR, "All");

        mockMvc.perform(authed(post("/api/videos/999999999/view"), tokenFor(operator)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("ვიდეო ვერ მოიძებნა"));
    }
}
