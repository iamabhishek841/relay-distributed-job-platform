package dev.relay.controller;

import dev.relay.dto.BurstRequest;
import dev.relay.dto.DuplicateRequest;
import dev.relay.dto.FailureInjectionRequest;
import dev.relay.service.ControlService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/control")
public class ControlController {
    private final ControlService control;

    public ControlController(ControlService control) {
        this.control = control;
    }

    @PostMapping("/burst")
    public Map<String, Object> burst(@Valid @RequestBody(required = false) BurstRequest request) {
        return control.burst(request == null ? new BurstRequest(null, null, null) : request);
    }

    @PostMapping("/inject-503")
    public Map<String, Object> inject503(@Valid @RequestBody(required = false) FailureInjectionRequest request) {
        return control.inject503(request == null ? new FailureInjectionRequest(null, null, null) : request);
    }

    @PostMapping("/duplicate")
    public Map<String, Object> duplicate(@Valid @RequestBody(required = false) DuplicateRequest request) {
        return control.duplicate(request == null ? new DuplicateRequest(null) : request);
    }

    @PostMapping("/crash-window")
    public Map<String, Object> crashWindow() {
        return control.crashAfterSideEffect();
    }

    @PostMapping("/kill-worker")
    public Map<String, Object> killWorker(@RequestParam(required = false) String workerId) {
        return control.killWorker(workerId);
    }

    @PostMapping("/start-worker")
    public Map<String, Object> startWorker() {
        return control.startReplacementWorker();
    }

    @DeleteMapping("/reset")
    public Map<String, Object> reset() {
        return control.reset();
    }
}
