package com.geoalert.health;

import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Readiness of this instance: the database answers and the instance is not shutting down.
 * During a rolling upgrade the load balancer stops sending to an instance as soon as it starts
 * draining.
 */
@Service
public class HealthService {

    private final JdbcTemplate jdbc;
    private final AtomicBoolean stopping = new AtomicBoolean(false);

    public HealthService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean ready() {
        if (stopping.get()) {
            return false;
        }
        try {
            Integer one = jdbc.queryForObject("SELECT 1", Integer.class);
            return one != null && one == 1;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @EventListener(ContextClosedEvent.class)
    public void onShutdown() {
        stopping.set(true);
    }
}
