package ge.magti.portal.search;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlobalSearchCacheTest {

    private static final SearchQueryService.GlobalSearchResult EMPTY =
            new SearchQueryService.GlobalSearchResult(List.of(), List.of(), List.of());

    @Test
    void aRepeatedSearchIsAnsweredAfreshSoNewContentShowsAtOnce() {
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

        assertEquals(2, computations.get());
        assertEquals(0, cache.inflightCount(), "nothing is kept once answered");
    }

    @Test
    void identicalConcurrentSearchesShareOneComputation() throws Exception {
        GlobalSearchCache cache = new GlobalSearchCache();
        AtomicInteger computations = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        CompletableFuture<SearchQueryService.GlobalSearchResult> first = CompletableFuture.supplyAsync(() ->
                cache.getOrCompute("same", () -> {
                    computations.incrementAndGet();
                    started.countDown();
                    await(release);
                    return EMPTY;
                }));
        assertTrue(started.await(5, TimeUnit.SECONDS));
        CompletableFuture<SearchQueryService.GlobalSearchResult> second = new CompletableFuture<>();
        Thread joiner = new Thread(() -> second.complete(cache.getOrCompute("same", () -> {
            computations.incrementAndGet();
            return EMPTY;
        })));
        joiner.start();
        // Parked on the first caller's future, not computing its own.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (joiner.getState() != Thread.State.WAITING && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        release.countDown();

        assertSame(EMPTY, first.get(5, TimeUnit.SECONDS));
        assertSame(EMPTY, second.get(5, TimeUnit.SECONDS));
        assertEquals(1, computations.get());
        assertEquals(0, cache.inflightCount());
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
