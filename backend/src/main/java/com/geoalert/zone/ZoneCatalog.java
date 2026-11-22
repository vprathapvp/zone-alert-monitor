package com.geoalert.zone;

import com.geoalert.ingest.PositionTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Active zones, read once from the zone table at start-up. Both instances read the same rows,
 * so they evaluate containment identically. Reloading zones means re-running migrate and
 * restarting instances (zones are reference data, not live data).
 */
@Component
public class ZoneCatalog implements PositionTracker.ZoneLookup {

    private static final Logger log = LoggerFactory.getLogger(ZoneCatalog.class);

    private final List<Zone> zones;
    private final List<Zone> restricted;
    private final List<ZoneRow> rows;

    public record ZoneRow(String zoneId, String name, String zoneClass, String geometryJson) {
    }

    public ZoneCatalog(JdbcTemplate jdbc) {
        this.rows = jdbc.query("SELECT zone_id, name, zone_class, geometry FROM zone WHERE active ORDER BY zone_id",
                (rs, i) -> new ZoneRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)));
        List<Zone> all = new ArrayList<>();
        for (ZoneRow r : rows) {
            try {
                all.add(new Zone(r.zoneId(), r.name(), Zone.ZoneClass.valueOf(r.zoneClass()),
                        ZoneFile.fromJson(r.geometryJson())));
            } catch (IOException e) {
                throw new UncheckedIOException("zone " + r.zoneId() + " has unreadable geometry", e);
            }
        }
        if (all.isEmpty()) {
            throw new IllegalStateException("no zones in the database; run the migrate step first");
        }
        this.zones = List.copyOf(all);
        this.restricted = all.stream().filter(Zone::raisesAlerts).toList();
        log.info("zones ready: total={} restricted={}", zones.size(), restricted.size());
    }

    public List<Zone> zones() {
        return zones;
    }

    public List<ZoneRow> rows() {
        return rows;
    }

    /** RESTRICTED zones that contain the point. ADVISORY zones never raise alerts. */
    @Override
    public Set<String> restrictedZonesAt(double lat, double lon) {
        Set<String> hits = null;
        for (Zone z : restricted) {
            if (z.geometry().contains(lat, lon)) {
                if (hits == null) {
                    hits = new LinkedHashSet<>();
                }
                hits.add(z.zoneId());
            }
        }
        return hits == null ? Set.of() : hits;
    }
}
