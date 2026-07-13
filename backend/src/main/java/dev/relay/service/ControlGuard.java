package dev.relay.service;

import org.springframework.stereotype.Component;

@Component
public class ControlGuard {
    private static final double CAPACITY = 20.0;
    private static final double REFILL_PER_SECOND = 2.0;

    private double tokens = CAPACITY;
    private long lastRefillNanos = System.nanoTime();

    public synchronized void acquire(double cost, String action) {
        refill();
        if (tokens < cost) {
            double missing = cost - tokens;
            long retryAfterMillis = Math.max(250L, (long) Math.ceil((missing / REFILL_PER_SECOND) * 1_000));
            throw new ControlRateLimitException(
                    "Control-plane rate limit reached for " + action + "; retry in about " + retryAfterMillis + " ms"
            );
        }
        tokens -= cost;
    }

    private void refill() {
        long now = System.nanoTime();
        double elapsedSeconds = (now - lastRefillNanos) / 1_000_000_000.0;
        if (elapsedSeconds > 0) {
            tokens = Math.min(CAPACITY, tokens + elapsedSeconds * REFILL_PER_SECOND);
            lastRefillNanos = now;
        }
    }
}
