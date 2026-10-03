package ge.magti.portal.web;

import ge.magti.portal.RequiresOracle;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PO-53 (owner, 2026-10-03): an audited action waits at most five seconds
 * for the audit chain's lock (V54), then answers "busy, try again" and lets
 * go of its connection. Before, it waited as long as the lock was held, and
 * enough such waits drained the pool for the whole portal
 * (scripts/qa/check_db_hang.py, A2).
 *
 * <p>Not {@code @Transactional}: the lock has to be held by a different
 * session than the request's, exactly as a slow colleague would hold it.
 */
@RequiresOracle
@SpringBootTest
@AutoConfigureMockMvc
class AuditChainLockWaitIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private DataSource dataSource;

    @Test
    void aSignInBehindAHeldAuditLockIsToldToRetryWithinSeconds() throws Exception {
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (Statement lock = holder.createStatement()) {
                lock.execute("SELECT tip_hash FROM audit_chain_state WHERE id = 1 FOR UPDATE");
                long started = System.nanoTime();
                mockMvc.perform(post("/api/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"email\":\"content@magti.ge\",\"password\":\"anything\"}"))
                        .andExpect(status().isServiceUnavailable())
                        .andExpect(header().exists("Retry-After"))
                        .andExpect(jsonPath("$.code").value("busy"));
                long seconds = (System.nanoTime() - started) / 1_000_000_000L;
                assertTrue(seconds < 15, "waited " + seconds + " s for the audit lock; the limit is 5");
            } finally {
                holder.rollback();
            }
        }
    }
}
