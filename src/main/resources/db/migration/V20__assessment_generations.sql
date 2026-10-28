-- Generation is independent of the browser's HTTP request. A completed assessment is published atomically.
CREATE TABLE assessment_generations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users(id),
    job_id uuid NOT NULL REFERENCES market_job_posts(id),
    target_job_title varchar(255) NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'QUEUED'
        CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED')),
    assessment_id uuid REFERENCES assessment_sessions(id),
    lease_token uuid,
    lease_until timestamptz,
    error_code varchar(40),
    message varchar(255),
    requested_at timestamptz NOT NULL DEFAULT now(),
    started_at timestamptz,
    completed_at timestamptz,
    CHECK ((status = 'RUNNING') = (lease_token IS NOT NULL AND lease_until IS NOT NULL)),
    CHECK (status <> 'SUCCEEDED' OR assessment_id IS NOT NULL)
);

CREATE UNIQUE INDEX uq_assessment_generations_active_owner_job
    ON assessment_generations(user_id,job_id) WHERE status IN ('QUEUED','RUNNING');
CREATE INDEX ix_assessment_generations_queue
    ON assessment_generations(status,requested_at,id) WHERE status IN ('QUEUED','RUNNING');
CREATE INDEX ix_assessment_generations_owner_history
    ON assessment_generations(user_id,requested_at DESC);
