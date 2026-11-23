-- CR-1 step 6b (contract). SET NOT NULL uses the validated CHECK constraint as proof and does not
-- scan the table (PostgreSQL 12+): ACCESS EXCLUSIVE for milliseconds. The CHECK is then redundant.
SET LOCAL lock_timeout = '5s';
ALTER TABLE report ALTER COLUMN source_type SET NOT NULL;
ALTER TABLE report DROP CONSTRAINT report_source_type_present;
