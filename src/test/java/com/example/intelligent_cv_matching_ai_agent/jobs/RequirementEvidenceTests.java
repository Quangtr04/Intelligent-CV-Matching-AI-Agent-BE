package com.example.intelligent_cv_matching_ai_agent.jobs;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.util.candidate.jobs.RequirementEvidence;
import com.example.intelligent_cv_matching_ai_agent.util.candidate.jobs.RequirementEvidence.Chunk;
import com.example.intelligent_cv_matching_ai_agent.util.candidate.jobs.RequirementEvidence.Requirement;
import com.example.intelligent_cv_matching_ai_agent.util.candidate.learning.MissingSkillAnalysis;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.*;

class RequirementEvidenceTests {

    @Test
    void chunksUseReviewedCompetenceFieldsAndExcludeContactGoalsAndStaleRawText() {
        var resume = fields(
            "id",
            UUID.randomUUID(),
            "raw_content",
            "STALE JAVA",
            "structured_data",
            fields(
                "full_name",
                "PRIVATE NAME",
                "email",
                "PRIVATE EMAIL",
                "career_goal",
                "WANT AWS",
                "skills",
                List.of(Map.of("name", "SQL")),
                "experience",
                List.of(fields("job_title", "Backend", "description", "Added database indexes")),
                "projects",
                List.of(fields("description", "Built ordering endpoints", "technologies", List.of("Java"))),
                "education",
                List.of(Map.of("degree", "Bachelor")),
                "certifications",
                List.of(Map.of("name", "AWS certification"))
            )
        );
        var chunks = RequirementEvidence.resumeChunks(List.of(resume));
        String text = chunks.toString();
        assertTrue(text.contains("SQL"));
        assertTrue(text.contains("Added database indexes"));
        assertTrue(text.contains("Built ordering endpoints"));
        assertTrue(text.contains("Bachelor"));
        assertTrue(text.contains("AWS certification"));
        for (String excluded : List.of("PRIVATE NAME", "PRIVATE EMAIL", "WANT AWS", "STALE JAVA"))
            assertFalse(text.contains(excluded));
        assertEquals(chunks.size(), chunks.stream().map(Chunk::id).distinct().count());
    }

    @Test
    void longEvidenceIsSplitWithoutLosingText() {
        String description = "Database optimization. ".repeat(200);
        var chunks = RequirementEvidence.resumeChunks(
            List.of(
                fields(
                    "id",
                    UUID.randomUUID(),
                    "structured_data",
                    fields("projects", List.of(Map.of("description", description)))
                )
            )
        );
        assertTrue(chunks.size() > 1);
        assertEquals(
            "description: " + description.strip(),
            chunks.stream().map(Chunk::text).collect(java.util.stream.Collectors.joining())
        );
    }

    @Test
    void careerIntentDoesNotBecomeProfileEvidence() {
        var chunks = RequirementEvidence.profileChunks(
            fields(
                "target_job_title",
                "AWS expert",
                "career_goal",
                "Want Java",
                "current_job_title",
                "Designer",
                "years_experience",
                0
            ),
            List.of()
        );
        assertFalse(chunks.toString().contains("AWS"));
        assertFalse(chunks.toString().contains("Java"));
        assertTrue(chunks.toString().contains("Designer"));
    }

    @Test
    void aliasesSettleSimpleDeclarationsButNeverExperienceOrDifferentTechnologies() {
        var chunks = List.of(new Chunk("c0", "resume:x/skills/0", "name: Postgres"));
        assertTrue(
            RequirementEvidence.declaredSkillVerdict(req("PostgreSQL", "MANDATORY", "PostgreSQL"), chunks).isPresent()
        );
        assertTrue(RequirementEvidence.declaredSkillVerdict(req("MySQL", "MANDATORY", "MySQL"), chunks).isEmpty());
        assertTrue(
            RequirementEvidence
                .declaredSkillVerdict(req("3 years PostgreSQL experience", "MANDATORY", "PostgreSQL"), chunks).isEmpty()
        );
        assertTrue(
            RequirementEvidence
                .declaredSkillVerdict(req("Optimize PostgreSQL queries", "MANDATORY", "PostgreSQL"), chunks).isEmpty()
        );
    }

