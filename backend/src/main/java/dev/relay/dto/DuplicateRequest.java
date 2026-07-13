package dev.relay.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record DuplicateRequest(
        @Min(2) @Max(1000) Integer requests
) {
}
