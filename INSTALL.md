# Installation (offline)

Target: Linux x86_64, Docker Engine with the Compose plugin. Only `docker`, `docker compose`, `tar`,
`gzip`, `cat` and `sha256sum` are used. Nothing is built or pulled; no network access is needed.

Put all release files in one directory (`geoalert-deploy-1.1.0.tar.gz`, `images-base.tar.gz`,
`images-1.0.0.tar.gz`, `images-1.1.0.tar.gz`, `SHA256SUMS`) and run the commands from it.

**Users file.** The users file (JSON array of `username`, `role`, `password`, like
`seed-users.sample.json`) is a deploy-time secret. It goes to `geoalert/secrets/users.json` and is
mounted read-only into the backends as `/run/secrets/users.json`. The database password goes to
`geoalert/secrets/DB_PASSWORD` (one line, your choice). Neither file is in any image.

**Addresses.** Console: `http://<host>:8080/` · API: `http://<host>:8080/api/v1/...` ·
Health: `http://<host>:8080/health`. The stack is ready when `docker compose ps` shows `lb` as
`healthy` (about one minute after start).

## A. Start v1.1.0 on an empty database (5 commands)

```sh
sha256sum -c SHA256SUMS
tar -xzf geoalert-deploy-1.1.0.tar.gz && cd geoalert
for f in ../images-base.tar.gz ../images-1.1.0.tar.gz; do docker load -i "$f"; done
cat /path/to/users.json > secrets/users.json && printf '%s\n' 'choose-a-db-password' > secrets/DB_PASSWORD
docker compose --env-file versions/1.1.0.env up -d
```

## B. Start v1.0.0 on an empty database

```sh
sha256sum -c SHA256SUMS
tar -xzf geoalert-deploy-1.1.0.tar.gz && cd geoalert
for f in ../images-base.tar.gz ../images-1.0.0.tar.gz; do docker load -i "$f"; done
cat /path/to/users.json > secrets/users.json && printf '%s\n' 'choose-a-db-password' > secrets/DB_PASSWORD
docker compose --env-file versions/1.0.0.env up -d
```

(The deploy files are the same for both versions; `versions/*.env` selects the image versions.)

## C. Upgrade a running v1.0.0 to v1.1.0, one instance at a time

Run from the `geoalert` directory of the running v1.0.0. Traffic keeps flowing throughout; every step
can be stopped and rolled back (see `CR-1.md` for locks, checks and rollback of each step).

```sh
# 0. Load the 1.1.0 images (nothing changes yet).
docker load -i ../images-1.1.0.tar.gz

# 1. Expand the schema: nullable source_type column, fill trigger, backfill procedure. Old code keeps working.
docker compose --env-file versions/upgrade-1-expand.env run --rm migrate

# 2. Instance 1 (and console replica 1) to 1.1.0; instance 2 stays on 1.0.0. Waits until healthy.
docker compose --env-file versions/upgrade-2-half.env up -d --no-deps --wait backend-1 console-1

# 3. Instance 2 (and console replica 2) to 1.1.0.
docker compose --env-file versions/1.1.0.env up -d --no-deps --wait backend-2 console-2

# 4. Backfill source_type for existing rows, in small committed batches (resumable, can be cancelled).
docker compose --env-file versions/1.1.0.env exec -T db psql -U geoalert -d geoalert -v ON_ERROR_STOP=1 -c "CALL report_backfill_source_type(5000, 10)"

# 5. Verify: must print 0.
docker compose --env-file versions/1.1.0.env exec -T db psql -U geoalert -d geoalert -tA -c "SELECT count(*) FROM report WHERE source_type IS NULL"

# 6. Contract: make source_type mandatory (validated without blocking writes).
docker compose --env-file versions/1.1.0.env run --rm migrate
```

## Day-to-day

```sh
docker compose --env-file versions/1.1.0.env ps          # status and health
docker compose --env-file versions/1.1.0.env logs -f lb  # JSON request log (status, upstream, requestId)
docker compose --env-file versions/1.1.0.env down        # stop (data stays in the pgdata volume)
docker compose --env-file versions/1.1.0.env down -v     # stop and DELETE the database
```

Changing users: replace `secrets/users.json`, then restart one backend at a time:
`docker compose --env-file versions/1.1.0.env restart backend-1` (then `backend-2`).

Diagnosis: see `RUNBOOK.md`.
