-- Keep CV/profile comparisons separate from standalone CV quality analyses.
-- Results and both input snapshots are append-only through the application API.
CREATE TABLE resume_profile_alignments (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    resume_id uuid NOT NULL REFERENCES resumes(id),
    user_id uuid NOT NULL REFERENCES users(id),
    resume_content_hash varchar(64) NOT NULL CHECK (resume_content_hash ~ '^[0-9a-f]{64}$'),
    profile_hash varchar(64) NOT NULL CHECK (profile_hash ~ '^[0-9a-f]{64}$'),
    profile_snapshot jsonb NOT NULL CHECK (jsonb_typeof(profile_snapshot) = 'object'),
    resume_snapshot jsonb NOT NULL CHECK (jsonb_typeof(resume_snapshot) = 'object'),
    result_json jsonb NOT NULL CHECK (jsonb_typeof(result_json) = 'object'),
    model_name varchar(255) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX ix_resume_profile_alignments_owner_resume_created
    ON resume_profile_alignments(user_id,resume_id,created_at DESC,id DESC);

-- Preserve each tier's administrator-configured quota value and period.
INSERT INTO subscription_quotas(tier_id,feature_code,quota_value,quota_period)
SELECT tier_id,'CV_PROFILE_ALIGNMENT',quota_value,quota_period
FROM subscription_quotas
WHERE feature_code='CV_ANALYZE'
ON CONFLICT(tier_id,feature_code) DO NOTHING;
