-- CR-1 backfill, run by the operator after all instances are on 1.1.0 (INSTALL.md, step 4):
--   CALL report_backfill_source_type(batch_size, pause_ms[, start_after]);
-- Walks the primary key in order, updates at most batch_size rows per transaction and commits
-- each batch, so locks are held for milliseconds and progress survives a cancel. Each batch
-- only touches rows whose source_type is still NULL, so re-running is harmless. Ingest never updates
-- existing reports, so the batches do not contend with live writes; pause_ms leaves room for them.
CREATE PROCEDURE report_backfill_source_type(batch_size integer DEFAULT 5000,
                                             pause_ms integer DEFAULT 10,
                                             start_after text DEFAULT '')
    LANGUAGE plpgsql
AS $$
DECLARE
    last_id   text COLLATE "C" := start_after;
    batch_max text COLLATE "C";
    updated   bigint;
    total     bigint := 0;
    batches   bigint := 0;
    started   timestamptz := clock_timestamp();
BEGIN
    LOOP
        SET LOCAL lock_timeout = '2s';
        SELECT max(report_id) INTO batch_max
          FROM (SELECT report_id FROM report WHERE report_id > last_id ORDER BY report_id LIMIT batch_size) b;
        EXIT WHEN batch_max IS NULL;

        UPDATE report
           SET source_type = report_source_type_of(source)
         WHERE report_id > last_id AND report_id <= batch_max AND source_type IS NULL;
        GET DIAGNOSTICS updated = ROW_COUNT;

        total := total + updated;
        batches := batches + 1;
        last_id := batch_max;
        COMMIT;

        IF batches % 100 = 0 THEN
            RAISE NOTICE 'backfill: % rows updated, last report_id %, % rows/s',
                total, last_id, round(total / greatest(extract(epoch FROM clock_timestamp() - started), 0.001));
        END IF;
        IF pause_ms > 0 THEN
            PERFORM pg_sleep(pause_ms / 1000.0);
        END IF;
    END LOOP;
    RAISE NOTICE 'backfill done: % rows updated in % s', total,
        round(extract(epoch FROM clock_timestamp() - started)::numeric, 1);
END $$;
