package dev.relay.service;

import dev.relay.domain.FailureKind;

public class JobExecutionException extends Exception {
    private final FailureKind kind;
    private final String code;

    public JobExecutionException(FailureKind kind, String code, String message) {
        super(message);
        this.kind = kind;
        this.code = code;
    }

    public FailureKind kind() {
        return kind;
    }

    public String code() {
        return code;
    }
}
