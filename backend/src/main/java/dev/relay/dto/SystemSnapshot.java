package dev.relay.dto;

import dev.relay.domain.WorkerView;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record SystemSnapshot(
        Instant timestamp,
        Map<String, Long> jobCounts,
        long queueDepth,
        double throughputPerSecond,
        long p95ExecutionMs,
        long p99ExecutionMs,
        long retriesLastHour,
        long recoveredJobs,
        long sideEffects,
        List<WorkerView> workers,
        List<Map<String, Object>> recentEvents
) {
}
