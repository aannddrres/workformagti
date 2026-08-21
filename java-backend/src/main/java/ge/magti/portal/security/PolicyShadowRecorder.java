package ge.magti.portal.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs the new authorization policy beside the old one and records where they
 * disagree, without letting the new one decide anything yet.
 *
 * <p>Phase 3 of the org-access plan is deliberately not a cutover. The rules
 * being replaced -- a manager's department string, a flat permission list,
 * eligibility by role -- have been in production long enough that the
 * difference between "the new rule is wrong" and "the old rule was wrong" is
 * not knowable from reading either. Serving the old answer while counting the
 * new one turns that into data, and Phase 4 flips the switch on evidence
 * rather than on confidence.
 *
 * <p><b>A disagreement is not a bug report.</b> Some are the point: a content
 * admin who could export org-wide and now resolves to nothing is the fix
 * working. What matters is the shape of the diff -- which decisions move, for
 * whom, in which direction -- which is why {@link #snapshot()} counts
 * agreements too. A decision point with zero of both is not "clean", it is
 * unexercised, and that is worth seeing before trusting it.
 *
 * <p><b>Expect a large diff before the backfill runs.</b> Scope decisions read
 * {@code users.team_id}, which is populated on zero rows until the Phase 2
 * backfill fills it. Until then every scoped caller resolves to nobody and
 * every scope comparison disagrees. That is the correct reading of the data,
 * not a fault in the resolver, and it makes the counter a usable check on
 * whether the backfill has actually been applied.
 */
@Service
public class PolicyShadowRecorder {

    /** One prefix for every line, so an operator can grep the whole shadow run out of a log. */
    static final String MARKER = "POLICY_SHADOW";

    private static final Logger logger = LoggerFactory.getLogger(PolicyShadowRecorder.class);

    private final Map<String, AtomicLong> agreements = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> disagreements = new ConcurrentHashMap<>();

    /**
     * @param decision a stable name for the decision point, e.g. {@code "scope.export"}
     * @param subject  what the decision was about -- a user id, never a name or an email:
     *                 this ends up in application logs, which are not an export surface
     * @param legacy   what the rule in force answered
     * @param proposed what the new rule would answer
     */
    public void record(String decision, Object subject, Object legacy, Object proposed) {
        boolean agrees = legacy == null ? proposed == null : legacy.equals(proposed);
        counter(agrees ? agreements : disagreements, decision).incrementAndGet();
        if (agrees) {
            return;
        }
        // WARN, not ERROR: nothing is broken and nothing was denied. This is a
        // measurement, and an ERROR here would train whoever watches the logs
        // to ignore the category before the cutover needs their attention.
        logger.warn("{} decision={} subject={} legacy={} proposed={}", MARKER, decision, subject, legacy, proposed);
    }

    /** Both counts per decision point, for the access-diff report the cutover gate needs. */
    public Map<String, Counts> snapshot() {
        Map<String, Counts> result = new ConcurrentHashMap<>();
        for (String decision : agreements.keySet()) {
            result.put(decision, countsFor(decision));
        }
        for (String decision : disagreements.keySet()) {
            result.putIfAbsent(decision, countsFor(decision));
        }
        return Map.copyOf(result);
    }

    private Counts countsFor(String decision) {
        return new Counts(
                counter(agreements, decision).get(),
                counter(disagreements, decision).get());
    }

    private static AtomicLong counter(Map<String, AtomicLong> counters, String decision) {
        return counters.computeIfAbsent(decision, key -> new AtomicLong());
    }

    /**
     * @param agreed    times the two rules gave the same answer
     * @param disagreed times they did not
     */
    public record Counts(long agreed, long disagreed) {

        /** Zero of both means unexercised, which is not the same as clean. */
        public boolean unexercised() {
            return agreed == 0 && disagreed == 0;
        }
    }
}
