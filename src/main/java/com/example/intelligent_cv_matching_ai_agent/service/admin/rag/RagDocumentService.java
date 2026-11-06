package com.example.intelligent_cv_matching_ai_agent.service.admin.rag;

import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiService;

import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;

import com.example.intelligent_cv_matching_ai_agent.repository.admin.rag.RagDocumentRepository;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;

import com.example.intelligent_cv_matching_ai_agent.util.admin.rag.RagDocumentSupport;
import com.example.intelligent_cv_matching_ai_agent.util.shared.resume.ResumeData;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

@Service
public class RagDocumentService {

    private final RagDocumentRepository db;

    private final CurrentUser user;

    private final AiService ai;

    public RagDocumentService(
        RagDocumentRepository db,
        CurrentUser user,
        AiService ai) {
        this.db = db;
        this.user = user;
        this.ai = ai;
    }

    public List<Map<String, Object>> list() {
        user.requireOperationsRole();

        return db.listRagDocuments();
    }

    public Map<String, Object> get(
        UUID id) {
        user.requireOperationsRole();

        Map<String, Object> document = new LinkedHashMap<>(db.getRagDocumentsById(id));
        document.put("chunks", db.listRagChunksByDocumentId(id));

        return document;
    }

    @Transactional
    public Map<String, Object> create(
        Map<String, Object> input) {
        user.requireOperationsRole();

        String title = ResumeData.text(input.get("title"), "title", 255);
        String content = ResumeData.text(input.get("content"), "content", 100_000);
        if (title.isBlank() || content.isBlank())
            throw ApiException.badRequest("title and content are required");
        if (input.containsKey("sourceType") && !"ADMIN".equals(input.get("sourceType")))
            throw ApiException.badRequest("sourceType must be ADMIN");

        Object rawMetadata = input.getOrDefault("metadata", Map.of());
        if (!(rawMetadata instanceof Map<?, ?> sourceMetadata))
            throw ApiException.badRequest("metadata must be an object");

        Map<String, Object> metadata = new LinkedHashMap<>();
        sourceMetadata.forEach(
            (
                k,
                v) -> metadata.put(k.toString(), v)
        );
        metadata.put("disabled", false);
        List<String> chunks = RagDocumentSupport.split(content);

        UUID id = db.insertRagDocuments(
            Map.of(
                "uploaded_by",
                user.id(),
                "title",
                title,
                "source_type",
                "ADMIN",
                "metadata",
                db.json(metadata),
                "chunks_count",
                chunks.size(),
                "embedding_status",
                "PENDING"
            )
        );

        for (int index = 0; index < chunks.size(); index++)
            db.insertRagChunks(Map.of("document_id", id, "chunk_index", index, "content", chunks.get(index)));

        audit("IMPORT_RAG_DOCUMENT", id, Map.of("chunks", chunks.size()));

        return get(id);
    }

    /**
     * Each step commits independently so provider failures leave visible, retryable
     * index states.
     */
    public Map<String, Object> index(
        UUID id) {
        user.requireOperationsRole();

        Map<String, Object> document = db.getRagDocumentsById(id);
        if (document.get("metadata") instanceof Map<?, ?> metadata && Boolean.TRUE.equals(metadata.get("disabled")))
            throw ApiException.conflict("Archived RAG documents cannot be indexed");
        if (!ai.embeddingsEnabled())
            throw new ApiException(503, "AI embedding provider is disabled");

        int started = db.updateRagDocumentsById(id);
        if (started != 1)
            throw ApiException.conflict("Document is already being indexed or has been archived");

        try {
            List<Map<String, Object>> chunks = db.listRagChunksByDocumentId2(id);
            if (chunks.isEmpty())
                throw ApiException.conflict("Document has no chunks to index; import content first");

            for (Map<String, Object> chunk : chunks) {
                UUID chunkId = (UUID) chunk.get("id");

                if (db.findRagChunkEmbeddingsByChunkId(chunkId, ai.provider(), ai.embeddingModel()).isPresent())
                    continue;

                UUID job = db.insertVectorIndexingJobs(
                    Map.of(
                        "entity_type",
                        "RAG_CHUNK",
                        "entity_id",
                        chunkId,
                        "operation",
                        "EMBED",
                        "provider",
                        ai.provider(),
                        "model",
                        ai.embeddingModel(),
                        "status",
                        "PROCESSING",
                        "attempt_count",
                        1,
                        "started_at",
                        Instant.now()
                    )
                );

                try {
                    float[] vector = ai.embed(chunk.get("content").toString());
                    // A concurrent archive prevents publishing this chunk or reviving its document.
                    Map<String, Object> active = db.getRagDocumentsById2(id);
                    if (active.get("metadata") instanceof Map<?, ?> metadata
                        && Boolean.TRUE.equals(metadata.get("disabled")))
                        throw ApiException.conflict("Document was archived during indexing");

                    db.updateRagChunkEmbeddings(chunkId, ai.provider(), ai.embeddingModel(), Arrays.toString(vector));
                    db.updateVectorIndexingJobsById(job);
                } catch (RuntimeException ex) {
                    db.updateVectorIndexingJobsById2(RagDocumentSupport.safeError(ex), job);
                    throw ex;
                }
            }

            int changed = db.updateRagDocumentsById2(chunks.size(), id);
            if (changed != 1)
                throw ApiException.conflict("Document was archived during indexing");

            db.insertVectorStoreMetrics(
                Map.of(
                    "provider",
                    ai.provider(),
                    "index_name",
                    "rag_chunk_embeddings:" + ai.embeddingModel(),
                    "vector_count",
                    ((Number) db.countRagChunkEmbeddingsByProvider(ai.provider(), ai.embeddingModel())).longValue(),
                    "status",
                    "AVAILABLE",
                    "measured_at",
                    Instant.now()
                )
            );
            audit(
                "INDEX_RAG_DOCUMENT",
                id,
                Map.of("provider", ai.provider(), "model", ai.embeddingModel(), "chunks", chunks.size())
            );

            return get(id);
        } catch (RuntimeException ex) {
            db.updateRagDocumentsById3(id);
            throw ex;
        }
    }

    @Transactional
    public Map<String, Object> archive(
        UUID id) {
        user.requireOperationsRole();

        db.getRagDocumentsById3(id);

        db.updateRagDocumentsById4(id);
        audit("ARCHIVE_RAG_DOCUMENT", id, Map.of("disabled", true));

        return get(id);
    }

    /** Retained for callers that previously used the service as a chunking utility. */
    public static List<String> split(
        String content) {
        return RagDocumentSupport.split(content);
    }

    private void audit(
        String action,
        UUID id,
        Map<String, Object> detail) {
        db.insertAuditLogs(
            Map.of(
                "actor_user_id",
                user.id(),
                "action",
                action,
                "entity_type",
                "RAG_DOCUMENT",
                "entity_id",
                id,
                "new_value",
                db.json(detail)
            )
        );
    }
}
