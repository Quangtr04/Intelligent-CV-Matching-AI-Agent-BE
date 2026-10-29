-- Password recovery links are single-use tokens, stored only as SHA-256 hashes.
CREATE TABLE reset_password_tokens (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash varchar(255) NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX ix_reset_password_tokens_owner_created
    ON reset_password_tokens(user_id,created_at DESC);
CREATE INDEX ix_reset_password_tokens_owner_active
    ON reset_password_tokens(user_id) WHERE revoked_at IS NULL;
