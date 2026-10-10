package com.example.intelligent_cv_matching_ai_agent.repository.shared.resume.jpa;

import com.example.intelligent_cv_matching_ai_agent.entity.shared.resume.ResumeTemplate;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Typed persistence access for resume_templates. */
public interface ResumeTemplateJpaRepository extends JpaRepository<ResumeTemplate, UUID> {
}
