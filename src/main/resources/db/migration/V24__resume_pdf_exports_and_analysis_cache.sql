-- source is an origin enum (UPLOAD / MANUAL / AI_BUILDER); file_url stores the exported path.
ALTER TABLE resumes ADD COLUMN original_file_url text;
ALTER TABLE resumes ADD COLUMN pdf_input_hash varchar(64);
UPDATE resumes SET original_file_url = file_url WHERE source = 'UPLOAD';

-- A Redis miss can reuse a persisted assessment of exactly the same inputs.
ALTER TABLE resume_analyses ADD COLUMN input_hash varchar(64);
CREATE INDEX ix_resume_analyses_input_hash
    ON resume_analyses (resume_id, analysis_type, input_hash, created_at DESC)
    WHERE input_hash IS NOT NULL;
