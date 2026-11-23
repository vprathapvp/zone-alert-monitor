-- CR-1 step 6a (contract), only after the backfill left no NULL (INSTALL.md step 5).
-- ADD ... NOT VALID: brief ACCESS EXCLUSIVE, no scan.
-- VALIDATE: SHARE UPDATE EXCLUSIVE while it scans: reads AND writes continue, only other DDL waits.
-- If a NULL is still there, VALIDATE fails, nothing changes, and the backfill can be re-run.
SET LOCAL lock_timeout = '5s';
ALTER TABLE report ADD CONSTRAINT report_source_type_present CHECK (source_type IS NOT NULL) NOT VALID;
ALTER TABLE report VALIDATE CONSTRAINT report_source_type_present;
ALTER TABLE report VALIDATE CONSTRAINT report_source_type_valid;
