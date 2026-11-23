package com.geoalert.health;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.geoalert.common.ErrorCode;
import com.geoalert.common.Problems;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Zero-downtime stop. On SIGTERM this runs before the web server's graceful shutdown: for a few
 * seconds every NEW request is answered 503 without being processed, while in-flight requests
 * finish. The load balancer retries a 503 on the other instance (safe: nothing was done) and marks
 * this one down, so no client request fails during a rolling upgrade.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class Drain extends OncePerRequestFilter implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(Drain.class);

    private final ObjectMapper mapper;
    private final long drainMillis;
    private volatile boolean running;
    private volatile boolean draining;

    public Drain(ObjectMapper mapper, @Value("${DRAIN_SECONDS:5}") long drainSeconds) {
        this.mapper = mapper;
        this.drainMillis = drainSeconds * 1000;
    }

    public boolean draining() {
        return draining;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (draining) {
            res.setStatus(503);
            res.setHeader("Connection", "close");
            res.setHeader("Retry-After", "1");
            res.setContentType(Problems.PROBLEM_JSON.toString());
            mapper.writeValue(res.getOutputStream(), Problems.body(ErrorCode.INTERNAL_ERROR, 503,
                    "Instance is shutting down; retry", req.getRequestURI()));
            return;
        }
        chain.doFilter(req, res);
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        draining = true;
        log.info("draining before shutdown for {} ms", drainMillis);
        try {
            Thread.sleep(drainMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Highest phase: stopped first, before the web server starts its graceful shutdown. */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }
}
