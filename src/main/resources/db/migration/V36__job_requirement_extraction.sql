-- Derived snapshot; legacy skills and original source remain intact.
ALTER TABLE market_job_posts
    ADD COLUMN requirement_extraction jsonb,
    ADD COLUMN requirement_extraction_verified boolean NOT NULL DEFAULT false;
COMMENT ON COLUMN market_job_posts.requirement_extraction_verified IS
    'Manual review lock: deterministic reprocessing must not replace this snapshot when true';
