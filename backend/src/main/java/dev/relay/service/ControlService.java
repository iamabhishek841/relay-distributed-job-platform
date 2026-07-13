package dev.relay.service;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import dev.relay.config.RelayProperties;
import dev.relay.dto.*;
import dev.relay.repository.JobRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Service
public class ControlService {
    private final JobRepository jobs;
    private final JobService jobService;
    private final WorkerFleetService workers;
    private final EventStreamService events;
    private final RelayProperties properties;
    private final ObjectMapper objectMapper;
    private final ControlGuard guard;
    private final RelayMetrics metrics;

    public ControlService(
            JobRepository jobs,
            JobService jobService,
            WorkerFleetService workers,
            EventStreamService events,
            RelayProperties properties,
            ObjectMapper objectMapper,
            ControlGuard guard,
            RelayMetrics metrics
    ) {
        this.jobs = jobs;
        this.jobService = jobService;
        this.workers = workers;
        this.events = events;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.guard = guard;
        this.metrics = metrics;
    }

    public Map<String, Object> burst(BurstRequest request) {
        guard.acquire(5, "burst");
        int count = request.count() == null ? 1_000 : request.count();
        if (count > properties.maxBurstSize()) {
            throw new IllegalArgumentException("Burst exceeds configured maximum of " + properties.maxBurstSize());
        }
        int durationMs = request.durationMs() == null ? 120 : request.durationMs();
        String tenant = request.tenantId() == null || request.tenantId().isBlank()
                ? "burst-tenant"
                : request.tenantId();

        long started = System.nanoTime();
        jobs.insertBurst(count, durationMs, tenant);
        metrics.submitted(count);
        long submissionMs = (System.nanoTime() - started) / 1_000_000;

        Map<String, Object> result = Map.of(
                "accepted", count,
                "tenantId", tenant,
                "durationMs", durationMs,
                "submissionMs", submissionMs
        );
        events.publish("BURST_ACCEPTED", "SYSTEM", "relay", result, true);
        return result;
    }

    public Map<String, Object> inject503(FailureInjectionRequest request) {
        guard.acquire(2, "inject-503");
        int count = request.count() == null ? 20 : request.count();
        int failAttempts = request.failAttempts() == null ? 3 : request.failAttempts();
        int durationMs = request.durationMs() == null ? 180 : request.durationMs();

        for (int i = 0; i < count; i++) {
            ObjectNode payload = objectMapper.createObjectNode()
                    .put("durationMs", durationMs)
                    .put("failureMode", "FAIL_FIRST_N")
                    .put("failAttempts", failAttempts);
            jobService.submit(new SubmitJobRequest(
                    "dependency-failure",
                    "SIMULATED_HTTP",
                    payload,
                    10,
                    Math.max(5, failAttempts + 1),
                    Instant.now(),
                    null
            ));
        }

        Map<String, Object> result = Map.of(
                "accepted", count,
                "failAttempts", failAttempts,
                "failure", "HTTP_503"
        );
        events.publish("DEPENDENCY_FAILURE_INJECTED", "SYSTEM", "relay", result, true);
        return result;
    }

    public Map<String, Object> duplicate(DuplicateRequest request) {
        guard.acquire(3, "duplicate-submission");
        int count = request.requests() == null ? 100 : request.requests();
        String idempotencyKey = "order-" + UUID.randomUUID();
        ObjectNode payload = objectMapper.createObjectNode()
                .put("durationMs", 250)
                .put("failureMode", "NONE")
                .put("effectKey", idempotencyKey);

        Set<UUID> uniqueJobIds = new HashSet<>();
        long duplicateResponses;

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<SubmitJobResponse>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                futures.add(executor.submit(() -> jobService.submit(new SubmitJobRequest(
                        "idempotency-tenant",
                        "SIMULATED_SIDE_EFFECT",
                        payload,
                        20,
                        5,
                        Instant.now(),
                        idempotencyKey
                ))));
            }

            long duplicates = 0;
            for (Future<SubmitJobResponse> future : futures) {
                SubmitJobResponse response = future.get();
                uniqueJobIds.add(response.jobId());
                if (response.duplicate()) {
                    duplicates++;
                }
            }
            duplicateResponses = duplicates;
        } catch (Exception error) {
            throw new IllegalStateException("Duplicate submission experiment failed", error);
        }

        Map<String, Object> result = Map.of(
                "requests", count,
                "uniqueJobs", uniqueJobIds.size(),
                "duplicateResponses", duplicateResponses,
                "idempotencyKey", idempotencyKey
        );
        events.publish("IDEMPOTENCY_STRESS_COMPLETED", "SYSTEM", "relay", result, true);
        return result;
    }

    public Map<String, Object> crashAfterSideEffect() {
        guard.acquire(3, "crash-window");
        String effectKey = "invoice-" + UUID.randomUUID();
        ObjectNode payload = objectMapper.createObjectNode()
                .put("durationMs", 900)
                .put("failureMode", "CRASH_AFTER_SIDE_EFFECT_ONCE")
                .put("effectKey", effectKey);

        SubmitJobResponse response = jobService.submit(new SubmitJobRequest(
                "correctness-tenant",
                "SIMULATED_SIDE_EFFECT",
                payload,
                100,
                5,
                Instant.now(),
                effectKey
        ));

        Map<String, Object> result = Map.of(
                "jobId", response.jobId(),
                "effectKey", effectKey,
                "expected", "first worker crashes after side effect; lease recovery retries without duplicating effect"
        );
        events.publish("CRASH_WINDOW_INJECTED", "JOB", response.jobId().toString(), result, true);
        return result;
    }

    public Map<String, Object> killWorker(String workerId) {
        guard.acquire(4, "terminate-worker");
        String killed = workers.killWorker(workerId);
        return Map.of("workerId", killed, "status", "DEAD");
    }

    public Map<String, Object> startReplacementWorker() {
        guard.acquire(2, "start-worker");
        String workerId = workers.startReplacementWorker();
        return Map.of("workerId", workerId, "status", "ACTIVE");
    }

    public Map<String, Object> reset() {
        guard.acquire(10, "reset");
        jobs.resetAll();
        events.publish("SYSTEM_RESET", "SYSTEM", "relay", Map.of(), false);
        return Map.of("status", "RESET", "at", Instant.now());
    }
}
