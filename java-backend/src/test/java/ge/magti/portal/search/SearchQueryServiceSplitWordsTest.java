package ge.magti.portal.search;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The query tokeniser feeds both the trigram candidate lookup and the exact
 * substring re-verify, so a token that carries stray punctuation poisons both.
 * The 2026-08-31 search-quality probe found that a trailing {@code ?}/{@code !}
 * dropped a query to zero results while the bare word returned dozens; these
 * pin the edge-punctuation strip that fixes it, and pin that interior
 * characters (hyphens, slashes) are still kept.
 */
class SearchQueryServiceSplitWordsTest {

    @Test
    void stripsTrailingQuestionAndExclamationThatUsedToZeroOutSearch() {
        assertThat(SearchQueryService.splitWords("ინტერნეტი?")).containsExactly("ინტერნეტი");
        assertThat(SearchQueryService.splitWords("ტარიფი!!!")).containsExactly("ტარიფი");
        assertThat(SearchQueryService.splitWords("VPN?")).containsExactly("VPN");
        assertThat(SearchQueryService.splitWords("ტარიფი.")).containsExactly("ტარიფი");
    }

    @Test
    void keepsInteriorPunctuationSoRealTermsSurvive() {
        // Only the edges are stripped -- the hyphen inside a term is content.
        assertThat(SearchQueryService.splitWords("Wi-Fi")).containsExactly("Wi-Fi");
        assertThat(SearchQueryService.splitWords("A/B")).containsExactly("A/B");
    }

    @Test
    void splitsOnWhitespaceAndDropsPunctuationOnlyTokens() {
        assertThat(SearchQueryService.splitWords("მობილური ინტერნეტი"))
                .containsExactly("მობილური", "ინტერნეტი");
        // A leading punctuation blob and surrounding spaces collapse away.
        assertThat(SearchQueryService.splitWords("  ?!  ტარიფი  ,")).containsExactly("ტარიფი");
    }

    @Test
    void emptyAndNullYieldNoWords() {
        assertThat(SearchQueryService.splitWords(null)).isEmpty();
        assertThat(SearchQueryService.splitWords("   ")).isEmpty();
        assertThat(SearchQueryService.splitWords("???")).isEmpty();
    }
}
