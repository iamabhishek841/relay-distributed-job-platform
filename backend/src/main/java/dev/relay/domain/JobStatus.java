package dev.relay.domain;

public enum JobStatus {
    BLOCKED,
    QUEUED,
    RUNNING,
    RETRY_WAIT,
    SUCCEEDED,
    DEAD_LETTER,
    CANCELLED
}
