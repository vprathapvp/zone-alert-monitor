package com.geoalert.ingest;

/**
 * CR-1: the source type derived from a report's source field. Must match the SQL function
 * report_source_type_of (V2 migration), which fills rows written by older versions.
 */
public enum SourceType {
    SATELLITE, GROUND, MOBILE, UNKNOWN;

    public static SourceType of(String source) {
        if (source == null) {
            return UNKNOWN;
        }
        if (source.startsWith("SAT")) {
            return SATELLITE;
        }
        if (source.startsWith("GND")) {
            return GROUND;
        }
        if (source.startsWith("MOB")) {
            return MOBILE;
        }
        return UNKNOWN;
    }
}
