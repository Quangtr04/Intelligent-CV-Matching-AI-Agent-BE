package com.example.intelligent_cv_matching_ai_agent.repository.admin.rag.jpa;

import com.example.intelligent_cv_matching_ai_agent.entity.admin.rag.RagChunk;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Typed persistence access for rag_chunks. */
public interface RagChunkJpaRepository extends JpaRepository<RagChunk, UUID> {
}
