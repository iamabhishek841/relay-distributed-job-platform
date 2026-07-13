CREATE TABLE IF NOT EXISTS jobs (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    job_type VARCHAR(64) NOT NULL,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    status VARCHAR(32) NOT NULL,
    priority INTEGER NOT NULL DEFAULT 0,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    max_attempts INTEGER NOT NULL DEFAULT 5,
    scheduled_at TIMESTAMPTZ NOT NULL,
    lease_owner VARCHAR(128),
    lease_expires_at TIMESTAMPTZ,
    idempotency_key VARCHAR(160),
    workflow_id UUID,
    cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
    last_error_code VARCHAR(64),
    last_error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMPTZ
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_jobs_tenant_idempotency
    ON jobs (tenant_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE INDEX IF NOT EXISTS ix_jobs_runnable
    ON jobs (status, scheduled_at, priority DESC, created_at)
    WHERE status IN ('QUEUED', 'RETRY_WAIT');

CREATE INDEX IF NOT EXISTS ix_jobs_expired_lease
    ON jobs (lease_expires_at)
    WHERE status = 'RUNNING';

CREATE INDEX IF NOT EXISTS ix_jobs_workflow
    ON jobs (workflow_id);

CREATE TABLE IF NOT EXISTS job_attempts (
    id BIGSERIAL PRIMARY KEY,
    job_id UUID NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    attempt_number INTEGER NOT NULL,
    worker_id VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    finished_at TIMESTAMPTZ,
    duration_ms BIGINT,
    error_code VARCHAR(64),
    error_message TEXT,
    UNIQUE(job_id, attempt_number)
);

CREATE INDEX IF NOT EXISTS ix_attempts_job ON job_attempts(job_id, attempt_number DESC);
CREATE INDEX IF NOT EXISTS ix_attempts_started ON job_attempts(started_at DESC);

CREATE TABLE IF NOT EXISTS workers (
    id VARCHAR(128) PRIMARY KEY,
    status VARCHAR(32) NOT NULL,
    last_heartbeat TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    started_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS side_effects (
    effect_key VARCHAR(200) PRIMARY KEY,
    job_id UUID NOT NULL REFERENCES jobs(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS system_events (
    id BIGSERIAL PRIMARY KEY,
    event_type VARCHAR(64) NOT NULL,
    entity_type VARCHAR(64) NOT NULL,
    entity_id VARCHAR(160),
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS ix_system_events_created ON system_events(created_at DESC);

CREATE TABLE IF NOT EXISTS workflows (
    id UUID PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS workflow_dependencies (
    job_id UUID NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    depends_on_job_id UUID NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    PRIMARY KEY(job_id, depends_on_job_id),
    CHECK(job_id <> depends_on_job_id)
);

CREATE INDEX IF NOT EXISTS ix_workflow_deps_parent ON workflow_dependencies(depends_on_job_id);
