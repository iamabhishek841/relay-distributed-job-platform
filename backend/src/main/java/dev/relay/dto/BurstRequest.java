package dev.relay.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record BurstRequest(
        @Min(1) @Max(10000) Integer count,
        @Min(10) @Max(30000) Integer durationMs,
        String tenantId
) {
}
