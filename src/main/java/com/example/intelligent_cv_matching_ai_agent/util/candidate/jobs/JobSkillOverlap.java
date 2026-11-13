package com.example.intelligent_cv_matching_ai_agent.util.candidate.jobs;

import java.util.*;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.*;

public final class JobSkillOverlap {

    private JobSkillOverlap() {
    }

    public static Map<String, Object> overlap(
        String candidate,
        Map<String, Object> job) {
        Set<String> skills = new HashSet<>(
            com.example.intelligent_cv_matching_ai_agent.util.shared.resume.ResumeSkillMatcher.find(candidate)
        );
        return RequirementCoverage.assess(skills, job);
    }
}
