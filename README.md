# Restricted-zone monitoring

A Java/Spring Boot backend that ingests a live position feed and raises alerts when assets enter
restricted zones, a React map console for operators, and an offline Docker delivery with two backend
instances behind a load balancer.

- **Install offline:** [INSTALL.md](INSTALL.md) · **Diagnose:** [docs/RUNBOOK.md](docs/RUNBOOK.md) ·
  **Change record:** [docs/CR-1.md](docs/CR-1.md) · **Contract:** [openapi.yaml](openapi.yaml)
- **Measurements:** [docs/evidence/](docs/evidence/)

## Run it

**Online, from a clean checkout** (Java 21, Maven, Node 22, Docker):

```sh
# tests (unit + integration on a real PostgreSQL via Testcontainers, including two instances)
mvn -f backend/pom.xml test                    # report: backend/target/surefire-reports/
(cd frontend && npm ci && npm test)

# the whole stack, built locally
docker build --platform linux/amd64 -f backend/Dockerfile  -t geoalert-backend:1.1.0 .
docker build --platform linux/amd64 -f frontend/Dockerfile -t geoalert-console:1.1.0 .
cat seed-users.sample.json > deploy/secrets/users.json      # local only; never committed
printf '%s\n' 'local-db-password' > deploy/secrets/DB_PASSWORD
docker compose -f deploy/compose.yaml --env-file deploy/versions/1.1.0.env up -d
# console: http://localhost:8080/   feed: FEED_PASSWORD=... python3 replay.py --speed max
```

**Checks against a running stack:** `python3 scripts/smoke.py --users deploy/secrets/users.json`
(scenario, redelivery, late report, 20 parallel claims, audit, CSP);
`python3 scripts/bench_viewport.py`; `cd frontend && npx playwright test` (browser evidence, needs an
empty database); `python3 scripts/traffic.py` (counts failed requests during an upgrade).

**Offline:** `scripts/build-bundle.sh` builds every image from the release tags for linux/amd64 and
writes the release files and `SHA256SUMS` to `dist/release/`; the target follows [INSTALL.md](INSTALL.md).

## Architecture and data flow

```
 replay.py ──POST /reports/batch──┐                                     ┌── browser (console)
                                  ▼                                     ▼ GET /, /api/*, /api/v1/live (SSE)
                         ┌──────────────── lb (nginx :8080) ───────────────┐
                         │  /api → backend-1 | backend-2   / → console-1|2 │
                         └───────┬───────────────────────┬─────────────────┘
                         backend-1 (Spring Boot)   backend-2 (Spring Boot)      migrate (one-shot:
                                 │      change feed poll      │                 Flyway + zone load)
                                 └────────── PostgreSQL 16 ───┘
```

