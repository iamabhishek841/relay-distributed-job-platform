package dev.relay.service;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import dev.relay.domain.AttemptView;
import dev.relay.domain.JobRecord;
import dev.relay.domain.JobStatus;
import dev.relay.dto.SubmitJobRequest;
import dev.relay.dto.SubmitJobResponse;
import dev.relay.dto.WorkflowSubmitRequest;
import dev.relay.dto.WorkflowSubmitResponse;
import dev.relay.repository.JobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Service
public class JobService {
    private final JobRepository jobRepository;
    private final WorkflowValidator workflowValidator;
    private final EventStreamService events;
    private final ObjectMapper objectMapper;
    private final RelayMetrics metrics;

    public JobService(
            JobRepository jobRepository,
            WorkflowValidator workflowValidator,
            EventStreamService events,
            ObjectMapper objectMapper,
            RelayMetrics metrics
    ) {
        this.jobRepository = jobRepository;
        this.workflowValidator = workflowValidator;
        this.events = events;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    public SubmitJobResponse submit(SubmitJobRequest request) {
        UUID proposedId = UUID.randomUUID();
        JobRecord job = jobRepository.insertJob(proposedId, request, JobStatus.QUEUED, null);
        boolean duplicate = !job.id().equals(proposedId);

        if (!duplicate) {
            metrics.submitted();
            events.publishSampled("JOB_SUBMITTED", "JOB", job.id().toString(), Map.of(
                    "tenantId", job.tenantId(),
                    "jobType", job.jobType()
            ));
        } else {
            metrics.duplicateSuppressed();
            events.publish("DUPLICATE_SUPPRESSED", "JOB", job.id().toString(), Map.of(
                    "tenantId", job.tenantId(),
                    "idempotencyKey", request.idempotencyKey()
            ), true);
        }

        return new SubmitJobResponse(job.id(), job.status(), duplicate);
    }

    public JobRecord get(UUID id) {
        return jobRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Job not found: " + id));
    }

    public List<JobRecord> list(String status, int limit) {
        return jobRepository.listJobs(status, limit);
    }

    public List<AttemptView> attempts(UUID jobId) {
        get(jobId);
        return jobRepository.listAttempts(jobId);
    }

    public void cancel(UUID id) {
        if (!jobRepository.cancel(id)) {
            throw new IllegalStateException("Only queued, retry-wait, or blocked jobs can be cancelled");
        }
        events.publish("JOB_CANCELLED", "JOB", id.toString(), Map.of(), true);
    }

    public void replay(UUID id) {
        if (!jobRepository.replay(id)) {
            throw new IllegalStateException("Only dead-letter or cancelled jobs can be replayed");
        }
        events.publish("JOB_REPLAYED", "JOB", id.toString(), Map.of(), true);
    }

    @Transactional
    public WorkflowSubmitResponse submitWorkflow(WorkflowSubmitRequest request) {
        Map<String, WorkflowSubmitRequest.WorkflowNode> nodes = new LinkedHashMap<>();
        Map<String, List<String>> dependencies = new LinkedHashMap<>();

        for (WorkflowSubmitRequest.WorkflowNode node : request.nodes()) {
            if (nodes.putIfAbsent(node.key(), node) != null) {
                throw new IllegalArgumentException("Duplicate workflow node key: " + node.key());
            }
            dependencies.put(node.key(), node.dependsOn() == null ? List.of() : node.dependsOn());
        }

        workflowValidator.validate(dependencies);
        UUID workflowId = jobRepository.insertWorkflow(request.name());
        Map<String, UUID> jobIds = new LinkedHashMap<>();

        for (WorkflowSubmitRequest.WorkflowNode node : request.nodes()) {
            ObjectNode payload = objectMapper.createObjectNode();
            if (node.payload() != null) {
                if (!node.payload().isObject()) {
                    throw new IllegalArgumentException("Workflow node payload must be a JSON object: " + node.key());
                }
                payload.setAll((ObjectNode) node.payload());
            }
            if (!payload.has("durationMs")) {
                payload.put("durationMs", 300);
            }
            if (!payload.has("failureMode")) {
                payload.put("failureMode", "NONE");
            }

            SubmitJobRequest jobRequest = new SubmitJobRequest(
                    request.tenantId(),
                    node.jobType(),
                    payload,
                    0,
                    5,
                    Instant.now(),
                    null
            );

            JobStatus initial = dependencies.get(node.key()).isEmpty() ? JobStatus.QUEUED : JobStatus.BLOCKED;
            UUID id = UUID.randomUUID();
            JobRecord job = jobRepository.insertJob(id, jobRequest, initial, workflowId);
            jobIds.put(node.key(), job.id());
        }

        dependencies.forEach((node, parents) -> {
            for (String parent : parents) {
                jobRepository.insertDependency(jobIds.get(node), jobIds.get(parent));
            }
        });
        metrics.submitted(jobIds.size());

        events.publish("WORKFLOW_SUBMITTED", "WORKFLOW", workflowId.toString(), Map.of(
                "name", request.name(),
                "nodes", request.nodes().size()
        ), true);

        return new WorkflowSubmitResponse(workflowId, jobIds);
    }
}
