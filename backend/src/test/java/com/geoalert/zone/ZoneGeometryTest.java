package com.geoalert.zone;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Checks against the supplied zones.geojson, including its deliberate defects. */
class ZoneGeometryTest {

    private static ZoneFile.Parsed parsed;
    private static Map<String, Zone> zones;

    @BeforeAll
    static void load() throws Exception {
        try (InputStream in = ZoneGeometryTest.class.getResourceAsStream("/zones.geojson")) {
            parsed = ZoneFile.parse(in.readAllBytes());
        }
        zones = parsed.zones().stream().collect(Collectors.toMap(Zone::zoneId, Function.identity()));
    }

    private static boolean in(String zone, double lat, double lon) {
        return zones.get(zone).geometry().contains(lat, lon);
    }

    @Test
    void loadsAllTwelveZones() {
        assertThat(zones).hasSize(12);
        assertThat(zones.get("Z-09").raisesAlerts()).isFalse();
        assertThat(zones.get("Z-01").raisesAlerts()).isTrue();
    }

    @Test
    void simpleRectangle() {
        assertThat(in("Z-01", 38.3, 168.4)).isTrue();
        assertThat(in("Z-01", 37.9, 168.4)).isFalse();
    }

    @Test
    void holeIsOutside() {
        assertThat(in("Z-03", 39.2, 172.7)).isTrue();   // inside outer ring
        assertThat(in("Z-03", 39.6, 173.2)).isFalse();  // inside the hole
    }

    @Test
    void multiPolygonHasTwoParts() {
        assertThat(in("Z-06", 33.2, 167.2)).isTrue();
        assertThat(in("Z-06", 33.5, 168.1)).isTrue();
        assertThat(in("Z-06", 33.2, 167.7)).isFalse();  // between the parts
    }

    @Test
    void antimeridianZoneIsSmallNotTheWholeGlobe() {
        // Z-08 spans 179.3 east to 179.4 west across the 180th meridian.
        assertThat(in("Z-08", 34.5, 179.5)).isTrue();
        assertThat(in("Z-08", 34.5, -179.6)).isTrue();
        assertThat(in("Z-08", 34.5, 180.0)).isTrue();
        assertThat(in("Z-08", 34.5, -180.0)).isTrue();
        assertThat(in("Z-08", 34.5, 170.0)).isFalse();  // a naive reading covers this
        assertThat(in("Z-08", 34.5, 0.0)).isFalse();
        assertThat(in("Z-08", 34.5, -179.3)).isFalse();
        assertThat(parsed.warnings()).anyMatch(w -> w.startsWith("Z-08") && w.contains("180th meridian"));
    }

    @Test
    void clockwiseRingWorksAndIsReported() {
        assertThat(in("Z-02", 35.4, 171.0)).isTrue();
        assertThat(parsed.warnings()).anyMatch(w -> w.startsWith("Z-02") && w.contains("clockwise"));
    }

    @Test
    void overlappingZonesBothContainThePoint() {
        assertThat(in("Z-04", 32.6, 174.8)).isTrue();
        assertThat(in("Z-05", 32.6, 174.8)).isTrue();
    }

    @Test
    void tinyZone() {
        assertThat(in("Z-11", 31.015, 169.015)).isTrue();
        assertThat(in("Z-11", 31.05, 169.015)).isFalse();
    }

    @Test
    void refusesBrokenFiles() {
        String dupe = """
                {"type":"FeatureCollection","features":[
                 {"type":"Feature","properties":{"zoneId":"A","name":"a","class":"RESTRICTED"},
                  "geometry":{"type":"Polygon","coordinates":[[[0,0],[1,0],[1,1],[0,0]]]}},
                 {"type":"Feature","properties":{"zoneId":"A","name":"b","class":"RESTRICTED"},
                  "geometry":{"type":"Polygon","coordinates":[[[0,0],[1,0],[1,1],[0,0]]]}}]}""";
        assertThatThrownBy(() -> ZoneFile.parse(dupe.getBytes())).hasMessageContaining("duplicate");
        String badClass = dupe.replace("\"class\":\"RESTRICTED\"", "\"class\":\"SECRET\"");
        assertThatThrownBy(() -> ZoneFile.parse(badClass.getBytes())).hasMessageContaining("unknown class");
    }
}
