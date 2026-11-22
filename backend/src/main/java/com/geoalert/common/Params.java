package com.geoalert.common;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

/** Query parameter parsing with contract-defined refusals (400 VALIDATION_FAILED). */
public final class Params {

    private Params() {
    }

    public static int limit(String raw, int defaultValue, int max) {
        if (raw == null) {
            return defaultValue;
        }
        int value;
        try {
            value = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw ApiException.validation("limit must be an integer from 1 to " + max);
        }
        if (value < 1 || value > max) {
            throw ApiException.validation("limit must be an integer from 1 to " + max);
        }
        return value;
    }

    public static Instant instant(String name, String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw.trim()).toInstant();
        } catch (DateTimeParseException e) {
            throw ApiException.validation(name + " must be an ISO-8601 instant, for example 2026-11-02T06:00:05.120Z");
        }
    }
}
