package dev.relay.service;

public class WorkerCrashException extends RuntimeException {
    public WorkerCrashException(String message) {
        super(message);
    }
}
