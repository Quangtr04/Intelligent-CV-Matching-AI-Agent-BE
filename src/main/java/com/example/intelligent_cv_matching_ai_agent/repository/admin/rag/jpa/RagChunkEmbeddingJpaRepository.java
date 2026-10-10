package com.example.intelligent_cv_matching_ai_agent.repository.admin.rag.jpa;

import com.example.intelligent_cv_matching_ai_agent.entity.admin.rag.RagChunkEmbedding;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Typed persistence access for rag_chunk_embeddings. */
public interface RagChunkEmbeddingJpaRepository extends JpaRepository<RagChunkEmbedding, UUID> {
}
