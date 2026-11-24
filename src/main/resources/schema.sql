CREATE TABLE IF NOT EXISTS jobs (
    id UUID PRIMARY KEY,
    kind VARCHAR(32) NOT NULL CHECK (kind IN ('WORD_COUNT','SHA256','DIGIT_CLASSIFY')),
    payload JSONB NOT NULL,
    state VARCHAR(16) NOT NULL CHECK (state IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','CANCELLED')),
    priority INT NOT NULL CHECK (priority BETWEEN 0 AND 9),
    attempts INT NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    max_attempts INT NOT NULL CHECK (max_attempts BETWEEN 1 AND 5),
    idempotency_key VARCHAR(100) UNIQUE,
    request_hash VARCHAR(64) NOT NULL,
    available_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    worker_id TEXT,
    result JSONB,
    error TEXT,
    CONSTRAINT valid_lease CHECK ((state = 'RUNNING') = (lease_token IS NOT NULL AND lease_until IS NOT NULL)),
    CONSTRAINT attempt_limit CHECK (attempts <= max_attempts)
);
CREATE INDEX IF NOT EXISTS jobs_queue_idx ON jobs(priority DESC, available_at, created_at) WHERE state = 'QUEUED';
CREATE INDEX IF NOT EXISTS jobs_lease_idx ON jobs(lease_until) WHERE state = 'RUNNING';
CREATE INDEX IF NOT EXISTS jobs_recent_idx ON jobs(created_at DESC);