**A position report**: lb → one backend → `IngestController` (415/413 limits, splits lines) →
`ReportValidator` (each line alone, no database) → one transaction in `IngestService`:
advisory locks for the batch's assets (sorted) → `INSERT … ON CONFLICT (report_id) DO NOTHING
RETURNING` (what comes back is *accepted*, the rest *duplicate*) → current positions read →
`PositionTracker` walks the accepted reports in event time and finds zone entries → alerts inserted
(partial unique index skips a second active one; trigger writes the OPENED audit row) → positions
upserted → rejected lines and counters written → commit. **To the browser**: every instance polls
the database twice a second for rows changed since its last watermark (transaction-id based, see
`ChangeFeed`) and pushes them as server-sent events to the browsers connected to it, so a browser
sees changes made through either instance. The console merges them into stores outside React and
the canvas redraws on the next animation frame.

**A claim**: button (or Enter) → `POST /alerts/{id}/claim` → `AlertService.claim`: `SELECT … FOR
UPDATE` on the alert row → decide from its state (Table 6) → `UPDATE` → the trigger on `alert`
appends `CLAIMED` to `alert_audit` in the same transaction → commit → 200 to the caller; the
change feed brings the new state to every console. Losers get 409 `ALERT_ALREADY_CLAIMED`, which
the panel turns into "Someone else was faster: op03 claimed … first".

## The rules: mechanism and proof

All tests are in `backend/src/test/java/com/geoalert/`. Each runs against a real PostgreSQL over real
HTTP, most with requests sent in parallel through a start gate; `TwoInstancesTest` starts a second
application instance on the same database.

| Rule | Mechanism (where it cannot be bypassed) | Tests that fail when it breaks |
|---|---|---|
| **R1** exactly-once | `report_id` is the primary key; insert is `ON CONFLICT DO NOTHING RETURNING`, so a redelivery on any instance stores nothing and changes no position and raises no alert (alerts and positions are only derived from *newly inserted* rows). | `R1ExactlyOnceTest` (same batch 10× in parallel; redelivery after resolve; reused id with other payload), `TwoInstancesTest.sameBatchOnBothInstancesAtOnceIsStoredOnce`, second full replay (0 accepted) |
| **R2** event-time order | Per-asset `pg_advisory_xact_lock`, taken in sorted order by every instance; accepted reports are walked in `(observedAt, reportId)` order; the position upsert additionally has `WHERE (old.observed_at, old.report_id) < (new…)`. Late reports are stored but never move or alert. | `R2EventTimeTest` (60 shuffled reports in 12 parallel batches; late report inside a zone; late vs new racing), `PositionTrackerTest`, `TwoInstancesTest.outOfOrderAcrossInstancesEndsAtNewest` |
| **R3** one active alert | Partial unique index `alert (asset_id, zone_id) WHERE status IN ('OPEN','CLAIMED')`; alert insert is `ON CONFLICT … DO NOTHING`. | `R3OneActiveAlertTest` (30 parallel entries → 1; DB refuses a 2nd active row; re-entry after resolve; overlap, hole, antimeridian, advisory) |
| **R4** one owner | `SELECT … FOR UPDATE` on the alert row, decision under the lock; check constraint ties `claimed_by` to `status`; trigger refuses an owner change without a state change. | `R4OneOwnerTest` (20 operators × 5 rounds → exactly 1 winner, 19 × 409), `TwoInstancesTest.claimsSplitAcrossInstancesHaveOneWinner`, `AlertLifecycleTest` |
| **R5** audit trail | Trigger `AFTER INSERT OR UPDATE ON alert` writes the audit row, so it is in the same transaction for *any* writer (application or psql); illegal transitions raise; `alert_audit` has triggers that refuse UPDATE, DELETE and TRUNCATE. | `R5AuditTrailTest` (full lifecycle; repeated commands add nothing; SQL change outside the app is audited; append-only; illegal transition changes nothing) |
| **R6** bad input isolated | Validation per line outside the transaction mirrors every column constraint (ranges, lengths, control characters), so no line can fail the batch statement; raw rejected line stored as `bytea` (NUL bytes, invalid UTF-8 cannot fail); if the batch statement still fails with a data error, reports are retried one by one. Rejections are counted (`ingest_counter`) and listed by `GET /api/v1/rejections` (SUPERVISOR): line, code, raw text, time. | `R6BadInputTest` (every bad kind mixed with good lines; invalid UTF-8 + NUL; stats; bad and good batches in parallel), `ReportValidatorTest` (50 cases) |

## Five decisions and the alternatives I rejected

1. **The database enforces the rules; the application only decides.** Primary key, partial unique
   index, check constraints, triggers, row and advisory locks. *Rejected:* application-level locks or
   caches (do not protect the second instance), a distributed lock service (another component to run
   offline), `SERIALIZABLE` everywhere (retries under the feed's contention, harder to reason about).
2. **One transaction per batch, set-based, with per-asset advisory locks in sorted order.** A batch
   costs ~7 round trips whatever its size; sorting the lock keys makes deadlocks impossible.
   *Rejected:* a transaction per report (≈10× slower), `SELECT … FOR UPDATE` on `asset_position`
   (rows do not exist for new assets, so two first reports would race), a message broker in front
   (extra component; the HTTP request already is the unit of at-least-once delivery).
3. **Live updates by polling a transaction-id watermark, not a broker.** Rows carry the writing
   transaction's id; each poll reads rows at or above the previous snapshot's `xmin`, which can never
   skip a transaction that commits late. Each instance polls once for all its browsers. *Rejected:*
   `LISTEN/NOTIFY` (8 kB payload limit, lost while a listener reconnects, a global lock at commit),
   Redis/Kafka (extra component), timestamp polling (misses transactions that commit out of order).
4. **A hand-drawn canvas map instead of a map library.** No tiles, glyphs or fonts to bundle, works
   offline by construction, 3,000 headings as one path per colour, and seamless across the 180th
   meridian, which the data sits on. *Rejected:* MapLibre/Leaflet (need bundled glyphs/tiles; DOM
   markers do not scale to 1,500 updates/s), WebGL (not needed for 3,000 points).
5. **Migrations as an explicit one-shot `migrate` step, never by the running application.** Makes the
   CR-1 expand/backfill/contract order explicit and lets two versions run against one schema.
   *Rejected:* Flyway on application start-up (both instances race, and the contract step would run
   as soon as the first new instance starts).

## Data findings and what I did

Found by profiling the whole feed (225,748 lines) and the zone file; every case below has a test.

| Finding | Count | Decision |
|---|---|---|
| Truncated JSON lines; `"heartbeat"` strings and arrays instead of objects | 261 + 20 | `MALFORMED_JSON` |
| **`observedAt` = 2031-01-01** | 3 | **Rejected `INVALID_TIMESTAMP` (more than 24 h in the future, configurable).** Accepted, it would become the newest position for ever (R2) and freeze the asset: every real report after it would be "late" and no alert could ever be raised for it again. Rejecting keeps R2 literally true. |
| `observedAt` = 1970-01-01 (epoch sentinel) | 3 | Accepted (a valid instant); R2 makes it a late, track-only report. Earlier than 1970 is rejected. |
| `observedAt` without offset (`2026-11-02T06:05:25.697`), `02/11/2026 06:01:22`, `2026-13-45T99…`, `yesterday`, epoch numbers | ~120 | `INVALID_TIMESTAMP`: an instant needs an offset; guessing the zone would rewrite the recording. |
| `observedAt` with `+04:00` offset | 9 | Valid ISO-8601 instant; normalised to UTC, not rewritten. |
| Same `reportId`, different payload (position moved 0.5°) | 14 | The id is the report's identity (contract): the first stored wins, later ones are duplicates. Under parallel delivery "first" is arrival order; this is an upstream bug to report. |
| Empty / missing `reportId`, `assetId`, coordinates | 40+ | `MISSING_FIELD`. Whitespace-only counts as empty. |
| lat/lon as strings, booleans, objects, arrays; out of range (e.g. lon -761) | 160+ | `INVALID_COORDINATE`: numbers only, no coercion. |
| assetId `ast-00017`, `" AST-00017"`, `<script>…`, `'; DROP TABLE`, `../../etc/passwd`, U+FFFD | 40 | `INVALID_FIELD`, no normalisation (`ast-00017` is not silently merged into `AST-00017`). The pattern is matched on the whole string, so a trailing newline cannot slip through `$`. |
| `0,0` positions ("null island") | 5 | Accepted: valid by the contract and outside every zone; flagged here as a likely GPS failure upstream. |
| `headingDeg` 360, `speedKn` -1 | 36 / 2 | Stored as received (optional fields, no rule); the console shows heading mod 360 and negative speed as "unknown". |
| Extra fields `altM`, `meta` | 144 | Ignored. |
| Duplicate deliveries in the recording itself | 8,929 | Duplicates (R1). |
| **Zone Z-08 crosses the 180th meridian** (179.3 → -179.4) | 1 | Read naively it spans 358.7° of longitude and would put most of the map in a restricted zone. Rings are unwrapped and points tested at lon ± 360. |
| Z-02 outer ring is clockwise (RFC 7946 wants counter-clockwise) | 1 | Orientation ignored (even-odd test); warning in the migrate log. |
| Z-03 has a hole, Z-06 has two parts, Z-04/Z-05 overlap, Z-11 is ~3 km wide, Z-12 is at negative longitudes | | Handled by the containment rules; each raises its own alert. |

