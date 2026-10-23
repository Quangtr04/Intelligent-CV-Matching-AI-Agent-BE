package com.example.intelligent_cv_matching_ai_agent.util.candidate.jobs;

import java.util.*;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.*;

public final class JobSkillOverlap {

    private JobSkillOverlap() {
    }

    public static Map<String, Object> overlap(
        String candidate,
        Map<String, Object> job) {
        String lower = " " + candidate.toLowerCase(Locale.ROOT) + " ";
        List<String> strengths = new ArrayList<>(), gaps = new ArrayList<>();
        int requiredCount = 0, matched = 0;

        for (Object raw : array(job.get("skills"))) {
            var skill = object(raw);
            String name = text(skill.get("name")).toLowerCase(Locale.ROOT);
            boolean required = !Boolean.FALSE.equals(skill.get("required"));
            boolean found = java.util.regex.Pattern
                .compile("(?<![\\p{L}\\p{N}])" + java.util.regex.Pattern.quote(name) + "(?![\\p{L}\\p{N}])")
                .matcher(lower).find();

            if (found)
                strengths.add(name);
            else if (required)
                gaps.add(name);

            if (required) {
                requiredCount++;

                if (found)
                    matched++;
            }
        }

        double score = requiredCount == 0 ? 0.0 : (double) matched / requiredCount;

        return fields(
            "score",
            score,
            "reason",
            requiredCount == 0
                ? "No structured job skills available for comparison"
                : "Matched " + matched + " of " + requiredCount + " required skills; keyword comparison",
            "strengths",
            strengths,
            "skill_gaps",
            gaps
        );
    }
}
