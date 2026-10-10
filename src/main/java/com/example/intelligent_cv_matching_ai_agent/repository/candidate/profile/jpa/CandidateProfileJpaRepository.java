package com.example.intelligent_cv_matching_ai_agent.repository.candidate.profile.jpa;

import com.example.intelligent_cv_matching_ai_agent.entity.candidate.profile.CandidateProfile;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Typed persistence access for candidate_profiles. */
public interface CandidateProfileJpaRepository extends JpaRepository<CandidateProfile, UUID> {
}
