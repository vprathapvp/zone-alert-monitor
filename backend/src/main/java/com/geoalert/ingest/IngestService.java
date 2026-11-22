package com.geoalert.ingest;

import com.geoalert.AppProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ThreadLocalRandom;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Ingests one batch. Validation happens outside the transaction; everything that writes happens
 * in one short transaction:
 *
 * <ol>
 *   <li>lock every asset in the batch (advisory locks, sorted: no deadlocks, works across instances)</li>
 *   <li>insert reports ON CONFLICT DO NOTHING: what comes back is accepted, the rest is duplicate (R1)</li>
 *   <li>read current positions, walk accepted reports in event time (R2, alert rule)</li>
 *   <li>open alerts (R3 via partial unique index, R5 via trigger), write new positions</li>
 *   <li>store rejected lines and add to the counters (R6)</li>
 * </ol>
 */
@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);
    private static final int COUNTER_SHARDS = 16;
    private static final int MAX_ATTEMPTS = 3;

    private final IngestRepository repo;
    private final TransactionTemplate tx;
    private final PositionTracker.ZoneLookup zones;
    private final ReportValidator validator;
    private final Counter acceptedCounter;
    private final Counter duplicateCounter;
    private final Counter lateCounter;
    private final Counter alertsOpenedCounter;
    private final Map<RejectionCode, Counter> rejectedCounters = new EnumMap<>(RejectionCode.class);
    private final Timer batchTimer;

    public IngestService(IngestRepository repo, TransactionTemplate tx, PositionTracker.ZoneLookup zones,
                         AppProperties props, Clock clock, MeterRegistry meters) {
        this.repo = repo;
        this.tx = tx;
        this.zones = zones;
        this.validator = new ReportValidator(clock, props.maxFutureSkew());
        this.acceptedCounter = meters.counter("ingest.lines", "outcome", "accepted");
        this.duplicateCounter = meters.counter("ingest.lines", "outcome", "duplicate");
        this.lateCounter = meters.counter("ingest.late");
        this.alertsOpenedCounter = meters.counter("alerts.opened");
        for (RejectionCode code : RejectionCode.values()) {
            rejectedCounters.put(code, meters.counter("ingest.lines", "outcome", "rejected", "code", code.name()));
        }
        this.batchTimer = Timer.builder("ingest.batch").publishPercentiles(0.5, 0.95, 0.99).register(meters);
    }

    private record TxOutcome(Set<String> inserted, int alertsOpened, int late) {
    }

    public BatchResult ingest(List<BatchLine> lines, String requestId) {
        return batchTimer.record(() -> doIngest(lines, requestId));
    }

    private BatchResult doIngest(List<BatchLine> lines, String requestId) {
        List<RejectedLine> rejected = new ArrayList<>();
        // First occurrence of each reportId in the batch, with its line number.
        Map<String, ValidReport> unique = new LinkedHashMap<>();
        Map<String, BatchLine> lineOf = new HashMap<>();
        int duplicatesInBatch = 0;
        for (BatchLine line : lines) {
            switch (validator.validate(line.text())) {
                case ReportValidator.Invalid inv -> rejected.add(RejectedLine.of(line, inv.code(), inv.detail()));
                case ReportValidator.Valid v -> {
                    if (unique.putIfAbsent(v.report().reportId(), v.report()) != null) {
                        duplicatesInBatch++;
                    } else {
                        lineOf.put(v.report().reportId(), line);
                    }
                }
            }
        }

        TxOutcome outcome;
        try {
            outcome = runWithRetry(new ArrayList<>(unique.values()), rejected, lines.size(), duplicatesInBatch, requestId);
        } catch (TransientDataAccessException e) {
            throw e; // 500: the sender re-sends the batch, which is safe (R1)
        } catch (DataAccessException e) {
            // Defence for R6: should a valid-looking line still break the batch statement, store
            // each report on its own so the bad one cannot take the good ones down with it.
            log.warn("batch write failed, isolating reports one by one: {}", e.getMostSpecificCause().getClass().getSimpleName());
            outcome = isolate(new ArrayList<>(unique.values()), lineOf, rejected, lines.size(), duplicatesInBatch, requestId);
        }

        int accepted = outcome.inserted().size();
        int duplicates = lines.size() - accepted - rejected.size();
        acceptedCounter.increment(accepted);
        duplicateCounter.increment(duplicates);
        lateCounter.increment(outcome.late());
        alertsOpenedCounter.increment(outcome.alertsOpened());
        rejected.forEach(r -> rejectedCounters.get(r.code()).increment());
        if (!rejected.isEmpty()) {
            log.info("batch had rejected lines", kv("received", lines.size()), kv("rejected", rejected.size()));
        }

        List<BatchResult.Rejection> rejections = rejected.stream()
                .sorted(Comparator.comparingInt(RejectedLine::lineNo))
                .map(r -> new BatchResult.Rejection(r.lineNo(), r.code().name(), r.detail()))
                .toList();
        return new BatchResult(lines.size(), accepted, duplicates, rejected.size(), rejections);
    }

    private TxOutcome runWithRetry(List<ValidReport> reports, List<RejectedLine> rejected, int received,
                                   int duplicatesInBatch, String requestId) {
        for (int attempt = 1; ; attempt++) {
            try {
                return tx.execute(status -> write(reports, rejected, received, duplicatesInBatch, requestId));
            } catch (TransientDataAccessException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw e;
                }
                log.warn("transient database error, retrying batch", kv("attempt", attempt));
            }
        }
    }

    private TxOutcome isolate(List<ValidReport> reports, Map<String, BatchLine> lineOf, List<RejectedLine> rejected,
                              int received, int duplicatesInBatch, String requestId) {
        Set<String> inserted = new TreeSet<>();
        int alerts = 0;
        int late = 0;
        List<RejectedLine> allRejected = new ArrayList<>(rejected);
        for (ValidReport r : reports) {
            try {
                TxOutcome one = runWithRetry(List.of(r), List.of(), 0, 0, requestId);
                inserted.addAll(one.inserted());
                alerts += one.alertsOpened();
                late += one.late();
            } catch (DataAccessException e) {
                log.warn("report could not be stored", kv("line", lineOf.get(r.reportId()).lineNo()));
                allRejected.add(RejectedLine.of(lineOf.get(r.reportId()), RejectionCode.INVALID_FIELD,
                        "report could not be stored"));
            }
        }
        rejected.clear();
        rejected.addAll(allRejected);
        runWithRetry(List.of(), rejected, received, duplicatesInBatch, requestId);
        return new TxOutcome(inserted, alerts, late);
    }

    /** The transaction body. No network calls happen in here other than to the database. */
    private TxOutcome write(List<ValidReport> reports, List<RejectedLine> rejected, int received,
                            int duplicatesInBatch, String requestId) {
        Set<String> inserted = Set.of();
        int alertsOpened = 0;
        int late = 0;
        if (!reports.isEmpty()) {
            repo.lockAssets(lockKeys(reports));

            List<ValidReport> byId = new ArrayList<>(reports);
            byId.sort(Comparator.comparing(ValidReport::reportId));
            inserted = repo.insertReports(byId);

            Map<String, List<ValidReport>> acceptedByAsset = new HashMap<>();
            for (ValidReport r : reports) {
                if (inserted.contains(r.reportId())) {
                    acceptedByAsset.computeIfAbsent(r.assetId(), k -> new ArrayList<>()).add(r);
                }
            }
            Map<String, PositionTracker.Current> current = repo.currentPositions(acceptedByAsset.keySet());

            List<ValidReport> newPositions = new ArrayList<>();
            List<String> alertAssets = new ArrayList<>();
            List<String> alertZones = new ArrayList<>();
            List<ValidReport> alertTriggers = new ArrayList<>();
            for (Map.Entry<String, List<ValidReport>> e : acceptedByAsset.entrySet()) {
                PositionTracker.Result result = PositionTracker.advance(current.get(e.getKey()), e.getValue(), zones);
                late += result.late();
                if (result.newPosition() != null) {
                    newPositions.add(result.newPosition());
                }
                for (PositionTracker.Entry entry : result.entries()) {
                    alertAssets.add(e.getKey());
                    alertZones.add(entry.zoneId());
                    alertTriggers.add(entry.trigger());
                }
            }
            alertsOpened = repo.openAlerts(alertAssets, alertZones, alertTriggers);
            int written = repo.upsertPositions(newPositions);
            if (written != newPositions.size()) {
                // Cannot happen while the asset locks hold; logged loudly if it ever does.
                log.error("position guard skipped rows", kv("expected", newPositions.size()), kv("written", written));
            }
        }
        repo.insertRejections(rejected, requestId);
        int accepted = inserted.size();
        int duplicates = duplicatesInBatch + (reports.size() - accepted);
        repo.addCounters(ThreadLocalRandom.current().nextInt(COUNTER_SHARDS),
                received, accepted, duplicates, rejected.size());
        return new TxOutcome(inserted, alertsOpened, late);
    }

    private static int[] lockKeys(List<ValidReport> reports) {
        // String.hashCode is specified by the language, so every instance derives the same key.
        return reports.stream().mapToInt(r -> r.assetId().hashCode()).distinct().sorted().toArray();
    }
}
