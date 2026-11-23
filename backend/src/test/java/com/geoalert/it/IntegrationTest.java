package com.geoalert.it;

import com.geoalert.MigrateCommand;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Real PostgreSQL (Testcontainers), real HTTP, the real migration path (Flyway + zone load, as the
 * migrate container runs it). One container is shared by all integration tests; each test uses
 * its own asset ids so tests do not see each other's data.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTest {

    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16.8-alpine");
    static final Path USERS_FILE;
    static final String PASSWORD_PREFIX = "pw-";

    static {
        PG.start();
        try (InputStream zones = IntegrationTest.class.getResourceAsStream("/zones.geojson")) {
            MigrateCommand.migrate(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword(), "latest", zones.readAllBytes());
            USERS_FILE = Files.createTempFile("users", ".json");
            List<String> users = new ArrayList<>();
            users.add(user("feed", "INGEST"));
            users.add(user("sup01", "SUPERVISOR"));
            for (int i = 1; i <= 20; i++) {
                users.add(user(String.format("op%02d", i), "OPERATOR"));
            }
            Files.writeString(USERS_FILE, "[" + String.join(",", users) + "]");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String user(String name, String role) {
        return "{\"username\":\"" + name + "\",\"role\":\"" + role + "\",\"password\":\"" + PASSWORD_PREFIX + name + "\"}";
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
        r.add("geoalert.users-file", USERS_FILE::toString);
        r.add("geoalert.instance-id", () -> "test-a");
        r.add("DRAIN_SECONDS", () -> "0");
    }

    @LocalServerPort
    protected int port;

    @Autowired
    protected JdbcTemplate jdbc;

    protected Api api;

    @BeforeEach
    void setUpApi() {
        api = new Api("http://localhost:" + port);
    }

    /** A fresh asset id per test so tests are independent on the shared database. */
    protected static String newAsset() {
        return "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    }

    protected static String newId() {
        return UUID.randomUUID().toString();
    }

    /** Z-01 Alpha Range (RESTRICTED) is the box lon 168.0..168.8, lat 38.0..38.6. */
    protected static final double IN_LAT = 38.3, IN_LON = 168.4;
    protected static final double OUT_LAT = 37.0, OUT_LON = 168.4;

    protected static String report(String reportId, String assetId, String observedAt, double lat, double lon) {
        return "{\"reportId\":\"" + reportId + "\",\"assetId\":\"" + assetId + "\",\"kind\":\"SURFACE\",\"observedAt\":\""
                + observedAt + "\",\"lat\":" + lat + ",\"lon\":" + lon + ",\"speedKn\":10.5,\"headingDeg\":90.0,\"source\":\"SAT-1\"}";
    }

    protected static String at(int second) {
        return com.geoalert.common.Times.format(java.time.Instant.parse("2026-11-02T06:00:00Z").plusSeconds(second));
    }

    /** Runs the tasks at the same moment (start gate) on separate threads and returns their results. */
    protected static <T> List<T> parallel(List<java.util.concurrent.Callable<T>> tasks) throws Exception {
        var pool = java.util.concurrent.Executors.newFixedThreadPool(tasks.size());
        var gate = new java.util.concurrent.CountDownLatch(1);
        try {
            List<java.util.concurrent.Future<T>> futures = new ArrayList<>();
            for (var t : tasks) {
                futures.add(pool.submit(() -> {
                    gate.await();
                    return t.call();
                }));
            }
            gate.countDown();
            List<T> results = new ArrayList<>();
            for (var f : futures) {
                results.add(f.get(60, java.util.concurrent.TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
