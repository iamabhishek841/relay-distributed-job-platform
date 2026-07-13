package dev.relay.service;

public class ControlRateLimitException extends RuntimeException {
    public ControlRateLimitException(String message) {
        super(message);
    }
}
