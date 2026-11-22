package com.geoalert.ingest;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;

/** A report that passed validation. observedAt is truncated to microseconds (database precision). */
public record ValidReport(
        String reportId,
        String assetId,
        Instant observedAt,
        double lat,
        double lon,
        String kind,
        Double speedKn,
        Double headingDeg,
        String source) {

    /**
     * Event-time order used for R2. Equal observedAt is broken by reportId in byte order (the same
     * order as COLLATE "C" in the database) so the outcome never depends on arrival order.
     */
    public static int compareEventTime(Instant aTime, String aId, Instant bTime, String bId) {
        int c = aTime.compareTo(bTime);
        if (c != 0) {
            return c;
        }
        return Arrays.compareUnsigned(aId.getBytes(StandardCharsets.UTF_8), bId.getBytes(StandardCharsets.UTF_8));
    }
}
