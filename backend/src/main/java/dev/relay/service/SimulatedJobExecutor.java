package dev.relay.service;

import tools.jackson.databind.JsonNode;
import dev.relay.domain.FailureKind;
import dev.relay.domain.JobRecord;
import dev.relay.repository.JobRepository;
import org.springframework.stereotype.Component;

@Component
public class SimulatedJobExecutor {
    private final JobRepository jobRepository;

    public SimulatedJobExecutor(JobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    public void execute(JobRecord job) throws JobExecutionException {
        JsonNode payload = job.payload();
        int durationMs = intValue(payload, "durationMs", 180);
        String failureMode = textValue(payload, "failureMode", "NONE");
        int failAttempts = intValue(payload, "failAttempts", 0);
        String effectKey = textValue(payload, "effectKey", null);

        sleep(durationMs);

        switch (failureMode) {
            case "FAIL_FIRST_N" -> {
                if (job.attemptCount() <= failAttempts) {
                    throw new JobExecutionException(
                            FailureKind.TRANSIENT,
                            "DEPENDENCY_503",
                            "Simulated dependency returned HTTP 503"
                    );
                }
            }
            case "ALWAYS_503" -> throw new JobExecutionException(
                    FailureKind.TRANSIENT,
                    "DEPENDENCY_503",
                    "Simulated dependency returned HTTP 503"
            );
            case "TIMEOUT" -> throw new JobExecutionException(
                    FailureKind.TRANSIENT,
                    "DEPENDENCY_TIMEOUT",
                    "Simulated dependency timed out"
            );
            case "PERMANENT" -> throw new JobExecutionException(
                    FailureKind.PERMANENT,
                    "INVALID_PAYLOAD",
                    "Simulated payload validation failure"
            );
            case "CRASH_AFTER_SIDE_EFFECT_ONCE" -> {
                if (effectKey == null || effectKey.isBlank()) {
                    throw new JobExecutionException(
                            FailureKind.PERMANENT,
                            "MISSING_EFFECT_KEY",
                            "Crash-after-side-effect jobs require effectKey"
                    );
                }
                jobRepository.recordSideEffect(effectKey, job.id());
                if (job.attemptCount() == 1) {
                    throw new WorkerCrashException("Worker crashed after external side effect and before acknowledgement");
                }
            }
            case "NONE" -> {
                // Normal execution.
            }
            default -> throw new JobExecutionException(
                    FailureKind.PERMANENT,
                    "UNKNOWN_FAILURE_MODE",
                    "Unsupported failureMode: " + failureMode
            );
        }

        if (effectKey != null && !effectKey.isBlank()) {
            jobRepository.recordSideEffect(effectKey, job.id());
        }
    }

    private static int intValue(JsonNode payload, String field, int defaultValue) {
        JsonNode node = payload == null ? null : payload.get(field);
        return node == null || !node.canConvertToInt() ? defaultValue : node.asInt();
    }

    private static String textValue(JsonNode payload, String field, String defaultValue) {
        JsonNode node = payload == null ? null : payload.get(field);
        return node == null || node.isNull() ? defaultValue : node.asString();
    }

    private static void sleep(int durationMs) {
        try {
            Thread.sleep(Math.max(0, durationMs));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new WorkerCrashException("Worker thread interrupted during execution");
        }
    }
}
