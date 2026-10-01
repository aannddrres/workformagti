package ge.magti.portal.web;

import ge.magti.portal.RequiresOracle;
import ge.magti.portal.domain.Role;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The same scenario once {@code ROLLOUT_FILE_ENTITLEMENT} is on -- UAT finding
 * F-1, kept as a regression test.
 *
 * <p>The refusal is a 404 rather than a 403 on purpose: a 403 confirms the
 * file exists, which is the single fact someone guessing stored names is
 * trying to establish.
 */
@RequiresOracle
@SpringBootTest(properties = "portal.rollout.file-entitlement-enabled=true")
@AutoConfigureMockMvc
@Transactional
class FileEntitlementEnforcedIntegrationTest extends FileEntitlementScenarioSupport {

    @Test
    void missingDirectArticleAndFileUrlsStayOpaque() throws Exception {
        long marker = System.nanoTime();
        var admin = createUser("missing-direct-admin-" + marker + "@magti.ge", Role.CONTENT_ADMIN, "All");
        String token = tokenFor(admin);
        String missingId = "999999999";

        mockMvc.perform(get("/api/articles/" + missingId).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/articles/" + missingId + "/archive")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/uploads/missing-" + marker).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void draftPublicationAndArchiveChangeArticleAndDirectFileAccessTogether() throws Exception {
        long marker = System.nanoTime();
        String target = "publication-target-" + marker;
        var author = createUser("publication-author-" + marker + "@magti.ge", Role.CONTENT_ADMIN, "All");
        var otherAdmin = createUser("publication-admin-" + marker + "@magti.ge", Role.CONTENT_ADMIN, "All");
        var reader = createUser("publication-reader-" + marker + "@magti.ge", Role.OPERATOR, target);
        var outsider = createUser("publication-outsider-" + marker + "@magti.ge", Role.OPERATOR, "outside-" + marker);
        String authorToken = tokenFor(author);
        String file = upload(authorToken);
        Long id = createArticle(authorToken, marker, List.of(target), file, true);
        String articleUrl = "/api/articles/" + id;
        String fileUrl = "/uploads/" + file;

        for (var denied : List.of(otherAdmin, reader, outsider)) {
            String token = tokenFor(denied);
            mockMvc.perform(get(articleUrl).header("Authorization", "Bearer " + token))
                    .andExpect(status().isNotFound());
            mockMvc.perform(get(fileUrl).header("Authorization", "Bearer " + token))
                    .andExpect(status().isNotFound());
        }
        String draftBody = mockMvc.perform(get(articleUrl).header("Authorization", "Bearer " + authorToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long categoryId = objectMapper.readTree(draftBody).path("category_id").asLong();
        mockMvc.perform(put(articleUrl).header("Authorization", "Bearer " + authorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "title", "გამოქვეყნების ტესტი " + marker,
                                "content", "<p><img src=\"" + fileUrl + "\"></p>",
                                "category_id", categoryId,
                                "target_departments", List.of(target),
                                "status", "published",
                                "is_draft", false))))
                .andExpect(status().isOk());

        String readerToken = tokenFor(reader);
        mockMvc.perform(get(articleUrl).header("Authorization", "Bearer " + readerToken))
                .andExpect(status().isOk());
        mockMvc.perform(get(fileUrl).header("Authorization", "Bearer " + readerToken))
                .andExpect(status().isOk());
        mockMvc.perform(get(articleUrl).header("Authorization", "Bearer " + tokenFor(outsider)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(fileUrl).header("Authorization", "Bearer " + tokenFor(outsider)))
                .andExpect(status().isNotFound());

        mockMvc.perform(post(articleUrl + "/archive").header("Authorization", "Bearer " + authorToken))
                .andExpect(status().isOk());
        mockMvc.perform(get(articleUrl).header("Authorization", "Bearer " + readerToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(fileUrl).header("Authorization", "Bearer " + readerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void privateArticleAttachmentIsReadableOnlyByItsAuthor() throws Exception {
        long marker = System.nanoTime();
        var author = createUser("draft-author-" + marker + "@magti.ge", Role.CONTENT_ADMIN, "All");
        var otherAdmin = createUser("draft-admin-" + marker + "@magti.ge", Role.CONTENT_ADMIN, "All");
        String authorToken = tokenFor(author);
        String file = upload(authorToken);
        Long id = createArticle(authorToken, marker, List.of("All"), file, true);

        mockMvc.perform(get("/api/articles/" + id).header("Authorization", "Bearer " + tokenFor(otherAdmin)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/uploads/" + file).header("Authorization", "Bearer " + tokenFor(otherAdmin)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/articles/" + id).header("Authorization", "Bearer " + authorToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/uploads/" + file).header("Authorization", "Bearer " + authorToken))
                .andExpect(status().isOk());
    }

    @Test
    void attachmentUsesTheSameFullAudienceAsArticleDetail() throws Exception {
        long marker = System.nanoTime();
        var admin = createUser("many-targets-admin-" + marker + "@magti.ge", Role.CONTENT_ADMIN, "All");
        String lateDepartment = "late-target-" + marker;
        var reader = createUser("many-targets-reader-" + marker + "@magti.ge", Role.OPERATOR, lateDepartment);
        var outsider = createUser("many-targets-outsider-" + marker + "@magti.ge", Role.OPERATOR, "outside-" + marker);
        String adminToken = tokenFor(admin);
        String file = upload(adminToken);
        List<String> departments = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            departments.add("early-target-" + marker + "-" + i);
            // Somebody works there: an audience reaching nobody is refused since 2026-10-01.
            createUser("early-target-" + marker + "-" + i + "@magti.ge", Role.OPERATOR, "early-target-" + marker + "-" + i);
        }
        departments.add(lateDepartment);
        Long id = createArticle(adminToken, marker, departments, file, false);

        mockMvc.perform(get("/api/articles/" + id).header("Authorization", "Bearer " + tokenFor(reader)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/uploads/" + file).header("Authorization", "Bearer " + tokenFor(reader)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/uploads/" + file).header("Authorization", "Bearer " + tokenFor(outsider)))
                .andExpect(status().isNotFound());
    }

    @Test
    void videoFileHonorsDepartmentAndKeepsArchiveDenialEvenForAdmin() throws Exception {
        long marker = System.nanoTime();
        var admin = createUser("video-admin-" + marker + "@magti.ge", Role.CONTENT_ADMIN, "All");
        var tech = createUser("video-tech-" + marker + "@magti.ge", Role.OPERATOR, "ტექნიკური — ჯგუფი 03");
        var info = createUser("video-info-" + marker + "@magti.ge", Role.OPERATOR, "საინფორმაციო");
        String adminToken = tokenFor(admin);
        String file = upload(adminToken);
        Long id = createVideo(adminToken, "ტექნიკური", file);
        mockMvc.perform(post("/api/videos/" + id + "/view").header("Authorization", "Bearer " + tokenFor(info)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/uploads/" + file).header("Authorization", "Bearer " + tokenFor(info)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/uploads/" + file).header("Authorization", "Bearer " + tokenFor(tech)))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        mockMvc.perform(get("/uploads/" + file).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/videos/" + id + "/archive").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/uploads/" + file).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
        createNews(adminToken, "All", file, false);
        mockMvc.perform(get("/uploads/" + file).header("Authorization", "Bearer " + tokenFor(info)))
                .andExpect(status().isOk());
    }

    @Test
    void privateNewsAttachmentIsOnlyReadableByItsAuthorEvenForAnotherAdmin() throws Exception {
        long marker = System.nanoTime();
        var author = createUser("news-author-" + marker + "@magti.ge", Role.CONTENT_ADMIN, "All");
        var otherAdmin = createUser("news-admin-" + marker + "@magti.ge", Role.CONTENT_ADMIN, "All");
        var operator = createUser("news-reader-" + marker + "@magti.ge", Role.OPERATOR, "ტექნიკური");
        String authorToken = tokenFor(author);
        String file = upload(authorToken);
        Long newsId = createNews(authorToken, "All", file, true);

        for (var outsider : java.util.List.of(operator, otherAdmin)) {
            String token = tokenFor(outsider);
            mockMvc.perform(get("/api/news/" + newsId).header("Authorization", "Bearer " + token))
                    .andExpect(status().isNotFound());
            mockMvc.perform(get("/uploads/" + file).header("Authorization", "Bearer " + token))
                    .andExpect(status().isNotFound());
            assertEquals("DENIED_NOT_VISIBLE", decisionFor(outsider, "FILE_ACCESS_DENIED")
                    .orElseThrow().get("reason").asText());
        }
        mockMvc.perform(get("/api/news/" + newsId).header("Authorization", "Bearer " + authorToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/uploads/" + file).header("Authorization", "Bearer " + authorToken))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));

        // Another readable reference may grant access, without turning drafts into orphans.
        createNews(authorToken, "All", file, false);
        mockMvc.perform(get("/uploads/" + file).header("Authorization", "Bearer " + tokenFor(operator)))
                .andExpect(status().isOk());
    }

    @Test
    void anOutsiderIsRefusedAsThoughTheFileDidNotExist() throws Exception {
        Fixture fixture = createScenario();

        mockMvc.perform(get("/uploads/" + fixture.filename())
                        .header("Authorization", "Bearer " + fixture.outsiderToken()))
                .andExpect(status().isNotFound());

        var recorded = decisionFor(fixture.outsider(), "FILE_ACCESS_DENIED")
                .orElseThrow(() -> new AssertionError("an enforced refusal must be audited"));
        assertEquals("DENIED", recorded.get("result").asText());
        assertEquals("DENIED_NOT_VISIBLE", recorded.get("reason").asText());
    }

    @Test
    void theIntendedAudienceStillGetsTheFile() throws Exception {
        Fixture fixture = createScenario();

        mockMvc.perform(get("/uploads/" + fixture.filename())
                        .header("Authorization", "Bearer " + fixture.insiderToken()))
                .andExpect(status().isOk());
    }
}
