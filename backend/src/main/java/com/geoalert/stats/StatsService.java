package com.geoalert.stats;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class StatsService {

    private final JdbcTemplate jdbc;

    public StatsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Stats(long received, long accepted, long duplicates, long rejected, long assets,
                        Map<String, Long> alerts) {
    }

    /** One snapshot (repeatable read) so the totals are consistent with each other. */
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Stats stats() {
        Map<String, Object> c = jdbc.queryForMap("""
                SELECT coalesce(sum(received), 0) AS received, coalesce(sum(accepted), 0) AS accepted,
                       coalesce(sum(duplicates), 0) AS duplicates, coalesce(sum(rejected), 0) AS rejected
                  FROM ingest_counter
                """);
        long assets = jdbc.queryForObject("SELECT count(*) FROM asset_position", Long.class);
        Map<String, Long> alerts = new LinkedHashMap<>();
        alerts.put("open", 0L);
        alerts.put("claimed", 0L);
        alerts.put("resolved", 0L);
        jdbc.query("SELECT status, count(*) FROM alert GROUP BY status",
                rs -> {
                    alerts.put(rs.getString(1).toLowerCase(), rs.getLong(2));
                });
        return new Stats(num(c.get("received")), num(c.get("accepted")), num(c.get("duplicates")),
                num(c.get("rejected")), assets, alerts);
    }

    private static long num(Object o) {
        return ((Number) o).longValue();
    }
}
