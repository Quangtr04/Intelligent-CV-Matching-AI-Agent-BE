package com.example.intelligent_cv_matching_ai_agent.repository.admin.rag;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import java.util.*;
import org.springframework.stereotype.Repository;

/** SQL persistence operations used by RagDocumentService. */
@Repository
public class RagDocumentRepository {

    private final SqlStore db;

    public RagDocumentRepository(
        SqlStore db) {
        this.db = db;
    }

    public Object json(
        Object value) {
        return db.json(value);
    }

    public List<Map<String, Object>> listRagDocuments(
        Object... args) {
        return db.list("SELECT * FROM rag_documents ORDER BY created_at DESC LIMIT 100", args);
    }

    public Map<String, Object> getRagDocumentsById(
        Object... args) {
        return db.one("SELECT * FROM rag_documents WHERE id=?", args);
    }

    public List<Map<String, Object>> listRagChunksByDocumentId(
        Object... args) {
        return db.list(
            ("SELECT c.*,e.provider,e.model,e.created_at AS indexed_at FROM "
                + "rag_chunks c LEFT JOIN rag_chunk_embeddings e ON e.chunk_id=c.id AND e.embedding IS NOT NULL "
                + "WHERE c.document_id=? ORDER BY c.chunk_index,e.provider,e.model"),
            args
        );
    }

    public UUID insertRagDocuments(
        Map<String, Object> fields) {
        return db.insert("rag_documents", fields);
    }

    public UUID insertRagChunks(
        Map<String, Object> fields) {
        return db.insert("rag_chunks", fields);
    }

    public int updateRagDocumentsById(
        Object... args) {
        return db.update(
            ("UPDATE rag_documents SET embedding_status='PROCESSING' WHERE id=? AND "
                + "embedding_status IN ('PENDING','FAILED','COMPLETED') AND "
                + "COALESCE(metadata->>'disabled','false')<>'true'"),
            args
        );
    }

    public List<Map<String, Object>> listRagChunksByDocumentId2(
        Object... args) {
        return db.list("SELECT * FROM rag_chunks WHERE document_id=? ORDER BY chunk_index", args);
    }

    public Optional<Map<String, Object>> findRagChunkEmbeddingsByChunkId(
        Object... args) {
        return db.optional(
            ("SELECT id FROM rag_chunk_embeddings WHERE chunk_id=? AND provider=? AND model=? "
                + "AND embedding IS NOT NULL"),
            args
        );
    }

    public UUID insertVectorIndexingJobs(
        Map<String, Object> fields) {
        return db.insert("vector_indexing_jobs", fields);
    }

    public Map<String, Object> getRagDocumentsById2(
        Object... args) {
        return db.one("SELECT metadata FROM rag_documents WHERE id=?", args);
    }

    public int updateRagChunkEmbeddings(
        Object... args) {
        return db.update(
            ("INSERT INTO rag_chunk_embeddings(chunk_id,provider,model,embedding) "
                + "VALUES (?,?,?,?::vector) ON CONFLICT(chunk_id,provider,model) DO "
                + "UPDATE SET embedding=EXCLUDED.embedding,updated_at=now()"),
            args
        );
    }

    public int updateVectorIndexingJobsById(
        Object... args) {
        return db.update("UPDATE vector_indexing_jobs SET status='COMPLETED',completed_at=now() WHERE id=?", args);
    }

    public int updateVectorIndexingJobsById2(
        Object... args) {
        return db.update(
            ("UPDATE vector_indexing_jobs SET status='FAILED',error_message=?," + "completed_at=now() WHERE id=?"),
            args
        );
    }

    public int updateRagDocumentsById2(
        Object... args) {
        return db.update(
            ("UPDATE rag_documents SET embedding_status='COMPLETED',chunks_count=? "
                + "WHERE id=? AND COALESCE(metadata->>'disabled','false')<>'true'"),
            args
        );
    }

    public UUID insertVectorStoreMetrics(
        Map<String, Object> fields) {
        return db.insert("vector_store_metrics", fields);
    }

    public Object countRagChunkEmbeddingsByProvider(
        Object... args) {
        return db.scalar(
            "SELECT count(*) FROM rag_chunk_embeddings WHERE provider=? AND model=? AND embedding IS NOT NULL",
            args
        );
    }

    public int updateRagDocumentsById3(
        Object... args) {
        return db.update("UPDATE rag_documents SET embedding_status='FAILED' WHERE id=?", args);
    }

    public Map<String, Object> getRagDocumentsById3(
        Object... args) {
        return db.one("SELECT id FROM rag_documents WHERE id=?", args);
    }

    public int updateRagDocumentsById4(
        Object... args) {
        return db.update(
            ("UPDATE rag_documents SET embedding_status='FAILED'," + "metadata=COALESCE(metadata,"
                + "'{}'::jsonb)||jsonb_build_object('disabled',true,'archived_at',now()) " + "WHERE id=?"),
            args
        );
    }

    public UUID insertAuditLogs(
        Map<String, Object> fields) {
        return db.insert("audit_logs", fields);
    }

}
