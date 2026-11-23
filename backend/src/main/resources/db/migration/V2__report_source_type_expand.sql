-- CR-1 step 1 (expand). Safe while v1.0.0 instances are running and writing.
-- Locks: ACCESS EXCLUSIVE on report for milliseconds (catalogue change only: a nullable column
-- without a default does not rewrite the table). lock_timeout makes the step fail fast, instead of
-- queueing live writes behind it, if a long transaction holds the table; then simply re-run it.
SET LOCAL lock_timeout = '5s';

CREATE FUNCTION report_source_type_of(src text) RETURNS text
    LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$
    SELECT CASE
        WHEN src LIKE 'SAT%' THEN 'SATELLITE'
        WHEN src LIKE 'GND%' THEN 'GROUND'
        WHEN src LIKE 'MOB%' THEN 'MOBILE'
        ELSE 'UNKNOWN'
    END
$$;

ALTER TABLE report ADD COLUMN source_type text;

-- New rows are checked from now on; existing rows are validated in step 4 (no scan here).
ALTER TABLE report ADD CONSTRAINT report_source_type_valid
    CHECK (source_type IN ('SATELLITE', 'GROUND', 'MOBILE', 'UNKNOWN')) NOT VALID;

-- Rows written by v1.0.0 (which does not know the column) get their source type here, so from this
-- moment every new row has one, whichever version wrote it.
CREATE FUNCTION report_fill_source_type() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.source_type IS NULL THEN
        NEW.source_type := report_source_type_of(NEW.source);
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER report_fill_source_type_trg
    BEFORE INSERT ON report
    FOR EACH ROW EXECUTE FUNCTION report_fill_source_type();
