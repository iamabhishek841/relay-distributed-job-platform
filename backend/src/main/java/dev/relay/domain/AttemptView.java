package dev.relay.domain;

import java.time.Instant;
import java.util.UUID;

public record AttemptView(
        long id,
        UUID jobId,
        int attemptNumber,
        String workerId,
        String status,
        Instant startedAt,
        Instant finishedAt,
        Long durationMs,
        String errorCode,
        String errorMessage
) {
}
