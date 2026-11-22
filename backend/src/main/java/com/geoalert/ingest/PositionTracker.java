package com.geoalert.ingest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Applies R2 and the alert rule for one asset. Pure: no I/O, so it is unit-tested exhaustively.
 *
 * The newly accepted reports of a batch are walked in event-time order starting from the stored
 * current position. A report newer than the running position moves it; an entry into a RESTRICTED
 * zone is detected between consecutive positions, so an asset that enters and leaves within one
 * batch still raises its alert (no interpolation: only reported positions count). A report not
 * newer than the running position is late: track only, no move, no alert.
 */
public final class PositionTracker {

    public interface ZoneLookup {
        Set<String> restrictedZonesAt(double lat, double lon);
    }

    public record Current(String reportId, Instant observedAt, double lat, double lon) {
    }

    public record Entry(String zoneId, ValidReport trigger) {
    }

    /** newPosition is null when the current position does not change. */
    public record Result(ValidReport newPosition, List<Entry> entries, int late) {
    }

    private static final Comparator<ValidReport> EVENT_TIME = (a, b) ->
            ValidReport.compareEventTime(a.observedAt(), a.reportId(), b.observedAt(), b.reportId());

    private PositionTracker() {
    }

    public static Result advance(Current current, List<ValidReport> accepted, ZoneLookup zones) {
        List<ValidReport> ordered = new ArrayList<>(accepted);
        ordered.sort(EVENT_TIME);

        Instant curTime = current == null ? null : current.observedAt();
        String curId = current == null ? null : current.reportId();
        Set<String> curZones = current == null ? Set.of() : zones.restrictedZonesAt(current.lat(), current.lon());

        ValidReport moved = null;
        List<Entry> entries = new ArrayList<>();
        int late = 0;
        for (ValidReport r : ordered) {
            boolean newer = curTime == null
                    || ValidReport.compareEventTime(r.observedAt(), r.reportId(), curTime, curId) > 0;
            if (!newer) {
                late++;
                continue;
            }
            Set<String> zonesNow = zones.restrictedZonesAt(r.lat(), r.lon());
            for (String zoneId : zonesNow) {
                if (!curZones.contains(zoneId)) {
                    entries.add(new Entry(zoneId, r));
                }
            }
            curTime = r.observedAt();
            curId = r.reportId();
            curZones = zonesNow;
            moved = r;
        }
        return new Result(moved, entries, late);
    }
}
