package com.example.intelligent_cv_matching_ai_agent.entity.candidate.profile;

import java.io.Serializable;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Composite identifier for candidate_profile_skills. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CandidateProfileSkillId implements Serializable {

    private static final long serialVersionUID = 1L;

    private UUID userId;

    private UUID skillId;
}
