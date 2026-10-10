package com.example.intelligent_cv_matching_ai_agent.repository.candidate.resume.jpa;

import com.example.intelligent_cv_matching_ai_agent.entity.candidate.resume.Resume;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Typed persistence access for resumes. */
public interface ResumeJpaRepository extends JpaRepository<Resume, UUID> {
}
