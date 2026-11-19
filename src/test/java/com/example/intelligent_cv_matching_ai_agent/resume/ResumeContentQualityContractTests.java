package com.example.intelligent_cv_matching_ai_agent.resume;

import com.example.intelligent_cv_matching_ai_agent.util.shared.ai.AiFeatureCatalog;
import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.util.candidate.resume.ResumeContentQuality;
import com.example.intelligent_cv_matching_ai_agent.util.candidate.resume.ResumeWritingQuality;
import com.example.intelligent_cv_matching_ai_agent.util.shared.ai.AiResponseData;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ResumeContentQualityContractTests {

    @Test
    void nativeCvSchemaAndPromptRequireTheContentRubricConsumedByTheService() {
        Map<?, ?> schema = (Map<?, ?>) AiResponseData.outputFormat("CV_ANALYZE");
        assertTrue(((List<?>) schema.get("required")).contains("content_quality"));
        assertEquals(false, schema.get("additionalProperties"));
        Map<?, ?> quality = (Map<?, ?>) ((Map<?, ?>) schema.get("properties")).get("content_quality");
        Map<?, ?> properties = (Map<?, ?>) quality.get("properties");
        Map<?, ?> version = (Map<?, ?>) properties.get("rubric_version");
        assertEquals(List.of(ResumeContentQuality.RUBRIC_VERSION), version.get("enum"));
        Map<?, ?> criteria = (Map<?, ?>) properties.get("criteria");
        assertEquals(6, criteria.get("minItems"));
        assertEquals(6, criteria.get("maxItems"));
        assertCriterionSlots(criteria, ResumeContentQuality.CODES, 1);
        String rules = AiFeatureCatalog.defaultPrompt("CV_ANALYZE").get("user_prompt_template").toString();
        assertTrue(rules.contains(ResumeContentQuality.RUBRIC_VERSION));
        ResumeContentQuality.CODES.forEach(code -> assertTrue(rules.contains(code), code));
        assertTrue(rules.contains("at least one exact short quote"));
        assertTrue(AiResponseData.schema("CV_ANALYZE").contains("content_quality"));
    }

    @Test
    void nativeWritingSchemaPreventsDuplicateCodesAndIncompatibleStatusScorePairs() {
        Map<?, ?> schema = (Map<?, ?>) AiResponseData.outputFormat("CV_ANALYZE");
        Map<?, ?> quality = (Map<?, ?>) ((Map<?, ?>) schema.get("properties")).get("writing_quality");
        Map<?, ?> properties = (Map<?, ?>) quality.get("properties");
        assertCriterionSlots((Map<?, ?>) properties.get("criteria"), ResumeWritingQuality.CODES, 0);
    }

    @Test
    void nativeEvidenceRejectsInventedJoinedHeadingsAndUsesReviewedStringsOnly() {
        Map<String, Object> source = ResumeWritingQuality
            .source(Map.of("structured_data", Map.of(), "raw_content", "Experience\nProjects\nEducation\nSkills."));
        Map<?, ?> schema = (Map<?, ?>) AiResponseData.outputFormat("CV_ANALYZE", Map.of("resume", source));
        Map<?, ?> properties = (Map<?, ?>) schema.get("properties");
        for (String rubric : List.of("content_quality", "writing_quality")) {
            Map<?, ?> quality = (Map<?, ?>) properties.get(rubric);
            Map<?, ?> criteria = (Map<?, ?>) ((Map<?, ?>) quality.get("properties")).get("criteria");
            Map<?, ?> slot = (Map<?, ?>) ((List<?>) criteria.get("prefixItems")).getFirst();
            Map<?, ?> assessed = (Map<?, ?>) ((List<?>) slot.get("oneOf")).getFirst();
            Map<?, ?> fields = (Map<?, ?>) assessed.get("properties");
            Map<?, ?> evidence = (Map<?, ?>) fields.get("evidence");
            List<?> quotes = (List<?>) ((Map<?, ?>) evidence.get("items")).get("enum");
            assertEquals(List.of("Experience", "Projects", "Education", "Skills."), quotes);
            assertFalse(quotes.contains("Experience, Projects, Education, Skills."));
        }
        Map<?, ?> suggestions = (Map<?, ?>) properties.get("suggestions");
        Map<?, ?> suggestion = (Map<?, ?>) suggestions.get("items");
        Map<?, ?> original = (Map<?, ?>) ((Map<?, ?>) suggestion.get("properties")).get("original_content");
        assertEquals(List.of("Experience", "Projects", "Education", "Skills.", ""), original.get("enum"));

        Map<String, Object> reviewed = ResumeWritingQuality.source(
            Map.of(
                "structured_data",
                Map.of("summary", "Reviewed Java experience", "years_experience", 2),
                "raw_content",
                "Stale unreviewed content"
            )
        );
        assertEquals(List.of("Reviewed Java experience"), ResumeWritingQuality.evidenceOptions(reviewed));
    }

    private void assertCriterionSlots(
        Map<?, ?> criteria,
        List<String> codes,
        int minimumAssessedEvidence) {
        assertFalse(criteria.containsKey("items"), "Tuple schemas omit boolean items");
        List<?> slots = (List<?>) criteria.get("prefixItems");
        assertEquals(codes.size(), slots.size());
        assertEquals(codes.size(), criteria.get("minItems"));
        assertEquals(codes.size(), criteria.get("maxItems"));
        for (int index = 0; index < codes.size(); index++) {
            List<?> branches = (List<?>) ((Map<?, ?>) slots.get(index)).get("oneOf");
            assertEquals(2, branches.size());
            for (int branch = 0; branch < branches.size(); branch++) {
                Map<?, ?> criterion = (Map<?, ?>) branches.get(branch);
                Map<?, ?> fields = (Map<?, ?>) criterion.get("properties");
                assertEquals(List.of(codes.get(index)), ((Map<?, ?>) fields.get("code")).get("enum"));
                assertTrue(((List<?>) criterion.get("required")).containsAll(List.of("status", "score", "evidence")));
                assertEquals(
                    branch == 0 ? List.of("ASSESSED") : List.of("NOT_APPLICABLE", "INSUFFICIENT_EVIDENCE"),
                    ((Map<?, ?>) fields.get("status")).get("enum")
                );
                assertEquals(branch == 0 ? "number" : "null", ((Map<?, ?>) fields.get("score")).get("type"));
                assertEquals(
                    branch == 0 ? minimumAssessedEvidence : 0,
                    ((Map<?, ?>) fields.get("evidence")).get("minItems")
                );
            }
        }
    }

    @Test
    void contentScoreIsDerivedFromAssessedCriteriaRatherThanTheModelAggregate() {
        Map<String, Object> report = ResumeWritingTestData.report();
        report.put("content_score", 1);
        report.put("content_quality", ResumeWritingTestData.content(90, "Reviewed Java experience"));
        Map<String, Object> normalized = ResumeContentQuality.normalize(report, source());
        assertEquals(new BigDecimal("90.00"), normalized.get("score"));
        assertEquals(1, normalized.get("assessed_criteria"));
        List<?> criteria = (List<?>) normalized.get("criteria");
        assertEquals(6, criteria.size());
        assertNull(((Map<?, ?>) criteria.getFirst()).get("score"));
    }

    @Test
    void contentRubricRemainsMandatoryAndEvidenceCannotComeFromStaleRawText() {
        Map<String, Object> report = ResumeWritingTestData.report();
        report.remove("content_quality");
        assertEquals(
            502,
            assertThrows(ApiException.class, () -> ResumeContentQuality.normalize(report, source())).status()
        );
        report.put("content_quality", ResumeWritingTestData.content(90, "Old unreviewed claim"));
        assertEquals(
            502,
            assertThrows(ApiException.class, () -> ResumeContentQuality.normalize(report, source())).status()
        );
    }

    private static Map<String, Object> source() {
        return Map.of(
            "structured_data",
            Map.of("summary", "Reviewed Java experience"),
            "raw_content",
            "Old unreviewed claim"
        );
    }
}
