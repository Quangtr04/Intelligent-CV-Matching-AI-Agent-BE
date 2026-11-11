-- Only administrators can change a manager's delegated quota ceilings.
CREATE TABLE manager_quota_limits (
    manager_user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    max_token_quota BIGINT NOT NULL CHECK (max_token_quota >= 0),
    max_api_quota BIGINT NOT NULL CHECK (max_api_quota >= 0),
    updated_by UUID NOT NULL REFERENCES users(id),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
