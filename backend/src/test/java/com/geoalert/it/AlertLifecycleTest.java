package com.geoalert.it;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Table 6 of the brief: every allowed and refused command. */
class AlertLifecycleTest extends IntegrationTest {

    String openAlert() {
        String asset = newAsset();
        api.batch(List.of(report(newId(), asset, at(1), IN_LAT, IN_LON)));
        return api.get("op01", "/api/v1/alerts?assetId=" + asset).body().get("items").get(0).get("alertId").asText();
    }

    private void expectProblem(Api.Response r, int status, String code) {
        assertThat(r.status()).isEqualTo(status);
        assertThat(r.contentType()).startsWith("application/problem+json");
        assertThat(r.body().get("code").asText()).isEqualTo(code);
        assertThat(r.body().get("status").asInt()).isEqualTo(status);
        assertThat(r.body().has("title")).isTrue();
        assertThat(r.body().has("type")).isTrue();
    }

    @Test
    void openAlertShape() {
        String id = openAlert();
        var a = api.get("op01", "/api/v1/alerts/" + id).body();
        assertThat(a.get("status").asText()).isEqualTo("OPEN");
        assertThat(a.get("zoneId").asText()).isEqualTo("Z-01");
        assertThat(a.get("openedAt").asText()).matches("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\.\\d{3}Z");
        assertThat(a.get("triggerObservedAt").asText()).isEqualTo(at(1));
        assertThat(a.get("claimedBy").isNull()).isTrue();
    }

    @Test
    void claimRules() {
        String id = openAlert();
        assertThat(api.post("op01", "/api/v1/alerts/" + id + "/claim", null).body().get("claimedBy").asText()).isEqualTo("op01");
        assertThat(api.post("op01", "/api/v1/alerts/" + id + "/claim", null).status()).isEqualTo(200);
        expectProblem(api.post("op02", "/api/v1/alerts/" + id + "/claim", null), 409, "ALERT_ALREADY_CLAIMED");
        expectProblem(api.post("sup01", "/api/v1/alerts/" + id + "/claim", null), 409, "ALERT_ALREADY_CLAIMED");
        api.post("op01", "/api/v1/alerts/" + id + "/resolve", "{\"note\":\"n\"}");
        expectProblem(api.post("op01", "/api/v1/alerts/" + id + "/claim", null), 409, "INVALID_STATE");
    }

    @Test
    void releaseRules() {
        String id = openAlert();
        expectProblem(api.post("op01", "/api/v1/alerts/" + id + "/release", null), 409, "INVALID_STATE");
        api.post("op01", "/api/v1/alerts/" + id + "/claim", null);
        expectProblem(api.post("op02", "/api/v1/alerts/" + id + "/release", null), 403, "NOT_ALERT_OWNER");
        var released = api.post("sup01", "/api/v1/alerts/" + id + "/release", null);
        assertThat(released.status()).isEqualTo(200);
        assertThat(released.body().get("status").asText()).isEqualTo("OPEN");
        assertThat(released.body().get("claimedBy").isNull()).isTrue();
    }

    @Test
    void resolveRules() {
        String id = openAlert();
        expectProblem(api.post("op01", "/api/v1/alerts/" + id + "/resolve", "{\"note\":\"n\"}"), 409, "INVALID_STATE");
        api.post("op01", "/api/v1/alerts/" + id + "/claim", null);
        expectProblem(api.post("op01", "/api/v1/alerts/" + id + "/resolve", "{\"note\":\"\"}"), 400, "VALIDATION_FAILED");
        expectProblem(api.post("op01", "/api/v1/alerts/" + id + "/resolve", "{\"note\":\"   \"}"), 400, "VALIDATION_FAILED");
        expectProblem(api.post("op01", "/api/v1/alerts/" + id + "/resolve", "{}"), 400, "VALIDATION_FAILED");
        expectProblem(api.post("op01", "/api/v1/alerts/" + id + "/resolve",
                "{\"note\":\"" + "x".repeat(501) + "\"}"), 400, "VALIDATION_FAILED");
        expectProblem(api.post("op02", "/api/v1/alerts/" + id + "/resolve", "{\"note\":\"n\"}"), 403, "NOT_ALERT_OWNER");

        var resolved = api.post("op01", "/api/v1/alerts/" + id + "/resolve", "{\"note\":\"" + "x".repeat(500) + "\"}");
        assertThat(resolved.status()).isEqualTo(200);
        assertThat(resolved.body().get("resolvedBy").asText()).isEqualTo("op01");
        assertThat(api.post("op01", "/api/v1/alerts/" + id + "/resolve", "{\"note\":\"again\"}").status()).isEqualTo(200);
        expectProblem(api.post("sup01", "/api/v1/alerts/" + id + "/resolve", "{\"note\":\"n\"}"), 409, "INVALID_STATE");
    }

    @Test
    void supervisorResolvesAnAlertOwnedBySomebodyElse() {
        String id = openAlert();
        api.post("op07", "/api/v1/alerts/" + id + "/claim", null);
        var r = api.post("sup01", "/api/v1/alerts/" + id + "/resolve", "{\"note\":\"supervisor close\"}");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().get("resolvedBy").asText()).isEqualTo("sup01");
    }

    @Test
    void unknownAlertAndRoles() {
        expectProblem(api.get("op01", "/api/v1/alerts/999999999"), 404, "NOT_FOUND");
        expectProblem(api.get("op01", "/api/v1/alerts/abc"), 404, "NOT_FOUND");
        expectProblem(api.post("feed", "/api/v1/alerts/1/claim", null), 403, "FORBIDDEN");
        expectProblem(api.send("POST", "/api/v1/alerts/1/claim", null, null, (String) null), 401, "UNAUTHENTICATED");
        expectProblem(api.send("GET", "/api/v1/alerts", "not-a-token", null, (String) null), 401, "UNAUTHENTICATED");
    }
}
