package com.geoalert.health;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HealthController {

    private final HealthService health;

    public HealthController(HealthService health) {
        this.health = health;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return health.ready()
                ? ResponseEntity.ok(Map.of("status", "UP"))
                : ResponseEntity.status(503).body(Map.of("status", "DOWN"));
    }
}
