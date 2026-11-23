package com.geoalert.it;

import com.geoalert.GeoAlertApplication;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A second, independent application instance on the same database: nothing in one JVM's memory can
 * protect the other, so these tests show the rules are enforced by the database.
 */
class TwoInstancesTest extends IntegrationTest {

    static ConfigurableApplicationContext second;
    static Api api2;

    @BeforeAll
    static void startSecondInstance() {
        second = new SpringApplicationBuilder(GeoAlertApplication.class).run(
                "--server.port=0",
                "--spring.datasource.url=" + PG.getJdbcUrl(),
                "--spring.datasource.username=" + PG.getUsername(),
                "--spring.datasource.password=" + PG.getPassword(),
                "--geoalert.users-file=" + USERS_FILE,
                "--geoalert.instance-id=test-b",
                "--DRAIN_SECONDS=0");
        int port2 = ((WebServerApplicationContext) second).getWebServer().getPort();
        api2 = new Api("http://localhost:" + port2);
    }

    @AfterAll
    static void stopSecondInstance() {
        second.close();
    }

    @Test
    void tokensFromOneInstanceWorkOnTheOther() {
        Api.Response r = api2.send("GET", "/api/v1/stats", api.token("op01"), null, (String) null);
        assertThat(r.status()).isEqualTo(200);
    }

    @Test
    void sameBatchOnBothInstancesAtOnceIsStoredOnce() throws Exception {
        String asset = newAsset();
        List<String> lines = List.of(
                report(newId(), asset, at(1), OUT_LAT, OUT_LON),
                report(newId(), asset, at(2), IN_LAT, IN_LON));
        List<Callable<Api.Response>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            tasks.add(() -> api.batch(lines));
            tasks.add(() -> api2.batch(lines));
        }
        List<Api.Response> results = parallel(tasks);
        assertThat(results.stream().mapToInt(r -> r.body().get("accepted").asInt()).sum()).isEqualTo(2);
        assertThat(api.get("op01", "/api/v1/alerts?assetId=" + asset).body().get("items")).hasSize(1);
        assertThat(api.get("op01", "/api/v1/assets/" + asset + "/track").body().get("items")).hasSize(2);
    }

    @Test
    void outOfOrderAcrossInstancesEndsAtNewest() throws Exception {
        String asset = newAsset();
        List<Callable<Api.Response>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            String line = report(newId(), asset, at(i), OUT_LAT, OUT_LON + i * 0.001);
            Api target = i % 2 == 0 ? api2 : api;
            tasks.add(() -> target.batch(List.of(line)));
        }
        java.util.Collections.reverse(tasks);
        parallel(tasks);
        assertThat(api.get("op01", "/api/v1/assets/" + asset).body().get("observedAt").asText()).isEqualTo(at(19));
    }

    @Test
    void claimsSplitAcrossInstancesHaveOneWinner() throws Exception {
        String asset = newAsset();
        api.batch(List.of(report(newId(), asset, at(1), IN_LAT, IN_LON)));
        String alertId = api.get("op01", "/api/v1/alerts?assetId=" + asset).body().get("items").get(0).get("alertId").asText();
        List<Callable<Api.Response>> tasks = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            String user = String.format("op%02d", i);
            Api target = i % 2 == 0 ? api2 : api;
            tasks.add(() -> target.post(user, "/api/v1/alerts/" + alertId + "/claim", null));
        }
        List<Api.Response> results = parallel(tasks);
        assertThat(results.stream().filter(r -> r.status() == 200)).hasSize(1);
        assertThat(results.stream().filter(r -> r.status() == 409)).hasSize(19);
    }
}
