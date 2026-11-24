ALTER TABLE market_job_posts
    ADD COLUMN skill_proficiency_updated_at timestamptz,
    ADD COLUMN skill_proficiency_error varchar(64);
