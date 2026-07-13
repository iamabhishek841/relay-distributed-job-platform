package dev.relay.controller;

import dev.relay.domain.AttemptView;
import dev.relay.domain.JobRecord;
import dev.relay.dto.SubmitJobRequest;
import dev.relay.dto.SubmitJobResponse;
import dev.relay.service.JobService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/jobs")
public class JobController {
    private final JobService jobs;

    public JobController(JobService jobs) {
        this.jobs = jobs;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public SubmitJobResponse submit(@Valid @RequestBody SubmitJobRequest request) {
        return jobs.submit(request);
    }

    @GetMapping
    public List<JobRecord> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "50") int limit
    ) {
        return jobs.list(status, limit);
    }

    @GetMapping("/{id}")
    public JobRecord get(@PathVariable UUID id) {
        return jobs.get(id);
    }

    @GetMapping("/{id}/attempts")
    public List<AttemptView> attempts(@PathVariable UUID id) {
        return jobs.attempts(id);
    }

    @PostMapping("/{id}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable UUID id) {
        jobs.cancel(id);
    }

    @PostMapping("/{id}/replay")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void replay(@PathVariable UUID id) {
        jobs.replay(id);
    }
}
