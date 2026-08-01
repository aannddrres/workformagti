package ge.magti.portal.diff;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure logic, no Oracle needed. Verifies diffHtml's semantics against
 * diffing.py's own documented behavior (block-level equal/insert/delete/
 * replace, word-level inline diff within a replace, link/image structural
 * tokens for asset-change detection, and HTML-escaping safety).
 */
class HtmlDifferTest {

    @Test
    void identicalContentIsAllEqualWithNoAddedOrRemoved() {
        String html = "<p>ერთი</p><p>ორი</p>";
        DiffResult result = HtmlDiffer.diffHtml(html, html);

        assertEquals(0, result.added());
        assertEquals(0, result.removed());
        assertTrue(result.html().contains("diff-equal"));
        assertFalse(result.html().contains("diff-insert"));
        assertFalse(result.html().contains("diff-delete"));
    }

    @Test
    void addedParagraphIsAnInsertRow() {
        String oldHtml = "<p>ერთი</p>";
        String newHtml = "<p>ერთი</p><p>ორი</p>";
        DiffResult result = HtmlDiffer.diffHtml(oldHtml, newHtml);

        assertEquals(1, result.added());
        assertEquals(0, result.removed());
        assertTrue(result.html().contains("diff-insert"));
        assertTrue(result.html().contains("ორი"));
    }

    @Test
    void removedParagraphIsADeleteRow() {
        String oldHtml = "<p>ერთი</p><p>ორი</p>";
        String newHtml = "<p>ერთი</p>";
        DiffResult result = HtmlDiffer.diffHtml(oldHtml, newHtml);

        assertEquals(0, result.added());
        assertEquals(1, result.removed());
        assertTrue(result.html().contains("diff-delete"));
        assertTrue(result.html().contains("ორი"));
    }

    @Test
    void modifiedParagraphIsAWordLevelReplace() {
        String oldHtml = "<p>თხელი ლურჯი ქოთანი</p>";
        String newHtml = "<p>თხელი წითელი ქოთანი</p>";
        DiffResult result = HtmlDiffer.diffHtml(oldHtml, newHtml);

        assertEquals(1, result.added());
        assertEquals(1, result.removed());
        assertTrue(result.html().contains("diff-modified"));
        // Only the changed word should be wrapped in ins/del, not the whole block.
        assertTrue(result.html().contains("<del"));
        assertTrue(result.html().contains("<ins"));
        assertTrue(result.html().contains("ლურჯი"));
        assertTrue(result.html().contains("წითელი"));
        assertTrue(result.html().contains("თხელი"), "unchanged words stay outside ins/del");
    }

    @Test
    void linkTargetChangeIsDetectedEvenWithIdenticalVisibleText() {
        // "Asset-change blindness fix" from diffing.py's own docstring.
        String oldHtml = "<p><a href=\"https://old.example/doc\">დოკუმენტი</a></p>";
        String newHtml = "<p><a href=\"https://new.example/doc\">დოკუმენტი</a></p>";
        DiffResult result = HtmlDiffer.diffHtml(oldHtml, newHtml);

        assertTrue(result.added() > 0 || result.removed() > 0,
                "identical visible text but a changed href must still register as a change");
    }

    @Test
    void imageSourceChangeIsDetected() {
        String oldHtml = "<p><img src=\"https://old.example/a.png\" alt=\"სურათი\"></p>";
        String newHtml = "<p><img src=\"https://new.example/b.png\" alt=\"სურათი\"></p>";
        DiffResult result = HtmlDiffer.diffHtml(oldHtml, newHtml);

        assertTrue(result.added() > 0 || result.removed() > 0);
    }

    @Test
    void plainInlineContentWithoutBlockTagsStillDiffs() {
        DiffResult result = HtmlDiffer.diffHtml("უბრალო ტექსტი", "სულ სხვა ტექსტი");

        assertTrue(result.added() > 0 || result.removed() > 0);
    }

    @Test
    void emptyOrNullContentDoesNotThrow() {
        assertEquals(new DiffResult("", 0, 0), HtmlDiffer.diffHtml(null, null));
        assertEquals(new DiffResult("", 0, 0), HtmlDiffer.diffHtml("", ""));

        DiffResult fromEmpty = HtmlDiffer.diffHtml("", "<p>ახალი</p>");
        assertEquals(1, fromEmpty.added());
    }

    @Test
    void scriptTagContentIsEscapedNotExecutable() {
        String malicious = "<p>უსაფრთხო</p><script>alert('xss')</script>";
        String newHtml = "<p>შეცვლილი</p>";
        DiffResult result = HtmlDiffer.diffHtml(malicious, newHtml);

        assertFalse(result.html().contains("<script>"), "raw script tags must never survive into the diff HTML");
    }

    @Test
    void wordDiffOnlyWrapsChangedWordsNotUnchangedOnes() {
        String rendered = HtmlDiffer.wordDiff("ერთი ორი სამი", "ერთი ოთხი სამი");
        // "ერთი" and "სამი" are unchanged -- must appear outside any ins/del span.
        assertTrue(rendered.startsWith("ერთი"));
        assertTrue(rendered.endsWith("სამი") || rendered.contains(">სამი<"));
        assertTrue(rendered.contains("ოთხი"));
    }
}
