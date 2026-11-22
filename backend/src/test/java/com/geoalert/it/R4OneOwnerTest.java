package com.geoalert.it;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** R4: at most one owner; of simultaneous claims exactly one succeeds. */
class R4OneOwnerTest extends IntegrationTest {

    String openAlert() {
        String asset = newAsset();
        api.batch(List.of(report(newId(), asset, at(1), IN_LAT, IN_LON)));
        return api.get("op01", "/api/v1/alerts?assetId=" + asset).body().get("items").get(0).get("alertId").asText();
    }

    @Test
    void twentyOperatorsClaimAtOnceAndExactlyOneWins() throws Exception {
        for (int round = 0; round < 5; round++) {
            String alertId = openAlert();
            List<Callable<Api.Response>> tasks = new ArrayList<>();
            for (int i = 1; i <= 20; i++) {
                String user = String.format("op%02d", i);
                tasks.add(() -> api.post(user, "/api/v1/alerts/" + alertId + "/claim", null));
            }
            List<Api.Response> results = parallel(tasks);

            List<Api.Response> won = results.stream().filter(r -> r.status() == 200).toList();
            assertThat(won).hasSize(1);
            assertThat(results.stream().filter(r -> r.status() == 409))
                    .hasSize(19)
                    .allMatch(r -> r.body().get("code").asText().equals("ALERT_ALREADY_CLAIMED"));
            String owner = won.get(0).body().get("claimedBy").asText();
            assertThat(api.get("op01", "/api/v1/alerts/" + alertId).body().get("claimedBy").asText()).isEqualTo(owner);
            var audit = api.get("op01", "/api/v1/alerts/" + alertId + "/audit").body().get("items");
            assertThat(audit).extracting(a -> a.get("action").asText()).containsExactly("OPENED", "CLAIMED");
        }
    }

    @Test
    void claimAndReleaseRacingKeepAConsistentOwner() throws Exception {
        String alertId = openAlert();
        api.post("op01", "/api/v1/alerts/" + alertId + "/claim", null);
        List<Callable<Api.Response>> tasks = new ArrayList<>();
        tasks.add(() -> api.post("op01", "/api/v1/alerts/" + alertId + "/release", null));
        for (int i = 2; i <= 10; i++) {
            String user = String.format("op%02d", i);
            tasks.add(() -> api.post(user, "/api/v1/alerts/" + alertId + "/claim", null));
        }
        parallel(tasks);
        Integer bad = jdbc.queryForObject("""
                SELECT count(*) FROM alert WHERE alert_id = ?
                   AND NOT ((status = 'OPEN' AND claimed_by IS NULL) OR (status = 'CLAIMED' AND claimed_by IS NOT NULL))
                """, Integer.class, Long.parseLong(alertId));
        assertThat(bad).isZero();
    }

    @Test
    void theDatabaseRefusesAnOwnerChangeWithoutAStateChange() {
        String alertId = openAlert();
        api.post("op01", "/api/v1/alerts/" + alertId + "/claim", null);
        assertThatThrownBy(() -> jdbc.update("UPDATE alert SET claimed_by = 'op02' WHERE alert_id = ?", Long.parseLong(alertId)))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE alert SET status = 'CLAIMED', claimed_by = NULL WHERE alert_id = ?",
                Long.parseLong(alertId)))
                .isInstanceOf(DataAccessException.class);
    }
}
