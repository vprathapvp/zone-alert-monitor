package com.geoalert.it;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

/** R2: the current position is the newest observedAt; late reports go to the track only. */
class R2EventTimeTest extends IntegrationTest {

    @Test
    void shuffledReportsInParallelBatchesEndAtTheNewest() throws Exception {
        String asset = newAsset();
        List<String> lines = new ArrayList<>();
        String newestId = null;
        for (int i = 0; i < 60; i++) {
            String id = newId();
            lines.add(report(id, asset, at(i), OUT_LAT + i * 0.001, OUT_LON));
            newestId = id;
        }
        Collections.shuffle(lines);
        List<Callable<Api.Response>> tasks = new ArrayList<>();
        for (int b = 0; b < 12; b++) {
            List<String> batch = lines.subList(b * 5, b * 5 + 5);
            tasks.add(() -> api.batch(batch));
        }
        parallel(tasks);

        var current = api.get("op01", "/api/v1/assets/" + asset).body();
        assertThat(current.get("reportId").asText()).isEqualTo(newestId);
        assertThat(current.get("observedAt").asText()).isEqualTo(at(59));
        var track = api.get("op01", "/api/v1/assets/" + asset + "/track").body().get("items");
        assertThat(track).hasSize(60);
        for (int i = 1; i < track.size(); i++) {
            assertThat(track.get(i).get("observedAt").asText()).isGreaterThanOrEqualTo(track.get(i - 1).get("observedAt").asText());
        }
    }

    @Test
    void lateReportInsideAZoneIsStoredButDoesNotMoveOrAlert() {
        String asset = newAsset();
        api.batch(List.of(report(newId(), asset, at(100), OUT_LAT, OUT_LON)));

        Api.Response r = api.batch(List.of(report(newId(), asset, at(50), IN_LAT, IN_LON)));

        assertThat(r.body().get("accepted").asInt()).isEqualTo(1);
        assertThat(api.get("op01", "/api/v1/assets/" + asset).body().get("observedAt").asText()).isEqualTo(at(100));
        assertThat(api.get("op01", "/api/v1/assets/" + asset + "/track").body().get("items")).hasSize(2);
        assertThat(api.get("op01", "/api/v1/alerts?assetId=" + asset).body().get("items")).isEmpty();
    }

    @Test
    void lateAndNewReportsRacingOnDifferentConnections() throws Exception {
        // Repeated many times: newest wins whichever request commits first.
        for (int round = 0; round < 10; round++) {
            String asset = newAsset();
            String old = report(newId(), asset, at(1), IN_LAT, IN_LON);
            String newer = report(newId(), asset, at(2), OUT_LAT, OUT_LON);
            parallel(List.<Callable<Api.Response>>of(() -> api.batch(List.of(old)), () -> api.batch(List.of(newer))));
            assertThat(api.get("op01", "/api/v1/assets/" + asset).body().get("observedAt").asText()).isEqualTo(at(2));
        }
    }
}
