package com.geoalert.it;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** The fixed contract: shapes, limits, validation, paging, headers. */
class ApiContractTest extends IntegrationTest {

    @Test
    void healthAndLogin() {
        var h = api.get(null, "/health");
        assertThat(h.status()).isEqualTo(200);
        assertThat(h.body().get("status").asText()).isEqualTo("UP");
        var bad = api.send("POST", "/api/v1/auth/login", null, "application/json", "{\"username\":\"op01\",\"password\":\"wrong\"}");
        assertThat(bad.status()).isEqualTo(401);
        assertThat(bad.body().get("code").asText()).isEqualTo("UNAUTHENTICATED");
        var ok = api.send("POST", "/api/v1/auth/login", null, "application/json",
                "{\"username\":\"op01\",\"password\":\"" + PASSWORD_PREFIX + "op01\"}");
        assertThat(ok.body().get("tokenType").asText()).isEqualTo("Bearer");
        assertThat(ok.body().get("user").get("role").asText()).isEqualTo("OPERATOR");
        assertThat(ok.body().get("expiresInSec").asInt()).isPositive();
    }

    @Test
    void batchMediaTypeAndSizeLimits() {
        var wrongType = api.send("POST", "/api/v1/reports/batch", api.token("feed"), "application/json", "{}");
        assertThat(wrongType.status()).isEqualTo(415);
        assertThat(wrongType.body().get("code").asText()).isEqualTo("UNSUPPORTED_MEDIA_TYPE");

        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 1001; i++) {
            lines.add("{}");
        }
        var tooMany = api.batch(lines);
        assertThat(tooMany.status()).isEqualTo(413);
        assertThat(tooMany.body().get("code").asText()).isEqualTo("PAYLOAD_TOO_LARGE");

        var exactly1000 = api.batch(lines.subList(0, 1000));
        assertThat(exactly1000.status()).isEqualTo(200);

        var tooBig = api.batch("{\"x\":\"" + "a".repeat(5 * 1024 * 1024) + "\"}\n");
        assertThat(tooBig.status()).isEqualTo(413);

