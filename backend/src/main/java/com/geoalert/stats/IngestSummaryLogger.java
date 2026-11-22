package com.geoalert.stats;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.Search;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Once a minute, one structured log line with this instance's ingest rate, rejections and HTTP
 * errors. "docker compose logs" is then enough to see throughput without any metrics stack.
 */
@Component
public class IngestSummaryLogger {

    private static final Logger log = LoggerFactory.getLogger("ingest.summary");

    private final MeterRegistry meters;
    private double lastAccepted;
    private double lastDuplicates;
    private double lastRejected;
    private double lastServerErrors;

    public IngestSummaryLogger(MeterRegistry meters) {
        this.meters = meters;
    }

    @Scheduled(fixedRate = 60_000, initialDelay = 60_000)
    public void logSummary() {
        double accepted = sum(Search.in(meters).name("ingest.lines").tag("outcome", "accepted"));
        double duplicates = sum(Search.in(meters).name("ingest.lines").tag("outcome", "duplicate"));
        double rejected = sum(Search.in(meters).name("ingest.lines").tag("outcome", "rejected"));
        double serverErrors = Search.in(meters).name("http.server.requests").tag("outcome", "SERVER_ERROR")
                .timers().stream().mapToDouble(t -> t.count()).sum();
        double lines = (accepted - lastAccepted) + (duplicates - lastDuplicates) + (rejected - lastRejected);
        if (lines > 0 || serverErrors > lastServerErrors) {
            log.info("ingest summary (last 60 s)",
                    kv("linesPerSec", Math.round(lines / 60.0)),
                    kv("accepted", (long) (accepted - lastAccepted)),
                    kv("duplicates", (long) (duplicates - lastDuplicates)),
                    kv("rejected", (long) (rejected - lastRejected)),
                    kv("http5xx", (long) (serverErrors - lastServerErrors)));
        }
        lastAccepted = accepted;
        lastDuplicates = duplicates;
        lastRejected = rejected;
        lastServerErrors = serverErrors;
    }

    private static double sum(Search search) {
        return search.counters().stream().mapToDouble(Counter::count).sum();
    }
}