    @Test
    void orNeedsOneAlternativeAndAndNeedsAll() {
        var chunks = List.of(new Chunk("c0", "resume:x/skills/0", "name: Java"));
        assertTrue(
            RequirementEvidence.declaredSkillVerdict(
                new Requirement("r0", "Java or Python", "MANDATORY", "OR", List.of("Java", "Python")),
                chunks
            ).isPresent()
        );
        assertTrue(
            RequirementEvidence.declaredSkillVerdict(
                new Requirement("r0", "Java and Python", "MANDATORY", "AND", List.of("Java", "Python")),
                chunks
            ).isEmpty()
        );
    }

    @Test
    void onlyRequiredCriteriaDetermineCoverageAndPartialHasExplicitHalfCredit() {
        var requirements = List.of(
            new Requirement("r0", "Java", "MANDATORY", "AND", List.of("Java")),
            new Requirement("r1", "Build and test API", "MANDATORY", "AND", List.of()),
            new Requirement("r2", "AWS", "PREFERRED", "AND", List.of("AWS"))
        );
        var report = RequirementEvidence.report(
            requirements,
            Map.of("r0", verdict("MET"), "r1", verdict("PARTIAL"), "r2", verdict("MISSING_EVIDENCE")),
            "PARSED"
        );
        assertEquals(0.75, report.get("score"));
        assertEquals(0.0, report.get("preferred_coverage"));
        assertEquals(2, report.get("required_count"));
    }

    @Test
    void missingAndUnclearSourcesNeverPresentCompleteCoverage() {
        var r = req("Java", "MANDATORY", "Java");
        var truncated = RequirementEvidence.report(List.of(r), Map.of("r0", verdict("MET")), "TRUNCATED");
        assertEquals(false, truncated.get("requirement_coverage_available"));
        assertNull(truncated.get("requirement_coverage"));
        var uncertain = RequirementEvidence.report(List.of(r), Map.of("r0", verdict("UNVERIFIED")), "PARSED");
        assertEquals(false, uncertain.get("coverage_complete"));
        assertEquals(0.0, uncertain.get("score"));
        var optionalOnly =
            RequirementEvidence.report(List.of(req("AWS", "PREFERRED", "AWS")), Map.of("r0", verdict("MET")), "PARSED");
        assertEquals(false, optionalOnly.get("requirement_coverage_available"));
    }

    @Test
    void citationsMustBelongToThisRequirementAndQuoteTheActualSource() {
        var supplied = List.of(
            fields(
                "requirement_id",
                "r0",
                "candidates",
                List.of(fields("chunk_id", "c0", "source", "projects/0", "text", "Built endpoints", "similarity", 0.99))
            )
        );
        var good = fields(
            "requirement_id",
            "r0",
            "status",
            "MET",
            "reason",
            "Documented action",
            "evidence",
            List.of(fields("chunk_id", "c0", "quote", "Built endpoints"))
        );
        assertEquals(
            "MET",
            RequirementEvidence.validate(supplied, Map.of("results", List.of(good))).get("r0").get("status")
        );
        for (var invalid : List.of(
            fields("chunk_id", "c0", "quote", "Invented REST API experience"),
            fields("chunk_id", "another-candidate", "quote", "Built endpoints")
        )) {
            var bad = new LinkedHashMap<>(good);
            bad.put("evidence", List.of(invalid));
            assertThrows(
                ApiException.class,
                () -> RequirementEvidence.validate(supplied, Map.of("results", List.of(bad)))
            );
        }
        var unsupported = new LinkedHashMap<>(good);
        unsupported.put("evidence", List.of());
        assertThrows(
            ApiException.class,
            () -> RequirementEvidence.validate(supplied, Map.of("results", List.of(unsupported)))
        );
        assertThrows(
            ApiException.class,
            () -> RequirementEvidence.validate(supplied, Map.of("results", List.of(good, good)))
        );
        assertThrows(ApiException.class, () -> RequirementEvidence.validate(supplied, Map.of("results", List.of())));
    }

    @Test
    void retrievalKeepsExactSkillEvidenceEvenWhenItsVectorIsBelowTopThree() {
        var chunks = List.of(
            new Chunk("c0", "projects/0", "Cloud deployment"),
            new Chunk("c1", "projects/1", "Web services"),
            new Chunk("c2", "projects/2", "Backend"),
            new Chunk("c3", "skills/0", "PostgreSQL")
        );
        var result = RequirementEvidence.retrieve(
            req("PostgreSQL", "MANDATORY", "PostgreSQL"),
            new float[]{1, 0},
            chunks,
            List.of(new float[]{1, 0}, new float[]{1, 0}, new float[]{1, 0}, new float[]{0, 1})
        );
        assertEquals(4, result.size());
        assertTrue(result.stream().anyMatch(row -> "c3".equals(row.get("chunk_id"))));
    }

