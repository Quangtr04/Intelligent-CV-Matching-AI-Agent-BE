-- qwen3-embedding:0.6b produces 1024-dimensional embeddings. Preserve the
-- previous 1536-dimensional vectors without truncating or converting them:
-- vectors from different models must be generated again from the source text.
DROP INDEX IF EXISTS ix_career_profile_embeddings_cosine;
DROP INDEX IF EXISTS ix_resume_embeddings_cosine;
DROP INDEX IF EXISTS ix_market_job_embeddings_cosine;
DROP INDEX IF EXISTS ix_rag_chunk_embeddings_cosine;

ALTER TABLE career_profile_embeddings RENAME COLUMN embedding TO embedding_legacy_1536;
ALTER TABLE career_profile_embeddings ADD COLUMN embedding vector(1024);
ALTER TABLE resume_embeddings RENAME COLUMN embedding TO embedding_legacy_1536;
ALTER TABLE resume_embeddings ADD COLUMN embedding vector(1024);
ALTER TABLE market_job_embeddings RENAME COLUMN embedding TO embedding_legacy_1536;
ALTER TABLE market_job_embeddings ADD COLUMN embedding vector(1024);
ALTER TABLE rag_chunk_embeddings RENAME COLUMN embedding TO embedding_legacy_1536;
ALTER TABLE rag_chunk_embeddings
    ALTER COLUMN embedding_legacy_1536 DROP NOT NULL,
    ADD COLUMN embedding vector(1024),
    ADD CONSTRAINT rag_chunk_embeddings_vector_present_check
        CHECK (embedding IS NOT NULL OR embedding_legacy_1536 IS NOT NULL);

-- Old rows retain their ID, source/model identity, hash and legacy vector.
-- They cannot participate in the new model's retrieval until reindexed.
UPDATE career_profile_embeddings SET is_current=false WHERE is_current;
UPDATE resume_embeddings SET is_current=false WHERE is_current;
UPDATE market_job_embeddings SET is_current=false WHERE is_current;
UPDATE rag_documents d
SET embedding_status='PENDING'
WHERE d.embedding_status='COMPLETED'
    AND EXISTS (
        SELECT 1 FROM rag_chunks c JOIN rag_chunk_embeddings e ON e.chunk_id=c.id
        WHERE c.document_id=d.id AND e.embedding_legacy_1536 IS NOT NULL
    );

CREATE INDEX ix_career_profile_embeddings_cosine ON career_profile_embeddings
    USING hnsw (embedding vector_cosine_ops) WHERE is_current AND embedding IS NOT NULL;
CREATE INDEX ix_resume_embeddings_cosine ON resume_embeddings
    USING hnsw (embedding vector_cosine_ops) WHERE is_current AND embedding IS NOT NULL;
CREATE INDEX ix_market_job_embeddings_cosine ON market_job_embeddings
    USING hnsw (embedding vector_cosine_ops) WHERE is_current AND embedding IS NOT NULL;
CREATE INDEX ix_rag_chunk_embeddings_cosine ON rag_chunk_embeddings
    USING hnsw (embedding vector_cosine_ops) WHERE embedding IS NOT NULL;
