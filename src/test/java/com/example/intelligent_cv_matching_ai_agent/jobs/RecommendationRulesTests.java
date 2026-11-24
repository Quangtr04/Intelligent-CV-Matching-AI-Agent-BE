package com.example.intelligent_cv_matching_ai_agent.jobs;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.service.candidate.jobs.RecommendationService;
import com.example.intelligent_cv_matching_ai_agent.util.candidate.jobs.*;
import com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.JobEmbeddingSupport;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.*;
import static org.junit.jupiter.api.Assertions.*;

class RecommendationRulesTests {

    @Test
    void duplicateCvsAreDeduplicatedButDifferentCareerIntentsRemainIndependent() {
        var first = cv("Java", "Backend developer");
        var duplicate = cv("Java", "Backend developer");
        var unrelated = cv("Python", "Data analyst");
        var docs =
            CandidateSearchProfile.normalize("ALL_CVS", List.of(first, duplicate, unrelated), Map.of(), List.of());
        assertEquals(2, docs.size());
        assertEquals(2, docs.getFirst().resumeIds().size());
        assertFalse(docs.getFirst().searchText().contains("Data analyst"));
        assertFalse(docs.getLast().searchText().contains("Java"));
    }

    @Test
    void goalsAndProfileTitleAreNotEvidenceOfSkillsOrExperience() {
        var profile = fields("target_job_title", "Senior AWS engineer", "profile_summary", "Want to learn Kubernetes");
        var doc = CandidateSearchProfile.normalize("CAREER_PROFILE", List.of(), profile, List.of(fields("name", "JS")))
            .getFirst();
        assertFalse(doc.evidence().skills().contains("AWS"));
        assertFalse(doc.evidence().skills().contains("Kubernetes"));
        assertTrue(doc.evidence().skills().stream().anyMatch(name -> name.equalsIgnoreCase("JavaScript")));
        assertNull(doc.evidence().yearsExperience());
    }

    @Test
    void allRelevantCvFieldsAffectTheEmbeddingHashWithoutNamesContactDetailsOrFileNames() {
        var cv = cv("Java", "Backend developer");
        var data = object(cv.get("structured_data"));
        data.put("personal_info", fields("full_name", "Private person", "email", "secret@example.test"));
        cv.put("file_name", "Senior AWS architect.pdf");
        var first = normalize(cv);
        assertFalse(first.searchText().contains("Private person"));
        assertFalse(first.searchText().contains("secret@example.test"));
        assertFalse(first.searchText().contains("Senior AWS architect.pdf"));
        data.remove("target_job_title");
        data.put("position", "Backend developer");
        assertEquals("Backend developer", normalize(cv).profile().get("desired_job_title"));
        data.put(
            "education",
            List.of(fields("school", "Engineering college", "degree", "Bachelor", "major", "Computer Science"))
        );
        assertNotEquals(first.contentHash(), normalize(cv).contentHash());
        data.put("projects", List.of(fields("name", "Service", "description", "Built a Redis cache")));
        assertTrue(normalize(cv).searchText().contains("Redis"));
    }

    @Test
    void exactAliasesAndOrRequirementsDoNotTreatRelatedDatabasesAsEquivalent() {
        var candidate = normalize(cv("JavaScript", "Developer"));
        var or = RecommendationRules.assess(candidate, job("JavaScript or Python required"));
        var and = RecommendationRules.assess(candidate, job("JavaScript and Python required"));
        assertTrue(number(or.get("score"), 0) > number(and.get("score"), 0));
        var postgres = normalize(cv("PostgreSQL", "Backend"));
        var assessment = RecommendationRules.assess(postgres, job("MySQL required"));
        assertEquals(0, number(assessment.get("score"), -1));
        assertFalse(array(assessment.get("skill_gaps")).isEmpty());
        var spring =
            RecommendationRules.assess(normalize(cv("Spring Framework", "Backend")), job("Spring Boot required"));
        assertEquals(0, number(spring.get("score"), -1));
    }

    @Test
    void preferredSkillsAreSeparateAndDoNotReduceMandatoryCoverage() {
        var assessment =
            RecommendationRules.assess(normalize(cv("Java", "Backend")), job("Java required\nNice to have: Python"));
        assertEquals(1, number(assessment.get("score"), 0));
        assertEquals(0, number(assessment.get("preferred_coverage"), -1));
    }

    @Test
    void relevantExperienceUsesMergedPeriodsAndDoesNotSubstituteUnrelatedTenure() {
        var cv = cv("Java", "Backend");
        object(cv.get("structured_data")).put(
            "experience",
            List.of(
                fields(
                    "job_title",
                    "Java developer",
                    "description",
                    "Built Java APIs",
                    "start_date",
                    "2020-01-01",
                    "end_date",
                    "2022-01-01"
                ),
                fields(
                    "job_title",
                    "Java developer",
                    "description",
                    "Maintained Java APIs",
                    "start_date",
                    "2021-01-01",
                    "end_date",
                    "2023-01-01"
                ),
                fields(
                    "job_title",
                    "Python developer",
                    "description",
                    "Python systems",
                    "start_date",
                    "2010-01-01",
                    "end_date",
                    "2020-01-01"
                )
            )
        );
        var assessment = RecommendationRules.assess(normalize(cv), job("At least 4 years of Java experience required"));
        assertEquals("INSUFFICIENT", assessment.get("experience_status"));
        var condition = object(array(assessment.get("experience_conditions")).getFirst());
        // Existing CV date semantics count inclusive months: Jan 2020 through Jan 2023 = 37 months.
        assertEquals(3.1, number(condition.get("candidate_years"), 0), 0.02);
        assertEquals(false, assessment.get("eligible_for_ai"));
    }

