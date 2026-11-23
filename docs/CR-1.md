# CR-1 change record: report source type (v1.1.0)

**Change.** Every stored report gets `source_type` (SAT… = SATELLITE, GND… = GROUND, MOB… = MOBILE,
anything else or none = UNKNOWN), mandatory for old and new rows; the track returns it and accepts
`?sourceType=`; the console shows it; `openapi.yaml` is 1.1.0.
**Constraints.** 500 M rows written all the time; instances upgraded one at a time (1.0.0 and 1.1.0
run together); no downtime, no failed request, no long lock; stop and roll back after every step.

**Design: expand → roll out → backfill → contract.** The schema first becomes able to hold the value
without requiring it, every writer (old or new) fills it, old rows are filled in small batches, and only
then is it made mandatory. Every migration sets `lock_timeout = 5s`: a DDL statement that cannot get its
lock quickly fails instead of making live writes queue behind it, and is simply re-run.

## Steps (commands: INSTALL.md section C)

| # | Step | What it locks, and how long | Verify | Roll back |
|---|---|---|---|---|
| 0 | Load 1.1.0 images | nothing | `docker images` shows `:1.1.0` | `docker rmi` |
| 1 | **Expand** (`migrate`, target V3): function `report_source_type_of`; `ADD COLUMN source_type text` (nullable, no default); `CHECK (…) NOT VALID`; `BEFORE INSERT` trigger that fills it; backfill procedure | `ACCESS EXCLUSIVE` on `report` for each `ALTER`/`CREATE TRIGGER`: catalogue-only, milliseconds; no rewrite, no scan | `\d report` shows the column and trigger; a new 1.0.0 insert gets a value: `SELECT source_type FROM report ORDER BY received_at DESC LIMIT 5` | Nothing needs undoing: 1.0.0 ignores the column. To remove it: `DROP TRIGGER report_fill_source_type_trg ON report; ALTER TABLE report DROP COLUMN source_type;` (catalogue-only) and delete versions 2-3 from `flyway_schema_history` |
| 2 | **Instance 1 → 1.1.0** (and console replica 1) | none in the DB. The old instance drains (answers 503 for 5 s, the lb retries elsewhere), then finishes in-flight requests | `docker compose ps` healthy; `GET /api/v1/assets/{id}/track` through the lb returns `sourceType` on some answers (mixed versions) | Same command with `versions/upgrade-1-expand.env` (back to 1.0.0); drains the same way |
| 3 | **Instance 2 → 1.1.0** (and console replica 2) | none | every track answer has `sourceType`; `/health` UP | `versions/upgrade-2-half.env` puts instance 2 back; step 2's rollback after that |
| 4 | **Backfill**: `CALL report_backfill_source_type(64, 50)`: 64 heap pages (≈ 4,000 rows) per transaction in physical order, 50 ms pause | Per batch: `ROW EXCLUSIVE` + row locks on the rows of 64 pages, one short committed transaction. Ingest only inserts new rows and never updates old ones, so there is no row contention | NOTICE every 200 batches (block, rows, rows/s); `SELECT count(*) FROM report WHERE source_type IS NULL` falls | Cancel at any time (Ctrl-C / `pg_cancel_backend`): only the current batch rolls back; done batches stay (harmless: the value is correct). Resume with the last printed block as `start_block` |
| 5 | **Verify** | none | prints `0` | n/a |
| 6 | **Contract** (`migrate`, latest): V4 `ADD CHECK (source_type IS NOT NULL) NOT VALID`, `VALIDATE`; V5 `SET NOT NULL`, drop the helper check | V4: `VALIDATE` takes `SHARE UPDATE EXCLUSIVE` while it scans: **reads and writes continue**, only other DDL/VACUUM wait. V5: `ACCESS EXCLUSIVE` for milliseconds: `SET NOT NULL` uses the validated check and does not scan (PG ≥ 12) | `information_schema.columns.is_nullable = 'NO'` for `report.source_type`; `flyway_schema_history` at 5 | V4 fails and changes nothing if any NULL is left (re-run the backfill). Undo: `ALTER TABLE report ALTER COLUMN source_type DROP NOT NULL` (catalogue-only); 1.1.0 and 1.0.0 both keep working |

**Mixed versions.** During steps 2-3 a 1.0.0 instance writes without the column (the trigger fills
it) and serves tracks without `sourceType` (an addition, so 1.0.0 clients are unaffected). A 1.1.0
instance writes the value itself and, for old rows not yet backfilled, derives it with the same SQL
function (`coalesce(source_type, report_source_type_of(source))`), so its answers are complete before
the backfill. 1.1.0 refuses to start unless V2 exists (`SchemaGuard`), so it cannot run before step 1.

## What must be true before the final step (6)

1. Step 5 prints 0 rows with `source_type IS NULL`.
2. The fill trigger exists (step 1), so no writer, old or new, can add a NULL between 5 and 6.
3. Both instances run 1.1.0 (not strictly required thanks to the trigger, but it keeps the trigger
   removable in a later release) and no long-running transaction holds `report` (`pg_stat_activity`),
   otherwise V5's short exclusive lock waits up to `lock_timeout` and the step is re-run.

## Backfill at 500 million rows

Measured on the developer laptop (Docker Desktop, PostgreSQL 16.8 with default I/O settings, live
traffic running): **14,400 rows/s** with `batch_blocks = 64, pause_ms = 50` over
2.2 M rows (see `docs/evidence/upgrade-rehearsal.txt`). At that rate 500 M rows take
**≈ 9.6 hours** (500 M / 14,400 rows/s ≈ 34,700 s). On production hardware with fast storage I would expect 2-4× that rate; the
VALIDATE scan of step 6 adds one sequential read of the table (tens of minutes at 500 M rows) without
blocking writes.

## Keeping the backfill from hurting live traffic

- Small transactions (5,000 rows), committed one by one: no long-held locks, no long-running
  transaction that would hold back VACUUM or the console's change feed (which waits for the oldest
  running transaction).
- **Physical order.** Each batch is a TID range scan over 64 consecutive heap pages: a short
  sequential read, constant cost however far it has got. The first version walked the primary key;
  report ids are random, so every row was a random page read. In rehearsal that saturated the disk,
  and a live ingest COMMIT waited 456 s for its WAL flush. Walking pages fixed it (see evidence).
- `pause_ms` between batches leaves I/O and WAL bandwidth to ingest; raise it if ingest latency
  (`ingest.batch` p95, `ingest summary` log, slow COMMITs in the PostgreSQL log) rises, lower it at
  night. It can be stopped and resumed at any point with `start_block`.
- `lock_timeout = 2s` per batch: a batch that would wait on a lock fails instead of blocking others.
- WAL and bloat: every row is rewritten once (≈ table size in WAL). Run it with replication / backup
  capacity in mind, and let autovacuum (or a manual `VACUUM report` afterwards) reclaim the old tuples.
- No new index: the `?sourceType=` filter applies after the existing `(asset_id, observed_at)` index has
  selected one asset's rows, so no `CREATE INDEX` on 500 M rows is needed.

## Rehearsal

`scripts/upgrade-rehearsal.sh` runs exactly these commands on a loaded 1.0.0 (recorded feed plus 2 M
synthetic rows) while `scripts/traffic.py` reads, claims, resolves and writes fresh reports every
100 ms, and counts every failed request. Result of the final run: **13,470 requests, 0 failed**, p99 178 ms, max 2.8 s; the three earlier
runs and what each one fixed are in `docs/evidence/upgrade-rehearsal.txt`.
