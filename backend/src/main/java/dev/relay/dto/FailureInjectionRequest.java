package dev.relay.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record FailureInjectionRequest(
        @Min(1) @Max(1000) Integer count,
        @Min(1) @Max(10) Integer failAttempts,
        @Min(10) @Max(30000) Integer durationMs
) {
}
