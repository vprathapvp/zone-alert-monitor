package com.geoalert;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "geoalert")
public record AppProperties(
        String instanceId,
        String usersFile,
        Duration maxFutureSkew,
        Duration tokenTtl,
        long livePollIntervalMs) {
}
