-- Candidate wishlist of market jobs shown in job discovery.
CREATE TABLE saved_jobs (
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    market_job_id uuid NOT NULL REFERENCES market_job_posts(id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, market_job_id)
);

CREATE INDEX ix_saved_jobs_owner_created
    ON saved_jobs(user_id, created_at DESC);
CREATE INDEX ix_saved_jobs_job
    ON saved_jobs(market_job_id);

