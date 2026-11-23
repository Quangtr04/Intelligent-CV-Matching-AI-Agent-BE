package com.example.intelligent_cv_matching_ai_agent.jobs;

import com.example.intelligent_cv_matching_ai_agent.util.candidate.jobs.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.*;
import static org.junit.jupiter.api.Assertions.*;

class RecommendationScoresTests {

    @Test
    void vectorSimilarityDeterminesScoreAndOrder() {
        var lower = fields("id", "a", "rule_score", 1.0, "vector_score", 0.4);
        var higher = fields("id", "b", "rule_score", 0.1, "vector_score", 0.9);
        var jobs = new ArrayList<>(List.of(lower, higher));
        jobs.forEach(RecommendationScores::apply);
        jobs.sort(RecommendationScores.ranking());
        assertSame(higher, jobs.getFirst());
        assertEquals(0.9, higher.get("final_score"));
        assertEquals(RecommendationScores.KIND, RecommendationScores.assessment(higher).get("score_kind"));
    }

    @Test
    void negativeSimilarityDisplaysZeroAndMissingVectorsRemainUnscored() {
        var negative = fields("vector_score", -0.25, "rule_score", 1.0);
        var fallback = fields("rule_score", 0.6);
        RecommendationScores.apply(negative);
        RecommendationScores.apply(fallback);
        assertEquals(0.0, negative.get("final_score"));
        assertEquals(null, fallback.get("final_score"));
        assertEquals("RECOMMENDATION_VECTOR_SIMILARITY", RecommendationScores.assessment(fallback).get("score_kind"));
    }

    @Test
    void quoteValidationAllowsWhitespaceFormattingButRejectsFabricatedOrBlankQuotes() {
        UUID id = UUID.randomUUID();
        var batch = List.of(fields("id", id, "jd_evidence", "Experience with Java\nand APIs"));
        var answer = fields(
            "job_id",
            id,
            "score",
            0.75,
            "assessment_status",
            "SUFFICIENT_EVIDENCE",
            "evidence",
            List.of(fields("cv_quote", "Built Java APIs", "jd_quote", "Java and APIs"))
        );
        var result =
            JobMatchRerank.validateRecommendations(batch, fields("results", List.of(answer)), "Built Java\nAPIs");
        assertEquals(true, result.get(id).get("usable"));
        assertEquals("SCORED", result.get(id).get("validation_status"));
        answer.put("evidence", List.of(fields("cv_quote", "Built AWS systems", "jd_quote", "Java and APIs")));
        assertEquals(
            "INVALID_EVIDENCE",
            JobMatchRerank.validateRecommendations(batch, fields("results", List.of(answer)), "Built Java APIs").get(id)
                .get("validation_status")
        );
        answer.put("evidence", List.of(fields("cv_quote", "\u00a0", "jd_quote", "Java and APIs")));
        assertEquals(
            false,
            JobMatchRerank.validateRecommendations(batch, fields("results", List.of(answer)), "Built Java APIs").get(id)
                .get("usable")
        );
    }

    @Test
    void recommendationPayloadOmitsRepeatedCatalogAndRuleSnapshotsButKeepsGroundedRequirements() {
        var source = fields(
            "id",
            UUID.randomUUID(),
            "job_title",
            "Java backend",
            "parsed_requirements",
            "Java required",
            "raw_content",
            "Requirements: Java required",
            "skills",
            List.of(fields("name", "Java", "required", true)),
            "rule_assessment",
            fields(
                "requirement_coverage",
                0.5,
                "experience_status",
                "UNVERIFIED",
                "bloom_requirements",
                "catalog-data".repeat(20000),
                "requirements",
                "catalog-data".repeat(20000)
            )
        );
        var batch = JobMatchRerank.recommendationBatches(List.of(source)).getFirst();
        String json = tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(batch);
        assertTrue(json.length() < 15000);
        assertFalse(json.contains("catalog-data"));
        assertTrue(text(batch.getFirst().get("jd_evidence")).contains("Java required"));
        assertEquals(0.5, object(batch.getFirst().get("rule_assessment")).get("requirement_coverage"));
        assertFalse(object(batch.getFirst().get("rule_assessment")).containsKey("bloom_requirements"));
    }
}
