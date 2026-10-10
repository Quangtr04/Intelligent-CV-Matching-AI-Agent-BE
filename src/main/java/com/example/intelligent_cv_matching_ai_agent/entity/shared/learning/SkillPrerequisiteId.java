package com.example.intelligent_cv_matching_ai_agent.entity.shared.learning;

import java.io.Serializable;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Composite identifier for skill_prerequisites. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SkillPrerequisiteId implements Serializable {

    private static final long serialVersionUID = 1L;

    private UUID skillId;

    private UUID prerequisiteId;
}
