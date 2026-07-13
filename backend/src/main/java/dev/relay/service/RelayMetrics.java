package dev.relay.service;

import dev.relay.repository.JobRepository;
import dev.relay.repository.WorkerRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class RelayMetrics {
    private final JobRepository jobs;
    private final WorkerRepository workers;

    private final AtomicLong queueDepth = new AtomicLong();
    private final AtomicLong runningJobs = new AtomicLong();
    private final AtomicLong deadLetterJobs = new AtomicLong();
    private final AtomicLong activeWorkers = new AtomicLong();

    private final Counter submitted;
    private final Counter completed;
    private final Counter retried;
    private final Counter recovered;
    private final Counter duplicatesSuppressed;
    private final Counter staleTransitionsRejected;
    private final Counter deadLettered;
    private final Timer executionDuration;

    public RelayMetrics(MeterRegistry registry, JobRepository jobs, WorkerRepository workers) {
        this.jobs = jobs;
        this.workers = workers;

        registry.gauge("relay.queue.depth", queueDepth);
        registry.gauge("relay.jobs.running", runningJobs);
        registry.gauge("relay.jobs.dead_letter", deadLetterJobs);
        registry.gauge("relay.workers.active", activeWorkers);

        submitted = registry.counter("relay.jobs.submitted");
        completed = registry.counter("relay.jobs.completed");
        retried = registry.counter("relay.jobs.retried");
        recovered = registry.counter("relay.jobs.recovered");
        duplicatesSuppressed = registry.counter("relay.idempotency.duplicates_suppressed");
        staleTransitionsRejected = registry.counter("relay.leases.stale_transitions_rejected");
        deadLettered = registry.counter("relay.jobs.dead_lettered");
        executionDuration = Timer.builder("relay.job.execution")
                .publishPercentileHistogram()
                .register(registry);
    }

    public void submitted() {
        submitted.increment();
    }

    public void submitted(long count) {
        if (count > 0) {
            submitted.increment(count);
        }
    }

    public void completed(long durationMs) {
        completed.increment();
        executionDuration.record(Duration.ofMillis(Math.max(0, durationMs)));
    }

    public void retried() {
        retried.increment();
    }

    public void recovered() {
        recovered.increment();
    }

    public void duplicateSuppressed() {
        duplicatesSuppressed.increment();
    }

    public void staleTransitionRejected() {
        staleTransitionsRejected.increment();
    }

    public void deadLettered() {
        deadLettered.increment();
    }

    @Scheduled(fixedDelay = 1_000)
    void refreshGauges() {
        Map<String, Long> counts = jobs.countsByStatus();
        queueDepth.set(counts.getOrDefault("QUEUED", 0L) + counts.getOrDefault("RETRY_WAIT", 0L));
        runningJobs.set(counts.getOrDefault("RUNNING", 0L));
        deadLetterJobs.set(counts.getOrDefault("DEAD_LETTER", 0L));
        activeWorkers.set(workers.listWorkers().stream()
                .filter(worker -> "ACTIVE".equals(worker.status()))
                .count());
    }
}
