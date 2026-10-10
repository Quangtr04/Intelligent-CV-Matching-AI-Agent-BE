package com.example.intelligent_cv_matching_ai_agent.repository.candidate.resume.jpa;

import com.example.intelligent_cv_matching_ai_agent.entity.candidate.resume.ResumeSkill;
import com.example.intelligent_cv_matching_ai_agent.entity.candidate.resume.ResumeSkillId;
import org.springframework.data.jpa.repository.JpaRepository;

/** Typed persistence access for resume_skills. */
public interface ResumeSkillJpaRepository extends JpaRepository<ResumeSkill, ResumeSkillId> {
}
