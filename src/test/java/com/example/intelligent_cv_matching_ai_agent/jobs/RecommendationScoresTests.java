package com.example.intelligent_cv_matching_ai_agent.jobs;

import com.example.intelligent_cv_matching_ai_agent.util.candidate.jobs.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.*;
import static org.junit.jupiter.api.Assertions.*;

class RecommendationScoresTests {

    @Test
    void llmSuitabilityOverridesRuleCoverageAndDeterminesThePrimaryOrder() {
        var lowRules = job("a", 0.0, 0.9, true);
        var highRules = job("b", 1.0, 0.4, true);
        var jobs = new ArrayList<>(List.of(highRules, lowRules));
        jobs.forEach(RecommendationScores::apply);
        jobs.sort(RecommendationScores.ranking());
        assertSame(lowRules, jobs.getFirst());
        assertEquals(0.9, lowRules.get("final_score"));
        var assessment = RecommendationScores.assessment(lowRules);
        assertEquals(0.0, assessment.get("rule_coverage_score"));
        assertEquals(0.9, assessment.get("score"));
        assertEquals(RecommendationScores.KIND, assessment.get("score_kind"));
    }

    @Test
    void unassessedAndRejectedResponsesHaveNoPercentageEvenWithFullRuleCoverage() {
        var pending = job("a", 1.0, 0.99, false);
        RecommendationScores.apply(pending);
        assertNull(pending.get("final_score"));
        assertEquals(false, pending.get("score_available"));
        assertEquals(1.0, RecommendationScores.assessment(pending).get("rule_coverage_score"));
    }

    @Test
    void aValidZeroFromTheLlmIsDifferentFromAnUnassessedJob() {
        var scored = job("a", 0.0, 0.0, true);
        var pending = job("b", 1.0, 1.0, false);
        var jobs = new ArrayList<>(List.of(pending, scored));
        jobs.forEach(RecommendationScores::apply);
        jobs.sort(RecommendationScores.ranking());
        assertSame(scored, jobs.getFirst());
        assertEquals(0.0, scored.get("final_score"));
        assertEquals("SCORED", scored.get("ai_status"));
        assertNull(pending.get("final_score"));
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

    private static Map<String, Object> job(
        String id,
        double coverage,
        double score,
        boolean usable) {
        return fields(
            "id",
            id,
            "rule_score",
            coverage,
            "final_score",
            coverage,
            "rule_assessment",
            fields("score", coverage, "requirement_coverage", coverage),
            "ai_assessment",
            fields("score", score, "usable", usable)
        );
    }
}
