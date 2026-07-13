package dev.relay.controller;

import dev.relay.dto.WorkflowSubmitRequest;
import dev.relay.dto.WorkflowSubmitResponse;
import dev.relay.service.JobService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/workflows")
public class WorkflowController {
    private final JobService jobs;

    public WorkflowController(JobService jobs) {
        this.jobs = jobs;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public WorkflowSubmitResponse submit(@Valid @RequestBody WorkflowSubmitRequest request) {
        return jobs.submitWorkflow(request);
    }
}