## Evidence

| What | Result | Source |
|---|---|---|
| Ingest, `replay.py --speed max --connections 4`, full stack | **4,351 lines/s** sustained, 0 HTTP errors | [ingest-throughput.txt](docs/evidence/ingest-throughput.txt) |
| Second replay of the same feed | 0 accepted, 224,882 duplicates, 866 rejected | same |
| Viewport, up to 1,000 assets matching, 4 concurrent | **p95 67.5 ms**, 98.8 % ≤ 100 ms (DB share 11.5 ms) | [viewport-benchmark.txt](docs/evidence/viewport-benchmark.txt), [query plan](docs/evidence/viewport-query-plan.txt) |
| Main thread at 5× replay with continuous pan/zoom | longest task **52 ms** (one task > 50 ms in 40 s) | [frontend-evidence.json](docs/evidence/frontend-evidence.json) |
| Requests to other hosts from the console | **0** (every request off-origin is blocked and listed); CSP `connect-src 'self'` | same |
| Accessibility, axe-core WCAG 2.1 A/AA | **0 violations** on sign-in, console, resolve dialog, zones view; claim and resolve done keyboard-only | same |
| 30 s network loss | console shows the interruption, returns to Live without reload, counts equal `/stats` | same |
| Rule invariants after an interrupted 5× replay | 0 violations of R2/R3/R5, counters equal stored rows | [ingest-throughput.txt](docs/evidence/ingest-throughput.txt) |
| Tests | 115 backend (unit + integration), 4 frontend unit, 1 browser end-to-end | `mvn test`, `npm test`, `npx playwright test` |

