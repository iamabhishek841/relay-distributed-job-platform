package dev.relay.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkflowValidatorTest {

    private final WorkflowValidator validator = new WorkflowValidator();

    @Test
    void acceptsAcyclicWorkflow() {
        Map<String, List<String>> graph = Map.of(
                "ingest", List.of(),
                "validate", List.of("ingest"),
                "score", List.of("validate"),
                "enrich", List.of("validate"),
                "publish", List.of("score", "enrich")
        );

        assertDoesNotThrow(() -> validator.validate(graph));
    }

    @Test
    void rejectsCycle() {
        Map<String, List<String>> graph = Map.of(
                "a", List.of("c"),
                "b", List.of("a"),
                "c", List.of("b")
        );

        assertThrows(IllegalArgumentException.class, () -> validator.validate(graph));
    }

    @Test
    void rejectsUnknownDependency() {
        Map<String, List<String>> graph = Map.of(
                "a", List.of(),
                "b", List.of("missing")
        );

        assertThrows(IllegalArgumentException.class, () -> validator.validate(graph));
    }
}
