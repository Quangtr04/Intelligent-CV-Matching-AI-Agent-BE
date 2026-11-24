ALTER TABLE market_job_posts
    ADD COLUMN skill_proficiency_analysis jsonb,
    ADD COLUMN skill_proficiency_input_key text,
    ADD COLUMN skill_proficiency_lease uuid,
    ADD COLUMN skill_proficiency_retry_at timestamptz,
    ADD COLUMN skill_proficiency_attempts integer NOT NULL DEFAULT 0,
    ADD COLUMN skill_proficiency_status varchar(20)
        CHECK (skill_proficiency_status IN ('PROCESSING', 'COMPLETED', 'RETRY', 'FAILED'));

CREATE INDEX ix_market_job_skill_proficiency_queue
    ON market_job_posts (skill_proficiency_retry_at NULLS FIRST, id)
    WHERE status = 'ACTIVE';