**Bottleneck.** Ingest throughput climbs over the run (3,173 → 4,351 lines/s) as the JIT warms up,
and the per-batch cost is in PostgreSQL: index maintenance (two indexes on `report`, three on
`asset_position`), WAL and commit, plus batches that share assets waiting on each other's advisory
locks. I did not profile beyond that; the target is met with about 2× headroom. Viewport latency is
dominated by JSON and the network path, not the query (11.5 ms in the database for 1,000 rows).

## Accessibility (WCAG 2.1 AA)

Everything in the alert workflow is a native button, radio, `<details>` or `<dialog>` (focus trap and
Escape for free), with a visible 3 px focus ring. New alerts are announced in a polite live region at
most once every 10 s, summarised ("4 new alerts, latest …") and can be muted; action results and lost
races go to an assertive region. The *Zones* tab lists which assets are inside which zone as text, so
nothing depends on seeing the map. The map itself is keyboard-operable (arrows, +/−, Escape).

## What depends on PostgreSQL, and MariaDB

- **Advisory locks** (`pg_advisory_xact_lock`): MariaDB `GET_LOCK()` is session-scoped, not
  transaction-scoped; I would instead keep one row per asset in `asset_position` and lock with
  `SELECT … FOR UPDATE` after an `INSERT IGNORE` of the asset row, in sorted order.
- **Partial unique index** (R3): MariaDB has none. Use a generated column
  `active_key = IF(status IN ('OPEN','CLAIMED'), CONCAT(asset_id,'|',zone_id), NULL)` with a unique
  index (NULLs do not collide).
- **`ON CONFLICT … RETURNING`**: MariaDB has `INSERT IGNORE` and (10.5+) `INSERT … RETURNING`, but
  RETURNING does not tell inserted from ignored rows; I would insert, then select which of the batch's
  ids carry this transaction's marker column, or compare `ROW_COUNT()` per row.
- **Triggers for the audit** port directly (MariaDB triggers are row-level and transactional); the
  append-only guard is a `BEFORE UPDATE/DELETE` trigger with `SIGNAL`.
- **Change feed** uses `xid8`/`pg_current_snapshot()`: on MariaDB I would use a monotonic `change_seq`
  from a sequence plus a short overlap window and client-side merge (already idempotent), or binlog CDC.
- **GiST index** on `point(lon, lat)`: MariaDB spatial index on a `POINT` column (InnoDB R-tree).
- **`COLLATE "C"`**: `utf8mb4_bin`. **CR-1** `ADD COLUMN` is instant in MariaDB 10.3+ as well;
  `NOT VALID`/`VALIDATE` constraints do not exist, so the final NOT NULL needs an online `ALTER`
  (`ALGORITHM=INPLACE, LOCK=NONE`) or a tool such as gh-ost.

## Building and patching inside the air gap

