package com.example.intelligent_cv_matching_ai_agent.repository.shared.learning.jpa;

import com.example.intelligent_cv_matching_ai_agent.entity.shared.learning.SkillPrerequisite;
import com.example.intelligent_cv_matching_ai_agent.entity.shared.learning.SkillPrerequisiteId;
import org.springframework.data.jpa.repository.JpaRepository;

/** Typed persistence access for skill_prerequisites. */
public interface SkillPrerequisiteJpaRepository extends JpaRepository<SkillPrerequisite, SkillPrerequisiteId> {
}
