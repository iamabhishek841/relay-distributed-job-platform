package dev.relay.service;

import dev.relay.dto.SystemSnapshot;
import dev.relay.repository.EventRepository;
import dev.relay.repository.JobRepository;
import dev.relay.repository.WorkerRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

@Service
public class MetricsService {
    private final JobRepository jobs;
    private final WorkerRepository workers;
    private final EventRepository events;

    public MetricsService(JobRepository jobs, WorkerRepository workers, EventRepository events) {
        this.jobs = jobs;
        this.workers = workers;
        this.events = events;
    }

    public SystemSnapshot snapshot() {
        Map<String, Long> counts = jobs.countsByStatus();
        long queueDepth = counts.getOrDefault("QUEUED", 0L) + counts.getOrDefault("RETRY_WAIT", 0L);

        return new SystemSnapshot(
                Instant.now(),
                counts,
                queueDepth,
                jobs.throughputPerSecond(),
                jobs.percentileExecution(0.95),
                jobs.percentileExecution(0.99),
                jobs.retriesLastHour(),
                events.countByType("LEASE_EXPIRED_JOB_RECOVERED"),
                jobs.sideEffectCount(),
                workers.listWorkers(),
                events.recent(24)
        );
    }
}
