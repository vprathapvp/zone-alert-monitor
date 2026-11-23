# Runbook

Only what exists on site: `docker compose`, plus `psql` inside `db` and `wget` inside `lb`. Run from
the bundle directory; `$C` = `docker compose --env-file versions/1.1.0.env`, `$Q` =
`$C exec db psql -U geoalert -d geoalert -c`.

**First look, any symptom:** `$C ps` (all Up, healthy) · `$C logs --since 10m lb` (JSON per request:
status, upstream, `durationMs`, `requestId`) · `$C logs --since 10m backend-1 backend-2 | grep -E '"(WARN|ERROR)"'`.
A client's `X-Request-Id` finds the lb line and the backend line: `$C logs | grep <id>`.

## Slow ingest

1. **Rate:** each backend logs `ingest summary (last 60 s)` every minute (`linesPerSec`, accepted,
   duplicates, rejected, http5xx): `$C logs --since 5m backend-1 backend-2 | grep "ingest summary"`.
2. **Database waits:**
   `$Q "SELECT pid, state, wait_event_type, wait_event, now()-xact_start AS age, left(query,60) FROM pg_stat_activity WHERE datname='geoalert' AND state<>'idle' ORDER BY age DESC"`
   - `Lock/advisory`: batches waiting on each other's assets: expected under overlap, lowers throughput.
   - `idle in transaction` with a large age: an open manual session; `SELECT pg_terminate_backend(<pid>)`.
   - `IO` / `WALWrite`, or slow `COMMIT` lines in `$C logs db | grep duration` (> 500 ms is logged):
     the disk is saturated, e.g. by the CR-1 backfill. Cancel it (`pg_cancel_backend`) or raise `pause_ms`.
3. **Backend CPU:** `docker stats`. Retried batches: `$C logs backend-1 | grep -c "transient database error"`.
4. Many **rejections** look like low throughput: `GET /api/v1/rejections` (supervisor) shows line, code,
   raw text and time.

## Console stops updating

1. Header shows *"Live updates interrupted … reconnecting"*: the browser lost its stream and retries every
   ≤ 5 s, reloading a full snapshot on reconnect. Nothing to do once the network is back.
2. Header shows *Live* but nothing moves: call `GET /api/v1/stats` twice. If `accepted` does not grow,
   the feed is the problem (see above), not the console.
3. Change feed errors: `$C logs --since 5m backend-1 backend-2 | grep "change feed poll failed"`.
4. A long-running transaction delays live updates (the feed waits for the oldest running transaction):
   find it with the query in *Slow ingest* step 2.

## A claim hangs

A claim locks one alert row and normally takes milliseconds.
1. Blocker: `$Q "SELECT pid, now()-xact_start AS age, pg_blocking_pids(pid) AS blocked_by, left(query,60) FROM pg_stat_activity WHERE cardinality(pg_blocking_pids(pid)) > 0"`.
   Usually a manual session holding `alert` open; `SELECT pg_terminate_backend(<blocker pid>)`.
2. No lock: find the request in the lb log; two `upstreamStatus` values mean the lb retried it on the other
   instance. A slow `COMMIT` in the db log means the disk (see *Slow ingest*).
3. Retrying is safe: a repeated claim by the owner answers 200 and changes nothing.

**Metrics (no external service):** `GET /api/v1/stats`; `/actuator/metrics/{name}` and
`/actuator/prometheus` (supervisor token): `ingest.lines{outcome}`, `ingest.batch`, `ingest.late`,
`alerts.opened`, `live.subscribers`, `http.server.requests`.
