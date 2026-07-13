package dev.relay.dto;

import dev.relay.domain.JobStatus;

import java.util.UUID;

public record SubmitJobResponse(
        UUID jobId,
        JobStatus status,
        boolean duplicate
) {
}