    @Test
    void missingDatesRemainUnverifiedAndDoNotInventYearsOrProficiency() {
        var assessment = RecommendationRules.assess(
            normalize(cv("Java", "Backend")),
            job(("At least 3 years of Java experience required\nAnalyze distributed " + "system architecture"))
        );
        assertEquals("UNVERIFIED", assessment.get("experience_status"));
        assertFalse(array(assessment.get("jd_requirements")).isEmpty());
        var condition = object(array(assessment.get("experience_conditions")).getFirst());
        assertNull(condition.get("candidate_years"));
    }

    @Test
    void jobInputIsStableCleanAndChangesOnlyWhenSemanticContentChanges() {
        var job = job("Java required");
        job.put("job_title", "Backend");
        job.put("parsed_description", "<p>Build APIs</p>\nBenefits:\nFree lunch and gym");
        job.put("parsed_responsibilities", "<p>Deploy services</p>");
        String text = JobEmbeddingSupport.embeddingText(job), hash = JobEmbeddingSupport.inputHash(job);
        assertFalse(text.contains("Build APIs"));
        assertFalse(text.contains("Deploy services"));
        assertTrue(text.contains("Job title: Backend"));
        assertTrue(text.contains("Skills: Java"));
        assertFalse(text.contains("<p>"));
        assertFalse(text.contains("Free lunch"));
        job.put("salary_text", "3000 USD");
        job.put("raw_content_hash", "a".repeat(64));
        assertEquals(hash, JobEmbeddingSupport.inputHash(job));
        job.put("parsed_requirements", "Python required");
        assertNotEquals(hash, JobEmbeddingSupport.inputHash(job));
    }

    @Test
    void aiOutputRequiresRealBatchIdsAndLiteralEvidenceBeforeItAffectsRanking() {
        UUID id = UUID.randomUUID();
        var batch = List.of(fields("id", id, "raw_content", "Java required"));
        var answer = fields(
            "job_id",
            id,
            "score",
            0.9,
            "assessment_status",
            "SUFFICIENT_EVIDENCE",
            "evidence",
            List.of(fields("cv_quote", "Built Java APIs", "jd_quote", "Java required"))
        );
        assertEquals(
            true,
            JobMatchRerank.validateRecommendations(batch, fields("results", List.of(answer)), "Built Java APIs").get(id)
                .get("usable")
        );
        answer.put("evidence", List.of(fields("cv_quote", "Invented AWS experience", "jd_quote", "Java required")));
        assertEquals(
            false,
            JobMatchRerank.validateRecommendations(batch, fields("results", List.of(answer)), "Built Java APIs").get(id)
                .get("usable")
        );
        answer.put("job_id", UUID.randomUUID());
        assertThrows(
            ApiException.class,
            () -> JobMatchRerank.validateRecommendations(batch, fields("results", List.of(answer)), "Built Java APIs")
        );
    }

    @Test
    void rankingIsLexicographicAndSimilarityNeverBecomesCoverage() {
        var coverage = fields("id", "a", "final_score", 1.0, "vector_score", 0.1, "advisory_score", 0.1);
        var similarity = fields("id", "b", "final_score", 0.5, "vector_score", 1.0, "advisory_score", 1.0);
        var jobs = new ArrayList<>(List.of(similarity, coverage));
        jobs.sort(RecommendationRules.ranking());
        assertSame(coverage, jobs.getFirst());
        assertEquals(0.5, similarity.get("final_score"));
    }

    @Test
    void vectorsMustHaveTheConfiguredDimensionAndFiniteNonZeroComponents() {
        assertThrows(ApiException.class, () -> RecommendationService.validateVector(new float[10], 1024));
        assertThrows(ApiException.class, () -> RecommendationService.validateVector(new float[1024], 1024));
        float[] vector = new float[1024];
        vector[0] = Float.NaN;
        assertThrows(ApiException.class, () -> RecommendationService.validateVector(vector, 1024));
        vector[0] = 1;
        assertDoesNotThrow(() -> RecommendationService.validateVector(vector, 1024));
    }

    private static Map<String, Object> cv(
        String skill,
        String role) {
        return fields(
            "id",
            UUID.randomUUID(),
            "status",
            "READY",
            "structured_data",
            fields("target_job_title", role, "skills", List.of(fields("name", skill)))
        );
    }

    private static CandidateSearchProfile.Document normalize(
        Map<String, Object> cv) {
        return CandidateSearchProfile.normalize("SELECTED_CV", List.of(cv), Map.of(), List.of()).getFirst();
    }

    private static Map<String, Object> job(
        String requirements) {
        return fields("parsed_requirements", requirements);
    }
}
