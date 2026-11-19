-- Content-addressed caches are private to the candidate. A changed source/model/policy uses a new key.
CREATE TABLE matching_evidence_vectors (
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    provider varchar(100) NOT NULL,
    model varchar(150) NOT NULL,
    content_hash varchar(64) NOT NULL,
    embedding vector(1024) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, provider, model, content_hash)
);

CREATE TABLE matching_evidence_reports (
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    input_hash varchar(64) NOT NULL,
    report jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, input_hash)
);

CREATE INDEX ix_matching_evidence_reports_created ON matching_evidence_reports(created_at);
