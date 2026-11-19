-- Preserve old vectors while permitting a new normalized input hash in content_hash.
-- Provider/model/dimension retain their existing identities and vector(1024) schema.
ALTER TABLE market_job_embeddings ADD COLUMN source_content_hash varchar(64);
ALTER TABLE market_job_embeddings ADD COLUMN input_version varchar(40) NOT NULL DEFAULT 'legacy-full-v1';
UPDATE market_job_embeddings SET source_content_hash=content_hash WHERE source_content_hash IS NULL;

CREATE INDEX ix_market_job_embedding_compatibility ON market_job_embeddings(provider,model,source_content_hash)
WHERE is_current AND embedding IS NOT NULL;
