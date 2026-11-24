package com.example.intelligent_cv_matching_ai_agent.util.candidate.jobs;

import java.util.*;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.*;

/** Scores recommendations from vector similarity. */
public final class RecommendationScores {

    public static final String KIND = "RECOMMENDATION_VECTOR_SIMILARITY";

    private RecommendationScores() {
    }

    public static void apply(
        Map<String, Object> job) {
        double score = number(job.get("vector_score"), Double.NaN);
        boolean available = Double.isFinite(score);
        job.put("score_available", available);
        job.put("final_score", available ? Math.clamp(score, 0.0, 1.0) : null);
    }

    public static Map<String, Object> assessment(
        Map<String, Object> job) {
        return fields(
            "score",
            job.get("final_score"),
            "score_kind",
            KIND,
            "suitability_score_available",
            job.get("score_available")
        );
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
