# Runbook

Everything below uses only what exists on the site: `docker`, `docker compose`, and the tools inside
the containers (`psql` in `db`, `wget` in `lb`). Run the commands from the bundle directory. `$C` means
`docker compose --env-file versions/1.1.0.env`.

## First look (any symptom)

```
$C ps                                              # all services Up; db, lb, console-* (healthy)
$C logs --since 10m lb | tail -50                  # every request: status, upstream, durationMs, requestId
$C logs --since 10m backend-1 backend-2 | grep -E '"level":"(WARN|ERROR)"'
$C exec lb wget -qO- http://127.0.0.1:8080/health  # {"status":"UP"} = one instance can serve
```
Logs are JSON, one line per event, with `requestId`. A request ID from a client error leads through the
lb line to the backend line: `$C logs backend-1 backend-2 | grep <requestId>`.

## Ingest is slow

1. **Measure.** Each backend logs `ingest summary (last 60 s)` once a minute with `linesPerSec`,
   `accepted`, `duplicates`, `rejected`, `http5xx`: `$C logs --since 5m backend-1 backend-2 | grep "ingest summary"`.
   The two instances together should do well over 2,000 lines/s. `replay.py` prints the client's view.
2. **Is it the database?** Look at active queries and waits:
   ```
   $C exec db psql -U geoalert -c "SELECT pid, state, wait_event_type, wait_event, now()-xact_start AS age, left(query,80)
                                    FROM pg_stat_activity WHERE datname='geoalert' AND state<>'idle' ORDER BY age DESC"
   ```
   - Many `Lock / advisory`: batches serialise on the same assets. Expected under heavy overlap; it shows
     as lower throughput, not errors. Smaller batches from the sender spread the locks.
   - A long `age` on one `idle in transaction` session: something holds a transaction open (e.g. a manual
     psql session). It also holds back the live feed (see below). `idle_in_transaction_session_timeout`
     (60 s) ends it; or `SELECT pg_terminate_backend(<pid>)`.
   - `IO`/`WALWrite` waits: disk is the bottleneck. Check `docker stats` for the db container.
   - Slow statements are logged by PostgreSQL itself (> 500 ms): `$C logs --since 10m db | grep duration`.
3. **Is it the backend?** `docker stats` CPU near the limit on backend-* means validation/JSON is the
   limit. `$C logs backend-1 | grep -c "transient database error"` counts retried batches.
4. **Rejections are not slowness**, but a sender sending garbage looks like low accepted counts.
   Inspect them as a supervisor: `GET /api/v1/rejections` (line, code, raw text, time).

## The console stops updating

1. The header says *"Live updates interrupted … reconnecting"*: the browser lost its stream. It retries
   every ≤ 5 s and reloads a full snapshot on reconnect; nothing to do once the network is back.
2. Header says *Live* but nothing moves: is data arriving at all? `GET /api/v1/stats` twice: `accepted`
   must grow. If not, the problem is the feed (see *Ingest is slow*), not the console.
3. Is the change feed running? `$C logs --since 5m backend-1 backend-2 | grep "change feed poll failed"`.
   Subscribers per instance: `$C exec lb wget -qO- --header "Authorization: Bearer <supervisor token>" http://127.0.0.1:8080/actuator/metrics/live.subscribers`.
4. A long-running transaction delays live updates: the feed sends only what committed after the oldest
   running transaction started. Find it with the `pg_stat_activity` query above (oldest `age`).
5. The lb keeps streams open for 1 h (`proxy_read_timeout`); heartbeats every 15 s keep them alive. If a
   firewall between browser and lb cuts idle connections faster than 15 s, the console reconnects in a loop:
   visible as many `/api/v1/live` lines in the lb log.

## A claim hangs

A claim locks one alert row (`SELECT … FOR UPDATE`) and normally takes a few milliseconds.

1. Find the blocker:
   ```
   $C exec db psql -U geoalert -c "SELECT a.pid, a.state, now()-a.xact_start AS age, pg_blocking_pids(a.pid) AS blocked_by, left(a.query,80)
                                    FROM pg_stat_activity a WHERE cardinality(pg_blocking_pids(a.pid)) > 0"
   ```
2. The blocker is almost always a transaction left open on the `alert` table (manual SQL session) or an
   ingest batch stuck on the database disk. Terminate the blocking pid if it is a manual session:
   `SELECT pg_terminate_backend(<pid>)`.
3. If no lock is involved, check the request reached a backend: lb log line for the request (status,
   `upstream`, `durationMs`). `upstreamStatus` with two values means the lb retried on the other instance.
4. Claims are safe to retry from the console: a repeated claim by the owner answers 200 and changes nothing.

## Useful numbers

`GET /api/v1/stats` (any role) · `/actuator/prometheus` and `/actuator/metrics/{name}` (SUPERVISOR) with
`ingest.lines{outcome}`, `ingest.batch` (p95/p99), `ingest.late`, `alerts.opened`, `live.subscribers`,
`http.server.requests{status}`.
