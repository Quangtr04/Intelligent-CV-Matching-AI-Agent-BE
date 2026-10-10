package com.example.intelligent_cv_matching_ai_agent.repository.candidate.profile.jpa;

import com.example.intelligent_cv_matching_ai_agent.entity.candidate.profile.CandidateProfileSkill;
import com.example.intelligent_cv_matching_ai_agent.entity.candidate.profile.CandidateProfileSkillId;
import org.springframework.data.jpa.repository.JpaRepository;

/** Typed persistence access for candidate_profile_skills. */
public interface CandidateProfileSkillJpaRepository
    extends
    JpaRepository<CandidateProfileSkill, CandidateProfileSkillId> {
}
