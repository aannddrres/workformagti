-- Login throttling that survives running more than one instance.
--
-- LoginRateLimiter counted attempts in a ConcurrentHashMap inside each JVM.
-- With the two replicas k8s/30-backend-deployment.yaml declares, a load
-- balancer spreads attempts across pods that cannot see each other, so the
-- documented "10 attempts per minute per account" was really 20 -- and
-- n x 10 for any n. The control did not do what its own documentation said,
-- which is the failure mode worth fixing even when the number is small.
--
-- The rows are throwaway: one per login attempt, read only within a
-- one-minute window, swept by JdbcLoginAttemptStore. Volume is a few per
-- second at the very worst (600 staff arriving at 09:00, one login each),
-- so this is cheap next to the authentication query it guards, which hits
-- the same database anyway.
--
-- Deliberately NOT audit_logs: that table is a tamper-evident hash chain of
-- decisions somebody may have to answer for years later, and filling it
-- with per-keystroke throttle counters would bury the decisions and grow
-- the chain for no evidentiary gain. A failed login is already audited
-- there separately (LOGIN_FAILED).
CREATE TABLE login_attempts (
    id           NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    attempt_key  VARCHAR2(400 CHAR) NOT NULL,
    attempted_at TIMESTAMP(6) NOT NULL
);

-- The read path: "how many attempts under this key since <window start>".
-- Both columns, in this order, so the count is answered from the index.
CREATE INDEX ix_login_attempts_key ON login_attempts (attempt_key, attempted_at);

-- The sweep path: "everything older than <cutoff>", regardless of key.
CREATE INDEX ix_login_attempts_age ON login_attempts (attempted_at);
