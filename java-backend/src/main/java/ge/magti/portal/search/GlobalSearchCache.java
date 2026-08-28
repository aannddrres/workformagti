package ge.magti.portal.search;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Java equivalent of {@code state.py}'s 60-second TTL {@code search_cache}
 * plus {@code routers/search.py}'s {@code single_flight} request coalescing
 * (routers/search.py:20-35) -- concurrent callers for the same key share one
 * computation instead of each hitting the database, same as the Python
 * asyncio version. Implemented with a plain {@link ConcurrentHashMap} of
 * {@link CompletableFuture}s rather than asyncio's single-threaded-event-loop
 * trick, since Spring MVC serves requests on separate threads: {@link
 * ConcurrentHashMap#putIfAbsent} is the synchronization point instead of
 * asyncio's cooperative scheduling.
 */
@Component
public class GlobalSearchCache {

    private static final Duration TTL = Duration.ofSeconds(60);
    static final int MAX_ENTRIES = 512;

    private record Entry(SearchQueryService.GlobalSearchResult value, Instant expiresAt) {
    }

    private final ConcurrentHashMap<String, Entry> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<SearchQueryService.GlobalSearchResult>> inflight =
            new ConcurrentHashMap<>();

    public SearchQueryService.GlobalSearchResult getOrCompute(
            String key, Supplier<SearchQueryService.GlobalSearchResult> factory) {
        Entry cached = cache.get(key);
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
            return cached.value();
        }

        CompletableFuture<SearchQueryService.GlobalSearchResult> future = new CompletableFuture<>();
        CompletableFuture<SearchQueryService.GlobalSearchResult> existing = inflight.putIfAbsent(key, future);
        if (existing != null) {
            return existing.join();
        }
        try {
            SearchQueryService.GlobalSearchResult result = factory.get();
            putBounded(key, new Entry(result, Instant.now().plus(TTL)));
            future.complete(result);
            return result;
        } catch (RuntimeException e) {
            future.completeExceptionally(e);
            throw e;
        } finally {
            inflight.remove(key);
        }
    }

    private void putBounded(String key, Entry entry) {
        synchronized (cache) {
            Instant now = Instant.now();
            cache.entrySet().removeIf(candidate -> !candidate.getValue().expiresAt().isAfter(now));
            if (!cache.containsKey(key) && cache.size() >= MAX_ENTRIES) {
                cache.entrySet().stream()
                        .min(Comparator.comparing(candidate -> candidate.getValue().expiresAt()))
                        .map(Map.Entry::getKey)
                        .ifPresent(cache::remove);
            }
            cache.put(key, entry);
        }
    }

    int cachedEntryCount() {
        return cache.size();
    }
}
