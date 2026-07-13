package dev.relay.repository;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import dev.relay.domain.AttemptView;
import dev.relay.domain.JobRecord;
import dev.relay.domain.JobStatus;
import dev.relay.dto.SubmitJobRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Repository
public class JobRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JobRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    private final RowMapper<JobRecord> jobMapper = (rs, rowNum) -> mapJob(rs);

    public JobRecord insertJob(UUID id, SubmitJobRequest request, JobStatus initialStatus, UUID workflowId) {
        String json = toJson(request.payload());
        Instant scheduledAt = request.scheduledAt() == null ? Instant.now() : request.scheduledAt();
        int priority = request.priority() == null ? 0 : request.priority();
        int maxAttempts = request.maxAttempts() == null ? 5 : request.maxAttempts();

        try {
            return jdbc.queryForObject("""
                    INSERT INTO jobs (
                        id, tenant_id, job_type, payload, status, priority, max_attempts,
                        scheduled_at, idempotency_key, workflow_id
                    ) VALUES (?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?)
                    RETURNING *
                    """, jobMapper,
                    id,
                    request.tenantId(),
                    request.jobType(),
                    json,
                    initialStatus.name(),
                    priority,
                    maxAttempts,
                    Timestamp.from(scheduledAt),
                    blankToNull(request.idempotencyKey()),
                    workflowId
            );
        } catch (DuplicateKeyException duplicate) {
            if (request.idempotencyKey() == null || request.idempotencyKey().isBlank()) {
                throw duplicate;
            }
            return findByIdempotencyKey(request.tenantId(), request.idempotencyKey())
                    .orElseThrow(() -> duplicate);
        }
    }

    public List<UUID> insertBurst(int count, int durationMs, String tenantId) {
        List<UUID> ids = new ArrayList<>(count);
        List<Object[]> args = new ArrayList<>(count);
        String payload = "{\"durationMs\":" + durationMs + ",\"failureMode\":\"NONE\"}";

        for (int i = 0; i < count; i++) {
            UUID id = UUID.randomUUID();
            ids.add(id);
            args.add(new Object[]{
                    id, tenantId, "SIMULATED", payload, JobStatus.QUEUED.name(),
                    0, 5, Timestamp.from(Instant.now())
            });
        }

        jdbc.batchUpdate("""
                INSERT INTO jobs (
                    id, tenant_id, job_type, payload, status, priority, max_attempts, scheduled_at
                ) VALUES (?, ?, ?, ?::jsonb, ?, ?, ?, ?)
                """, args);
        return ids;
    }

    public Optional<JobRecord> findById(UUID id) {
        return jdbc.query("SELECT * FROM jobs WHERE id = ?", jobMapper, id).stream().findFirst();
    }

    public Optional<JobRecord> findByIdempotencyKey(String tenantId, String key) {
        return jdbc.query("""
                SELECT * FROM jobs
                WHERE tenant_id = ? AND idempotency_key = ?
                """, jobMapper, tenantId, key).stream().findFirst();
    }

    public List<JobRecord> listJobs(String status, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        if (status == null || status.isBlank()) {
            return jdbc.query("""
                    SELECT * FROM jobs
                    ORDER BY created_at DESC
                    LIMIT ?
                    """, jobMapper, safeLimit);
        }
        return jdbc.query("""
                SELECT * FROM jobs
                WHERE status = ?
                ORDER BY created_at DESC
                LIMIT ?
                """, jobMapper, status, safeLimit);
    }

    @Transactional
    public Optional<JobRecord> claimNext(String workerId, Duration leaseDuration) {
        return jdbc.query("""
                WITH candidate AS (
                    SELECT id
                    FROM jobs
                    WHERE status IN ('QUEUED', 'RETRY_WAIT')
                      AND scheduled_at <= NOW()
                      AND cancel_requested = FALSE
                    ORDER BY priority DESC, scheduled_at ASC, created_at ASC
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1
                )
                UPDATE jobs j
                SET status = 'RUNNING',
                    lease_owner = ?,
                    lease_expires_at = NOW() + (? * INTERVAL '1 millisecond'),
                    attempt_count = attempt_count + 1,
                    updated_at = NOW()
                FROM candidate c
                WHERE j.id = c.id
                RETURNING j.*
                """, jobMapper, workerId, leaseDuration.toMillis()).stream().findFirst();
    }

    public long startAttempt(JobRecord job, String workerId) {
        Long id = jdbc.queryForObject("""
                INSERT INTO job_attempts (
                    job_id, attempt_number, worker_id, status
                ) VALUES (?, ?, ?, 'RUNNING')
                RETURNING id
                """, Long.class, job.id(), job.attemptCount(), workerId);
        return Objects.requireNonNull(id);
    }

    public record CompletionResult(boolean applied, List<UUID> unblocked) {}

    @Transactional
    public CompletionResult completeSuccess(JobRecord job, long attemptId, long durationMs, String workerId) {
        jdbc.update("""
                UPDATE job_attempts
                SET status = 'SUCCEEDED',
                    finished_at = NOW(),
                    duration_ms = ?
                WHERE id = ?
                """, durationMs, attemptId);

        int applied = jdbc.update("""
                UPDATE jobs
                SET status = 'SUCCEEDED',
                    lease_owner = NULL,
                    lease_expires_at = NULL,
                    last_error_code = NULL,
                    last_error_message = NULL,
                    completed_at = NOW(),
                    updated_at = NOW()
                WHERE id = ?
                  AND status = 'RUNNING'
                  AND lease_owner = ?
                  AND lease_expires_at >= NOW()
                """, job.id(), workerId);

        if (applied == 0) {
            jdbc.update("""
                    UPDATE job_attempts
                    SET status = 'STALE',
                        error_code = 'LEASE_OWNERSHIP_LOST',
                        error_message = 'Completion rejected because the worker no longer owns the active lease'
                    WHERE id = ?
                    """, attemptId);
            return new CompletionResult(false, List.of());
        }

        List<UUID> unblocked = jdbc.query("""
                UPDATE jobs child
                SET status = 'QUEUED',
                    scheduled_at = NOW(),
                    updated_at = NOW()
                WHERE child.status = 'BLOCKED'
                  AND child.id IN (
                      SELECT wd.job_id
                      FROM workflow_dependencies wd
                      WHERE wd.depends_on_job_id = ?
                  )
                  AND NOT EXISTS (
                      SELECT 1
                      FROM workflow_dependencies dep
                      JOIN jobs parent ON parent.id = dep.depends_on_job_id
                      WHERE dep.job_id = child.id
                        AND parent.status <> 'SUCCEEDED'
                  )
                RETURNING child.id
                """, (rs, rowNum) -> rs.getObject("id", UUID.class), job.id());

        if (job.workflowId() != null) {
            jdbc.update("""
                    UPDATE workflows
                    SET status = CASE
                        WHEN NOT EXISTS (
                            SELECT 1 FROM jobs
                            WHERE workflow_id = workflows.id
                              AND status <> 'SUCCEEDED'
                        ) THEN 'SUCCEEDED'
                        ELSE status
                    END,
                    updated_at = NOW()
                    WHERE id = ?
                    """, job.workflowId());
        }

        return new CompletionResult(true, unblocked);
    }

    @Transactional
    public boolean completeTransientFailure(
            JobRecord job,
            long attemptId,
            long durationMs,
            String code,
            String message,
            Instant nextAttemptAt,
            String workerId
    ) {
        jdbc.update("""
                UPDATE job_attempts
                SET status = 'RETRY_WAIT',
                    finished_at = NOW(),
                    duration_ms = ?,
                    error_code = ?,
                    error_message = ?
                WHERE id = ?
                """, durationMs, code, message, attemptId);

        int applied = jdbc.update("""
                UPDATE jobs
                SET status = 'RETRY_WAIT',
                    scheduled_at = ?,
                    lease_owner = NULL,
                    lease_expires_at = NULL,
                    last_error_code = ?,
                    last_error_message = ?,
                    updated_at = NOW()
                WHERE id = ?
                  AND status = 'RUNNING'
                  AND lease_owner = ?
                  AND lease_expires_at >= NOW()
                """, Timestamp.from(nextAttemptAt), code, message, job.id(), workerId);

        if (applied == 0) {
            jdbc.update("""
                    UPDATE job_attempts
                    SET status = 'STALE',
                        error_code = 'LEASE_OWNERSHIP_LOST',
                        error_message = 'Failure transition rejected because the worker no longer owns the active lease'
                    WHERE id = ?
                    """, attemptId);
        }
        return applied == 1;
    }

    @Transactional
    public boolean moveToDeadLetter(JobRecord job, long attemptId, long durationMs, String code, String message, String workerId) {
        jdbc.update("""
                UPDATE job_attempts
                SET status = 'DEAD_LETTER',
                    finished_at = NOW(),
                    duration_ms = ?,
                    error_code = ?,
                    error_message = ?
                WHERE id = ?
                """, durationMs, code, message, attemptId);

        int applied = jdbc.update("""
                UPDATE jobs
                SET status = 'DEAD_LETTER',
                    lease_owner = NULL,
                    lease_expires_at = NULL,
                    last_error_code = ?,
                    last_error_message = ?,
                    completed_at = NOW(),
                    updated_at = NOW()
                WHERE id = ?
                  AND status = 'RUNNING'
                  AND lease_owner = ?
                  AND lease_expires_at >= NOW()
                """, code, message, job.id(), workerId);

        if (applied == 0) {
            jdbc.update("""
                    UPDATE job_attempts
                    SET status = 'STALE',
                        error_code = 'LEASE_OWNERSHIP_LOST',
                        error_message = 'Dead-letter transition rejected because the worker no longer owns the active lease'
                    WHERE id = ?
                    """, attemptId);
            return false;
        }

        if (job.workflowId() != null) {
            jdbc.update("""
                    UPDATE jobs
                    SET last_error_code = 'DEPENDENCY_FAILED',
                        last_error_message = 'A workflow dependency entered DEAD_LETTER',
                        updated_at = NOW()
                    WHERE workflow_id = ?
                      AND status = 'BLOCKED'
                    """, job.workflowId());
            jdbc.update("""
                    UPDATE workflows
                    SET status = 'FAILED', updated_at = NOW()
                    WHERE id = ?
                    """, job.workflowId());
        }
        return true;
    }

    public void extendLeases(String workerId, Duration leaseDuration) {
        jdbc.update("""
                UPDATE jobs
                SET lease_expires_at = NOW() + (? * INTERVAL '1 millisecond'),
                    updated_at = NOW()
                WHERE status = 'RUNNING'
                  AND lease_owner = ?
                  AND lease_expires_at >= NOW()
                """, leaseDuration.toMillis(), workerId);
    }

    @Transactional
    public List<UUID> recoverExpiredLeases() {
        List<UUID> expired = jdbc.query("""
                SELECT id
                FROM jobs
                WHERE status = 'RUNNING'
                  AND lease_expires_at < NOW()
                ORDER BY lease_expires_at
                FOR UPDATE SKIP LOCKED
                LIMIT 200
                """, (rs, rowNum) -> rs.getObject("id", UUID.class));

        List<UUID> recovered = new ArrayList<>();
        for (UUID jobId : expired) {
            int changed = jdbc.update("""
                    UPDATE jobs
                    SET status = 'QUEUED',
                        scheduled_at = NOW(),
                        lease_owner = NULL,
                        lease_expires_at = NULL,
                        last_error_code = 'LEASE_EXPIRED',
                        last_error_message = 'Worker lease expired before completion',
                        updated_at = NOW()
                    WHERE id = ?
                      AND status = 'RUNNING'
                      AND lease_expires_at < NOW()
                    """, jobId);

            if (changed == 1) {
                jdbc.update("""
                        UPDATE job_attempts
                        SET status = 'ABANDONED',
                            finished_at = NOW(),
                            duration_ms = GREATEST(0, EXTRACT(EPOCH FROM (NOW() - started_at)) * 1000)::BIGINT,
                            error_code = 'LEASE_EXPIRED',
                            error_message = 'Worker heartbeat stopped and lease expired'
                        WHERE job_id = ?
                          AND status = 'RUNNING'
                        """, jobId);
                recovered.add(jobId);
            }
        }
        return recovered;
    }

    public boolean recordSideEffect(String effectKey, UUID jobId) {
        int inserted = jdbc.update("""
                INSERT INTO side_effects(effect_key, job_id)
                VALUES (?, ?)
                ON CONFLICT (effect_key) DO NOTHING
                """, effectKey, jobId);
        return inserted == 1;
    }

    public List<AttemptView> listAttempts(UUID jobId) {
        return jdbc.query("""
                SELECT *
                FROM job_attempts
                WHERE job_id = ?
                ORDER BY attempt_number
                """, (rs, rowNum) -> new AttemptView(
                rs.getLong("id"),
                rs.getObject("job_id", UUID.class),
                rs.getInt("attempt_number"),
                rs.getString("worker_id"),
                rs.getString("status"),
                instant(rs, "started_at"),
                instant(rs, "finished_at"),
                (Long) rs.getObject("duration_ms"),
                rs.getString("error_code"),
                rs.getString("error_message")
        ), jobId);
    }

    public boolean cancel(UUID jobId) {
        return jdbc.update("""
                UPDATE jobs
                SET status = 'CANCELLED',
                    updated_at = NOW(),
                    completed_at = NOW()
                WHERE id = ?
                  AND status IN ('QUEUED', 'RETRY_WAIT', 'BLOCKED')
                """, jobId) == 1;
    }

    public boolean replay(UUID jobId) {
        return jdbc.update("""
                UPDATE jobs
                SET status = 'QUEUED',
                    attempt_count = 0,
                    scheduled_at = NOW(),
                    lease_owner = NULL,
                    lease_expires_at = NULL,
                    completed_at = NULL,
                    last_error_code = NULL,
                    last_error_message = NULL,
                    updated_at = NOW()
                WHERE id = ?
                  AND status IN ('DEAD_LETTER', 'CANCELLED')
                """, jobId) == 1;
    }

    public Map<String, Long> countsByStatus() {
        Map<String, Long> counts = new LinkedHashMap<>();
        jdbc.query("""
                SELECT status, COUNT(*) AS count
                FROM jobs
                GROUP BY status
                """, rs -> counts.put(rs.getString("status"), rs.getLong("count")));
        for (JobStatus status : JobStatus.values()) {
            counts.putIfAbsent(status.name(), 0L);
        }
        return counts;
    }

    public long retriesLastHour() {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM job_attempts
                WHERE attempt_number > 1
                  AND started_at >= NOW() - INTERVAL '1 hour'
                """, Long.class);
        return count == null ? 0 : count;
    }

    public double throughputPerSecond() {
        Double value = jdbc.queryForObject("""
                SELECT COUNT(*) / 60.0
                FROM jobs
                WHERE status = 'SUCCEEDED'
                  AND completed_at >= NOW() - INTERVAL '60 seconds'
                """, Double.class);
        return value == null ? 0.0 : value;
    }

    public long percentileExecution(double percentile) {
        Long value = jdbc.queryForObject("""
                SELECT COALESCE(
                    PERCENTILE_CONT(?) WITHIN GROUP (ORDER BY duration_ms),
                    0
                )::BIGINT
                FROM job_attempts
                WHERE status = 'SUCCEEDED'
                  AND duration_ms IS NOT NULL
                  AND started_at >= NOW() - INTERVAL '1 hour'
                """, Long.class, percentile);
        return value == null ? 0 : value;
    }

    public long sideEffectCount() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM side_effects", Long.class);
        return count == null ? 0 : count;
    }

    @Transactional
    public void resetAll() {
        jdbc.execute("""
                TRUNCATE TABLE
                    workflow_dependencies,
                    job_attempts,
                    side_effects,
                    jobs,
                    workflows,
                    system_events
                RESTART IDENTITY CASCADE
                """);
    }

    public UUID insertWorkflow(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO workflows(id, name, status)
                VALUES (?, ?, 'RUNNING')
                """, id, name);
        return id;
    }

    public void insertDependency(UUID jobId, UUID dependsOnJobId) {
        jdbc.update("""
                INSERT INTO workflow_dependencies(job_id, depends_on_job_id)
                VALUES (?, ?)
                """, jobId, dependsOnJobId);
    }

    private JobRecord mapJob(ResultSet rs) throws SQLException {
        try {
            return new JobRecord(
                    rs.getObject("id", UUID.class),
                    rs.getString("tenant_id"),
                    rs.getString("job_type"),
                    objectMapper.readTree(rs.getString("payload")),
                    JobStatus.valueOf(rs.getString("status")),
                    rs.getInt("priority"),
                    rs.getInt("attempt_count"),
                    rs.getInt("max_attempts"),
                    instant(rs, "scheduled_at"),
                    rs.getString("lease_owner"),
                    instant(rs, "lease_expires_at"),
                    rs.getString("idempotency_key"),
                    rs.getObject("workflow_id", UUID.class),
                    rs.getBoolean("cancel_requested"),
                    rs.getString("last_error_code"),
                    rs.getString("last_error_message"),
                    instant(rs, "created_at"),
                    instant(rs, "updated_at"),
                    instant(rs, "completed_at")
            );
        } catch (JacksonException e) {
            throw new SQLException("Invalid JSON payload in jobs table", e);
        }
    }

    private String toJson(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node == null ? objectMapper.createObjectNode() : node);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Payload cannot be serialized", e);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
