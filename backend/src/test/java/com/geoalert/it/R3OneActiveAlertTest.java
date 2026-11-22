package com.geoalert.it;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** R3: never more than one OPEN or CLAIMED alert per asset and zone. */
class R3OneActiveAlertTest extends IntegrationTest {

    @Test
    void manyParallelEntriesForOneAssetAndZoneRaiseOneAlert() throws Exception {
        String asset = newAsset();
        List<Callable<Api.Response>> tasks = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            int t = i * 2;
            // Every batch is an exit followed by an entry, so every batch on its own is an entry.
            tasks.add(() -> api.batch(List.of(
                    report(newId(), asset, at(t), OUT_LAT, OUT_LON),
                    report(newId(), asset, at(t + 1), IN_LAT, IN_LON))));
        }
        parallel(tasks);
        Integer active = jdbc.queryForObject(
                "SELECT count(*) FROM alert WHERE asset_id = ? AND zone_id = 'Z-01' AND status IN ('OPEN','CLAIMED')",
                Integer.class, asset);
        assertThat(active).isEqualTo(1);
    }

    @Test
    void afterResolveTheNextEntryRaisesANewAlertButStayingInsideDoesNot() {
        String asset = newAsset();
        api.batch(List.of(report(newId(), asset, at(1), IN_LAT, IN_LON)));
        String alertId = api.get("op02", "/api/v1/alerts?assetId=" + asset).body().get("items").get(0).get("alertId").asText();
        api.post("op02", "/api/v1/alerts/" + alertId + "/claim", null);
        api.post("op02", "/api/v1/alerts/" + alertId + "/resolve", "{\"note\":\"ok\"}");

        api.batch(List.of(report(newId(), asset, at(2), IN_LAT, IN_LON + 0.01)));   // stays inside
        assertThat(api.get("op02", "/api/v1/alerts?assetId=" + asset).body().get("items")).hasSize(1);

        api.batch(List.of(report(newId(), asset, at(3), OUT_LAT, OUT_LON)));
        api.batch(List.of(report(newId(), asset, at(4), IN_LAT, IN_LON)));           // enters again
        var items = api.get("op02", "/api/v1/alerts?assetId=" + asset).body().get("items");
        assertThat(items).hasSize(2);
        assertThat(items.get(1).get("status").asText()).isEqualTo("OPEN");
    }

    @Test
    void reEntryWhileAlertIsOpenRaisesNothing() {
        String asset = newAsset();
        api.batch(List.of(report(newId(), asset, at(1), IN_LAT, IN_LON)));
        api.batch(List.of(report(newId(), asset, at(2), OUT_LAT, OUT_LON)));
        api.batch(List.of(report(newId(), asset, at(3), IN_LAT, IN_LON)));
        assertThat(api.get("op02", "/api/v1/alerts?assetId=" + asset).body().get("items")).hasSize(1);
    }

    @Test
    void theDatabaseItselfRefusesASecondActiveAlert() {
        String asset = newAsset();
        api.batch(List.of(report(newId(), asset, at(1), IN_LAT, IN_LON)));
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO alert (asset_id, zone_id, status, trigger_report_id, trigger_observed_at, last_actor)
                VALUES (?, 'Z-01', 'OPEN', 'x', now(), 'test')""", asset))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void overlappingZonesRaiseOneAlertEach() {
        String asset = newAsset();
        api.batch(List.of(report(newId(), asset, at(1), 32.6, 174.8)));   // inside Z-04 and Z-05
        var items = api.get("op02", "/api/v1/alerts?assetId=" + asset).body().get("items");
        assertThat(items).extracting(n -> n.get("zoneId").asText()).containsExactlyInAnyOrder("Z-04", "Z-05");
    }

    @Test
    void advisoryZoneNeverRaises() {
        String asset = newAsset();
        api.batch(List.of(report(newId(), asset, at(1), 40.8, 169.9)));   // Z-09 India Advisory
        assertThat(api.get("op02", "/api/v1/alerts?assetId=" + asset).body().get("items")).isEmpty();
    }

    @Test
    void antimeridianZoneRaisesOnBothSides() {
        String east = newAsset();
        String west = newAsset();
        api.batch(List.of(report(newId(), east, at(1), 34.5, 179.6), report(newId(), west, at(1), 34.5, -179.6)));
        assertThat(api.get("op02", "/api/v1/alerts?assetId=" + east).body().get("items").get(0).get("zoneId").asText())
                .isEqualTo("Z-08");
        assertThat(api.get("op02", "/api/v1/alerts?assetId=" + west).body().get("items").get(0).get("zoneId").asText())
                .isEqualTo("Z-08");
        String far = newAsset();
        api.batch(List.of(report(newId(), far, at(1), 34.5, 170.0)));
        assertThat(api.get("op02", "/api/v1/alerts?assetId=" + far).body().get("items")).isEmpty();
    }

    @Test
    void holeDoesNotRaise() {
        String asset = newAsset();
        api.batch(List.of(report(newId(), asset, at(1), 39.6, 173.2)));   // hole of Z-03
        assertThat(api.get("op02", "/api/v1/alerts?assetId=" + asset).body().get("items")).isEmpty();
    }
}
