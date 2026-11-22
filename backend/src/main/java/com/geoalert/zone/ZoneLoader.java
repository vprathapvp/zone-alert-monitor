package com.geoalert.zone;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

/** Writes the parsed zone file to the zone table in one transaction. Idempotent. */
public final class ZoneLoader {

    private ZoneLoader() {
    }

    public static int sync(Connection c, List<Zone> zones) throws SQLException {
        boolean autoCommit = c.getAutoCommit();
        c.setAutoCommit(false);
        try {
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO zone (zone_id, name, zone_class, geometry, active, loaded_at)
                    VALUES (?, ?, ?, ?, true, now())
                    ON CONFLICT (zone_id) DO UPDATE
                       SET name = EXCLUDED.name, zone_class = EXCLUDED.zone_class,
                           geometry = EXCLUDED.geometry, active = true, loaded_at = now()
                    """)) {
                for (Zone z : zones) {
                    ps.setString(1, z.zoneId());
                    ps.setString(2, z.name());
                    ps.setString(3, z.zoneClass().name());
                    ps.setString(4, ZoneFile.toJson(z.geometry()));
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            Array ids = c.createArrayOf("text", zones.stream().map(Zone::zoneId).toArray());
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE zone SET active = false WHERE NOT (zone_id = ANY (?)) AND active")) {
                ps.setArray(1, ids);
                ps.executeUpdate();
            }
            c.commit();
            return zones.size();
        } catch (SQLException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(autoCommit);
        }
    }
}
