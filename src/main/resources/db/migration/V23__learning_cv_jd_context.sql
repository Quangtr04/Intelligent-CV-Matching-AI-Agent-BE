-- Private learning inputs never become public market job posts.
ALTER TABLE assessment_sessions
    ADD COLUMN resume_id uuid REFERENCES resumes(id),
    ADD COLUMN input_hash varchar(64),
    ADD COLUMN context_snapshot jsonb;

ALTER TABLE assessment_questions ADD COLUMN cv_evidence text;

ALTER TABLE assessment_generations
    ALTER COLUMN job_id DROP NOT NULL,
    ADD COLUMN resume_id uuid REFERENCES resumes(id),
    ADD COLUMN input_hash varchar(64),
    ADD COLUMN context_snapshot jsonb;

DROP INDEX uq_assessment_generations_active_owner_job;
CREATE UNIQUE INDEX uq_assessment_generations_active_owner_context
    ON assessment_generations(user_id,input_hash)
    WHERE status IN ('QUEUED','RUNNING') AND input_hash IS NOT NULL;
CREATE UNIQUE INDEX uq_assessment_generations_legacy_active_owner_job
    ON assessment_generations(user_id,job_id)
    WHERE status IN ('QUEUED','RUNNING') AND input_hash IS NULL;

ALTER TABLE gap_analyses
    ADD COLUMN context_key varchar(100),
    ADD COLUMN input_hash varchar(64),
    ADD COLUMN context_snapshot jsonb;
UPDATE gap_analyses SET context_key='job:' || market_job_id::text WHERE market_job_id IS NOT NULL;
CREATE INDEX ix_gap_analyses_owner_context ON gap_analyses(user_id,context_key,version DESC);
CREATE INDEX ix_assessment_sessions_owner_resume_job
    ON assessment_sessions(user_id,resume_id,market_job_id,submitted_at DESC)
    WHERE status='completed';
