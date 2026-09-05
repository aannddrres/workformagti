package ge.magti.portal.article;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ge.magti.portal.domain.Article;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.util.TbilisiTime;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Java half of a rule that is written twice.
 *
 * <p>{@link ArticleVisibility} is the single backend answer to "may this
 * person read this article", and its class comment explains why: two copies
 * would be two answers, and the interesting failure is the quiet one. But the
 * reader-facing lifecycle clause of that rule <i>is</i> written a second time,
 * in {@code angular-frontend/src/app/shared/article-visibility.ts}, because
 * the list screens filter client-side before the server is asked. Nothing
 * connected the two: no shared test, and neither file's comments mentioned the
 * other, so an edit to one would have left the other quietly disagreeing —
 * the same shape of bug as SEC-13, one layer up.
 *
 * <p>{@code docs/api-contract/article-visibility-cases.json} is now the one
 * statement of that clause. This test runs it against the Java rule;
 * {@code article-visibility.spec.ts} runs the same file against the TypeScript
 * one. Adding a case obliges both.
 *
 * <p>Audience and the content-administrator bypass are not in the fixture —
 * the frontend does not evaluate them, so there is nothing to keep in step.
 * Here they are neutralised: the article targets {@code "All"} and the user is
 * an operator, which isolates status and date.
 */
class ArticleVisibilityParityTest {

    private static final Path CASES = firstExisting(
            Path.of("../docs/api-contract/article-visibility-cases.json"),
            Path.of("docs/api-contract/article-visibility-cases.json"));

    /** Far enough either side of now that no clock or offset difference matters. */
    private static final OffsetDateTime PAST = TbilisiTime.now().minusDays(1);
    private static final OffsetDateTime FUTURE = TbilisiTime.now().plusDays(1);

    @TestFactory
    List<DynamicTest> theSharedCasesAllHold() throws IOException {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : cases()) {
            String name = c.get("name").asText();
            boolean expected = c.get("visible").asBoolean();
            tests.add(DynamicTest.dynamicTest(name, () -> assertEquals(
                    expected,
                    ArticleVisibility.isVisible(article(c), List.of("All"), operator()),
                    "case \"" + name + "\" in docs/api-contract/article-visibility-cases.json disagrees with "
                            + "ArticleVisibility.java. That file is also run by the Angular suite, so whichever "
                            + "side is wrong, fix it rather than editing the case: the point of the fixture is "
                            + "that the two implementations cannot drift apart.")));
        }
        return tests;
    }

    /**
     * Guards the guard. Every assertion above lives inside a loop over a JSON
     * file; an empty or unreadable file would produce a green run that proved
     * nothing at all.
     */
    @Test
    void theFixtureIsActuallyBeingRead() throws IOException {
        assertTrue(Files.exists(CASES), "not found: " + CASES);
        JsonNode cases = cases();
        assertTrue(cases.size() >= 8,
                "only " + cases.size() + " cases parsed out of " + CASES + "; the fixture is not being read");
        boolean anyTrue = false;
        boolean anyFalse = false;
        for (JsonNode c : cases) {
            anyTrue |= c.get("visible").asBoolean();
            anyFalse |= !c.get("visible").asBoolean();
        }
        assertTrue(anyTrue && anyFalse,
                "the fixture asserts only one outcome; it cannot catch a rule that returns a constant");
    }

    private static JsonNode cases() throws IOException {
        return new ObjectMapper().readTree(Files.readString(CASES)).get("cases");
    }

    private static Article article(JsonNode c) {
        Article article = new Article();
        article.setStatus(c.get("status").asText());
        JsonNode when = c.get("publishedAt");
        if (when != null && !when.isNull()) {
            article.setPublishedAt("past".equals(when.asText()) ? PAST : FUTURE);
        }
        return article;
    }

    private static User operator() {
        User user = new User();
        user.setRole(Role.OPERATOR);
        user.setDepartment("ტექნიკური");
        return user;
    }

    private static Path firstExisting(Path... candidates) {
        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        return candidates[0];
    }
}
