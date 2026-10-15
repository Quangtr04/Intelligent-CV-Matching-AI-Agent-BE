-- Preserve V1-V4 checksums; ingestion automation is an additive migration.
ALTER TABLE market_job_posts
    ADD COLUMN employment_type varchar(100),
    ADD COLUMN valid_through timestamptz,
    ADD COLUMN content_fingerprint varchar(64);

CREATE INDEX ix_market_jobs_fingerprint ON market_job_posts(content_fingerprint)
    WHERE status = 'ACTIVE' AND content_fingerprint IS NOT NULL;
CREATE INDEX ix_market_jobs_expiry ON market_job_posts(valid_through)
    WHERE status = 'ACTIVE' AND valid_through IS NOT NULL;

-- One canonical job may be advertised at several source URLs.
CREATE TABLE market_job_sources (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    market_job_id uuid NOT NULL REFERENCES market_job_posts(id),
    source_site varchar(50) NOT NULL,
    source_url text NOT NULL UNIQUE,
    content_hash varchar(64) NOT NULL,
    valid_through timestamptz,
    status varchar(32) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'EXPIRED', 'REMOVED')),
    last_seen_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_market_job_sources_job ON market_job_sources(market_job_id, status);
CREATE INDEX ix_market_job_sources_expiry ON market_job_sources(valid_through)
    WHERE status = 'ACTIVE' AND valid_through IS NOT NULL;

INSERT INTO market_job_sources(market_job_id,source_site,source_url,content_hash,status,last_seen_at)
SELECT DISTINCT ON (source_url) id,upper(source_site),source_url,raw_content_hash,
       CASE WHEN status='ACTIVE' THEN 'ACTIVE' WHEN status='EXPIRED' THEN 'EXPIRED' ELSE 'REMOVED' END,
       collected_at
FROM market_job_posts WHERE source_url IS NOT NULL
ORDER BY source_url,(status='ACTIVE') DESC,collected_at DESC,id;

CREATE TABLE job_collection_leases (
    name varchar(100) PRIMARY KEY,
    lease_token uuid,
    lease_until timestamptz,
    last_started_at timestamptz,
    last_completed_at timestamptz,
    last_error text
);

ALTER TABLE market_crawl_runs ADD COLUMN requested_by uuid REFERENCES users(id);

ALTER TABLE vector_indexing_jobs
    DROP CONSTRAINT vector_indexing_jobs_status_check,
    ADD CONSTRAINT vector_indexing_jobs_status_check
        CHECK (status IN ('PENDING','QUEUED','PROCESSING','COMPLETED','FAILED','CANCELLED')),
    ADD COLUMN content_hash varchar(64),
    ADD COLUMN requested_by uuid REFERENCES users(id),
    ADD COLUMN next_attempt_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN lease_token uuid,
    ADD COLUMN lease_until timestamptz;

CREATE UNIQUE INDEX ux_job_embedding_work
    ON vector_indexing_jobs(entity_type,entity_id,operation,provider,model,content_hash)
    WHERE entity_type='JOB' AND operation='INDEX' AND content_hash IS NOT NULL;
CREATE INDEX ix_job_embedding_ready ON vector_indexing_jobs(next_attempt_at,created_at)
    WHERE entity_type='JOB' AND status IN ('PENDING','QUEUED');
CREATE INDEX ix_job_embedding_leases ON vector_indexing_jobs(lease_until)
    WHERE entity_type='JOB' AND status='PROCESSING';
