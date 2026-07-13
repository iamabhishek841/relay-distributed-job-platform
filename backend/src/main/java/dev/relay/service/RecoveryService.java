package dev.relay.service;

import dev.relay.config.RelayProperties;
import dev.relay.repository.JobRepository;
import dev.relay.repository.WorkerRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class RecoveryService {
    private final JobRepository jobs;
    private final WorkerRepository workers;
    private final EventStreamService events;
    private final RelayMetrics metrics;
    private final RelayProperties properties;

    public RecoveryService(
            JobRepository jobs,
            WorkerRepository workers,
            EventStreamService events,
            RelayMetrics metrics,
            RelayProperties properties
    ) {
        this.jobs = jobs;
        this.workers = workers;
        this.events = events;
        this.metrics = metrics;
        this.properties = properties;
    }

    @Scheduled(fixedDelay = 1_000)
    void recoverExpiredLeases() {
        if (!properties.recoveryEnabled()) {
            return;
        }

        Duration staleWorkerThreshold = Duration.ofSeconds(
                Math.max(properties.leaseSeconds() * 2L, properties.heartbeatSeconds() * 3L)
        );
        workers.markStaleWorkersDead(staleWorkerThreshold);
        workers.pruneDeadWorkers(Duration.ofMinutes(10));

        List<UUID> recovered = jobs.recoverExpiredLeases();
        for (UUID jobId : recovered) {
            metrics.recovered();
            events.publish("LEASE_EXPIRED_JOB_RECOVERED", "JOB", jobId.toString(), Map.of(
                    "action", "requeued"
            ), true);
        }
    }
}
