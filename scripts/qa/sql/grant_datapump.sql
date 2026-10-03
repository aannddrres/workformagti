-- Owner-approved 2026-10-03, for the backup/restore rehearsal only: lets the
-- throwaway MAGTI_QA schema write its own Data Pump export into the PDB's
-- standard DATA_PUMP_DIR. Nothing else. The grant disappears with the user
-- (qa_schema_drop.sql drops MAGTI_QA CASCADE).
GRANT READ, WRITE ON DIRECTORY DATA_PUMP_DIR TO MAGTI_QA;
SELECT directory_path FROM all_directories WHERE directory_name = 'DATA_PUMP_DIR';
