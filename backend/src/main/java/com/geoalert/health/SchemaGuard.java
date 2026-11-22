package com.geoalert.health;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Refuses to start against a schema that lacks the migrations this build needs, with a clear
 * message instead of SQL errors at the first request. Newer schemas are accepted: migrations are
 * expand-first, so an older build keeps working on a newer schema during a rolling upgrade.
 */
@Component
public class SchemaGuard {

    /** Highest migration this build depends on. */
    static final String REQUIRED_VERSION = "1";

    public SchemaGuard(JdbcTemplate jdbc) {
        Integer applied = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version = ? AND success", Integer.class, REQUIRED_VERSION);
        if (applied == null || applied == 0) {
            throw new IllegalStateException("database schema lacks migration V" + REQUIRED_VERSION
                    + "; run the migrate step before starting this version");
        }
    }
}
