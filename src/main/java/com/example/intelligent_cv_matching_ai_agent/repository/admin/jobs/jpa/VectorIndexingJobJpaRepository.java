package com.example.intelligent_cv_matching_ai_agent.repository.admin.jobs.jpa;

import com.example.intelligent_cv_matching_ai_agent.entity.admin.jobs.VectorIndexingJob;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Typed persistence access for vector_indexing_jobs. */
public interface VectorIndexingJobJpaRepository extends JpaRepository<VectorIndexingJob, UUID> {
}
