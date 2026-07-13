package dev.relay.service;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

@Component
public class BackoffPolicy {
    private static final long BASE_MILLIS = 500;
    private static final long MAX_MILLIS = 30_000;

    public Duration nextDelay(int attemptNumber) {
        int exponent = Math.max(0, Math.min(attemptNumber - 1, 20));
        long exponential = Math.min(MAX_MILLIS, BASE_MILLIS * (1L << exponent));
        long jitterBound = Math.max(1, exponential / 4);
        long jitter = ThreadLocalRandom.current().nextLong(jitterBound);
        return Duration.ofMillis(Math.min(MAX_MILLIS, exponential + jitter));
    }
}
