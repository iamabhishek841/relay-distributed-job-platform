package dev.relay.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BackoffPolicyTest {

    private final BackoffPolicy policy = new BackoffPolicy();

    @Test
    void delayGrowsAndRemainsCapped() {
        Duration first = policy.nextDelay(1);
        Duration fourth = policy.nextDelay(4);
        Duration veryLate = policy.nextDelay(30);

        assertTrue(first.toMillis() >= 500);
        assertTrue(fourth.toMillis() >= 4_000);
        assertTrue(veryLate.toMillis() <= 30_000);
    }
}
