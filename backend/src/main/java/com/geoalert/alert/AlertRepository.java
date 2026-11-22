package com.geoalert.alert;

import com.geoalert.common.Times;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * State changes set last_actor; the alert trigger turns every status change into an audit row in
 * the same transaction (R5). version and change_xid move with every change (live feed).
 */
@Repository
public class AlertRepository {

    private static final String TOUCH = "version = version + 1, change_xid = pg_current_xact_id()";

    private final JdbcTemplate jdbc;

    public AlertRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Alert> find(long id) {
        return jdbc.query("SELECT " + Alert.COLUMNS + " FROM alert WHERE alert_id = ?", Alert::map, id)
                .stream().findFirst();
    }

    /** Row lock: concurrent commands on one alert, on any instance, run one after the other (R4). */
    public Optional<Alert> lock(long id) {
        return jdbc.query("SELECT " + Alert.COLUMNS + " FROM alert WHERE alert_id = ? FOR UPDATE", Alert::map, id)
                .stream().findFirst();
    }

    public Alert claim(long id, String user) {
        return jdbc.queryForObject("UPDATE alert SET status = 'CLAIMED', claimed_by = ?, claimed_at = now(), last_actor = ?, "
                + TOUCH + " WHERE alert_id = ? AND status = 'OPEN' RETURNING " + Alert.COLUMNS, Alert::map, user, user, id);
    }

    public Alert release(long id, String actor) {
        return jdbc.queryForObject("UPDATE alert SET status = 'OPEN', claimed_by = NULL, claimed_at = NULL, last_actor = ?, "
                + TOUCH + " WHERE alert_id = ? AND status = 'CLAIMED' RETURNING " + Alert.COLUMNS, Alert::map, actor, id);
    }

    public Alert resolve(long id, String actor, String note) {
        return jdbc.queryForObject("UPDATE alert SET status = 'RESOLVED', resolved_by = ?, resolved_at = now(), "
                + "resolution_note = ?, last_actor = ?, " + TOUCH
                + " WHERE alert_id = ? AND status = 'CLAIMED' RETURNING " + Alert.COLUMNS, Alert::map, actor, note, actor, id);
    }

    public List<AuditEntry> audit(long id) {
        return jdbc.query("""
                SELECT at, actor, action, from_status, to_status
                  FROM alert_audit WHERE alert_id = ? ORDER BY audit_id
                """, (rs, i) -> new AuditEntry(Times.format(rs.getTimestamp(1)), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5)), id);
    }

    public List<Alert> page(AlertStatus status, String assetId, String zoneId, long afterId, int limit) {
        StringBuilder sql = new StringBuilder("SELECT " + Alert.COLUMNS + " FROM alert WHERE alert_id > ?");
        List<Object> args = new ArrayList<>();
        args.add(afterId);
        if (status != null) {
            sql.append(" AND status = ?");
            args.add(status.name());
        }
        if (assetId != null) {
            sql.append(" AND asset_id = ?");
            args.add(assetId);
        }
        if (zoneId != null) {
            sql.append(" AND zone_id = ?");
            args.add(zoneId);
        }
        sql.append(" ORDER BY alert_id LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), Alert::map, args.toArray());
    }
}
