package com.example.intelligent_cv_matching_ai_agent.util.candidate.jobs;

import java.util.*;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.*;

/** A suitability percentage exists only after a grounded LLM assessment; rule coverage stays separate. */
public final class RecommendationScores {

    public static final String KIND = "RECOMMENDATION_AI_SUITABILITY";

    private RecommendationScores() {
    }

    public static void apply(
        Map<String, Object> job) {
        var ai = object(job.get("ai_assessment"));
        boolean available = Boolean.TRUE.equals(ai.get("usable"));
        job.put("score_available", available);
        job.put("final_score", available ? ai.get("score") : null);
        if (available)
            job.put("ai_status", "SCORED");
        else
            job.putIfAbsent("ai_status", "NOT_SELECTED");
    }

    public static Map<String, Object> assessment(
        Map<String, Object> job) {
        var result = new LinkedHashMap<>(object(job.get("rule_assessment")));
        result.put("rule_coverage_score", result.get("score"));
        result.put("score", job.get("final_score"));
        result.put("score_kind", KIND);
        result.put("suitability_score_available", job.get("score_available"));
        result.put("ai_status", job.get("ai_status"));
        return result;
    }

    public static Comparator<Map<String, Object>> ranking() {
        return Comparator.<Map<String, Object>>comparingDouble(job -> number(job.get("final_score"), -1)).reversed()
            .thenComparing(job -> text(job.get("id")));
    }

    public static boolean hasScore(
        Map<String, Object> job) {
        double score = number(job.get("final_score"), -1);
        return Double.isFinite(score) && score >= 0 && score <= 1;
    }
}
