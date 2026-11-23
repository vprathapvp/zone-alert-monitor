package com.geoalert.asset;

/** sourceType: CR-1 (1.1.0). */
public record TrackPoint(String reportId, String observedAt, double lat, double lon, String source,
                         String sourceType) {
}
