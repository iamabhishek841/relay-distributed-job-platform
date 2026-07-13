package dev.relay.controller;

import dev.relay.dto.SystemSnapshot;
import dev.relay.service.MetricsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/system")
public class SystemController {
    private final MetricsService metrics;

    public SystemController(MetricsService metrics) {
        this.metrics = metrics;
    }

    @GetMapping("/snapshot")
    public SystemSnapshot snapshot() {
        return metrics.snapshot();
    }
}