Keep a small build enclave inside the client network: a Linux build host with Docker/BuildKit, a
private registry (e.g. `registry:2` or Harbor) and mirrors of the three package ecosystems the build
needs: a Maven repository proxy (Nexus/Artifactory or a plain file repository), an npm registry mirror
(Verdaccio or an offline npm cache), and the pinned base images (`maven`, `eclipse-temurin`, `node`,
`nginx-unprivileged`, `postgres`). These are imported once through the client's media transfer
process: every artefact listed with its SHA-256, scanned, and approved; the lockfiles
(`package-lock.json`, the pinned Maven versions) define exactly what must be imported, and the build
then runs with `mvn -o` and `npm ci --offline`. A patch (say a CVE in Spring or in the JRE) is a new
import of only the changed artefacts plus the source change, built in the enclave by the same
`scripts/build-bundle.sh`, tagged, and rolled out with the same one-instance-at-a-time procedure as
CR-1. Base image updates follow the same path; the image digests and an SBOM per release make it
auditable which patched artefact went where.

## Limitations and incomplete areas

- **Users file reload** needs a rolling restart; tokens are valid for 8 h and are not revoked when a
  user is removed. No login rate limiting.
- **Live stream fan-out** writes to each browser from the poll thread; a very slow browser delays the
  others on that instance (fine for tens of operators; per-client queues would fix it).
- **Late-entry semantics**: by the brief, a late report never raises an alert, so the alert outcome
  for out-of-order input depends on arrival order. Within one batch reports are ordered by event time.
- **Same-millisecond ties**: the database keeps microseconds; the API shows milliseconds. The console
  can briefly prefer the wrong one of two reports observed in the same millisecond.
- **Alert list paging** is by id; an alert created by a transaction that commits after a client has
  paged past its id is not returned to that traversal (the contract's guarantee holds for a stable set).
- **Viewport p99** exceeds 100 ms at 1,000 matched assets under 4 concurrent clients on this laptop.
- **Console** shows at most 300 alert rows (resolved ones only after they change while connected).
- **Kubernetes manifests and CI** were not done ("Could").

## Actual effort

_To be filled in by the candidate._

## AI-assisted development

Tool: Claude Code (Claude Opus) in VS Code, used as a pair programmer for the whole submission: data
profiling scripts, backend, tests, frontend, Docker/compose, documents. I reviewed each change, ran
the tests and measurements myself, and steered the design decisions above. No instruction or rules
file was given to the agent beyond the brief and the candidate pack.

Cases where the generated output was wrong, unsafe or not good enough, and what changed:

1. **Ambiguous exception handler.** The generated `ProblemHandler` added an `@ExceptionHandler` for
   `AsyncRequestNotUsableException`, which Spring's `ResponseEntityExceptionHandler` already handles;
   the application context failed to start. Caught by the first integration test run; handler removed.
2. **Valid input classified as malformed.** The validator set Jackson's `maxStringLength` to 100,000,
   so a syntactically valid line with a long field came back `MALFORMED_JSON` instead of the field
   error. Noticed while writing the R6 test for a 200 kB line; the cap was removed (the 5 MB body
   limit already bounds it) and the test now expects the contract code.
3. **Fallback lost the line number.** The one-by-one fallback for R6 first stored rejected reports with
   line 0, so the response would not "name the line". Found in review; the line is now carried through.
4. **Drain filter answered 500, not 503.** The first version wrote the problem body (status 500) and
   set 503 afterwards, when the response was already committed, so the load balancer would not have
   retried it. Fixed by setting the status first.
5. **Docker build not reproducible offline.** The generated Dockerfile ran `mvn -o` after
   `dependency:go-offline`, which does not fetch every plugin and test-scope artefact; the build failed.
   It also added `# syntax=docker/dockerfile:1`, which pulls a frontend image from Docker Hub on every
   build and would break builds inside the air gap. Replaced with a BuildKit cache mount and the
   built-in frontend.
6. **The reconnect test did not test a network loss.** Chromium's offline emulation does not interrupt
   an already-open streaming response, so the console simply stayed "Live". The test now freezes the
   load balancer (`docker pause`), which stalls every connection like a cut link.
7. **CSP in a `<meta>` tag** would have broken the Vite dev server's inline preamble; it moved to the
   nginx response header, where it also covers the production build.
