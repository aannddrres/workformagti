package ge.magti.portal.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Writes the audit chain's current end to the application log every hour.
 *
 * <p>A hash chain proves that what is there was not edited. It cannot prove
 * that nothing was cut off the end: delete the newest rows, point
 * {@code audit_chain_state} at the row before them, and every check still
 * passes, because every remaining row is genuine (tamper check, 2026-10-02).
 * The answer is a copy of the end kept somewhere the database's own users
 * cannot rewrite -- here the log, which leaves the pod for IT's collection
 * (owner, 2026-10-02).
 *
 * <p>To use it: take any logged {@code AUDIT_CHAIN_ANCHOR} line and open
 * {@code GET /api/audit-logs/{id}/verify} for its id. The row must still be
 * there and its {@code row_hash} must equal the logged hash. A missing row,
 * or a different hash, means the ledger was cut back after that hour.
 * Every replica writes the line; duplicates are harmless.
 */
@Component
public class AuditChainAnchor {

    private static final Logger log = LoggerFactory.getLogger(AuditChainAnchor.class);
    private static final long INTERVAL_MS = 60 * 60 * 1000L;

    private final AuditChainService chainService;

    public AuditChainAnchor(AuditChainService chainService) {
        this.chainService = chainService;
    }

    @Scheduled(initialDelay = 5 * 60 * 1000L, fixedDelay = INTERVAL_MS)
    public void anchor() {
        try {
            chainService.tip().ifPresent(tip -> log.info("AUDIT_CHAIN_ANCHOR id={} row_hash={} chained_rows={}",
                    tip.get("id"), tip.get("row_hash"), tip.get("chained")));
        } catch (RuntimeException e) {
            log.warn("AUDIT_CHAIN_ANCHOR could not read the chain's end: {}", e.getClass().getSimpleName());
        }
    }
}
