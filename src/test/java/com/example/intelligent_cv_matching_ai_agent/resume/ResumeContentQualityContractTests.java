package com.example.intelligent_cv_matching_ai_agent.resume;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.util.candidate.resume.ResumeContentQuality;
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
        Map<?, ?> criterion = (Map<?, ?>) criteria.get("items");
        Map<?, ?> fields = (Map<?, ?>) criterion.get("properties");
        assertEquals(ResumeContentQuality.CODES, ((Map<?, ?>) fields.get("code")).get("enum"));
        assertTrue(((List<?>) criterion.get("required")).containsAll(List.of("status", "score", "evidence")));
        String rules = AiResponseData.taskInstructions("CV_ANALYZE");
        assertTrue(rules.contains(ResumeContentQuality.RUBRIC_VERSION));
        ResumeContentQuality.CODES.forEach(code -> assertTrue(rules.contains(code), code));
        assertTrue(rules.contains("at least one exact short quote"));
        assertTrue(AiResponseData.schema("CV_ANALYZE").contains("content_quality"));
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
