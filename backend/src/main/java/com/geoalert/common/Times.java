package com.geoalert.common;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** UTC, ISO-8601 with milliseconds and Z, as the house convention requires. */
public final class Times {

    private static final DateTimeFormatter API = DateTimeFormatter
            .ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC);

    private Times() {
    }

    public static String format(Instant instant) {
        return instant == null ? null : API.format(instant);
    }

    public static String format(Timestamp ts) {
        return ts == null ? null : API.format(ts.toInstant());
    }
}
