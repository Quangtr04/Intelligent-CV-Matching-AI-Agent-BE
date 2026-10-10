package com.example.intelligent_cv_matching_ai_agent.entity.candidate.resume;

import java.io.Serializable;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Composite identifier for resume_skills. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ResumeSkillId implements Serializable {

    private static final long serialVersionUID = 1L;

    private UUID resumeId;

    private UUID skillId;
}