    @Test
    void roadmapReusesEvidenceButWeakAssessmentStillRequiresPractice() {
        var report = fields(
            "requirements",
            List.of(
                fields(
                    "text",
                    "Java",
                    "importance",
                    "MANDATORY",
                    "skills",
                    List.of("Java"),
                    "status",
                    "MET",
                    "evidence",
                    List.of()
                )
            )
        );
        assertTrue(MissingSkillAnalysis.fromEvidence(report, Map.of()).learning().isEmpty());
        var assessment = fields("skill_scores", List.of(fields("skill", "Java", "score", 50)));
        var analysis = MissingSkillAnalysis.fromEvidence(report, assessment);
        assertEquals(1, analysis.learning().size());
        assertEquals("NEEDS_PRACTICE", analysis.learning().getFirst().get("gap_status"));
    }

    @Test
    void optionalAdvancedRequirementDoesNotTurnAMetRequiredSkillIntoAMandatoryLearningGap() {
        var report = fields(
            "requirements",
            List.of(
                fields("text", "Java", "importance", "MANDATORY", "skills", List.of("Java"), "status", "MET"),
                fields(
                    "text",
                    "Advanced Java tuning",
                    "importance",
                    "PREFERRED",
                    "skills",
                    List.of("Java"),
                    "status",
                    "MISSING_EVIDENCE"
                )
            )
        );
        assertTrue(MissingSkillAnalysis.fromEvidence(report, Map.of()).learning().isEmpty());
    }

    @Test
    void unmetDurationCannotBeFixedByInventingACourseAndOrDoesNotRequireEveryAlternative() {
        var report = fields(
            "requirements",
            List.of(
                fields(
                    "text",
                    "3 years Java experience",
                    "importance",
                    "MANDATORY",
                    "skills",
                    List.of("Java"),
                    "status",
                    "NOT_MET"
                ),
                fields(
                    "text",
                    "Java or Python",
                    "importance",
                    "MANDATORY",
                    "relation",
                    "OR",
                    "skills",
                    List.of("Java", "Python"),
                    "status",
                    "MISSING_EVIDENCE"
                )
            )
        );
        assertTrue(
            MissingSkillAnalysis.fromEvidence(report, Map.of()).learning().stream()
                .allMatch(row -> "OPTIONAL_REVIEW".equals(row.get("gap_status")))
        );
        assertEquals(
            "OPTIONAL_REVIEW",
            MissingSkillAnalysis.fromEvidence(report, Map.of()).skills().getFirst().get("action")
        );
        var weak = fields("skill_scores", List.of(fields("skill", "Java", "score", 40)));
        assertEquals(
            1,
            MissingSkillAnalysis.fromEvidence(report, weak).learning().stream()
                .filter(row -> "Java".equals(row.get("skill_name")) && "NEEDS_PRACTICE".equals(row.get("gap_status")))
                .count()
        );
    }

    @Test
    void manuallyTaggedSkillIsNotCountedAgainWhenTheRequirementAlreadyMentionsIt() {
        var requirements = RequirementEvidence.requirements(
            fields("parsed_requirements", "Java", "skills", List.of(fields("name", "Java", "required", true)))
        );
        assertEquals(1, requirements.size());
        assertEquals(List.of("Java"), requirements.getFirst().skills());
    }

    @Test
    void unverifiedProviderDependentRequirementsNeverBecomeMissingSkillsOrMandatoryCourses() {
        var report = RequirementEvidence
            .report(List.of(req("Java", "MANDATORY", "Java")), Map.of("r0", verdict("UNVERIFIED")), "PARSED");
        assertTrue(array(report.get("skill_gaps")).isEmpty());
        assertTrue(
            MissingSkillAnalysis.fromEvidence(report, Map.of()).learning().stream()
                .allMatch(row -> "OPTIONAL_REVIEW".equals(row.get("gap_status")))
        );
    }

    private static Requirement req(
        String text,
        String importance,
        String skill) {
        return new Requirement("r0", text, importance, "AND", List.of(skill));
    }

    private static Map<String, Object> verdict(
        String status) {
        return fields("status", status, "reason", "Test evidence", "evidence", List.of());
    }
}
