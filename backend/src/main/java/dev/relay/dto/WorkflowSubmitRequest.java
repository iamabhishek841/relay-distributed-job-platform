package dev.relay.dto;

import tools.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record WorkflowSubmitRequest(
        @NotBlank String name,
        @NotBlank String tenantId,
        @NotEmpty List<@Valid WorkflowNode> nodes
) {
    public record WorkflowNode(
            @NotBlank String key,
            @NotBlank String jobType,
            JsonNode payload,
            List<String> dependsOn
    ) {
    }
}
