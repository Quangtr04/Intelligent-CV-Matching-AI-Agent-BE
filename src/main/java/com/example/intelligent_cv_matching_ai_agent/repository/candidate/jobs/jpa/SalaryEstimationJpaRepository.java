package com.example.intelligent_cv_matching_ai_agent.repository.candidate.jobs.jpa;

import com.example.intelligent_cv_matching_ai_agent.entity.candidate.jobs.SalaryEstimation;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Typed persistence access for salary_estimations. */
public interface SalaryEstimationJpaRepository extends JpaRepository<SalaryEstimation, UUID> {
}
