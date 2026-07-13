package dev.relay.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "relay")
public record RelayProperties(
        int workerCount,
        int leaseSeconds,
        int heartbeatSeconds,
        long claimPollMillis,
        double eventSampleRate,
        String allowedOrigins,
        int maxBurstSize,
        boolean workersEnabled,
        boolean recoveryEnabled
) {
}
