package com.geoalert.live;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.geoalert.alert.Alert;
import com.geoalert.asset.AssetPosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * Brings changes made by ANY instance to the browsers connected to THIS instance, using only
 * PostgreSQL (no broker).
 *
 * Every write to asset_position and alert stamps change_xid = the writing transaction's id. Each
 * poll reads, in one snapshot, the snapshot's xmin (every transaction with a smaller id has
 * finished) and all rows with change_xid >= the previous poll's xmin. A transaction still running
 * at poll time has an id >= this poll's xmin, so the next poll picks it up after it commits: no
 * change is skipped, whatever order transactions commit in. A row can be sent twice; the console
 * merges by (observedAt, reportId) for positions and by version for alerts, so repeats are harmless.
 */
@Component
public class ChangeFeed {

    private static final Logger log = LoggerFactory.getLogger(ChangeFeed.class);
    private static final int MAX_POSITIONS = 20_000;
    private static final int MAX_ALERTS = 5_000;
    private static final int CHUNK = 1_000;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate snapshotTx;
    private final LiveHub hub;
    private final ObjectMapper mapper;
    private volatile String watermark;

    public ChangeFeed(JdbcTemplate jdbc, PlatformTransactionManager tm, LiveHub hub, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.hub = hub;
        this.mapper = mapper;
        this.snapshotTx = new TransactionTemplate(tm);
        snapshotTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshotTx.setReadOnly(true);
        snapshotTx.setTimeout(5);
    }

    private record Delta(String xmin, List<AssetPosition> positions, List<Alert> alerts) {
    }

    @Scheduled(fixedDelayString = "${geoalert.live-poll-interval-ms:500}")
    public void poll() {
        try {
            String since = watermark;
            boolean read = since != null && hub.hasSubscribers();
            Delta delta = snapshotTx.execute(s -> {
                String xmin = jdbc.queryForObject("SELECT pg_snapshot_xmin(pg_current_snapshot())::text", String.class);
                if (!read) {
                    return new Delta(xmin, List.of(), List.of());
                }
                List<AssetPosition> positions = jdbc.query("SELECT " + AssetPosition.COLUMNS
                        + " FROM asset_position WHERE change_xid >= ?::xid8 LIMIT ?", AssetPosition::map, since, MAX_POSITIONS + 1);
                List<Alert> alerts = jdbc.query("SELECT " + Alert.COLUMNS
                        + " FROM alert WHERE change_xid >= ?::xid8 ORDER BY alert_id LIMIT ?", Alert::map, since, MAX_ALERTS + 1);
                return new Delta(xmin, positions, alerts);
            });
            if (delta == null) {
                return;
            }
            watermark = delta.xmin();
            if (delta.positions().size() > MAX_POSITIONS || delta.alerts().size() > MAX_ALERTS) {
                // Too much to stream (e.g. a long transaction held xmin back): clients reload.
                hub.broadcast("resync", "{}");
                return;
            }
            for (int i = 0; i < delta.positions().size(); i += CHUNK) {
                hub.broadcast("positions", mapper.writeValueAsString(
                        delta.positions().subList(i, Math.min(i + CHUNK, delta.positions().size()))));
            }
            if (!delta.alerts().isEmpty()) {
                hub.broadcast("alerts", mapper.writeValueAsString(delta.alerts()));
            }
        } catch (JsonProcessingException | RuntimeException e) {
            log.warn("live change feed poll failed: {}", e.getClass().getSimpleName());
        }
    }
}
