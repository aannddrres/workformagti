package ge.magti.portal.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import ge.magti.portal.RequiresOracle;
import ge.magti.portal.domain.Article;
import ge.magti.portal.domain.ArticleTargetDepartment;
import ge.magti.portal.domain.Favorite;
import ge.magti.portal.domain.News;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.domain.VideoInstruction;
import ge.magti.portal.query.CompleteResultGuard;
import ge.magti.portal.repository.ArticleRepository;
import ge.magti.portal.repository.ArticleTargetDepartmentRepository;
import ge.magti.portal.repository.FavoriteRepository;
import ge.magti.portal.repository.NewsRepository;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

/**
 * Real Oracle, real HTTP, real Spring Security filter chain -- same
 * infrastructure as {@link CategoryControllerIntegrationTest}.
 */
@RequiresOracle
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class FavoriteControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private FavoriteRepository favoriteRepository;
    @Autowired
    private ArticleRepository articleRepository;
    @Autowired
    private NewsRepository newsRepository;
    @Autowired
    private ArticleTargetDepartmentRepository targetDepartmentRepository;
    @Autowired
    private VideoInstructionRepository videoRepository;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private User createUser(String email, Role role) {
        return createUser(email, role, "All");
    }

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

    /**
     * An ordinary article its readers may open: published, for everyone.
     * Until A17 a bookmark showed any article's title, so this fixture got by
     * with neither a status nor an audience.
     */
    private Article createArticle(String title) {
        return publishedArticle(title, "All");
    }

    private Article publishedArticle(String title, String department) {
        Article article = new Article();
        article.setTitle(title);
        article.setContent("შინაარსი");
        // The entity defaults is_draft to true; an authorless private draft
        // has no title for anyone (PO-34), and this fixture is an ordinary article.
        article.setDraft(false);
        article.setStatus("published");
        Article saved = articleRepository.saveAndFlush(article);
        ArticleTargetDepartment target = new ArticleTargetDepartment();
        target.setArticleId(saved.getId());
        target.setDepartment(department);
        targetDepartmentRepository.saveAndFlush(target);
        return saved;
    }

    private News publishedNews(String title, String department, boolean expired) {
        News news = new News();
        news.setTitle(title);
        news.setContent("შინაარსი");
        news.setTargetDepartment(department);
        news.setDraft(false);
        news.setCreatedAt(TbilisiTime.now());
        if (expired) {
            news.setExpiresAt(TbilisiTime.now().minusDays(1));
        }
        return newsRepository.saveAndFlush(news);
    }

    private VideoInstruction video(String title, String department, boolean archived) {
        VideoInstruction video = new VideoInstruction();
        video.setTitle(title);
        video.setVideoUrl("https://youtu.be/dQw4w9WgXcQ");
        video.setCategory("ტესტი");
        video.setTargetDepartment(department);
        video.setArchived(archived);
        video.setCreatedAt(TbilisiTime.now());
        return videoRepository.saveAndFlush(video);
    }

    /** item_title as the bookmark answers it, when added and then when listed. */
    private List<String> bookmarkTitles(User user, String itemType, Long itemId) throws Exception {
        String added = mockMvc.perform(authed(post("/api/favorites"), tokenFor(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"item_type\":\"" + itemType + "\",\"item_id\":" + itemId + "}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String listed = mockMvc.perform(authed(get("/api/favorites"), tokenFor(user)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String listedTitle = null;
        for (var favorite : objectMapper.readTree(listed)) {
            if (itemType.equals(favorite.get("item_type").asText()) && itemId == favorite.get("item_id").asLong()) {
                listedTitle = favorite.get("item_title").asText();
            }
        }
        return List.of(objectMapper.readTree(added).get("item_title").asText(), String.valueOf(listedTitle));
    }

    private News createNews(String title) {
        News news = new News();
        news.setTitle(title);
        news.setContent("შინაარსი");
        news.setTargetDepartment("All");
        news.setCreatedAt(TbilisiTime.now());
        return newsRepository.saveAndFlush(news);
    }

    @Test
    void noTokenIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/favorites"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Could not validate credentials"));
    }

    @Test
    void anonymousBookmarkRequestCannotCreateAFavorite() throws Exception {
        Article article = createArticle("anonymous-bookmark-target");
        long favoritesBefore = favoriteRepository.count();

        mockMvc.perform(post("/api/favorites").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"item_type\":\"article\",\"item_id\":" + article.getId() + "}"))
                .andExpect(status().isUnauthorized());
        assertEquals(favoritesBefore, favoriteRepository.count());
    }

    @Test
    void bookmarkCollectionAndMutationAreIsolatedPerCaller() throws Exception {
        User operator = createUser("fav-op1@magti.ge", Role.OPERATOR);
        User otherOperator = createUser("fav-op1-other@magti.ge", Role.OPERATOR);
        Article article = createArticle("რჩეულებში დასამატებელი სტატია");

        String addBody = mockMvc.perform(authed(post("/api/favorites"), tokenFor(operator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"item_type\":\"article\",\"item_id\":" + article.getId() + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item_type").value("article"))
                .andExpect(jsonPath("$.item_title").value("რჩეულებში დასამატებელი სტატია"))
                .andReturn().getResponse().getContentAsString();
        long favoriteId = objectMapper.readTree(addBody).get("id").asLong();

        mockMvc.perform(authed(get("/api/favorites"), tokenFor(operator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$[0].id").value((int) favoriteId));

        mockMvc.perform(authed(get("/api/favorites"), tokenFor(otherOperator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(0)));

        String otherAddBody = mockMvc.perform(authed(post("/api/favorites"), tokenFor(otherOperator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"item_type\":\"article\",\"item_id\":" + article.getId() + "}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long otherFavoriteId = objectMapper.readTree(otherAddBody).get("id").asLong();
        assertTrue(favoriteId != otherFavoriteId, "the same item must have a distinct favorite row per caller");

        mockMvc.perform(authed(get("/api/favorites"), tokenFor(otherOperator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$[0].id").value((int) otherFavoriteId));

        mockMvc.perform(authed(delete("/api/favorites/" + favoriteId), tokenFor(operator)))
                .andExpect(status().isNoContent());

        mockMvc.perform(authed(get("/api/favorites"), tokenFor(operator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(0)));

        mockMvc.perform(authed(get("/api/favorites"), tokenFor(otherOperator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$[0].id").value((int) otherFavoriteId));
    }

    @Test
    void bookmarkingTheSameItemTwiceIsIdempotent() throws Exception {
        User operator = createUser("fav-op2@magti.ge", Role.OPERATOR);
        News news = createNews("რჩეულ სიახლედ დასანიშნი");

        String firstBody = mockMvc.perform(authed(post("/api/favorites"), tokenFor(operator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"item_type\":\"news\",\"item_id\":" + news.getId() + "}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long firstId = objectMapper.readTree(firstBody).get("id").asLong();

        String secondBody = mockMvc.perform(authed(post("/api/favorites"), tokenFor(operator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"item_type\":\"news\",\"item_id\":" + news.getId() + "}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long secondId = objectMapper.readTree(secondBody).get("id").asLong();

        assertEquals(firstId, secondId);
        List<Favorite> stored = favoriteRepository.findByUserId(
                operator.getId(), CompleteResultGuard.sentinelPage());
        assertEquals(1, stored.size());
    }

    /**
     * A17. A bookmark takes any id and answered with that item's title, so
     * walking ids read the title of anything: another department's article,
     * news or video, and archived ones. The title now follows the rule the
     * item's own page applies; anything else shows the placeholder a deleted
     * item shows (the owner's decision, 2026-09-26).
     */
    @Test
    void aBookmarkNamesOnlyWhatTheCallerMayOpen() throws Exception {
        User operator = createUser("fav-a17-op@magti.ge", Role.OPERATOR, "Support");
        User contentAdmin = createUser("fav-a17-admin@magti.ge", Role.CONTENT_ADMIN, "Support");
        Article foreignArticle = publishedArticle("A17 სხვა დეპარტამენტის სტატია", "ტექნიკური");
        Article ownArticle = publishedArticle("A17 საკუთარი სტატია", "Support");
        News foreignNews = publishedNews("A17 სხვა დეპარტამენტის სიახლე", "ტექნიკური", false);
        News expiredNews = publishedNews("A17 ვადაგასული სიახლე", "Support", true);
        News ownNews = publishedNews("A17 საკუთარი სიახლე", "Support", false);
        VideoInstruction foreignVideo = video("A17 სხვა დეპარტამენტის ვიდეო", "ტექნიკური", false);
        VideoInstruction archivedVideo = video("A17 დაარქივებული ვიდეო", "Support", true);
        VideoInstruction ownVideo = video("A17 საკუთარი ვიდეო", "Support", false);

        assertEquals(List.of("მასალა #" + foreignArticle.getId(), "მასალა #" + foreignArticle.getId()),
                bookmarkTitles(operator, "article", foreignArticle.getId()), "another department's article");
        assertEquals(List.of("მასალა #" + foreignNews.getId(), "მასალა #" + foreignNews.getId()),
                bookmarkTitles(operator, "news", foreignNews.getId()), "another department's news");
        assertEquals(List.of("მასალა #" + expiredNews.getId(), "მასალა #" + expiredNews.getId()),
                bookmarkTitles(operator, "news", expiredNews.getId()), "expired news");
        assertEquals(List.of("მასალა #" + foreignVideo.getId(), "მასალა #" + foreignVideo.getId()),
                bookmarkTitles(operator, "video", foreignVideo.getId()), "another department's video");
        assertEquals(List.of("მასალა #" + archivedVideo.getId(), "მასალა #" + archivedVideo.getId()),
                bookmarkTitles(operator, "video", archivedVideo.getId()), "an archived video");

        // What the operator may open keeps its title, and so does everything
        // a content administrator may open.
        assertEquals(List.of("A17 საკუთარი სტატია", "A17 საკუთარი სტატია"),
                bookmarkTitles(operator, "article", ownArticle.getId()));
        assertEquals(List.of("A17 საკუთარი სიახლე", "A17 საკუთარი სიახლე"),
                bookmarkTitles(operator, "news", ownNews.getId()));
        assertEquals(List.of("A17 საკუთარი ვიდეო", "A17 საკუთარი ვიდეო"),
                bookmarkTitles(operator, "video", ownVideo.getId()));
        assertEquals(List.of("A17 სხვა დეპარტამენტის სტატია", "A17 სხვა დეპარტამენტის სტატია"),
                bookmarkTitles(contentAdmin, "article", foreignArticle.getId()));
        assertEquals(List.of("A17 დაარქივებული ვიდეო", "A17 დაარქივებული ვიდეო"),
                bookmarkTitles(contentAdmin, "video", archivedVideo.getId()));
    }

    @Test
    void favoriteWithADeletedUnderlyingItemFallsBackToAPlaceholderTitle() throws Exception {
        User operator = createUser("fav-op3@magti.ge", Role.OPERATOR);

        mockMvc.perform(authed(post("/api/favorites"), tokenFor(operator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"item_type\":\"article\",\"item_id\":999999999}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item_title").value("მასალა #999999999"));
    }

    @Test
    void aUserCannotRemoveAnotherUsersFavorite() throws Exception {
        User owner = createUser("fav-owner@magti.ge", Role.OPERATOR);
        User intruder = createUser("fav-intruder@magti.ge", Role.OPERATOR);
        Article article = createArticle("სხვის რჩეულებში სტატია");

        String body = mockMvc.perform(authed(post("/api/favorites"), tokenFor(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"item_type\":\"article\",\"item_id\":" + article.getId() + "}"))
                .andReturn().getResponse().getContentAsString();
        long favoriteId = objectMapper.readTree(body).get("id").asLong();

        mockMvc.perform(authed(delete("/api/favorites/" + favoriteId), tokenFor(intruder)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("რჩეული ვერ მოიძებნა"));

        assertTrue(favoriteRepository.findById(favoriteId).isPresent());
    }

    @Test
    void removingAMissingFavoriteIs404() throws Exception {
        User operator = createUser("fav-op4@magti.ge", Role.OPERATOR);

        mockMvc.perform(authed(delete("/api/favorites/999999999"), tokenFor(operator)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("რჩეული ვერ მოიძებნა"));
    }

    @Test
    void invalidFavoriteBodyDoesNotCreateABookmark() throws Exception {
        User operator = createUser("fav-invalid-" + System.nanoTime() + "@magti.ge", Role.OPERATOR);
        long before = favoriteRepository.findByUserId(
                operator.getId(), CompleteResultGuard.sentinelPage()).size();

        mockMvc.perform(authed(post("/api/favorites"), tokenFor(operator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"item_type\":\"\",\"item_id\":999999999}"))
                .andExpect(status().isBadRequest());

        assertEquals(before, favoriteRepository.findByUserId(
                operator.getId(), CompleteResultGuard.sentinelPage()).size());
    }
}
