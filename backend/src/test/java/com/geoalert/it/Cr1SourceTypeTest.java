package com.geoalert.it;

import com.geoalert.MigrateCommand;
import com.geoalert.ingest.SourceType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** CR-1 (1.1.0): source type on every stored report, API, and the expand/backfill/contract path. */
class Cr1SourceTypeTest extends IntegrationTest {

    @ParameterizedTest
    @CsvSource(nullValues = "NULL", value = {
            "SAT-1,SATELLITE", "SAT,SATELLITE", "GND-2,GROUND", "MOB-1,MOBILE", "XYZ-9,UNKNOWN",
            "sat-1,UNKNOWN", "'',UNKNOWN", "NULL,UNKNOWN", "' SAT-1',UNKNOWN"})
    void javaAndSqlMappingAgree(String source, String expected) {
        assertThat(SourceType.of(source).name()).isEqualTo(expected);
        assertThat(jdbc.queryForObject("SELECT report_source_type_of(?)", String.class, source)).isEqualTo(expected);
    }

    @Test
    void trackReturnsAndFiltersSourceType() {
        String asset = newAsset();
        api.batch(List.of(
                report(newId(), asset, at(1), 10, 10).replace("\"SAT-1\"", "\"GND-1\""),
                report(newId(), asset, at(2), 10, 10),
                report(newId(), asset, at(3), 10, 10).replace(",\"source\":\"SAT-1\"", "")));
        var items = api.get("op01", "/api/v1/assets/" + asset + "/track").body().get("items");
        assertThat(items).extracting(n -> n.get("sourceType").asText()).containsExactly("GROUND", "SATELLITE", "UNKNOWN");
        var sat = api.get("op01", "/api/v1/assets/" + asset + "/track?sourceType=SATELLITE").body().get("items");
        assertThat(sat).hasSize(1);
        assertThat(api.get("op01", "/api/v1/assets/" + asset + "/track?sourceType=satellite").status()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM report WHERE asset_id = ? AND source_type IS NULL",
                Integer.class, asset)).isZero();
    }

    /** Replays the production upgrade on its own database: 1.0.0 schema with data, then each CR-1 step. */
    @Test
    void expandBackfillContractOnALoadedDatabase() throws Exception {
        String db = "cr1_" + System.nanoTime();
        jdbc.execute("CREATE DATABASE " + db);
        String url = PG.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + db + "$1");
        byte[] zones;
        try (InputStream in = getClass().getResourceAsStream("/zones.geojson")) {
            zones = in.readAllBytes();
        }

        // 1.0.0 schema with 1,000 reports from 1.0.0 (no source_type column yet).
        MigrateCommand.migrate(url, PG.getUsername(), PG.getPassword(), "1", zones);
        try (Connection c = DriverManager.getConnection(url, PG.getUsername(), PG.getPassword());
             Statement s = c.createStatement()) {
            s.execute("""
                    INSERT INTO report (report_id, asset_id, observed_at, lat, lon, source)
                    SELECT 'old-' || g, 'A-' || (g % 50), now() - g * interval '1 s', 1, 1,
                           (ARRAY['SAT-1','GND-1','MOB-1','XYZ-9',NULL])[1 + g % 5]
                      FROM generate_series(1, 1000) g""");
        }

        // Step 1: expand.
        MigrateCommand.migrate(url, PG.getUsername(), PG.getPassword(), "3", zones);
        try (Connection c = DriverManager.getConnection(url, PG.getUsername(), PG.getPassword());
             Statement s = c.createStatement()) {
            // A 1.0.0 instance still writing during the rollout: its rows get a source type anyway.
            s.execute("INSERT INTO report (report_id, asset_id, observed_at, lat, lon, source) VALUES ('v100', 'A-1', now(), 1, 1, 'MOB-7')");
            assertThat(scalar(s, "SELECT source_type FROM report WHERE report_id = 'v100'")).isEqualTo("MOBILE");
            assertThat(scalar(s, "SELECT count(*) FROM report WHERE source_type IS NULL")).isEqualTo("1000");
        }

        // The contract step is refused while rows are missing: nothing changes.
        assertThatThrownBy(() -> MigrateCommand.migrate(url, PG.getUsername(), PG.getPassword(), "latest", zones))
                .hasMessageContaining("report_source_type_present");

        // Step 4: backfill in small committed batches; then step 6: contract.
        try (Connection c = DriverManager.getConnection(url, PG.getUsername(), PG.getPassword());
             Statement s = c.createStatement()) {
            s.execute("CALL report_backfill_source_type(64, 0)");
            assertThat(scalar(s, "SELECT count(*) FROM report WHERE source_type IS NULL")).isEqualTo("0");
            assertThat(scalar(s, "SELECT count(*) FROM report WHERE source_type = 'UNKNOWN'")).isEqualTo("400");
        }
        MigrateCommand.migrate(url, PG.getUsername(), PG.getPassword(), "latest", zones);
        try (Connection c = DriverManager.getConnection(url, PG.getUsername(), PG.getPassword());
             Statement s = c.createStatement()) {
            assertThat(scalar(s, "SELECT is_nullable FROM information_schema.columns WHERE table_name = 'report' AND column_name = 'source_type'"))
                    .isEqualTo("NO");
            // An old-style insert after the contract still works thanks to the fill trigger.
            s.execute("INSERT INTO report (report_id, asset_id, observed_at, lat, lon, source) VALUES ('v100b', 'A-1', now(), 1, 1, 'SAT-3')");
            assertThatThrownBy(() -> s.execute("UPDATE report SET source_type = 'BOGUS' WHERE report_id = 'v100b'"))
                    .hasMessageContaining("report_source_type_valid");
        }
    }

    private static String scalar(Statement s, String sql) throws Exception {
        try (ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
