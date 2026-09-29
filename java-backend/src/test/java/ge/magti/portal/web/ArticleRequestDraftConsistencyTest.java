package ge.magti.portal.web;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The create/update boundary rule for {@code is_draft} against {@code status}.
 *
 * <h2>What went wrong</h2>
 *
 * {@code is_draft} is the personal-autosave flag: it hides a row from everyone
 * but its author, content administrators included. {@code status} is editorial
 * state. They are independent columns, and until 2026-09-06 nothing on this
 * endpoint related them — so {@code POST /api/articles} carrying
 * {@code status: "published"} and no {@code is_draft} stored a row that
 * claimed to be published and appeared in nobody's list, with no error. The
 * absent-field default was an unconditional {@code true}.
 *
 * <p>That is the same failure that once left 122 imported articles visible to
 * exactly one account, and it is why {@code scripts/import_legacy_content.py}
 * carries a paragraph about writing {@code is_draft = 0}. It stayed invisible
 * in the product only because the Angular editor always sends the field.
 *
 * <h2>The two halves</h2>
 *
 * <ul>
 *   <li><b>Absent</b> now follows the status: a status that asks for readers
 *       means "not a draft". {@code bulkSetArticleStatus} already clears the
 *       flag when it publishes, so this is the existing rule, applied one
 *       endpoint earlier.
 *   <li><b>Explicitly true, with a reader-visible status</b> is refused.
 *       Coercing it would be guessing which of the two fields the caller meant.
 * </ul>
 *
 * <p>DB-free: this is bean validation on a record, so it runs in the fast
 * suite alongside the rule it protects.
 */
class ArticleRequestDraftConsistencyTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void startValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void stopValidator() {
        factory.close();
    }

    // ── the default, when the field is absent ────────────────────────

    @Test
    void anAbsentFlagFollowsTheStatus() {
        assertFalse(request(null, "published", null).isDraftOrDefault(),
                "publishing without saying is_draft must not store a row every reader list hides");
        assertFalse(request(null, "scheduled", past()).isDraftOrDefault(),
                "a schedule whose moment has passed is reader-visible, so the same applies");

        assertTrue(request(null, "draft", null).isDraftOrDefault(),
                "an explicit draft status still means a draft");
        assertTrue(request(null, null, null).isDraftOrDefault(),
                "no status at all defaults to draft, so the flag follows");
        assertTrue(request(null, "scheduled", future()).isDraftOrDefault(),
                "a schedule that has not arrived is not reader-visible yet");
        assertTrue(request(null, "archived", past()).isDraftOrDefault(),
                "archived is not a reader-visible status");
    }

    @Test
    void anExplicitFlagIsAlwaysObeyed() {
        assertTrue(request(true, "draft", null).isDraftOrDefault());
        assertFalse(request(false, "draft", null).isDraftOrDefault(),
                "somebody saying 'not a draft' about a draft-status article is a normal editorial state, "
                        + "and the default must not override an explicit value");
    }

    // ── the refusal ──────────────────────────────────────────────────

    @Test
    void anExplicitDraftMayNotAlsoBePublished() {
        assertEquals(Set.of("draftAndStatusConsistent"), violatedProperties(request(true, "published", null)),
                "is_draft:true with status:published is the pair that /uploads/{filename} served "
                        + "regardless until 2026-09-06; it has to be refused, not stored");
        assertEquals(Set.of("draftAndStatusConsistent"), violatedProperties(request(true, "scheduled", past())),
                "a due schedule is reader-visible too, so the same pair is the same contradiction");
    }

    @Test
    void everyOtherCombinationIsAccepted() {
        assertEquals(Set.of(), violatedProperties(request(true, "draft", null)));
        assertEquals(Set.of(), violatedProperties(request(true, "scheduled", future())),
                "still drafting something scheduled for next week is a real state and must stay allowed");
        assertEquals(Set.of(), violatedProperties(request(true, "archived", past())));
        assertEquals(Set.of(), violatedProperties(request(false, "published", null)));
        assertEquals(Set.of(), violatedProperties(request(null, "published", null)));
    }

    /**
     * Guards the guard. {@code @AssertTrue} on a record's extra accessor is
     * only picked up if the validator treats {@code isXxx()} as a property
     * getter; if it does not, every assertion above that expects no violation
     * would pass while the rule was never running at all.
     */
    @Test
    void theConstraintIsActuallyWiredIntoBeanValidation() {
        assertFalse(validator.getConstraintsForClass(ArticleRequest.class)
                        .getConstraintsForProperty("draftAndStatusConsistent") == null,
                "the validator does not see draftAndStatusConsistent as a property, so @AssertTrue on it "
                        + "never runs and this whole class passes vacuously");
    }

    // ── helpers ──────────────────────────────────────────────────────

    private Set<String> violatedProperties(ArticleRequest request) {
        return validator.validate(request).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .collect(Collectors.toSet());
    }

    private static OffsetDateTime past() {
        return OffsetDateTime.now().minusDays(1);
    }

    private static OffsetDateTime future() {
        return OffsetDateTime.now().plusDays(1);
    }

    /** A request that is valid in every respect except the fields under test. */
    private static ArticleRequest request(Boolean isDraft, String status, OffsetDateTime publishedAt) {
        return new ArticleRequest(
                "სათაური", "<p>ტექსტი</p>", 1L, null, List.of("All"),
                status, null, publishedAt, null, null, null, null, null,
                isDraft, null);
    }
}
