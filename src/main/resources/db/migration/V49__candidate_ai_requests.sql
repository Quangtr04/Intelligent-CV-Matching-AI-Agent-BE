CREATE TABLE candidate_ai_requests (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    kind text NOT NULL CHECK (kind IN ('ROADMAP', 'AI')),
    status text NOT NULL DEFAULT 'QUEUED' CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    input jsonb NOT NULL DEFAULT '{}',
    result_id uuid,
    message text,
    requested_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    lease_until timestamptz
);

CREATE UNIQUE INDEX candidate_ai_requests_active_owner
    ON candidate_ai_requests(user_id) WHERE status IN ('QUEUED', 'RUNNING');

CREATE INDEX candidate_ai_requests_history ON candidate_ai_requests(user_id, requested_at DESC);
CREATE INDEX candidate_ai_requests_queue ON candidate_ai_requests(requested_at) WHERE status = 'QUEUED';
