package ge.magti.portal.web;

import com.zaxxer.hikari.HikariDataSource;
import ge.magti.portal.RequiresOracle;
import ge.magti.portal.security.CorporateAuthClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Sign-in attempts that arrive together while the company directory takes
 * its time -- a scripted burst against one account, or a shift of operators
 * signing in at 09:00 while the directory is slow.
 *
 * <p>The directory is replaced by a stub that holds every caller on a latch,
 * so the attempts are all in flight at once for as long as the test needs.
 * The accounts do not exist in the portal, so a rejection writes no audit row
 * and nothing outlives the test except throttle rows, which are removed; each
 * run uses its own client address so it cannot eat into another test's
 * per-address budget.
 */
@RequiresOracle
@SpringBootTest(properties = {
        "portal.security.corporate.enabled=true",
        "portal.security.corporate.service-uri=https://oauth.example.test/auth/",
        "portal.security.corporate.client-id=InfoPortal",
        "portal.security.corporate.client-credential=dGVzdC1jbGllbnQ6dGVzdC1zZWNyZXQ=",
        "portal.security.corporate.domain=@example.ge"
})
@AutoConfigureMockMvc
class ConcurrentLoginIntegrationTest {

    /** LoginRateLimiter.MAX_ATTEMPTS_PER_ACCOUNT. */
    private static final int ACCOUNT_LIMIT = 10;

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DataSource dataSource;

    @MockitoBean
    private CorporateAuthClient directory;

    private final String clientAddress = "10.77." + ThreadLocalRandom.current().nextInt(1, 255)
            + "." + ThreadLocalRandom.current().nextInt(1, 255);
    private final AtomicInteger askedTheDirectory = new AtomicInteger();
    private final CountDownLatch release = new CountDownLatch(1);
    private final ExecutorService threads = Executors.newFixedThreadPool(16);

    @AfterEach
    void releaseAndClean() {
        release.countDown();
        threads.shutdownNow();
        jdbcTemplate.update("DELETE FROM login_attempts WHERE attempt_key LIKE ?", "%|" + clientAddress);
    }

    /**
     * LoginRateLimiter allows ten attempts a minute per (account, address)
     * and counts each attempt before deciding, so that simultaneous attempts
     * see one another. That only holds if the recorded attempt is visible to
     * them -- committed -- when they count.
     */
    @Test
    void simultaneousAttemptsOnOneAccountStayWithinTheLimit() throws Exception {
        directoryHoldsEveryCaller();
        String account = "burst-" + System.nanoTime() + "@example.ge";
        int attempts = ACCOUNT_LIMIT + 2;

        List<Future<Integer>> statuses = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            statuses.add(threads.submit(() -> login(account)));
        }
        AtomicInteger refusedEarly = new AtomicInteger();
        waitUntil(() -> {
            refusedEarly.set((int) statuses.stream().filter(Future::isDone).count());
            return askedTheDirectory.get() + refusedEarly.get() == attempts;
        });

        assertTrue(askedTheDirectory.get() <= ACCOUNT_LIMIT,
                askedTheDirectory.get() + " of " + attempts + " simultaneous attempts reached the directory; the limit is "
                        + ACCOUNT_LIMIT);
        release.countDown();
        for (Future<Integer> status : statuses) {
            int code = status.get(30, TimeUnit.SECONDS);
            assertTrue(code == 401 || code == 429, "unexpected status " + code);
        }
    }

    /**
     * The directory's timeouts are 5 s to connect and 10 s to answer. Every
     * sign-in that holds a pooled connection for that long takes one of the
     * pool's 30 from every other request in the portal.
     */
    @Test
    void aSignInWaitingOnTheDirectoryHoldsNoDatabaseConnection() throws Exception {
        directoryHoldsEveryCaller();
        int waiting = 5;
        HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);

        List<Future<Integer>> statuses = new ArrayList<>();
        for (int i = 0; i < waiting; i++) {
            String account = "wait-" + i + "-" + System.nanoTime() + "@example.ge";
            statuses.add(threads.submit(() -> login(account)));
        }
        waitUntil(() -> askedTheDirectory.get() == waiting);

        int active = pool.getHikariPoolMXBean().getActiveConnections();
        release.countDown();
        for (Future<Integer> status : statuses) {
            assertEquals(401, status.get(30, TimeUnit.SECONDS));
        }
        assertTrue(active < waiting,
                active + " pooled connections were checked out while " + waiting + " sign-ins waited on the directory");
    }

    private void directoryHoldsEveryCaller() {
        when(directory.authenticate(anyString(), anyString())).thenAnswer(invocation -> {
            askedTheDirectory.incrementAndGet();
            release.await(30, TimeUnit.SECONDS);
            return new CorporateAuthClient.Rejected();
        });
    }

    private int login(String account) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                        .with(request -> {
                            request.setRemoteAddr(clientAddress);
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + account + "\",\"password\":\"wrong\"}"))
                .andReturn().getResponse().getStatus();
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "the sign-ins never all arrived");
            Thread.sleep(50);
        }
        // Let any attempt still between the throttle and the directory get there.
        Thread.sleep(500);
    }
}
