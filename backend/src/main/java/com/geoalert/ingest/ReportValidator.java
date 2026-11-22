package com.geoalert.ingest;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.core.JsonFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.regex.Pattern;

/**
 * Judges one line of a batch on its own. Checks run in the order of the contract table
 * (MALFORMED_JSON, MISSING_FIELD, INVALID_COORDINATE, INVALID_TIMESTAMP, INVALID_FIELD); the first
 * failure is reported. Every value that reaches the database is checked here against the column's
 * constraints, so one bad line can never make the batch insert fail (R6).
 */
public final class ReportValidator {

    private static final Pattern ASSET_ID = Pattern.compile("[A-Z0-9][A-Z0-9-]{2,31}");
    private static final int MAX_REPORT_ID = 64;
    private static final int MAX_OPTIONAL_TEXT = 64;
    /** Earliest accepted observedAt. Anything earlier is not a plausible recording. */
    private static final Instant EARLIEST = Instant.parse("1970-01-01T00:00:00Z");
    private static final String[] REQUIRED = {"reportId", "assetId", "observedAt", "lat", "lon"};

    public sealed interface Outcome permits Valid, Invalid {
    }

    public record Valid(ValidReport report) implements Outcome {
    }

    public record Invalid(RejectionCode code, String detail) implements Outcome {
    }

    private final ObjectMapper mapper;
    private final Clock clock;
    private final Duration maxFutureSkew;

    public ReportValidator(Clock clock, Duration maxFutureSkew) {
        this.clock = clock;
        this.maxFutureSkew = maxFutureSkew;
        JsonFactory factory = JsonFactory.builder()
                .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(64).build())
                .build();
        this.mapper = JsonMapper.builder(factory)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .build();
    }

    public Outcome validate(String line) {
        JsonNode node;
        try {
            node = mapper.readTree(line);
        } catch (Exception e) {
            return new Invalid(RejectionCode.MALFORMED_JSON, "line is not valid JSON");
        }
        if (node == null || !node.isObject()) {
            return new Invalid(RejectionCode.MALFORMED_JSON, "line is not a JSON object");
        }

        for (String field : REQUIRED) {
            JsonNode v = node.get(field);
            if (v == null || v.isNull() || (v.isTextual() && v.asText().isBlank())) {
                return new Invalid(RejectionCode.MISSING_FIELD, field + " is missing, null or empty");
            }
        }

        JsonNode latNode = node.get("lat");
        JsonNode lonNode = node.get("lon");
        if (!latNode.isNumber() || !lonNode.isNumber()) {
            return new Invalid(RejectionCode.INVALID_COORDINATE, "lat and lon must be JSON numbers");
        }
        double lat = latNode.asDouble();
        double lon = lonNode.asDouble();
        if (!Double.isFinite(lat) || lat < -90 || lat > 90) {
            return new Invalid(RejectionCode.INVALID_COORDINATE, "lat must be from -90 to 90");
        }
        if (!Double.isFinite(lon) || lon < -180 || lon > 180) {
            return new Invalid(RejectionCode.INVALID_COORDINATE, "lon must be from -180 to 180");
        }

        JsonNode tsNode = node.get("observedAt");
        if (!tsNode.isTextual()) {
            return new Invalid(RejectionCode.INVALID_TIMESTAMP, "observedAt must be an ISO-8601 instant string");
        }
        Instant observedAt;
        try {
            // An instant needs an offset: "2026-11-02T06:00:05.120Z" or "...+04:00". A local
            // date-time without offset is ambiguous and refused.
            observedAt = OffsetDateTime.parse(tsNode.asText(), DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    .toInstant().truncatedTo(ChronoUnit.MICROS);
        } catch (DateTimeParseException e) {
            return new Invalid(RejectionCode.INVALID_TIMESTAMP, "observedAt is not an ISO-8601 instant with offset");
        }
        if (observedAt.isBefore(EARLIEST)) {
            return new Invalid(RejectionCode.INVALID_TIMESTAMP, "observedAt is before 1970-01-01T00:00:00Z");
        }
        if (observedAt.isAfter(clock.instant().plus(maxFutureSkew))) {
            // A future timestamp would become the "newest" position forever and freeze the asset
            // (every real report after it would be late). See README, data findings.
            return new Invalid(RejectionCode.INVALID_TIMESTAMP, "observedAt is in the future");
        }

        JsonNode assetNode = node.get("assetId");
        if (!assetNode.isTextual() || !ASSET_ID.matcher(assetNode.asText()).matches()) {
            return new Invalid(RejectionCode.INVALID_FIELD, "assetId must match ^[A-Z0-9][A-Z0-9-]{2,31}$");
        }
        JsonNode idNode = node.get("reportId");
        if (!idNode.isTextual()) {
            return new Invalid(RejectionCode.INVALID_FIELD, "reportId must be a string");
        }
        String reportId = idNode.asText();
        if (reportId.codePointCount(0, reportId.length()) > MAX_REPORT_ID) {
            return new Invalid(RejectionCode.INVALID_FIELD, "reportId is longer than 64 characters");
        }
        if (!isStorableText(reportId)) {
            return new Invalid(RejectionCode.INVALID_FIELD, "reportId contains control characters");
        }

        String kind;
        String source;
        Double speed;
        Double heading;
        try {
            kind = optionalText(node, "kind");
            source = optionalText(node, "source");
            speed = optionalNumber(node, "speedKn");
            heading = optionalNumber(node, "headingDeg");
        } catch (IllegalArgumentException e) {
            return new Invalid(RejectionCode.INVALID_FIELD, e.getMessage());
        }
        return new Valid(new ValidReport(reportId, assetNode.asText(), observedAt, lat, lon, kind, speed, heading, source));
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        if (!v.isTextual()) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        String s = v.asText();
        if (s.codePointCount(0, s.length()) > MAX_OPTIONAL_TEXT || !isStorableText(s)) {
            throw new IllegalArgumentException(field + " must be at most 64 printable characters");
        }
        return s;
    }

    private static Double optionalNumber(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        if (!v.isNumber() || !Double.isFinite(v.asDouble())) {
            throw new IllegalArgumentException(field + " must be a number");
        }
        return v.asDouble();
    }

    /** No control characters (PostgreSQL text cannot hold NUL) and no unpaired surrogates. */
    static boolean isStorableText(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                return false;
            }
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1))) {
                    return false;
                }
                i++;
            } else if (Character.isLowSurrogate(c)) {
                return false;
            }
        }
        return true;
    }
}
