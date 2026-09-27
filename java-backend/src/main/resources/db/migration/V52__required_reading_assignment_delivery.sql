-- PO-40 (2026-09-27): an article scheduled for later may be made mandatory in
-- advance, and the obligation comes into force at its publication moment. Its
-- assignment reminders go out then, once, and not before.
--
-- assignment_delivered_at is when a reading's ASSIGNMENT reminders were
-- delivered; NULL means not yet. The create endpoint delivers them at once for
-- material already in force. The reminder sweep delivers them, and sets this,
-- for a reading that comes into force later.
--
-- A reading that exists before this migration was delivered at creation
-- (PO-16), or predates reminders; either way the sweep must not send it an
-- assignment now. Each takes the time of its first ASSIGNMENT reminder where
-- there is one, and otherwise this migration's own time, as Tbilisi wall-clock
-- like every other TIMESTAMP in the schema (UTC+4, no daylight saving).
ALTER TABLE required_readings ADD (assignment_delivered_at TIMESTAMP(6));

UPDATE required_readings rr
   SET assignment_delivered_at = COALESCE(
           (SELECT MIN(r.created_at)
              FROM reminders r
             WHERE r.required_reading_id = rr.id
               AND r.reminder_type = 'ASSIGNMENT'),
           CAST(SYS_EXTRACT_UTC(SYSTIMESTAMP) + INTERVAL '4' HOUR AS TIMESTAMP(6)));
