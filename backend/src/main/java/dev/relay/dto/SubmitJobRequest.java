package dev.relay.dto;

import tools.jackson.databind.JsonNode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

public record SubmitJobRequest(
        @NotBlank String tenantId,
        @NotBlank String jobType,
        JsonNode payload,
        @Min(-100) @Max(100) Integer priority,
        @Min(1) @Max(20) Integer maxAttempts,
        Instant scheduledAt,
        String idempotencyKey
) {
}
