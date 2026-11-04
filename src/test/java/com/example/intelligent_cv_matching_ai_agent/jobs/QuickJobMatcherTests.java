package com.example.intelligent_cv_matching_ai_agent.jobs;

import com.example.intelligent_cv_matching_ai_agent.util.candidate.jobs.QuickJobMatcher;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QuickJobMatcherTests {

    @Test
    void cvFilenameAndDesiredProfileRoleAreNotSkillEvidence() {
        var cv = QuickJobMatcher.fromResume(Map.of("title", "Java Backend CV", "structured_data", Map.of()));
        var profile = QuickJobMatcher
            .fromProfile(Map.of("target_job_title", "Java Developer", "career_goal", "Learn Java"), List.of());
        assertFalse(cv.skills().contains("java"));
        assertFalse(profile.skills().contains("java"));
        assertEquals(
            false,
            QuickJobMatcher.assess(cv, job("Java developer", "Java is required.", "java")).get("eligible")
        );
    }

    @Test
    void factualSkillEvidenceRespectsNegationAndAliases() {
        var cv = resume("Built APIs using Spring Boot and PostgreSQL. Never used Python.", "Spring Boot", "Postgres");
        assertTrue(cv.skills().containsAll(List.of("spring boot", "postgresql")));
        assertFalse(cv.skills().contains("python"));
        var result = QuickJobMatcher.assess(cv, job("Backend developer", "", "spring boot", "spring", "postgresql"));
        assertEquals(1.0, result.get("skill_coverage"));
        assertEquals(2, ((List<?>) result.get("strengths")).size());
    }

    @Test
    void fresherWithJavaIsNotRecommendedForTheActualTenYearTechnicalLeadRequirement() {
        var cv = resume(
            "Final-year student seeking an entry-level opportunity after academic projects.",
            "Java",
            "Spring Boot"
        );
        var result = QuickJobMatcher.assess(
            cv,
            job(
                "Expert, Software Engineering Java Technical Lead",
                "Minimum of 10 years' experience as a Software Engineer. At least 2 years in a management position. Java (Spring Boot).",
                "java",
                "spring boot",
                "spring"
            )
        );
        assertEquals(1.0, result.get("skill_coverage"));
        assertEquals(10.0, result.get("required_years"));
        assertEquals("INSUFFICIENT", result.get("experience_status"));
        assertEquals(false, result.get("eligible"));
        assertNull(result.get("candidate_years"));
    }

    @Test
    void explicitProfileExperienceIsCheckedAgainstTheRequiredMinimum() {
        var candidate = QuickJobMatcher.fromProfile(Map.of("years_experience", 2), List.of(Map.of("name", "Java")));
        var result =
            QuickJobMatcher.assess(candidate, job("Java developer", "At least 5 years of Java experience.", "java"));
        assertEquals("INSUFFICIENT", result.get("experience_status"));
        assertEquals(2.0, result.get("candidate_years"));
        assertEquals(false, result.get("eligible"));
    }

    @Test
    void experiencedCandidateCanMatchTheSameRoleWithoutArtificialSourceDifferences() {
        var candidate = QuickJobMatcher.fromProfile(Map.of("years_experience", 12), List.of(Map.of("name", "Java")));
        var result =
            QuickJobMatcher.assess(candidate, job("Java Technical Lead", "Minimum of 10 years experience.", "java"));
        assertEquals("MATCHED", result.get("experience_status"));
        assertEquals(true, result.get("eligible"));
    }

    @Test
    void missingExperienceRemainsUnverifiedRatherThanBeingInvented() {
        var result = QuickJobMatcher.assess(
            resume("Built Java APIs.", "Java"),
            job("Java developer", "At least 2 years of experience.", "java")
        );
        assertEquals("UNVERIFIED", result.get("experience_status"));
        assertNull(result.get("candidate_years"));
        assertEquals(true, result.get("eligible"));
    }

    @Test
    void rawPostingRequirementsAreUsedWithoutCountingCompanyAgeOrBenefitKeywords() {
        Map<String, Object> job = Map.of(
            "job_title",
            "Backend developer",
            "skills",
            List.of(Map.of("name", "java", "required", true)),
            "raw_content",
            "A company with 30 years of experience. Your Skills and Experience "
                + "At least 2 years of Java experience. PostgreSQL required. "
                + "Why You'll Love Working Here Free Python courses and annual holidays."
        );
        var result = QuickJobMatcher.assess(resume("Java projects.", "Java"), job);
        assertEquals(2.0, result.get("required_years"));
        assertEquals(0.5, result.get("skill_coverage"));
        assertTrue(((List<?>) result.get("skill_gaps")).contains("postgresql"));
        assertFalse(((List<?>) result.get("skill_gaps")).contains("python"));
    }

    @Test
    void datedEmploymentDoesNotDoubleCountOverlappingRolesOrProjects() {
        var evidence = QuickJobMatcher.fromResume(
            Map.of(
                "structured_data",
                Map.of(
                    "skills",
                    List.of(Map.of("name", "Java")),
                    "experience",
                    List.of(
                        Map.of("start_date", "2021-01-01", "end_date", "2022-12-31"),
                        Map.of("start_date", "2022-01-01", "end_date", "2023-12-31")
                    ),
                    "projects",
                    List.of(Map.of("start_date", "2010-01-01", "end_date", "2020-12-31"))
                )
            )
        );
        assertEquals(3.0, evidence.yearsExperience());
        assertEquals(3.0, QuickJobMatcher.combine(List.of(evidence, evidence)).yearsExperience());
    }

    @Test
    void differentFactualSkillsProduceDifferentJobEligibilityAndCoverage() {
        var java = resume("Backend Java projects.", "Java");
        var react = resume("Frontend React projects.", "React", "TypeScript");
        var backend = job("Java developer", "", "java");
        var frontend = job("React developer", "", "react", "typescript");
        assertEquals(true, QuickJobMatcher.assess(java, backend).get("eligible"));
        assertEquals(false, QuickJobMatcher.assess(react, backend).get("eligible"));
        assertEquals(false, QuickJobMatcher.assess(java, frontend).get("eligible"));
        assertEquals(true, QuickJobMatcher.assess(react, frontend).get("eligible"));
    }

    private static QuickJobMatcher.Evidence resume(
        String summary,
        String... skills) {
        return QuickJobMatcher
            .fromResume(Map.of("structured_data", Map.of("summary", summary, "skills", List.of(skills))));
    }

    private static Map<String, Object> job(
        String title,
        String requirements,
        String... skills) {
        return Map.of(
            "job_title",
            title,
            "parsed_requirements",
            requirements,
            "skills",
            java.util.Arrays.stream(skills).map(skill -> Map.of("name", skill, "required", true)).toList()
        );
    }
}
