package com.geoalert;

import com.geoalert.zone.ZoneFile;
import com.geoalert.zone.ZoneLoader;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * One-shot deploy step: apply Flyway migrations (optionally only up to FLYWAY_TARGET) and load the
 * zone reference data. Configuration comes from the environment only.
 */
public final class MigrateCommand {

    private static final Logger log = LoggerFactory.getLogger(MigrateCommand.class);

    private MigrateCommand() {
    }

    static int run() {
        try {
            String zonesFile = System.getenv("ZONES_FILE");
            byte[] zones;
            if (zonesFile == null || zonesFile.isBlank()) {
                try (InputStream in = MigrateCommand.class.getResourceAsStream("/zones.geojson")) {
                    if (in == null) {
                        throw new IllegalStateException("zones.geojson not found in the image");
                    }
                    zones = in.readAllBytes();
                }
            } else {
                zones = Files.readAllBytes(Path.of(zonesFile));
            }
            migrate(requireEnv("DB_URL"), requireEnv("DB_USER"), secret("DB_PASSWORD"),
                    envOr("FLYWAY_TARGET", "latest"), zones);
            return 0;
        } catch (Exception e) {
            log.error("migrate failed: {}", e.getMessage(), e);
            return 1;
        }
    }

    public static void migrate(String url, String user, String password, String target, byte[] zonesGeoJson)
            throws IOException, SQLException {
        Flyway flyway = Flyway.configure()
                .dataSource(url, user, password)
                .locations("classpath:db/migration")
                .target(target)
                .connectRetries(30)
                .load();
        MigrateResult result = flyway.migrate();
        log.info("migrations applied: count={} target={} schemaVersion={}",
                result.migrationsExecuted, target, result.targetSchemaVersion);

        ZoneFile.Parsed parsed = ZoneFile.parse(zonesGeoJson);
        parsed.warnings().forEach(w -> log.warn("zone file: {}", w));
        try (Connection c = DriverManager.getConnection(url, user, password)) {
            int loaded = ZoneLoader.sync(c, parsed.zones());
            log.info("zones loaded: count={}", loaded);
        }
    }

    private static String requireEnv(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("environment variable " + name + " is required");
        }
        return v;
    }

    /** From the environment, else from the mounted secret file /run/secrets/NAME. */
    private static String secret(String name) throws IOException {
        String v = System.getenv(name);
        if (v != null && !v.isBlank()) {
            return v;
        }
        Path file = Path.of("/run/secrets", name);
        if (Files.isReadable(file)) {
            return Files.readString(file).strip();
        }
        throw new IllegalStateException(name + " is required (environment or /run/secrets/" + name + ")");
    }

    private static String envOr(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }
}
