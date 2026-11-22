package com.geoalert.ingest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/** Table 4 of the brief, plus the malformed inputs found in the supplied feed. */
class ReportValidatorTest {

    private final ReportValidator validator = new ReportValidator(
            Clock.fixed(Instant.parse("2026-11-20T00:00:00Z"), ZoneOffset.UTC), Duration.ofHours(24));

    private static String line(String reportId, String assetId, String observedAt, String lat, String lon) {
        return "{\"reportId\":" + reportId + ",\"assetId\":" + assetId + ",\"observedAt\":" + observedAt
                + ",\"lat\":" + lat + ",\"lon\":" + lon + ",\"source\":\"SAT-1\"}";
    }

    private static String good() {
        return line("\"r1\"", "\"AST-00001\"", "\"2026-11-02T06:00:05.120Z\"", "38.1", "168.2");
    }

    private RejectionCode code(String line) {
        return switch (validator.validate(line)) {
            case ReportValidator.Invalid i -> i.code();
            case ReportValidator.Valid v -> null;
        };
    }

    @Test
    void acceptsAValidReport() {
        assertThat(validator.validate(good())).isInstanceOf(ReportValidator.Valid.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"reportId\":\"df72\",\"assetId\":\"AST-03034\",\"lat\":34.2",   // truncated line
            "\"heartbeat\"",                                                  // JSON but not an object
            "[1,2,3]",
            "not json",
            "{\"reportId\":\"a\"} trailing",
            "{\"reportId\":\"a\",\"reportId\":\"b\"}"                         // duplicate key
    })
    void malformedJson(String line) {
        assertThat(code(line)).isEqualTo(RejectionCode.MALFORMED_JSON);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "\"\"|\"AST-00001\"|\"2026-11-02T06:00:05.120Z\"|38.1|168.2",
            "null|\"AST-00001\"|\"2026-11-02T06:00:05.120Z\"|38.1|168.2",
            "\"r1\"|\"\"|\"2026-11-02T06:00:05.120Z\"|38.1|168.2",
            "\"r1\"|\"AST-00001\"|null|38.1|168.2",
            "\"r1\"|\"AST-00001\"|\"2026-11-02T06:00:05.120Z\"|null|168.2",
            "\"r1\"|\"AST-00001\"|\"2026-11-02T06:00:05.120Z\"|38.1|\"\"",
            "\"  \"|\"AST-00001\"|\"2026-11-02T06:00:05.120Z\"|38.1|168.2",
    })
    void missingField(String id, String asset, String ts, String lat, String lon) {
        assertThat(code(line(id, asset, ts, lat, lon))).isEqualTo(RejectionCode.MISSING_FIELD);
    }

    @Test
    void absentFieldIsMissing() {
        assertThat(code("{\"reportId\":\"r1\",\"assetId\":\"AST-00001\",\"observedAt\":\"2026-11-02T06:00:05.120Z\",\"lat\":1}"))
                .isEqualTo(RejectionCode.MISSING_FIELD);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "90.0001|168.2", "-345.2|171.3", "38.1|-761.15", "38.1|180.5",
            "\"38.1\"|168.2", "true|168.2", "{}|168.2", "[1]|168.2", "38.1|false"
    })
    void invalidCoordinate(String lat, String lon) {
        assertThat(code(line("\"r1\"", "\"AST-00001\"", "\"2026-11-02T06:00:05.120Z\"", lat, lon)))
                .isEqualTo(RejectionCode.INVALID_COORDINATE);
    }

    @Test
    void boundaryCoordinatesAreValid() {
        assertThat(code(line("\"r1\"", "\"AST-00001\"", "\"2026-11-02T06:00:05.120Z\"", "-90", "180"))).isNull();
        assertThat(code(line("\"r1\"", "\"AST-00001\"", "\"2026-11-02T06:00:05.120Z\"", "90", "-180"))).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\"yesterday\"", "\"2026-13-45T99:00:00Z\"", "\"02/11/2026 06:01:22\"",
            "\"2026-11-02T06:05:25.697\"",      // no offset: not an instant
            "1762063205",                       // epoch number
            "\"2031-01-01T00:00:00.000Z\"",     // future sentinel: would freeze the asset
            "\"1969-12-31T23:59:59Z\""
    })
    void invalidTimestamp(String ts) {
        assertThat(code(line("\"r1\"", "\"AST-00001\"", ts, "38.1", "168.2"))).isEqualTo(RejectionCode.INVALID_TIMESTAMP);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"2026-11-02T06:00:05Z\"", "\"2026-11-02T10:04:58.948+04:00\"", "\"2026-11-02T06:00:05.123456789Z\""})
    void otherIsoInstantsAreValid(String ts) {
        assertThat(code(line("\"r1\"", "\"AST-00001\"", ts, "38.1", "168.2"))).isNull();
    }

    @Test
    void offsetIsNormalisedToUtc() {
        var v = (ReportValidator.Valid) validator.validate(
                line("\"r1\"", "\"AST-00001\"", "\"2026-11-02T10:04:58.948+04:00\"", "38.1", "168.2"));
        assertThat(v.report().observedAt()).isEqualTo(Instant.parse("2026-11-02T06:04:58.948Z"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\"ast-00017\"", "\" AST-00017\"", "\"AST-00017 \"", "\"<script>alert(1)</script>\"",
            "\"AST-1'; DROP TABLE reports;--\"", "\"../../etc/passwd\"", "\"AB\"", "\"-AST\"",
            "\"AST-0001\\n\"", "42", "\"A23456789012345678901234567890123\""
    })
    void invalidAssetId(String asset) {
        assertThat(code(line("\"r1\"", asset, "\"2026-11-02T06:00:05.120Z\"", "38.1", "168.2")))
                .isEqualTo(RejectionCode.INVALID_FIELD);
    }

    @Test
    void reportIdRules() {
        String id64 = "\"" + "a".repeat(64) + "\"";
        String id65 = "\"" + "a".repeat(65) + "\"";
        assertThat(code(line(id64, "\"AST-00001\"", "\"2026-11-02T06:00:05.120Z\"", "38.1", "168.2"))).isNull();
        assertThat(code(line(id65, "\"AST-00001\"", "\"2026-11-02T06:00:05.120Z\"", "38.1", "168.2")))
                .isEqualTo(RejectionCode.INVALID_FIELD);
        assertThat(code(line("\"a\\u0000b\"", "\"AST-00001\"", "\"2026-11-02T06:00:05.120Z\"", "38.1", "168.2")))
                .isEqualTo(RejectionCode.INVALID_FIELD);
        assertThat(code(line("\"\\ud800\"", "\"AST-00001\"", "\"2026-11-02T06:00:05.120Z\"", "38.1", "168.2")))
                .isEqualTo(RejectionCode.INVALID_FIELD);
    }

    @Test
    void optionalFieldsAndExtraFields() {
        String base = "{\"reportId\":\"r1\",\"assetId\":\"AST-00001\",\"observedAt\":\"2026-11-02T06:00:05.120Z\",\"lat\":1,\"lon\":2";
        assertThat(code(base + "}")).isNull();
        assertThat(code(base + ",\"source\":null,\"speedKn\":null,\"headingDeg\":360.0,\"altM\":5,\"meta\":{}}")).isNull();
        assertThat(code(base + ",\"speedKn\":\"fast\"}")).isEqualTo(RejectionCode.INVALID_FIELD);
        assertThat(code(base + ",\"source\":7}")).isEqualTo(RejectionCode.INVALID_FIELD);
        assertThat(code(base + ",\"kind\":\"" + "x".repeat(65) + "\"}")).isEqualTo(RejectionCode.INVALID_FIELD);
    }

    @Test
    void checksRunInContractOrder() {
        // Missing field wins over a bad coordinate; a bad coordinate wins over a bad timestamp.
        assertThat(code(line("\"\"", "\"x\"", "\"bad\"", "500", "1"))).isEqualTo(RejectionCode.MISSING_FIELD);
        assertThat(code(line("\"r\"", "\"x\"", "\"bad\"", "500", "1"))).isEqualTo(RejectionCode.INVALID_COORDINATE);
        assertThat(code(line("\"r\"", "\"x\"", "\"bad\"", "5", "1"))).isEqualTo(RejectionCode.INVALID_TIMESTAMP);
    }
}
