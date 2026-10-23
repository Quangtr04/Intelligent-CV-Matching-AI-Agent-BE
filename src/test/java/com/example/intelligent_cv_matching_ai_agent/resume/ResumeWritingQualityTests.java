package com.example.intelligent_cv_matching_ai_agent.resume;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.util.candidate.resume.ResumeWritingQuality;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.junit.jupiter.api.Assertions.*;

class ResumeWritingQualityTests {

    @Test
    void derivesTheFiveCriterionMeanAndOverallScoreInsteadOfTrustingModelAggregates() {
        Map<String, Object> report = ResumeWritingTestData.report();
        report.put("content_score", 90);
        report.put("overall_score", 1);
        writing(report).put("score", 100);
        int[] scores = {40, 60, 80, 100, 70};
        for (int i = 0; i < scores.length; i++)
            criteria(report).get(i).put("score", scores[i]);
        Map<String, Object> result = ResumeWritingQuality.normalize(report, source("Verified experience"));
        assertEquals(new BigDecimal("86.00"), result.get("overall_score"));
        assertEquals(new BigDecimal("70.00"), writing(result).get("score"));
        assertEquals(new BigDecimal("0.20"), writing(result).get("writing_weight"));
        assertEquals(5, writing(result).get("assessed_criteria"));
    }

    @Test
    void excludesUnobservableCriteriaFromTheAverageInsteadOfInventingZeros() {
        Map<String, Object> report = ResumeWritingTestData.report();
        for (int i = 0; i < 4; i++) {
            criteria(report).get(i).put("status", i % 2 == 0 ? "NOT_APPLICABLE" : "INSUFFICIENT_EVIDENCE");
            criteria(report).get(i).put("score", null);
        }
        Map<String, Object> result = ResumeWritingQuality.normalize(report, source("Verified experience"));
        assertEquals(new BigDecimal("80.00"), writing(result).get("score"));
        assertEquals(1, writing(result).get("assessed_criteria"));
        assertEquals(new BigDecimal("84.00"), result.get("overall_score"));
    }

    @Test
    void fallsBackToContentOnlyWhenNoWritingCriterionIsObservable() {
        Map<String, Object> report = ResumeWritingTestData.report();
        for (Map<String, Object> row : criteria(report)) {
            row.put("status", "INSUFFICIENT_EVIDENCE");
            row.put("score", null);
        }
        Map<String, Object> result = ResumeWritingQuality.normalize(report, source("Verified experience"));
        assertNull(writing(result).get("score"));
        assertEquals(0, writing(result).get("assessed_criteria"));
        assertEquals(BigDecimal.ONE, writing(result).get("content_weight"));
        assertEquals(BigDecimal.ZERO, writing(result).get("writing_weight"));
        assertEquals(new BigDecimal("85.00"), result.get("overall_score"));
    }

    @Test
    void roundsStoredAndDerivedScoresToTwoDecimalPlaces() {
        Map<String, Object> report = ResumeWritingTestData.report();
        report.put("content_score", new BigDecimal("85.55"));
        criteria(report).forEach(row -> row.put("score", new BigDecimal("33.335")));
        Map<String, Object> result = ResumeWritingQuality.normalize(report, source("Verified experience"));
        assertEquals(new BigDecimal("33.34"), writing(result).get("score"));
        assertEquals(new BigDecimal("75.11"), result.get("overall_score"));
    }

    @ParameterizedTest
    @MethodSource("invalidScores")
    void rejectsInvalidProviderNumbersBeforeAnyScoreCanBeStored(
        Object invalid) {
        Map<String, Object> report = ResumeWritingTestData.report();
        report.put("content_score", invalid);
        assertThrows(IllegalArgumentException.class, () -> ResumeWritingQuality.validateModelResponse(report));
        report.put("content_score", 85);
        criteria(report).getFirst().put("score", invalid);
        assertThrows(IllegalArgumentException.class, () -> ResumeWritingQuality.validateModelResponse(report));
    }

    static Stream<Object> invalidScores() {
        return Stream.of(null, "80", -1, 101, Double.NaN, Double.POSITIVE_INFINITY);
    }

    @Test
    void rejectsMissingDuplicateAndUnknownCriteriaAndUnsupportedVersions() {
        Map<String, Object> report = ResumeWritingTestData.report();
        criteria(report).getFirst().put("code", "PROFESSIONAL_WORDING");
        assertThrows(IllegalArgumentException.class, () -> ResumeWritingQuality.validateModelResponse(report));
        criteria(report).getFirst().put("code", "FONT_SIZE");
        assertThrows(IllegalArgumentException.class, () -> ResumeWritingQuality.validateModelResponse(report));
        writing(report).put("criteria", List.of());
        assertThrows(IllegalArgumentException.class, () -> ResumeWritingQuality.validateModelResponse(report));
        writing(report).put("criteria", criteria(ResumeWritingTestData.report()));
        writing(report).put("rubric_version", "future-unreviewed-rubric");
        assertThrows(IllegalArgumentException.class, () -> ResumeWritingQuality.validateModelResponse(report));
    }

