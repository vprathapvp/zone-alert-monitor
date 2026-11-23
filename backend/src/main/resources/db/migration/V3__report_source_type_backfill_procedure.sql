-- CR-1 backfill, run by the operator after all instances are on 1.1.0 (INSTALL.md, step 4):
--   CALL report_backfill_source_type(batch_blocks, pause_ms[, start_block]);
--
-- Walks the table in PHYSICAL order, batch_blocks heap pages per transaction (TID range scan,
-- PostgreSQL 14+), and commits every batch. Each batch is a short sequential read of consecutive
-- pages. Walking the primary key instead would hit one random page per row, because report ids are
-- random: in rehearsal that saturated the disk and stalled live commits behind WAL flushes.
--
-- Only rows still NULL are touched, so the procedure can be cancelled and re-run (or resumed from
-- the last printed block). Rows written after V2 already have a value (trigger), and a row this
-- procedure moves to a later page already has its value too, so the end block is fixed at start.
-- Ingest never updates existing reports, so batches do not contend with live writes for row locks;
-- pause_ms leaves disk and WAL bandwidth to them.
CREATE PROCEDURE report_backfill_source_type(batch_blocks integer DEFAULT 64,
                                             pause_ms integer DEFAULT 50,
                                             start_block bigint DEFAULT 0)
    LANGUAGE plpgsql
AS $$
DECLARE
    end_block bigint := pg_relation_size('report') / current_setting('block_size')::bigint;
    b         bigint := start_block;
    updated   bigint;
    total     bigint := 0;
    batches   bigint := 0;
    started   timestamptz := clock_timestamp();
BEGIN
    RAISE NOTICE 'backfill: blocks % to %', start_block, end_block;
    WHILE b < end_block LOOP
        SET LOCAL lock_timeout = '2s';
        UPDATE report
           SET source_type = report_source_type_of(source)
         WHERE ctid >= format('(%s,0)', b)::tid
           AND ctid <  format('(%s,0)', b + batch_blocks)::tid
           AND source_type IS NULL;
        GET DIAGNOSTICS updated = ROW_COUNT;
        total := total + updated;
        batches := batches + 1;
        b := b + batch_blocks;
        COMMIT;

        IF batches % 200 = 0 THEN
            RAISE NOTICE 'backfill: block % of %, % rows, % rows/s', b, end_block, total,
                round(total / greatest(extract(epoch FROM clock_timestamp() - started), 0.001));
        END IF;
        IF pause_ms > 0 THEN
            PERFORM pg_sleep(pause_ms / 1000.0);
        END IF;
    END LOOP;
    RAISE NOTICE 'backfill done: % rows in % s (% rows/s)', total,
        round(extract(epoch FROM clock_timestamp() - started)::numeric, 1),
        round(total / greatest(extract(epoch FROM clock_timestamp() - started), 0.001));
END $$;
