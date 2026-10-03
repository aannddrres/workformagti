package ge.magti.portal.search;

import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Request coalescing ("single flight") for the header search:
 * concurrent callers for the same key share one computation instead of each
 * hitting the database. Implemented with a plain {@link ConcurrentHashMap} of
 * {@link CompletableFuture}s, since Spring MVC serves requests on separate
 * threads: {@link ConcurrentHashMap#putIfAbsent} is the synchronization point.
 *
 * <p><b>No result is kept once its request is answered.</b> This class used
 * to hold every result for 60 seconds -- but the original app cleared its
 * search cache on every content write, and the port never did (NewsController and VideoController
 * still say why: there was no cache to go stale when they were ported). An
 * operator who had searched a word saw an article published under it a
 * minute late, and an archived or retargeted one stayed in the list for a
 * minute, opening to "not found" (RoleFlowIntegrationTest, measured live at
 * 59.9 s, 2026-10-01). Clearing on writes would not have been enough either:
 * each replica holds its own map. Since the key became per person (PO-34) a
 * hit needed the same person to repeat the same words within the minute, so
 * the memory saved little; the owner chose an always-current answer.
 */
@Component
public class GlobalSearchCache {

    private final ConcurrentHashMap<String, CompletableFuture<SearchQueryService.GlobalSearchResult>> inflight =
            new ConcurrentHashMap<>();

    public SearchQueryService.GlobalSearchResult getOrCompute(
            String key, Supplier<SearchQueryService.GlobalSearchResult> factory) {
        CompletableFuture<SearchQueryService.GlobalSearchResult> future = new CompletableFuture<>();
        CompletableFuture<SearchQueryService.GlobalSearchResult> existing = inflight.putIfAbsent(key, future);
        if (existing != null) {
            return existing.join();
        }
        try {
            SearchQueryService.GlobalSearchResult result = factory.get();
            future.complete(result);
            return result;
        } catch (RuntimeException e) {
            future.completeExceptionally(e);
            throw e;
        } finally {
            inflight.remove(key);
        }
    }

    int inflightCount() {
        return inflight.size();
    }
}
