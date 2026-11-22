package com.geoalert.ingest;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** R2 and the alert rule for one asset, without a database. */
class PositionTrackerTest {

    /** Zone "Z" is lat 10..20; zone "Y" is lat 15..30 (overlapping). */
    private static final PositionTracker.ZoneLookup ZONES = (lat, lon) -> {
        Set<String> s = new java.util.TreeSet<>();
        if (lat >= 10 && lat <= 20) {
            s.add("Z");
        }
        if (lat >= 15 && lat <= 30) {
            s.add("Y");
        }
        return s;
    };

    private static ValidReport r(String id, int second, double lat) {
        return new ValidReport(id, "A-1", Instant.parse("2026-11-02T06:00:00Z").plusSeconds(second), lat, 0,
                null, null, null, null);
    }

    private static PositionTracker.Current cur(ValidReport r) {
        return new PositionTracker.Current(r.reportId(), r.observedAt(), r.lat(), r.lon());
    }

    @Test
    void firstReportInsideAZoneIsAnEntry() {
        var result = PositionTracker.advance(null, List.of(r("a", 1, 12)), ZONES);
        assertThat(result.newPosition().reportId()).isEqualTo("a");
        assertThat(result.entries()).extracting(PositionTracker.Entry::zoneId).containsExactly("Z");
    }

    @Test
    void stayingInsideIsNotAnEntry() {
        var result = PositionTracker.advance(cur(r("a", 1, 12)), List.of(r("b", 2, 13)), ZONES);
        assertThat(result.entries()).isEmpty();
        assertThat(result.newPosition().reportId()).isEqualTo("b");
    }

    @Test
    void lateReportNeverMovesAndNeverAlerts() {
        var current = cur(r("b", 10, 0));
        var result = PositionTracker.advance(current, List.of(r("a", 5, 12)), ZONES);
        assertThat(result.newPosition()).isNull();
        assertThat(result.entries()).isEmpty();
        assertThat(result.late()).isEqualTo(1);
    }

    @Test
    void enterAndLeaveInsideOneBatchStillRaises() {
        var result = PositionTracker.advance(cur(r("a", 0, 0)),
                List.of(r("c", 2, 0), r("b", 1, 12)), ZONES);   // delivered out of order
        assertThat(result.newPosition().reportId()).isEqualTo("c");
        assertThat(result.entries()).extracting(e -> e.trigger().reportId()).containsExactly("b");
    }

    @Test
    void overlappingZonesEachRaise() {
        var result = PositionTracker.advance(cur(r("a", 0, 0)), List.of(r("b", 1, 17)), ZONES);
        assertThat(result.entries()).extracting(PositionTracker.Entry::zoneId).containsExactlyInAnyOrder("Z", "Y");
    }

    @Test
    void movingBetweenOverlappingZonesOnlyRaisesTheNewOne() {
        var result = PositionTracker.advance(cur(r("a", 0, 12)), List.of(r("b", 1, 17)), ZONES);
        assertThat(result.entries()).extracting(PositionTracker.Entry::zoneId).containsExactly("Y");
    }

    @Test
    void equalObservedAtIsBrokenByReportIdWhateverTheArrivalOrder() {
        var x = r("x", 1, 0);
        var y = r("y", 1, 12);
        assertThat(PositionTracker.advance(cur(x), List.of(y), ZONES).newPosition()).isEqualTo(y);
        assertThat(PositionTracker.advance(cur(y), List.of(x), ZONES).newPosition()).isNull();
    }

    @Test
    void resultDoesNotDependOnOrderWithinTheBatch() {
        List<ValidReport> reports = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            reports.add(r("r" + i, i, i % 3 == 0 ? 12 : 0));
        }
        var expected = PositionTracker.advance(null, reports, ZONES);
        for (int k = 0; k < 10; k++) {
            Collections.shuffle(reports);
            var got = PositionTracker.advance(null, reports, ZONES);
            assertThat(got.newPosition()).isEqualTo(expected.newPosition());
            assertThat(got.entries()).isEqualTo(expected.entries());
        }
    }
}
