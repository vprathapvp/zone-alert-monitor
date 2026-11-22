package com.geoalert.it;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** R5: every state change has its audit entry, written in the same transaction; append-only. */
class R5AuditTrailTest extends IntegrationTest {

    String openAlert() {
        String asset = newAsset();
        api.batch(List.of(report(newId(), asset, at(1), IN_LAT, IN_LON)));
        return api.get("op01", "/api/v1/alerts?assetId=" + asset).body().get("items").get(0).get("alertId").asText();
    }

    @Test
    void fullLifecycleIsAudited() {
        String id = openAlert();
        api.post("op03", "/api/v1/alerts/" + id + "/claim", null);
        api.post("op03", "/api/v1/alerts/" + id + "/release", null);
        api.post("op04", "/api/v1/alerts/" + id + "/claim", null);
        api.post("sup01", "/api/v1/alerts/" + id + "/resolve", "{\"note\":\"closed by supervisor\"}");

        var audit = api.get("op01", "/api/v1/alerts/" + id + "/audit").body().get("items");
        assertThat(audit).extracting(a -> a.get("action").asText())
                .containsExactly("OPENED", "CLAIMED", "RELEASED", "CLAIMED", "RESOLVED");
        assertThat(audit).extracting(a -> a.get("actor").asText())
                .containsExactly("system", "op03", "op03", "op04", "sup01");
        assertThat(audit.get(0).get("fromStatus").isNull()).isTrue();
        assertThat(audit.get(4).get("fromStatus").asText()).isEqualTo("CLAIMED");
        assertThat(audit.get(4).get("toStatus").asText()).isEqualTo("RESOLVED");
    }

    @Test
    void repeatedCommandsThatChangeNothingAddNoEntry() throws Exception {
        String id = openAlert();
        List<Callable<Api.Response>> claims = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            claims.add(() -> api.post("op05", "/api/v1/alerts/" + id + "/claim", null));
        }
        assertThat(parallel(claims)).allMatch(r -> r.status() == 200);
        List<Callable<Api.Response>> resolves = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            resolves.add(() -> api.post("op05", "/api/v1/alerts/" + id + "/resolve", "{\"note\":\"done\"}"));
        }
        assertThat(parallel(resolves)).allMatch(r -> r.status() == 200);
        var audit = api.get("op01", "/api/v1/alerts/" + id + "/audit").body().get("items");
        assertThat(audit).extracting(a -> a.get("action").asText()).containsExactly("OPENED", "CLAIMED", "RESOLVED");
    }

    @Test
    void auditIsWrittenEvenWhenStateIsChangedOutsideTheApplication() {
        String id = openAlert();
        jdbc.update("UPDATE alert SET status = 'CLAIMED', claimed_by = 'dba', last_actor = 'dba' WHERE alert_id = ?",
                Long.parseLong(id));
        var audit = api.get("op01", "/api/v1/alerts/" + id + "/audit").body().get("items");
        assertThat(audit).extracting(a -> a.get("actor").asText()).containsExactly("system", "dba");
    }

    @Test
    void auditIsAppendOnly() {
        String id = openAlert();
        assertThatThrownBy(() -> jdbc.update("UPDATE alert_audit SET actor = 'x' WHERE alert_id = ?", Long.parseLong(id)))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM alert_audit WHERE alert_id = ?", Long.parseLong(id)))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void anIllegalTransitionChangesNeitherStateNorAudit() {
        String id = openAlert();
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE alert SET status = 'RESOLVED', resolved_by = 'x', resolved_at = now(), resolution_note = 'n', last_actor = 'x' WHERE alert_id = ?",
                Long.parseLong(id))).isInstanceOf(DataAccessException.class);
        assertThat(api.get("op01", "/api/v1/alerts/" + id).body().get("status").asText()).isEqualTo("OPEN");
        assertThat(api.get("op01", "/api/v1/alerts/" + id + "/audit").body().get("items")).hasSize(1);
    }
}
