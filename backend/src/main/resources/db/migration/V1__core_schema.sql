-- Core schema. Identifiers use COLLATE "C" so that ordering is byte order, identical
-- to the comparison done in Java (tie-break on reportId) and independent of the OS locale.

CREATE TABLE zone (
    zone_id     text PRIMARY KEY,
    name        text NOT NULL,
    zone_class  text NOT NULL CHECK (zone_class IN ('RESTRICTED', 'ADVISORY')),
    -- Normalised geometry (see ZoneGeometry): JSON array of polygons, each an array of rings.
    geometry    text NOT NULL,
    -- A zone removed from the file is deactivated, not deleted: old alerts still reference it.
    active      boolean NOT NULL DEFAULT true,
    loaded_at   timestamptz NOT NULL DEFAULT now()
);

-- Every accepted report. reportId is the identity of a report: the primary key makes a
-- second delivery a no-op (R1) whatever instance or connection it arrives on.
CREATE TABLE report (
    report_id    varchar(64) COLLATE "C" PRIMARY KEY,
    asset_id     varchar(32) COLLATE "C" NOT NULL,
    observed_at  timestamptz NOT NULL,
    lat          double precision NOT NULL CHECK (lat BETWEEN -90 AND 90),
    lon          double precision NOT NULL CHECK (lon BETWEEN -180 AND 180),
    kind         varchar(64),
    speed_kn     double precision,
    heading_deg  double precision,
    source       varchar(64),
    received_at  timestamptz NOT NULL DEFAULT now()
);
-- Track queries: one asset, ordered by event time.
CREATE INDEX report_asset_time_idx ON report (asset_id, observed_at, report_id);

-- Current position per asset (R2): always the accepted report with the newest observedAt.
-- change_xid is the writing transaction id; the live change feed reads it (see ChangeFeed).
CREATE TABLE asset_position (
    asset_id     varchar(32) COLLATE "C" PRIMARY KEY,
    report_id    varchar(64) COLLATE "C" NOT NULL REFERENCES report (report_id),
    observed_at  timestamptz NOT NULL,
    lat          double precision NOT NULL,
    lon          double precision NOT NULL,
    kind         varchar(64),
    speed_kn     double precision,
    heading_deg  double precision,
    source       varchar(64),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    change_xid   xid8 NOT NULL DEFAULT pg_current_xact_id()
);
CREATE INDEX asset_position_point_idx ON asset_position USING gist (point(lon, lat));
CREATE INDEX asset_position_change_idx ON asset_position (change_xid);

CREATE TABLE alert (
    alert_id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    asset_id             varchar(32) COLLATE "C" NOT NULL,
    zone_id              text NOT NULL REFERENCES zone (zone_id),
    status               text NOT NULL CHECK (status IN ('OPEN', 'CLAIMED', 'RESOLVED')),
    opened_at            timestamptz NOT NULL DEFAULT now(),
    trigger_report_id    varchar(64) COLLATE "C" NOT NULL,
    trigger_observed_at  timestamptz NOT NULL,
    claimed_by           text,
    claimed_at           timestamptz,
    resolved_by          text,
    resolved_at          timestamptz,
    resolution_note      text,
    -- Who made the last change; read by the audit trigger (R5).
    last_actor           text NOT NULL,
    version              integer NOT NULL DEFAULT 1,
    change_xid           xid8 NOT NULL DEFAULT pg_current_xact_id(),
    -- R4: the owner columns can only be in a state that matches the status.
    CONSTRAINT alert_state_consistent CHECK (
        (status = 'OPEN'     AND claimed_by IS NULL     AND resolved_by IS NULL) OR
        (status = 'CLAIMED'  AND claimed_by IS NOT NULL AND resolved_by IS NULL) OR
        (status = 'RESOLVED' AND resolved_by IS NOT NULL AND resolved_at IS NOT NULL
                             AND char_length(resolution_note) BETWEEN 1 AND 500)
    )
);
-- R3: at most one OPEN or CLAIMED alert per asset and zone, enforced by the database.
CREATE UNIQUE INDEX alert_one_active_idx ON alert (asset_id, zone_id) WHERE status IN ('OPEN', 'CLAIMED');
CREATE INDEX alert_status_idx ON alert (status, alert_id);
CREATE INDEX alert_asset_idx ON alert (asset_id, alert_id);
CREATE INDEX alert_zone_idx ON alert (zone_id, alert_id);
CREATE INDEX alert_change_idx ON alert (change_xid);

