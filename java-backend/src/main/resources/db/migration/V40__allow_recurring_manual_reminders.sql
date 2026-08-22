-- Oracle indexes a composite key when any component is non-null. Therefore
-- the V39 constraint also treated all MANUAL rows for one recipient as the
-- same key, even though required_reading_id is null. Automatic reminder
-- delivery must stay exactly-once; manual reminders must be repeatable after
-- the application-enforced cooldown.
ALTER TABLE reminders DROP CONSTRAINT uq_reminder_once;

CREATE UNIQUE INDEX uq_reminder_once ON reminders (
    CASE WHEN reminder_type <> 'MANUAL' THEN required_reading_id END,
    CASE WHEN reminder_type <> 'MANUAL' THEN recipient_user_id END,
    CASE WHEN reminder_type <> 'MANUAL' THEN reminder_type END
);
