#!/usr/bin/env bash
# Rehearses the v1.0.0 -> v1.1.0 upgrade under traffic, with the exact commands of INSTALL.md (C).
#
#   scripts/upgrade-rehearsal.sh [EXTRA_ROWS]
#
# Starts 1.0.0 on an empty database, loads the recorded feed (plus EXTRA_ROWS synthetic reports to
# measure the backfill rate, default 2,000,000), starts scripts/traffic.py (reads, claims, resolves
# and fresh report batches), runs the six upgrade steps with timings, and reports every failed request.
# Needs: images geoalert-{backend,console}:{1.0.0,1.1.0} loaded, deploy/secrets/{users.json,DB_PASSWORD}.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
EXTRA_ROWS="${1:-2000000}"
cd "$ROOT/deploy"
PY="${PYTHON:-python3}"
FEED_PASSWORD="$("$PY" -I -c "import json;print([u for u in json.load(open('secrets/users.json')) if u['role']=='INGEST'][0]['password'])")"
export FEED_PASSWORD
step() { printf '\n== %s  [%s]\n' "$1" "$(date -u +%H:%M:%S)"; }
timed() { local s=$SECONDS; "$@"; echo "   ($((SECONDS - s)) s)"; }
wait_healthy() { until docker compose --env-file versions/1.0.0.env ps --format '{{.Service}} {{.Status}}' | grep -qE '^lb .*\(healthy\)'; do sleep 2; done; }

step "fresh v1.0.0"
docker compose --env-file versions/1.0.0.env down -v >/dev/null 2>&1 || true
docker compose --env-file versions/1.0.0.env up -d >/dev/null 2>&1
wait_healthy

step "load: recorded feed + $EXTRA_ROWS synthetic reports"
"$PY" -I "$ROOT/replay.py" --speed max --connections 4 | tail -1
docker compose --env-file versions/1.0.0.env exec -T db psql -U geoalert -d geoalert -q -c "
  INSERT INTO report (report_id, asset_id, observed_at, lat, lon, source)
  SELECT md5(g::text), 'SYN-' || (g % 3000), now() - g * interval '10 ms', 1, 1,
         (ARRAY['SAT-1','GND-1','MOB-1','XYZ-9',NULL])[1 + g % 5]
    FROM generate_series(1, $EXTRA_ROWS) g;"
docker compose --env-file versions/1.0.0.env exec -T db psql -U geoalert -d geoalert -tA -c "SELECT 'reports: ' || count(*) FROM report"

step "traffic on (reads, claims, resolves, fresh batches every 100 ms)"
STOP_FILE=".traffic.stop"; rm -f "$STOP_FILE"   # relative: same file for any shell and Python
"$PY" -I "$ROOT/scripts/traffic.py" --password-file secrets/users.json --stop-file "$STOP_FILE" > /tmp/traffic.log 2>&1 &
TRAFFIC=$!
trap 'touch "$STOP_FILE"' EXIT   # traffic stops even if a step fails
sleep 10

step "0. load 1.1.0 images (already loaded here)"
step "1. expand";          timed docker compose --env-file versions/upgrade-1-expand.env run --rm migrate 2>&1 | grep -c '"level":"ERROR"' || true
step "2. instance 1";      timed docker compose --env-file versions/upgrade-2-half.env up -d --no-deps --wait backend-1 console-1 2>&1 | tail -1
sleep 10
step "3. instance 2";      timed docker compose --env-file versions/1.1.0.env up -d --no-deps --wait backend-2 console-2 2>&1 | tail -1
step "4. backfill";        timed docker compose --env-file versions/1.1.0.env exec -T db psql -U geoalert -d geoalert -v ON_ERROR_STOP=1 -c "CALL report_backfill_source_type(64, 50)" 2>&1 | grep -E "NOTICE" | tail -3
step "5. verify";          docker compose --env-file versions/1.1.0.env exec -T db psql -U geoalert -d geoalert -tA -c "SELECT count(*) FROM report WHERE source_type IS NULL"
step "6. contract";        timed docker compose --env-file versions/1.1.0.env run --rm migrate 2>&1 | grep -c '"level":"ERROR"' || true
sleep 10

step "traffic off"
touch "$STOP_FILE"; wait "$TRAFFIC" || true; rm -f "$STOP_FILE"
tail -12 /tmp/traffic.log
echo "lb: non-2xx answers during the run (502/503 here were retried on the other instance):"
docker compose --env-file versions/1.1.0.env logs --no-log-prefix lb 2>/dev/null | grep '"message":"request"'   | grep -oE '"status":[0-9]+|"upstreamStatus":"[^"]*"' | paste - - | sort | uniq -c | grep -v '"status":2[0-9][0-9]	"upstreamStatus":"2[0-9][0-9]"' || true
docker compose --env-file versions/1.1.0.env exec -T db psql -U geoalert -d geoalert -tA -c \
  "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank"
