package com.geoalert.it;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

/** R1: delivering a report again, any number of times, on any connection, changes nothing. */
class R1ExactlyOnceTest extends IntegrationTest {

    @Test
    void sameBatchDeliveredTenTimesInParallelIsStoredOnce() throws Exception {
        String asset = newAsset();
        List<String> lines = List.of(
                report(newId(), asset, at(1), OUT_LAT, OUT_LON),
                report(newId(), asset, at(2), IN_LAT, IN_LON),     // enters Z-01
                report(newId(), asset, at(3), IN_LAT, IN_LON + 0.01));
        List<Callable<Api.Response>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            tasks.add(() -> api.batch(lines));
        }
        List<Api.Response> results = parallel(tasks);

        assertThat(results).allMatch(r -> r.status() == 200);
        assertThat(results.stream().mapToInt(r -> r.body().get("accepted").asInt()).sum()).isEqualTo(3);
        assertThat(results.stream().mapToInt(r -> r.body().get("duplicates").asInt()).sum()).isEqualTo(27);
        assertThat(api.get("op01", "/api/v1/assets/" + asset + "/track").body().get("items")).hasSize(3);
        assertThat(api.get("op01", "/api/v1/alerts?assetId=" + asset).body().get("items")).hasSize(1);
        assertThat(api.get("op01", "/api/v1/assets/" + asset).body().get("observedAt").asText()).isEqualTo(at(3));
    }

    @Test
    void redeliveryAfterResolveRaisesNoNewAlertAndDoesNotMoveThePosition() {
        String asset = newAsset();
        String enter = report(newId(), asset, at(10), IN_LAT, IN_LON);
        api.batch(List.of(enter));
        String alertId = api.get("op01", "/api/v1/alerts?assetId=" + asset).body().get("items").get(0).get("alertId").asText();
        api.post("op01", "/api/v1/alerts/" + alertId + "/claim", null);
        api.post("op01", "/api/v1/alerts/" + alertId + "/resolve", "{\"note\":\"checked\"}");
        api.batch(List.of(report(newId(), asset, at(20), OUT_LAT, OUT_LON)));

        Api.Response again = api.batch(List.of(enter));   // the old entry report, delivered again

        assertThat(again.body().get("duplicates").asInt()).isEqualTo(1);
        assertThat(api.get("op01", "/api/v1/alerts?assetId=" + asset).body().get("items")).hasSize(1);
        assertThat(api.get("op01", "/api/v1/assets/" + asset).body().get("observedAt").asText()).isEqualTo(at(20));
        assertThat(api.get("op01", "/api/v1/assets/" + asset + "/track").body().get("items")).hasSize(2);
    }

    @Test
    void sameReportTwiceInOneBatch() {
        String asset = newAsset();
        String line = report(newId(), asset, at(1), OUT_LAT, OUT_LON);
        Api.Response r = api.batch(List.of(line, line));
        assertThat(r.body().get("accepted").asInt()).isEqualTo(1);
        assertThat(r.body().get("duplicates").asInt()).isEqualTo(1);
    }

    @Test
    void reusedReportIdWithADifferentPayloadIsADuplicateAndTheStoredReportStays() {
        String asset = newAsset();
        String id = newId();
        api.batch(List.of(report(id, asset, at(1), OUT_LAT, OUT_LON)));
        Api.Response r = api.batch(List.of(report(id, asset, at(1), IN_LAT, IN_LON)));
        assertThat(r.body().get("duplicates").asInt()).isEqualTo(1);
        assertThat(api.get("op01", "/api/v1/assets/" + asset).body().get("lat").asDouble()).isEqualTo(OUT_LAT);
        assertThat(api.get("op01", "/api/v1/alerts?assetId=" + asset).body().get("items")).isEmpty();
    }
}
