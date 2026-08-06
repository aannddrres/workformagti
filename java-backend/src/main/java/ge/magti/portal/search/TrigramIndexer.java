package ge.magti.portal.search;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Extracts distinct lowercase 3-character trigrams from text -- a hand-built
 * equivalent of the pg_trgm index the live Postgres app relies on for
 * search (CLAUDE.md), since Oracle has no built-in equivalent extension.
 * Used purely as a fast candidate pre-filter: {@link SearchQueryService}
 * narrows to rows sharing every trigram of a search word via an indexed
 * lookup, then always re-verifies with an exact substring check against the
 * real column before counting a match -- so a coincidental trigram overlap
 * (two different words can share the same trigram set) can never produce a
 * false positive, only extra candidates to recheck.
 *
 * <p>Unlike pg_trgm's own default (which pads text with boundary spaces for
 * fuzzy similarity scoring), this extraction has no padding -- it only ever
 * answers "does this word appear anywhere as a substring", an unanchored
 * search matching the live app's {@code ILIKE '%word%'} behavior exactly,
 * so boundary trigrams would just be noise.
 */
public final class TrigramIndexer {

    public static final int TRIGRAM_LENGTH = 3;

    private TrigramIndexer() {
    }

    /**
     * Distinct lowercase trigrams found in {@code text}. Empty for
     * {@code null}/blank input or input shorter than {@link #TRIGRAM_LENGTH}
     * characters -- such short text can never be trigram-filtered and must
     * fall back to a direct substring scan (see SearchQueryService).
     */
    public static Set<String> extract(String text) {
        Set<String> trigrams = new LinkedHashSet<>();
        if (text == null) {
            return trigrams;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        int lastStart = lower.length() - TRIGRAM_LENGTH;
        for (int i = 0; i <= lastStart; i++) {
            trigrams.add(lower.substring(i, i + TRIGRAM_LENGTH));
        }
        return trigrams;
    }
}
