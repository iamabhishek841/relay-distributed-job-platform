package dev.relay.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ControlGuardTest {

    @Test
    void rejectsActionsAfterBucketIsExhausted() {
        ControlGuard guard = new ControlGuard();

        assertDoesNotThrow(() -> guard.acquire(20, "test"));
        assertThrows(ControlRateLimitException.class, () -> guard.acquire(1, "test"));
    }
}
