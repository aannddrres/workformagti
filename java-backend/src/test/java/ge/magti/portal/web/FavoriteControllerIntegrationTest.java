package ge.magti.portal.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import ge.magti.portal.RequiresOracle;
import ge.magti.portal.domain.Article;
import ge.magti.portal.domain.Favorite;
import ge.magti.portal.domain.News;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.query.CompleteResultGuard;
import ge.magti.portal.repository.ArticleRepository;
import ge.magti.portal.repository.FavoriteRepository;
import ge.magti.portal.repository.NewsRepository;
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
    private JwtService jwtService;
    @Autowired
    private PasswordEncoder passwordEncoder;

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

    private static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder, String token) {
        return builder.header("Authorization", "Bearer " + token);
    }

    private Article createArticle(String title) {
        Article article = new Article();
        article.setTitle(title);
        article.setContent("შინაარსი");
        article.setDraft(false);
        return articleRepository.saveAndFlush(article);
    }

    private News createNews(String title) {
        News news = new News();
        news.setTitle(title);
        news.setContent("შინაარსი");
        news.setDraft(false);
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