-- R5: append-only audit trail.
CREATE TABLE alert_audit (
    audit_id     bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    alert_id     bigint NOT NULL REFERENCES alert (alert_id),
    at           timestamptz NOT NULL,
    actor        text NOT NULL,
    action       text NOT NULL CHECK (action IN ('OPENED', 'CLAIMED', 'RELEASED', 'RESOLVED')),
    from_status  text,
    to_status    text NOT NULL
);
CREATE INDEX alert_audit_alert_idx ON alert_audit (alert_id, audit_id);

-- The audit row is written by a trigger on alert, so it is in the same transaction as the
-- state change and no code path (application, script, psql) can change the state without it.
CREATE FUNCTION alert_write_audit() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    v_action text;
BEGIN
    IF TG_OP = 'INSERT' THEN
        INSERT INTO alert_audit (alert_id, at, actor, action, from_status, to_status)
        VALUES (NEW.alert_id, NEW.opened_at, NEW.last_actor, 'OPENED', NULL, NEW.status);
        RETURN NEW;
    END IF;
    IF NEW.status IS NOT DISTINCT FROM OLD.status THEN
        -- R4: ownership may only change together with a status change (claim/release/resolve).
        IF NEW.claimed_by IS DISTINCT FROM OLD.claimed_by OR NEW.resolved_by IS DISTINCT FROM OLD.resolved_by THEN
            RAISE EXCEPTION 'alert owner cannot change without a state change';
        END IF;
        RETURN NEW;
    END IF;
    v_action := CASE
        WHEN OLD.status = 'OPEN'    AND NEW.status = 'CLAIMED'  THEN 'CLAIMED'
        WHEN OLD.status = 'CLAIMED' AND NEW.status = 'OPEN'     THEN 'RELEASED'
        WHEN OLD.status = 'CLAIMED' AND NEW.status = 'RESOLVED' THEN 'RESOLVED'
    END;
    IF v_action IS NULL THEN
        RAISE EXCEPTION 'illegal alert transition % -> %', OLD.status, NEW.status;
    END IF;
    INSERT INTO alert_audit (alert_id, at, actor, action, from_status, to_status)
    VALUES (NEW.alert_id, now(), NEW.last_actor, v_action, OLD.status, NEW.status);
    RETURN NEW;
END $$;

CREATE TRIGGER alert_audit_trg
    AFTER INSERT OR UPDATE ON alert
    FOR EACH ROW EXECUTE FUNCTION alert_write_audit();

CREATE FUNCTION alert_audit_forbid_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'alert_audit is append-only';
END $$;

CREATE TRIGGER alert_audit_append_only
    BEFORE UPDATE OR DELETE ON alert_audit
    FOR EACH ROW EXECUTE FUNCTION alert_audit_forbid_change();
CREATE TRIGGER alert_audit_no_truncate
    BEFORE TRUNCATE ON alert_audit
    FOR EACH STATEMENT EXECUTE FUNCTION alert_audit_forbid_change();

-- R6: every rejected line, kept for inspection. The raw line is bytea so that no content
-- (NUL bytes, invalid UTF-8) can make the insert fail and take good lines down with it.
CREATE TABLE rejected_line (
    rejected_id    bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    received_at    timestamptz NOT NULL DEFAULT now(),
    request_id     varchar(64),
    line_no        integer NOT NULL,
    code           text NOT NULL,
    detail         text,
    raw            bytea NOT NULL,
    raw_truncated  boolean NOT NULL
);

-- Running totals for /stats. Sharded so that parallel ingest transactions do not queue
-- on one hot row; /stats sums the shards.
CREATE TABLE ingest_counter (
    shard       smallint PRIMARY KEY,
    received    bigint NOT NULL DEFAULT 0,
    accepted    bigint NOT NULL DEFAULT 0,
    duplicates  bigint NOT NULL DEFAULT 0,
    rejected    bigint NOT NULL DEFAULT 0
);
INSERT INTO ingest_counter (shard) SELECT generate_series(0, 15);

-- Token signing key shared by all instances; created on first start (see TokenKeyStore).
CREATE TABLE app_secret (
    name        text PRIMARY KEY,
    value       bytea NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now()
);
