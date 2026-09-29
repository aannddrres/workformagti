package ge.magti.portal.web;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The 2026-08-31 lifecycle probe found a single article create/update accepted
 * {@code status: "banana"} and stored it -- and since only the exact value
 * {@code "published"} is reader-visible, an unknown status silently hid the
 * article from every operator with no error. The bulk endpoint already
 * validated ({@link ArticleBulkStatusRequest}); this guards the same
 * constraint on {@link ArticleRequest}, the single-article path.
 */
class ArticleRequestStatusValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private ArticleRequest withStatus(String status) {
        return new ArticleRequest(
                "სათაური", "<p>ტექსტი</p>", 1L, null, List.of("All"),
                status, null, null, null, null, null, null, null, null, null);
    }

    private boolean statusRejected(String status) {
        return validator.validate(withStatus(status)).stream()
                .anyMatch(v -> v.getPropertyPath().toString().equals("status"));
    }

    @Test
    void rejectsAnUnknownStatus() {
        assertThat(statusRejected("banana")).isTrue();
        assertThat(statusRejected("pubished")).isTrue();   // the realistic typo
        assertThat(statusRejected("trashed")).isTrue();    // reached by DELETE, not by status
    }

    @Test
    void acceptsEveryValidStatus() {
        for (String ok : new String[]{"draft", "published", "scheduled", "archived"}) {
            assertThat(statusRejected(ok)).as("status '%s' must be accepted", ok).isFalse();
        }
    }

    @Test
    void allowsNullAndBlankBecauseTheHandlerDefaultsThemToDraft() {
        // @Pattern passes null; statusOrDefault() then applies "draft".
        assertThat(statusRejected(null)).isFalse();
        assertThat(withStatus(null).statusOrDefault()).isEqualTo("draft");
    }
}
