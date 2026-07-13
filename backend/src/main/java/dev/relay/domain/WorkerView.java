package dev.relay.domain;

import java.time.Instant;

public record WorkerView(
        String id,
        String status,
        Instant lastHeartbeat,
        Instant startedAt,
        int activeJobs
) {
}
