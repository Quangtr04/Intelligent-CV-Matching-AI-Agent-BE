package com.example.intelligent_cv_matching_ai_agent.repository.admin.monitoring.jpa;

import com.example.intelligent_cv_matching_ai_agent.entity.admin.monitoring.VectorStoreMetric;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Typed persistence access for vector_store_metrics. */
public interface VectorStoreMetricJpaRepository extends JpaRepository<VectorStoreMetric, UUID> {
}
