package dev.relay.service;

import dev.relay.config.RelayProperties;
import dev.relay.domain.FailureKind;
import dev.relay.domain.JobRecord;
import dev.relay.domain.WorkerView;
import dev.relay.repository.JobRepository;
import dev.relay.repository.WorkerRepository;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class WorkerFleetService {
    private final JobRepository jobs;
    private final WorkerRepository workers;
    private final SimulatedJobExecutor executor;
    private final BackoffPolicy backoff;
    private final EventStreamService events;
    private final RelayProperties properties;
    private final RelayMetrics metrics;
    private final Map<String, WorkerRuntime> runtimes = new ConcurrentHashMap<>();
    private final String instanceId = UUID.randomUUID().toString().substring(0, 8);

    public WorkerFleetService(
            JobRepository jobs,
            WorkerRepository workers,
            SimulatedJobExecutor executor,
            BackoffPolicy backoff,
            EventStreamService events,
            RelayProperties properties,
            RelayMetrics metrics
    ) {
        this.jobs = jobs;
        this.workers = workers;
        this.executor = executor;
        this.backoff = backoff;
        this.events = events;
        this.properties = properties;
        this.metrics = metrics;
    }

    @EventListener(ApplicationReadyEvent.class)
    void start() {
        if (!properties.workersEnabled()) {
            return;
        }
        for (int i = 1; i <= properties.workerCount(); i++) {
            startWorker(workerId(i));
        }
    }

    @PreDestroy
    void shutdown() {
        runtimes.values().forEach(WorkerRuntime::kill);
    }

    public List<WorkerView> listWorkers() {
        return workers.listWorkers();
    }

    public synchronized String killWorker(String requestedId) {
        WorkerRuntime runtime;
        if (requestedId != null && !requestedId.isBlank()) {
            runtime = runtimes.get(requestedId);
        } else {
            Set<String> busy = new LinkedHashSet<>();
            for (WorkerView view : workers.listWorkers()) {
                if ("ACTIVE".equals(view.status()) && view.activeJobs() > 0) {
                    busy.add(view.id());
                }
            }
            runtime = runtimes.values().stream()
                    .filter(WorkerRuntime::isActive)
                    .filter(candidate -> busy.isEmpty() || busy.contains(candidate.workerId))
                    .findFirst()
                    .orElse(null);
        }

        if (runtime == null || !runtime.isActive()) {
            throw new IllegalStateException("No active worker available to terminate");
        }

        runtime.kill();
        workers.markDead(runtime.workerId);
        events.publish("WORKER_LOST", "WORKER", runtime.workerId, Map.of(
                "reason", "operator termination",
                "leaseSeconds", properties.leaseSeconds()
        ), true);
        return runtime.workerId;
    }

    public synchronized String startReplacementWorker() {
        if (!properties.workersEnabled()) {
            throw new IllegalStateException("This Relay instance has its in-process worker fleet disabled");
        }
        int suffix = runtimes.size() + 1;
        String id = instanceId + "-worker-" + String.format("%02d", suffix);
        startWorker(id);
        return id;
    }

    @Scheduled(fixedDelay = 1_000)
    void heartbeat() {
        if (!properties.workersEnabled()) {
            return;
        }
        Duration lease = Duration.ofSeconds(properties.leaseSeconds());
        for (WorkerRuntime runtime : runtimes.values()) {
            if (!runtime.isActive()) {
                continue;
            }
            workers.heartbeat(runtime.workerId);
            jobs.extendLeases(runtime.workerId, lease);
        }
    }

    private void startWorker(String workerId) {
        WorkerRuntime runtime = new WorkerRuntime(workerId);
        runtimes.put(workerId, runtime);
        workers.upsert(workerId, "ACTIVE");
        runtime.start();
        events.publish("WORKER_STARTED", "WORKER", workerId, Map.of(
                "instanceId", instanceId
        ), true);
    }

    private String workerId(int index) {
        return instanceId + "-worker-" + String.format("%02d", index);
    }

    private final class WorkerRuntime implements Runnable {
        private final String workerId;
        private final AtomicBoolean active = new AtomicBoolean(true);
        private volatile Thread thread;

        private WorkerRuntime(String workerId) {
            this.workerId = workerId;
        }

        private void start() {
            thread = Thread.ofPlatform().name(workerId).start(this);
        }

        private boolean isActive() {
            return active.get();
        }

        private void kill() {
            active.set(false);
            Thread runningThread = thread;
            if (runningThread != null) {
                runningThread.interrupt();
            }
        }

        @Override
        public void run() {
            Duration lease = Duration.ofSeconds(properties.leaseSeconds());

            while (active.get() && !Thread.currentThread().isInterrupted()) {
                Optional<JobRecord> claimed = jobs.claimNext(workerId, lease);
                if (claimed.isEmpty()) {
                    sleepPoll();
                    continue;
                }
                execute(claimed.get());
            }
        }

        private void execute(JobRecord job) {
            long attemptId = jobs.startAttempt(job, workerId);
            long startedNanos = System.nanoTime();
            events.publishSampled("JOB_CLAIMED", "JOB", job.id().toString(), Map.of(
                    "workerId", workerId,
                    "attempt", job.attemptCount()
            ));

            try {
                executor.execute(job);
                long durationMs = elapsedMillis(startedNanos);
                JobRepository.CompletionResult completion = jobs.completeSuccess(job, attemptId, durationMs, workerId);
                if (!completion.applied()) {
                    metrics.staleTransitionRejected();
                    events.publish("STALE_COMPLETION_REJECTED", "JOB", job.id().toString(), Map.of(
                            "workerId", workerId,
                            "reason", "lease ownership lost"
                    ), true);
                    return;
                }
                metrics.completed(durationMs);
                events.publishSampled("JOB_SUCCEEDED", "JOB", job.id().toString(), Map.of(
                        "workerId", workerId,
                        "durationMs", durationMs
                ));
                for (UUID child : completion.unblocked()) {
                    events.publish("WORKFLOW_JOB_UNBLOCKED", "JOB", child.toString(), Map.of(
                            "completedDependency", job.id().toString()
                    ), false);
                }
            } catch (WorkerCrashException crash) {
                active.set(false);
                workers.markDead(workerId);
                events.publish("WORKER_LOST", "WORKER", workerId, Map.of(
                        "reason", crash.getMessage(),
                        "jobId", job.id().toString()
                ), true);
                Thread.currentThread().interrupt();
            } catch (JobExecutionException failure) {
                long durationMs = elapsedMillis(startedNanos);
                boolean canRetry = failure.kind() == FailureKind.TRANSIENT
                        && job.attemptCount() < job.maxAttempts();

                if (canRetry) {
                    Duration delay = backoff.nextDelay(job.attemptCount());
                    Instant nextAttempt = Instant.now().plus(delay);
                    boolean applied = jobs.completeTransientFailure(
                            job,
                            attemptId,
                            durationMs,
                            failure.code(),
                            failure.getMessage(),
                            nextAttempt,
                            workerId
                    );
                    if (!applied) {
                        metrics.staleTransitionRejected();
                        events.publish("STALE_COMPLETION_REJECTED", "JOB", job.id().toString(), Map.of(
                                "workerId", workerId,
                                "reason", "lease ownership lost"
                        ), true);
                        return;
                    }
                    metrics.retried();
                    events.publish("JOB_RETRY_SCHEDULED", "JOB", job.id().toString(), Map.of(
                            "workerId", workerId,
                            "attempt", job.attemptCount(),
                            "errorCode", failure.code(),
                            "retryInMs", delay.toMillis()
                    ), true);
                } else {
                    boolean applied = jobs.moveToDeadLetter(
                            job,
                            attemptId,
                            durationMs,
                            failure.code(),
                            failure.getMessage(),
                            workerId
                    );
                    if (!applied) {
                        metrics.staleTransitionRejected();
                        events.publish("STALE_COMPLETION_REJECTED", "JOB", job.id().toString(), Map.of(
                                "workerId", workerId,
                                "reason", "lease ownership lost"
                        ), true);
                        return;
                    }
                    metrics.deadLettered();
                    events.publish("JOB_DEAD_LETTERED", "JOB", job.id().toString(), Map.of(
                            "workerId", workerId,
                            "attempts", job.attemptCount(),
                            "errorCode", failure.code()
                    ), true);
                }
            } catch (RuntimeException unexpected) {
                long durationMs = elapsedMillis(startedNanos);
                boolean applied = jobs.moveToDeadLetter(
                        job,
                        attemptId,
                        durationMs,
                        "UNEXPECTED_ERROR",
                        unexpected.getMessage() == null ? unexpected.getClass().getSimpleName() : unexpected.getMessage(),
                        workerId
                );
                if (!applied) {
                    metrics.staleTransitionRejected();
                    events.publish("STALE_COMPLETION_REJECTED", "JOB", job.id().toString(), Map.of(
                            "workerId", workerId,
                            "reason", "lease ownership lost"
                    ), true);
                    return;
                }
                metrics.deadLettered();
                events.publish("JOB_DEAD_LETTERED", "JOB", job.id().toString(), Map.of(
                        "workerId", workerId,
                        "errorCode", "UNEXPECTED_ERROR"
                ), true);
            }
        }

        private void sleepPoll() {
            try {
                Thread.sleep(properties.claimPollMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        private long elapsedMillis(long startedNanos) {
            return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
        }
    }
}
