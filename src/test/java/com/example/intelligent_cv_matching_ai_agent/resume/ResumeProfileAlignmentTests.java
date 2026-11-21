package com.example.intelligent_cv_matching_ai_agent.resume;

import com.example.intelligent_cv_matching_ai_agent.util.shared.ai.AiFeatureCatalog;
import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.util.candidate.resume.ResumeProfileAlignment;
import com.example.intelligent_cv_matching_ai_agent.util.shared.ai.AiResponseData;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class ResumeProfileAlignmentTests {

    @Test
    void serverDerivesMeanAndCoverageWithoutCountingAbsentPreferencesOrSalary() {
        Map<String, Object> profile = profile();
        profile.put("career_goal", "Build reliable APIs");
        profile.put("skills", List.of(Map.of("name", "Python")));
        Map<String, Object> generated = generated();
        assessed(generated, "TARGET_ROLE", 100, "Java Developer", "Java Developer");
        assessed(generated, "SKILLS", 0, "Python", "Java");
        generated.put("score", 99);
        Map<String, Object> result = ResumeProfileAlignment.normalize(generated, resume(), profile);
        assertEquals(new BigDecimal("50.00"), result.get("score"));
        assertEquals(2, result.get("assessed_criteria"));
        assertEquals(3, result.get("applicable_criteria"));
        assertEquals(new BigDecimal("66.67"), result.get("coverage_percent"));
        assertNull(criterion(result, "CAREER_GOAL").get("score"));
        assertNull(criterion(result, "SALARY_EXPECTATION").get("score"));
        assertEquals("REQUIRES_JOB_DATA", criterion(result, "SALARY_EXPECTATION").get("status"));
        assertEquals(8, ((List<?>) result.get("criteria")).size());
    }

    @Test
    void unassessedInformationStaysNullAndAnEmptyProfileDoesNotAcquireInventedScores() {
        Map<String, Object> generated = generated();
        assessed(generated, "TARGET_ROLE", 100, "Invented role", "Invented CV");
        Map<String, Object> result = ResumeProfileAlignment.normalize(generated, resume(), Map.of());
        assertNull(result.get("score"));
        assertEquals(0, result.get("assessed_criteria"));
        assertEquals(0, result.get("applicable_criteria"));
        assertEquals("NOT_APPLICABLE", criterion(result, "TARGET_ROLE").get("status"));
        result = ResumeProfileAlignment.normalize(generated(), resume(), profile());
        assertNull(result.get("score"));
        assertEquals(0, result.get("assessed_criteria"));
        assertEquals(new BigDecimal("0.00"), result.get("coverage_percent"));
        assertNull(criterion(result, "TARGET_ROLE").get("score"));
    }

    @Test
    void currentRoleIsComparableWithoutInventingYearsOfExperience() {
        Map<String, Object> profile = Map.of("current_job_title", "Java Developer");
        assertTrue(ResumeProfileAlignment.hasComparableProfile(profile));
        Map<String, Object> generated = generated();
        assessed(generated, "EXPERIENCE", 80, "Java Developer", "Java Developer");
        Map<String, Object> result = ResumeProfileAlignment.normalize(generated, resume(), profile);
        assertEquals(new BigDecimal("80.00"), result.get("score"));
        assertEquals(1, result.get("applicable_criteria"));
        assertFalse(ResumeProfileAlignment.hasComparableProfile(Map.of("expected_salary", 7500000)));
        assertTrue(ResumeProfileAlignment.hasComparableProfile(Map.of("years_experience", BigDecimal.ZERO)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"stale raw claim", "summary", "Java Developer Java", "Java developer", "Invented claim"})
    void evidenceMustComeFromOneAuthoritativeValueRatherThanStaleRawKeysOrParaphrases(
        String quote) {
        Map<String, Object> generated = generated();
        assessed(generated, "TARGET_ROLE", 90, "Java Developer", quote);
        assertEquals(
            502,
            assertThrows(ApiException.class, () -> ResumeProfileAlignment.normalize(generated, resume(), profile()))
                .status()
        );
    }

    @Test
    void profileEvidenceCannotBeBorrowedFromAnotherCriterionOrAnEarlierProfile() {
        Map<String, Object> generated = generated();
        assessed(generated, "TARGET_ROLE", 90, "Java", "Java Developer");
        Map<String, Object> profile = profile();
        profile.put("target_job_title", "Backend Engineer");
        assertEquals(
            502,
            assertThrows(ApiException.class, () -> ResumeProfileAlignment.normalize(generated, resume(), profile))
                .status()
        );
        assessed(generated, "TARGET_ROLE", 90, "Java Developer", "Java Developer");
        assertThrows(ApiException.class, () -> ResumeProfileAlignment.normalize(generated, resume(), profile));
    }

    @Test
    void assessedCriteriaRequireBothSourcesAndMissingCvEvidenceNeverBecomesZero() {
        Map<String, Object> generated = generated();
        Map<String, Object> target = criterion(generated, "TARGET_ROLE");
        target.put("status", "ASSESSED");
        target.put("score", 90);
        target.put("cv_evidence", List.of("Java Developer"));
        assertThrows(ApiException.class, () -> ResumeProfileAlignment.normalize(generated, resume(), profile()));
        target.put("status", "MISSING_CV_EVIDENCE");
        target.put("score", 0);
        assertThrows(ApiException.class, () -> ResumeProfileAlignment.validateModelResponse(generated));
        target.put("score", null);
        target.put("cv_evidence", List.of());
        Map<String, Object> result = ResumeProfileAlignment.normalize(generated, resume(), profile());
        assertNull(result.get("score"));
        assertNull(criterion(result, "TARGET_ROLE").get("score"));
    }

    @Test
    void malformedDuplicateAndNonFiniteModelOutputsAreRejected() {
        Map<String, Object> missing = generated();
        ((List<?>) missing.get("criteria")).removeLast();
        assertThrows(ApiException.class, () -> ResumeProfileAlignment.validateModelResponse(missing));
        Map<String, Object> generated = generated();
        criterion(generated, "CAREER_GOAL").put("code", "TARGET_ROLE");
        Map<String, Object> duplicate = generated;
        assertThrows(ApiException.class, () -> ResumeProfileAlignment.validateModelResponse(duplicate));
        for (Number invalid : List.of(-1, 101, Double.NaN, Double.POSITIVE_INFINITY)) {
            Map<String, Object> broken = generated();
            assessed(broken, "TARGET_ROLE", invalid, "Java Developer", "Java Developer");
            assertThrows(ApiException.class, () -> ResumeProfileAlignment.validateModelResponse(broken));
        }
    }

    @Test
    void snapshotsExcludeIdentityMetadataAndStaleRawAndDoNotRetainMutableSections() {
        Map<String, Object> skill = new LinkedHashMap<>(Map.of("name", "Java", "skill_id", "hidden-id"));
        Map<String, Object> data = new LinkedHashMap<>(
            Map.of(
                "summary",
                "Java Developer",
                "skills",
                List.of(skill),
                "email",
                "private@example.test",
                "phone",
                "private-phone"
            )
        );
        Map<String, Object> snapshot = ResumeProfileAlignment.resumeSnapshot(
            Map.of("structured_data", data, "raw_content", "stale raw claim", "user_id", "hidden-user")
        );
        skill.put("name", "Changed later");
        assertFalse(snapshot.toString().contains("Changed later"));
        assertFalse(snapshot.toString().contains("hidden-id"));
        assertFalse(snapshot.toString().contains("private@example.test"));
        assertEquals("", snapshot.get("raw_content"));
        Map<String, Object> profile = ResumeProfileAlignment.snapshotProfile(
            Map.of(
                "user_id",
                "private-id",
                "target_job_title",
                "Java Developer",
                "updated_at",
                "now",
                "email",
                "private@example.test"
            ),
            List.of(Map.of("name", "Java", "skill_id", "private-skill-id"))
        );
        assertFalse(profile.containsKey("updated_at"));
        assertFalse(profile.toString().contains("private"));
    }

    @Test
    void unicodeCompositionAndWhitespaceMayNormalizeWithoutChangingMeaning() {
        Map<String, Object> generated = generated();
        assessed(generated, "TARGET_ROLE", 90, "Lập trình viên", "Lập\ntrình viên");
        Map<String, Object> source =
            ResumeProfileAlignment.resumeSnapshot(Map.of("structured_data", Map.of("summary", "Lập trình viên")));
        Map<String, Object> result =
            ResumeProfileAlignment.normalize(generated, source, Map.of("target_job_title", "Lập trình viên"));
        assertEquals(new BigDecimal("90.00"), result.get("score"));
    }

    @Test
    void nativeSchemaAndFeatureValidationAgreeWithTheServerRubric() {
        Map<?, ?> schema = (Map<?, ?>) AiResponseData.outputFormat("CV_PROFILE_ALIGNMENT");
        assertEquals(false, schema.get("additionalProperties"));
        Map<?, ?> criteria = (Map<?, ?>) ((Map<?, ?>) schema.get("properties")).get("criteria");
        assertEquals(7, criteria.get("minItems"));
        assertEquals(7, criteria.get("maxItems"));
        AiResponseData.validate("CV_PROFILE_ALIGNMENT", generated());
        assertTrue(
            AiFeatureCatalog.defaultPrompt("CV_PROFILE_ALIGNMENT").get("user_prompt_template").toString()
                .contains("do not estimate salary fit")
        );
    }

    @Test
    void constrainedSchemaOnlyOffersRealQuotesAndCouplesStatusToScoreAndEvidence() {
        Map<?, ?> schema = (Map<?, ?>) AiResponseData
            .outputFormat("CV_PROFILE_ALIGNMENT", Map.of("resume", resume(), "career_profile", profile()));
        Map<?, ?> criteria = (Map<?, ?>) ((Map<?, ?>) schema.get("properties")).get("criteria");
        Map<?, ?> items = (Map<?, ?>) criteria.get("items");
        List<?> choices = (List<?>) items.get("oneOf");
        Map<?, ?> assessed = (Map<?, ?>) ((Map<?, ?>) choices.getFirst()).get("properties");
        assertEquals("number", ((Map<?, ?>) assessed.get("score")).get("type"));
        Map<?, ?> cvQuotes = (Map<?, ?>) assessed.get("cv_evidence");
        assertEquals(1, cvQuotes.get("minItems"));
        assertEquals(List.of("Java Developer", "Java"), ((Map<?, ?>) cvQuotes.get("items")).get("enum"));
        Map<?, ?> absent = (Map<?, ?>) ((Map<?, ?>) choices.get(1)).get("properties");
        assertEquals("null", ((Map<?, ?>) absent.get("score")).get("type"));
        assertEquals(0, ((Map<?, ?>) absent.get("cv_evidence")).get("maxItems"));
        String paragraph = "Literal evidence with punctuation. ".repeat(30) + "😀";
        Map<String, Object> longResume =
            ResumeProfileAlignment.resumeSnapshot(Map.of("structured_data", Map.of("summary", paragraph)));
        for (String quote : ResumeProfileAlignment.cvEvidenceOptions(longResume)) {
            assertTrue(quote.length() <= 240);
            assertTrue(paragraph.contains(quote));
            assertFalse(Character.isHighSurrogate(quote.charAt(quote.length() - 1)));
        }
    }

    @Test
    void employerAndAddressCannotProveDesiredWorkPreferencesEvenWhenTheModelAssignsOneHundred() {
        Map<String, Object> profile = profile();
        profile.put("preferred_location", "Ho Chi Minh City");
        profile.put("workplace_preference", "REMOTE");
        profile.put("employment_type", "FULL_TIME");
        Map<String, Object> generated = generated();
        assessed(generated, "TARGET_ROLE", 90, "Java Developer", "Java Developer");
        assessed(generated, "LOCATION", 100, "Ho Chi Minh City", "Java Developer");
        assessed(generated, "WORKPLACE", 100, "REMOTE", "Java Developer");
        assessed(generated, "EMPLOYMENT_TYPE", 100, "FULL_TIME", "Java Developer");
        Map<String, Object> result = ResumeProfileAlignment.normalize(generated, resume(), profile);
        assertEquals(new BigDecimal("90.00"), result.get("score"));
        assertEquals(1, result.get("assessed_criteria"));
        for (String code : ResumeProfileAlignment.CV_PREFERENCE_CODES) {
            assertNull(criterion(result, code).get("score"));
            assertEquals("MISSING_CV_EVIDENCE", criterion(result, code).get("status"));
            assertEquals(List.of(), criterion(result, code).get("cv_evidence"));
        }
    }

    @Test
    void nativeSchemaForEmptySourcesForbidsAssessmentAndNonemptyEvidenceWithoutEmptyEnums() {
        Map<?, ?> schema = (Map<?, ?>) AiResponseData.outputFormat("CV_PROFILE_ALIGNMENT");
        Map<?, ?> criteria = (Map<?, ?>) ((Map<?, ?>) schema.get("properties")).get("criteria");
        List<?> states = (List<?>) ((Map<?, ?>) criteria.get("items")).get("oneOf");
        for (Object state : states) {
            Map<?, ?> fields = (Map<?, ?>) ((Map<?, ?>) state).get("properties");
            assertEquals("null", ((Map<?, ?>) fields.get("score")).get("type"));
            assertEquals(0, ((Map<?, ?>) fields.get("cv_evidence")).get("maxItems"));
            assertEquals(0, ((Map<?, ?>) fields.get("profile_evidence")).get("maxItems"));
        }
        assertFalse(schema.toString().contains("enum=[]"));
    }

    private static Map<String, Object> profile() {
        return new LinkedHashMap<>(
            Map.of(
                "target_job_title",
                "Java Developer",
                "skills",
                List.of(Map.of("name", "Java")),
                "expected_salary",
                7500000,
                "salary_currency",
                "VND"
            )
        );
    }

    private static Map<String, Object> resume() {
        return ResumeProfileAlignment.resumeSnapshot(
            Map.of(
                "structured_data",
                Map.of("summary", "Java Developer", "skills", List.of(Map.of("name", "Java"))),
                "raw_content",
                "stale raw claim"
            )
        );
    }

    private static Map<String, Object> generated() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String code : ResumeProfileAlignment.CODES) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", code);
            row.put("status", "INSUFFICIENT_EVIDENCE");
            row.put("score", null);
            row.put("feedback", "Insufficient explicit evidence for comparison.");
            row.put("profile_evidence", List.of());
            row.put("cv_evidence", List.of());
            row.put("suggestions", List.of());
            rows.add(row);
        }
        return new LinkedHashMap<>(Map.of("rubric_version", ResumeProfileAlignment.RUBRIC_VERSION, "criteria", rows));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> criterion(
        Map<String, Object> report,
        String code) {
        return (Map<String, Object>) ((List<?>) report.get("criteria")).stream()
            .filter(item -> code.equals(((Map<?, ?>) item).get("code"))).findFirst().orElseThrow();
    }

    private static void assessed(
        Map<String, Object> report,
        String code,
        Number score,
        String profileQuote,
        String cvQuote) {
        Map<String, Object> row = criterion(report, code);
        row.put("status", "ASSESSED");
        row.put("score", score);
        row.put("profile_evidence", List.of(profileQuote));
        row.put("cv_evidence", List.of(cvQuote));
    }
}
