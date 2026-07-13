package dev.relay.domain;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public record JobRecord(
        UUID id,
        String tenantId,
        String jobType,
        JsonNode payload,
        JobStatus status,
        int priority,
        int attemptCount,
        int maxAttempts,
        Instant scheduledAt,
        String leaseOwner,
        Instant leaseExpiresAt,
        String idempotencyKey,
        UUID workflowId,
        boolean cancelRequested,
        String lastErrorCode,
        String lastErrorMessage,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt
) {
}
