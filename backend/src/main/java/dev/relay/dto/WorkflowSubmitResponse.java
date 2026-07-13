package dev.relay.dto;

import java.util.Map;
import java.util.UUID;

public record WorkflowSubmitResponse(
        UUID workflowId,
        Map<String, UUID> jobs
) {
}
