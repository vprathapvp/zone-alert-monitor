package com.geoalert.asset;

public record TrackPoint(String reportId, String observedAt, double lat, double lon, String source) {
}