    @Test
    void requiresExplicitStatusesNullUnassessedScoresAndTextFeedback() {
        Map<String, Object> report = ResumeWritingTestData.report();
        Map<String, Object> row = criteria(report).getFirst();
        row.remove("status");
        assertThrows(IllegalArgumentException.class, () -> ResumeWritingQuality.validateModelResponse(report));
        row.put("status", "NOT_APPLICABLE");
        assertThrows(IllegalArgumentException.class, () -> ResumeWritingQuality.validateModelResponse(report));
        row.put("score", null);
        row.put("feedback", " ");
        assertThrows(IllegalArgumentException.class, () -> ResumeWritingQuality.validateModelResponse(report));
        row.put("feedback", "No experience prose was supplied.");
        row.put("evidence", List.of(Map.of("private", "trace")));
        assertThrows(IllegalArgumentException.class, () -> ResumeWritingQuality.validateModelResponse(report));
    }

    @Test
    void groundsQuotedEvidenceInAuthoritativeTextWithUnicodeAndWhitespaceNormalization() {
        Map<String, Object> report = ResumeWritingTestData.report();
        String quoted = "Tối ưu truy vấn PostgreSQL";
        criteria(report).getFirst().put("evidence", List.of(Normalizer.normalize(quoted, Normalizer.Form.NFD)));
        Map<String, Object> resume = Map.of(
            "structured_data",
            Map.of("experience", List.of(Map.of("description", "- Tối ưu\ntruy vấn PostgreSQL"))),
            "raw_content",
            "An older CV draft"
        );
        Map<String, Object> result = ResumeWritingQuality.normalize(report, resume);
        assertEquals(5, writing(result).get("assessed_criteria"));
        assertEquals(3, ((List<?>) writing(result).get("limitations")).size());
    }

    @Test
    void doesNotTreatOldExtractionOrJsonFieldNamesAsCurrentCandidateEvidence() {
        Map<String, Object> report = ResumeWritingTestData.report();
        Map<String, Object> resume = Map.of(
            "structured_data",
            Map.of("summary", "Reviewed and corrected facts"),
            "raw_content",
            "Outdated claim of 50% improvement"
        );
        for (String quote : List.of("50% improvement", "summary", "Invented achievement")) {
            criteria(report).getFirst().put("evidence", List.of(quote));
            ApiException error = assertThrows(ApiException.class, () -> ResumeWritingQuality.normalize(report, resume));
            assertEquals(502, error.status());
        }
    }

    @Test
    void rawOnlyResumesRetainOriginalBulletNewlinesWithoutTemplateDecorations() {
        String raw = "- Built APIs.\n- Added tests.";
        Map<String, Object> resume = new LinkedHashMap<>();
        resume.put("title", "CV");
        resume.put("language", "en");
        resume.put("source", "UPLOAD");
        resume.put("raw_content", raw);
        resume.put("template", Map.of("html_content", "<html>Template markup is not CV content</html>"));
        Map<String, Object> input = ResumeWritingQuality.source(resume);
        assertEquals(raw, input.get("raw_text"));
        assertEquals("RAW_TEXT", input.get("content_authority"));
        assertEquals("en", input.get("language"));
        assertFalse(input.containsKey("template"));
        Map<String, Object> report = ResumeWritingTestData.report();
        criteria(report).getFirst().put("evidence", List.of("- Built APIs."));
        assertDoesNotThrow(() -> ResumeWritingQuality.normalize(report, resume));
    }

    @Test
    void normalizedPersistableBreakdownDropsPrivateModelFields() {
        Map<String, Object> report = ResumeWritingTestData.report();
        writing(report).put("_internal", "PRIVATE_MODEL_TRACE");
        criteria(report).getFirst().put("_internal", "PRIVATE_MODEL_TRACE");
        Map<String, Object> normalized = ResumeWritingQuality.normalize(report, source("Reviewed facts"));
        assertFalse(writing(normalized).containsKey("_internal"));
        assertFalse(criteria(normalized).getFirst().containsKey("_internal"));
    }

    @Test
    void historicalAnalysesExposeNullQualityWithoutInventingAContentScore() {
        Map<String, Object> legacy =
            ResumeWritingQuality.expose(Map.of("overall_score", 80, "analysis_type", "RULE_BASED"));
        assertNull(legacy.get("writing_quality"));
        assertNull(legacy.get("content_score"));
        assertEquals(80, legacy.get("overall_score"));
    }

    private static Map<String, Object> source(
        String summary) {
        return Map.of("structured_data", Map.of("summary", summary));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> writing(
        Map<String, Object> result) {
        return (Map<String, Object>) result.get("writing_quality");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> criteria(
        Map<String, Object> result) {
        return (List<Map<String, Object>>) writing(result).get("criteria");
    }
}
