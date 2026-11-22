package com.geoalert.ingest;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Set-based SQL for one ingest transaction. Every statement takes whole arrays (unnest), so a
 * batch costs a fixed handful of round trips regardless of its size.
 */
@Repository
public class IngestRepository {

    /** Namespace for advisory locks so they cannot collide with any other advisory lock user. */
    static final int ASSET_LOCK_NAMESPACE = 0x2A51;

    private final JdbcTemplate jdbc;

    public IngestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Serialises transactions that touch the same asset, on every instance (the lock lives in the
     * database). Keys must arrive sorted and distinct: all transactions take their locks in the
     * same global order, so two batches can never wait on each other in a cycle.
     */
    public void lockAssets(int[] sortedDistinctKeys) {
        if (sortedDistinctKeys.length == 0) {
            return;
        }
        Integer[] keys = new Integer[sortedDistinctKeys.length];
        for (int i = 0; i < keys.length; i++) {
            keys[i] = sortedDistinctKeys[i];
        }
        jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "SELECT pg_advisory_xact_lock(" + ASSET_LOCK_NAMESPACE + ", k) FROM unnest(?::int[]) AS t(k)");
            ps.setArray(1, con.createArrayOf("int4", keys));
            return ps;
        }, rs -> {
        });
    }

    /** R1: inserts what is new, skips what is already stored. Returns the reportIds inserted. */
    public Set<String> insertReports(List<ValidReport> sortedById) {
        if (sortedById.isEmpty()) {
            return Set.of();
        }
        Set<String> inserted = new HashSet<>();
        jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO report (report_id, asset_id, observed_at, lat, lon, kind, speed_kn, heading_deg, source)
                    SELECT * FROM unnest(?::varchar[], ?::varchar[], ?::timestamptz[], ?::float8[], ?::float8[],
                                         ?::varchar[], ?::float8[], ?::float8[], ?::varchar[])
                    ON CONFLICT (report_id) DO NOTHING
                    RETURNING report_id
                    """);
            bindReports(con, ps, sortedById);
            return ps;
        }, rs -> {
            inserted.add(rs.getString(1));
        });
        return inserted;
    }

    public Map<String, PositionTracker.Current> currentPositions(Collection<String> assetIds) {
        Map<String, PositionTracker.Current> result = new HashMap<>();
        if (assetIds.isEmpty()) {
            return result;
        }
        jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "SELECT asset_id, report_id, observed_at, lat, lon FROM asset_position WHERE asset_id = ANY (?::varchar[])");
            ps.setArray(1, con.createArrayOf("varchar", assetIds.toArray()));
            return ps;
        }, rs -> {
            result.put(rs.getString(1), new PositionTracker.Current(rs.getString(2),
                    rs.getTimestamp(3).toInstant(), rs.getDouble(4), rs.getDouble(5)));
        });
        return result;
    }

    /**
     * Opens alerts for zone entries. R3 is enforced by the partial unique index: when an OPEN or
     * CLAIMED alert already exists for the asset and zone, the row is skipped. The audit row
     * (OPENED) is written by the alert trigger in this same transaction (R5).
     */
    public int openAlerts(List<String> assetIds, List<String> zoneIds, List<ValidReport> triggers) {
        if (assetIds.isEmpty()) {
            return 0;
        }
        String[] reportIds = triggers.stream().map(ValidReport::reportId).toArray(String[]::new);
        String[] times = triggers.stream().map(t -> t.observedAt().toString()).toArray(String[]::new);
        int[] count = {0};
        jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO alert (asset_id, zone_id, status, trigger_report_id, trigger_observed_at, last_actor)
                    SELECT a, z, 'OPEN', r, t, 'system'
                      FROM unnest(?::varchar[], ?::text[], ?::varchar[], ?::timestamptz[]) AS u(a, z, r, t)
                    ON CONFLICT (asset_id, zone_id) WHERE status IN ('OPEN', 'CLAIMED') DO NOTHING
                    RETURNING alert_id
                    """);
            ps.setArray(1, con.createArrayOf("varchar", assetIds.toArray()));
            ps.setArray(2, con.createArrayOf("text", zoneIds.toArray()));
            ps.setArray(3, con.createArrayOf("varchar", reportIds));
            ps.setArray(4, con.createArrayOf("text", times));
            return ps;
        }, rs -> {
            count[0]++;
        });
        return count[0];
    }

    /**
     * R2: writes the new current positions. The WHERE clause is a second line of defence behind
     * the asset lock: a row is only replaced by a report that is newer in event time.
     * Returns the number of rows written.
     */
    public int upsertPositions(List<ValidReport> positions) {
        if (positions.isEmpty()) {
            return 0;
        }
        return jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO asset_position (report_id, asset_id, observed_at, lat, lon, kind, speed_kn, heading_deg, source,
                                                updated_at, change_xid)
                    SELECT u.*, now(), pg_current_xact_id()
                      FROM unnest(?::varchar[], ?::varchar[], ?::timestamptz[], ?::float8[], ?::float8[],
                                  ?::varchar[], ?::float8[], ?::float8[], ?::varchar[]) AS u
                    ON CONFLICT (asset_id) DO UPDATE
                       SET report_id = EXCLUDED.report_id, observed_at = EXCLUDED.observed_at,
                           lat = EXCLUDED.lat, lon = EXCLUDED.lon, kind = EXCLUDED.kind,
                           speed_kn = EXCLUDED.speed_kn, heading_deg = EXCLUDED.heading_deg, source = EXCLUDED.source,
                           updated_at = now(), change_xid = pg_current_xact_id()
                     WHERE (asset_position.observed_at, asset_position.report_id)
                         < (EXCLUDED.observed_at, EXCLUDED.report_id)
                    """);
            bindReports(con, ps, positions);
            return ps;
        });
    }

    public void insertRejections(List<RejectedLine> rejected, String requestId) {
        if (rejected.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("""
                INSERT INTO rejected_line (request_id, line_no, code, detail, raw, raw_truncated)
                VALUES (?, ?, ?, ?, ?, ?)
                """, rejected, rejected.size(), (ps, r) -> {
            ps.setString(1, requestId);
            ps.setInt(2, r.lineNo());
            ps.setString(3, r.code().name());
            ps.setString(4, r.detail());
            ps.setBytes(5, r.raw());
            ps.setBoolean(6, r.truncated());
        });
    }

    public void addCounters(int shard, long received, long accepted, long duplicates, long rejected) {
        jdbc.update("""
                UPDATE ingest_counter
                   SET received = received + ?, accepted = accepted + ?, duplicates = duplicates + ?, rejected = rejected + ?
                 WHERE shard = ?
                """, received, accepted, duplicates, rejected, shard);
    }

    private static void bindReports(Connection con, PreparedStatement ps, List<ValidReport> reports) throws SQLException {
        int n = reports.size();
        String[] ids = new String[n];
        String[] assets = new String[n];
        String[] times = new String[n];
        Double[] lats = new Double[n];
        Double[] lons = new Double[n];
        String[] kinds = new String[n];
        Double[] speeds = new Double[n];
        Double[] headings = new Double[n];
        String[] sources = new String[n];
        for (int i = 0; i < n; i++) {
            ValidReport r = reports.get(i);
            ids[i] = r.reportId();
            assets[i] = r.assetId();
            times[i] = r.observedAt().toString();
            lats[i] = r.lat();
            lons[i] = r.lon();
            kinds[i] = r.kind();
            speeds[i] = r.speedKn();
            headings[i] = r.headingDeg();
            sources[i] = r.source();
        }
        ps.setArray(1, con.createArrayOf("varchar", ids));
        ps.setArray(2, con.createArrayOf("varchar", assets));
        ps.setArray(3, con.createArrayOf("text", times));
        ps.setArray(4, con.createArrayOf("float8", lats));
        ps.setArray(5, con.createArrayOf("float8", lons));
        ps.setArray(6, con.createArrayOf("varchar", kinds));
        ps.setArray(7, con.createArrayOf("float8", speeds));
        ps.setArray(8, con.createArrayOf("float8", headings));
        ps.setArray(9, con.createArrayOf("varchar", sources));
    }
}
