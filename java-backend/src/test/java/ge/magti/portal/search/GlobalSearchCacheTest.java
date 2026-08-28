package ge.magti.portal.search;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlobalSearchCacheTest {

    private static final SearchQueryService.GlobalSearchResult EMPTY =
            new SearchQueryService.GlobalSearchResult(List.of(), List.of(), List.of());

    @Test
    void repeatedKeyUsesCachedValue() {
        GlobalSearchCache cache = new GlobalSearchCache();
        AtomicInteger computations = new AtomicInteger();

        cache.getOrCompute("same", () -> {
            computations.incrementAndGet();
            return EMPTY;
        });
        cache.getOrCompute("same", () -> {
            computations.incrementAndGet();
            return EMPTY;
        });

        assertEquals(1, computations.get());
        assertEquals(1, cache.cachedEntryCount());
    }

    @Test
    void uniqueKeysCannotGrowCacheBeyondHardCeiling() {
        GlobalSearchCache cache = new GlobalSearchCache();

        for (int i = 0; i < GlobalSearchCache.MAX_ENTRIES + 25; i++) {
            cache.getOrCompute("query-" + i, () -> EMPTY);
        }

        assertTrue(cache.cachedEntryCount() <= GlobalSearchCache.MAX_ENTRIES);
    }
}
