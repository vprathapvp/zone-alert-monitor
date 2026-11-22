package com.geoalert.it;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

/** R6: a bad line never blocks or corrupts good lines; every rejection is counted and inspectable. */
class R6BadInputTest extends IntegrationTest {

    @Test
    void badLinesMixedWithGoodLines() {
        String asset = newAsset();
        String goodA = report(newId(), asset, at(1), OUT_LAT, OUT_LON);
        String goodB = report(newId(), asset, at(2), IN_LAT, IN_LON);
        String nulInId = report("bad\\u0000id", asset, at(3), IN_LAT, IN_LON);
        String hugeLine = "{\"reportId\":\"" + "x".repeat(200_000) + "\"}";
        List<String> lines = List.of(
                "{\"reportId\":\"trunc",                                   // 1 MALFORMED_JSON
                goodA,                                                     // 2
                "\"heartbeat\"",                                           // 3 MALFORMED_JSON
                report(newId(), "ast-lower", at(3), 1, 1),                  // 4 INVALID_FIELD
                report(newId(), asset, "yesterday", 1, 1),                 // 5 INVALID_TIMESTAMP
                report(newId(), asset, at(3), 200, 1),                     // 6 INVALID_COORDINATE
                report("", asset, at(3), 1, 1),                            // 7 MISSING_FIELD
                nulInId,                                                   // 8 INVALID_FIELD
                hugeLine,                                                  // 9 MISSING_FIELD
                goodB);                                                    // 10
        Api.Response r = api.batch(lines);

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().get("received").asInt()).isEqualTo(10);
        assertThat(r.body().get("accepted").asInt()).isEqualTo(2);
        assertThat(r.body().get("rejected").asInt()).isEqualTo(8);
        assertThat(r.body().get("rejections")).extracting(n -> n.get("line").asInt() + ":" + n.get("code").asText())
                .containsExactly("1:MALFORMED_JSON", "3:MALFORMED_JSON", "4:INVALID_FIELD", "5:INVALID_TIMESTAMP",
                        "6:INVALID_COORDINATE", "7:MISSING_FIELD", "8:INVALID_FIELD", "9:MISSING_FIELD");
        // The good lines took full effect, including the alert.
        assertThat(api.get("op01", "/api/v1/assets/" + asset).body().get("observedAt").asText()).isEqualTo(at(2));
        assertThat(api.get("op01", "/api/v1/alerts?assetId=" + asset).body().get("items")).hasSize(1);
    }

    @Test
    void invalidUtf8AndNulBytesAreRejectedAndStoredForInspection() {
        String asset = newAsset();
        var body = new java.io.ByteArrayOutputStream();
        body.writeBytes("{\"reportId\":\"".getBytes());
        body.writeBytes(new byte[]{(byte) 0xC3, (byte) 0x28});              // invalid UTF-8
        body.writeBytes("\",\"x\":".getBytes());
        body.writeBytes(new byte[]{0});                                     // raw NUL byte
        body.writeBytes("}\n".getBytes());
        body.writeBytes((report(newId(), asset, at(1), OUT_LAT, OUT_LON) + "\n").getBytes());
        Api.Response r = api.batchBytes(body.toByteArray());
        assertThat(r.body().get("accepted").asInt()).isEqualTo(1);
        assertThat(r.body().get("rejected").asInt()).isEqualTo(1);

        var page = api.get("sup01", "/api/v1/rejections?limit=50").body().get("items");
        assertThat(page).anySatisfy(item -> {
            assertThat(item.get("code").asText()).isEqualTo("MALFORMED_JSON");
            assertThat(item.get("raw").asText()).contains("reportId");
            assertThat(item.get("receivedAt").asText()).endsWith("Z");
        });
    }

    @Test
    void rejectionsAreCountedInStats() {
        long before = api.get("feed", "/api/v1/stats").body().get("rejected").asLong();
        api.batch(List.of("nope", "[]", "{}"));
        long after = api.get("feed", "/api/v1/stats").body().get("rejected").asLong();
        assertThat(after - before).isGreaterThanOrEqualTo(3);
    }

    @Test
    void badBatchesInParallelDoNotDisturbGoodBatches() throws Exception {
        List<Callable<Api.Response>> tasks = new ArrayList<>();
        List<String> assets = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String asset = newAsset();
            assets.add(asset);
            tasks.add(() -> api.batch(List.of(report(newId(), asset, at(1), OUT_LAT, OUT_LON),
                    report(newId(), asset, at(2), OUT_LAT, OUT_LON))));
            tasks.add(() -> api.batch(List.of("{bad", "\"x\"", report(newId(), "bad id", at(1), 1, 1))));
        }
        List<Api.Response> results = parallel(tasks);
        assertThat(results).allMatch(r -> r.status() == 200);
        for (String asset : assets) {
            assertThat(api.get("op01", "/api/v1/assets/" + asset + "/track").body().get("items")).hasSize(2);
        }
    }

    @Test
    void rejectionListIsForSupervisorsOnly() {
        assertThat(api.get("op01", "/api/v1/rejections").status()).isEqualTo(403);
    }
}