        var withCharset = api.send("POST", "/api/v1/reports/batch", api.token("feed"),
                "application/x-ndjson; charset=utf-8", "{}\n");
        assertThat(withCharset.status()).isEqualTo(200);
        assertThat(api.post("op01", "/api/v1/reports/batch", "{}").status()).isEqualTo(403);
    }

    @Test
    void viewportQuery() {
        String a = newAsset();
        String b = newAsset();
        api.batch(List.of(report(newId(), a, at(1), 38.3, 168.4), report(newId(), b, at(1), 34.5, -179.6)));

        var box = api.get("op01", "/api/v1/assets?bbox=168.3,38.2,168.5,38.4&limit=5000").body();
        assertThat(box.get("truncated").asBoolean()).isFalse();
        assertThat(box.get("items")).anyMatch(n -> n.get("assetId").asText().equals(a));
        assertThat(box.get("items")).noneMatch(n -> n.get("assetId").asText().equals(b));

        // minLon > maxLon crosses the 180th meridian.
        var crossing = api.get("op01", "/api/v1/assets?bbox=179,34,-179,35&limit=5000").body();
        assertThat(crossing.get("items")).anyMatch(n -> n.get("assetId").asText().equals(b));
        assertThat(crossing.get("items")).noneMatch(n -> n.get("assetId").asText().equals(a));

        var limited = api.get("op01", "/api/v1/assets?bbox=-180,-90,180,90&limit=1").body();
        assertThat(limited.get("items")).hasSize(1);
        assertThat(limited.get("truncated").asBoolean()).isTrue();

        for (String bad : List.of("", "1,2,3", "a,b,c,d", "0,10,1,5", "0,0,200,1", "0,-91,1,1")) {
            assertThat(api.get("op01", "/api/v1/assets?bbox=" + bad).status()).as(bad).isEqualTo(400);
        }
        assertThat(api.get("op01", "/api/v1/assets").status()).isEqualTo(400);
        for (String bad : List.of("0", "5001", "x", "-1")) {
            var r = api.get("op01", "/api/v1/assets?bbox=0,0,1,1&limit=" + bad);
            assertThat(r.status()).isEqualTo(400);
            assertThat(r.body().get("code").asText()).isEqualTo("VALIDATION_FAILED");
        }
    }

    @Test
    void assetAndTrack() {
        String a = newAsset();
        api.batch(List.of(report(newId(), a, at(3), 10, 10), report(newId(), a, at(1), 11, 11), report(newId(), a, at(2), 12, 12)));
        var pos = api.get("op01", "/api/v1/assets/" + a).body();
        assertThat(pos.get("observedAt").asText()).isEqualTo(at(3));
        assertThat(pos.get("kind").asText()).isEqualTo("SURFACE");
        assertThat(pos.get("headingDeg").asDouble()).isEqualTo(90.0);

        var track = api.get("op01", "/api/v1/assets/" + a + "/track").body();
        assertThat(track.get("items")).extracting(n -> n.get("observedAt").asText()).containsExactly(at(1), at(2), at(3));
        var window = api.get("op01", "/api/v1/assets/" + a + "/track?from=" + at(2) + "&to=" + at(2)).body();
        assertThat(window.get("items")).hasSize(1);
        var limited = api.get("op01", "/api/v1/assets/" + a + "/track?limit=2").body();
        assertThat(limited.get("items")).hasSize(2);
        assertThat(limited.get("truncated").asBoolean()).isTrue();

        assertThat(api.get("op01", "/api/v1/assets/NOPE-NOPE/track").status()).isEqualTo(404);
        assertThat(api.get("op01", "/api/v1/assets/NOPE-NOPE").status()).isEqualTo(404);
        assertThat(api.get("op01", "/api/v1/assets/" + a + "/track?from=yesterday").status()).isEqualTo(400);
        assertThat(api.get("op01", "/api/v1/assets/" + a + "/track?limit=5001").status()).isEqualTo(400);
    }

    @Test
    void alertCursorPagingReturnsEveryAlertOnce() {
        String a = newAsset();
        // Enter and leave five restricted zones: five alerts for one asset.
        double[][] points = {{38.3, 168.4}, {35.4, 171.0}, {39.2, 172.7}, {32.2, 174.2}, {36.2, 176.2}};
        List<String> lines = new ArrayList<>();
        int t = 0;
        for (double[] p : points) {
            lines.add(report(newId(), a, at(t++), p[0], p[1]));
            lines.add(report(newId(), a, at(t++), 0, 0));
        }
        api.batch(lines);
        Set<String> seen = new HashSet<>();
        String cursor = null;
        int pages = 0;
        do {
            var page = api.get("op01", "/api/v1/alerts?assetId=" + a + "&limit=2" + (cursor == null ? "" : "&cursor=" + cursor)).body();
            page.get("items").forEach(n -> assertThat(seen.add(n.get("alertId").asText())).isTrue());
            cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asText();
            pages++;
        } while (cursor != null);
        assertThat(seen).hasSize(5);
        assertThat(pages).isEqualTo(3);
        assertThat(api.get("op01", "/api/v1/alerts?cursor=garbage").status()).isEqualTo(400);
        assertThat(api.get("op01", "/api/v1/alerts?status=BOGUS").status()).isEqualTo(400);
        assertThat(api.get("op01", "/api/v1/alerts?limit=501").status()).isEqualTo(400);
        assertThat(api.get("op01", "/api/v1/alerts?status=OPEN&zoneId=Z-01&limit=500").status()).isEqualTo(200);
    }

    @Test
    void statsShape() {
        var s = api.get("feed", "/api/v1/stats").body();
        for (String f : List.of("received", "accepted", "duplicates", "rejected", "assets")) {
            assertThat(s.get(f).isNumber()).as(f).isTrue();
        }
        assertThat(s.get("alerts").get("open").isNumber()).isTrue();
        assertThat(s.get("alerts").get("claimed").isNumber()).isTrue();
        assertThat(s.get("alerts").get("resolved").isNumber()).isTrue();
        assertThat(api.get(null, "/api/v1/stats").status()).isEqualTo(401);
    }

    @Test
    void requestIdIsEchoed() throws Exception {
        HttpResponse<String> r = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/health")).header("X-Request-Id", "abc-123").build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(r.headers().firstValue("X-Request-Id")).contains("abc-123");
        HttpResponse<String> generated = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/health")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(generated.headers().firstValue("X-Request-Id")).isPresent();
    }

    @Test
    void errorsDoNotLeakInternals() {
        var r = api.post("op01", "/api/v1/alerts/1/resolve", "{not json");
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.body().toString()).doesNotContain("Exception").doesNotContain("jackson").doesNotContain("SQL");
        var unknown = api.get("op01", "/api/v1/nothing-here");
        assertThat(unknown.status()).isEqualTo(404);
        assertThat(unknown.body().get("code").asText()).isEqualTo("NOT_FOUND");
    }
}
