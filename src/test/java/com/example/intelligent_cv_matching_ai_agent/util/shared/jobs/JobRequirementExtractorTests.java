package com.example.intelligent_cv_matching_ai_agent.util.shared.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.example.intelligent_cv_matching_ai_agent.util.candidate.jobs.RequirementCoverage;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class JobRequirementExtractorTests {

    @Test
    void plusSeparatedListItemsDoNotLetOnePreferenceHideIndependentRequirements() {
        var result = JobRequirementExtractor
            .extract("", "Có kinh nghiệm Docker, Kubernetes + Ưu tiên Python + Required C++", "");
        var docker = result.requirements().stream()
            .filter(row -> row.skills().stream().anyMatch(skill -> skill.name().equals("Docker"))).findFirst()
            .orElseThrow();
        assertEquals("MANDATORY", docker.importance());
        assertTrue(
            result.requirements().stream().anyMatch(
                row -> row.importance().equals("PREFERRED")
                    && row.skills().stream().anyMatch(skill -> skill.name().equals("Python"))
            )
        );
        assertTrue(result.legacySkills().stream().anyMatch(skill -> skill.name().equals("C++") && skill.required()));
    }

    @Test
    void vietnameseOneOfDatabaseChoiceDoesNotRequireEveryDatabase() {
        var result =
            JobRequirementExtractor.extract("", "Thành thạo một trong các cơ sở dữ liệu: PostgreSQL, MongoDB", "");
        assertEquals("OR", result.requirements().getFirst().relation());
        assertTrue(result.legacySkills().stream().noneMatch(skill -> skill.required()));
    }

    @Test
    void alternativesFlexibleAndUnknownClausesCannotYieldMisleadingFullCoverage() {
        String requirements = """
            <ul><li>Basic knowledge of data handling (Excel, SQL, or BI tools)</li>
            <li>Familiarity with automation tools (e.g., n8n) or willingness to learn</li>
            <li>Strong logical thinking and problem-solving skills</li>
            <li>Good communication skills</li><li>English: Intermediate</li>
            <li>Familiarity with QuasarFlux operational protocols</li></ul>
            """;
        var result = JobRequirementExtractor.extract("", requirements, "");
        assertEquals("REVIEW_REQUIRED", result.status());
        assertTrue(
            result.requirements().stream().anyMatch(r -> r.relation().equals("MIXED") && r.skills().size() >= 3)
        );
        assertTrue(result.requirements().stream().anyMatch(r -> r.importance().equals("FLEXIBLE")));
        var match = RequirementCoverage.assess(Set.of("SQL"), Map.of("parsed_requirements", requirements));
        assertTrue((double) match.get("score") < 1);
        assertTrue((int) match.get("unverified_requirements") > 0);
    }

    @Test
    void alternativesCountOnceAndOptionalSkillsDoNotPenalize() {
        var match = RequirementCoverage.assess(
            Set.of("SQL"),
            Map.of("parsed_requirements", "<p>Excel or SQL required.</p><p>Docker is a plus.</p>")
        );
        assertEquals(1.0, match.get("score"));
        assertEquals(List.of(), match.get("skill_gaps"));
    }

    @Test
    void benefitsAndNavigationCannotIntroduceSkills() {
        var result = JobRequirementExtractor.extract("", "", """
            <nav>Python</nav><h2>Your Skills and Experience</h2><p>SQL required.</p>
            <h2>Why You'll Love Working Here</h2><p>Free Docker and Java courses</p>
            """);
        assertEquals(List.of("SQL"), result.legacySkills().stream().map(s -> s.name()).toList());
    }

    @Test
    void unknownTermsRemainReviewableRatherThanInventedSkills() {
        var result = JobRequirementExtractor.extract("", "Experience with NebulaLedger workflows.", "");
        assertEquals(1, result.reviewRequired());
        assertTrue(result.requirements().getFirst().skills().isEmpty());
        assertEquals("Experience with NebulaLedger workflows.", result.requirements().getFirst().text());
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = {
            "Kỹ năng giao tiếp tốt|Communication",
            "Kinh nghiệm kế toán|Accounting",
            "Prepare financial reporting|Financial reporting",
            "Đánh giá rủi ro|Risk assessment",
            "Customer service experience|Customer service",
            "Microsoft Excel|Excel",
            "Postgres|PostgreSQL",
            "PowerBI|Power BI"}
    )
    void bilingualDomainVocabulary(
        String text,
        String expected) {
        assertTrue(JobRequirementExtractor.recognize(text).stream().anyMatch(s -> s.name().equals(expected)));
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = {
            "Recall definitions of accounting terms|Remember",
            "Explain concepts and meaning|Understand",
            "Use SQL to generate reports|Apply",
            "Analyze root causes of failures|Analyze",
            "Evaluate solutions against criteria|Evaluate",
            "Design a new workflow solution|Create",
            "Design dashboards|UNDETERMINED",
            "Senior Java developer with 10 years experience|UNDETERMINED"}
    )
    void bloomRequiresObjectAndContext(
        String text,
        String level) {
        assertTrue(JobRequirementExtractor.bloom(text).stream().anyMatch(b -> b.level().equals(level)));
        assertTrue(JobRequirementExtractor.bloom(text).stream().allMatch(b -> b.evidence().equals(text)));
    }

    @Test
    void extractionKeepsSkillEvidenceWithoutClassifyingBloom() {
        String text = "Phân tích nguyên nhân lỗi Java và thiết kế kiến trúc hệ thống";
        var extraction = JobRequirementExtractor.extract("", text, "");
        assertEquals(JobRequirementExtractor.VERSION, extraction.version());
        assertEquals(1, extraction.requirements().size());
        assertTrue(extraction.requirements().getFirst().bloom().isEmpty());
        assertFalse(extraction.legacySkills().isEmpty());
    }

    @Test
    void mixedBooleanAndConditionalRequirementsNeedReview() {
        var result = JobRequirementExtractor.extract(
            "",
            ("<p>Java and SQL or Python required.</p><p>If assigned to finance, " + "Excel is required.</p>"),
            ""
        );
        assertEquals(2, result.reviewRequired());
    }

    @Test
    void partialRecognitionDoesNotHideUnknownConjuncts() {
        var result = JobRequirementExtractor.extract("", "SQL and NebulaLedger operational protocols required.", "");
        assertEquals(1, result.reviewRequired());
        var match = RequirementCoverage.assess(
            Set.of("SQL"),
            Map.of("parsed_requirements", "SQL and NebulaLedger operational protocols required.")
        );
        assertTrue((double) match.get("score") < 1);
    }

    @Test
    void persistedSnapshotRoundTripsAndVerifiedSnapshotsAreUsed() {
        var extraction = JobRequirementExtractor.extract("", "Excel or SQL required.", "");
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        @SuppressWarnings("unchecked")
        var snapshot = mapper.convertValue(extraction, Map.class);
        var result = JobRequirementExtractor.forJob(
            Map.of(
                "requirement_extraction",
                snapshot,
                "requirement_extraction_verified",
                true,
                "parsed_requirements",
                "Python required."
            )
        );
        assertEquals(extraction, result);
        assertEquals(
            1.0,
            RequirementCoverage.assess(Set.of("SQL"), Map.of("requirement_extraction", snapshot)).get("score")
        );
    }

    @Test
    void relatedTechnologiesAreNotEquivalent() {
        var match = RequirementCoverage.assess(Set.of("PostgreSQL"), Map.of("parsed_requirements", "SQL required."));
        assertEquals(0.0, match.get("score"));
        assertEquals(
            List.of("SQL Server"),
            JobRequirementExtractor.recognize("SQL Server").stream().map(s -> s.name()).toList()
        );
    }

    @Test
    void nonTechnicalCvMentionsRetainNegationAndLearningRules() {
        var skills = com.example.intelligent_cv_matching_ai_agent.util.shared.resume.ResumeSkillMatcher
            .find("No experience with Excel. Want to learn n8n. Used PowerBI to build reports.");
        assertEquals(List.of("Power BI"), skills);
    }

    @Test
    void mixedPlainTextHeadingsAndHtmlListsRetainRequirementsAndExcludeBenefits() {
        String content = "Top 3 Reasons To Join Us\r\nThe Job\r\n"
            + "<p><strong>Key Responsibilities</strong></p><ul><li>Manage product backlogs.</li></ul>\r\n"
            + "Your Skills and Experience\r\n<ul>"
            + "<li>Minimum 5 years of experience in Product Management or Product Ownership.</li>"
            + "<li>Hands-on experience with Agile/Scrum methodologies and sprint planning.</li>"
            + "<li>Excellent verbal and written English communication skills.</li></ul>\r\n"
            + "Why You'll Love Working Here\r\n<ul><li>Free Python courses.</li></ul>";
        var extracted = JobRequirementExtractor.extract("", "", content);
        assertEquals(3, extracted.requirements().stream().filter(r -> r.section().equals("REQUIREMENTS")).count());
        assertTrue(extracted.legacySkills().stream().anyMatch(s -> s.name().equals("Agile")));
        assertFalse(extracted.legacySkills().stream().anyMatch(s -> s.name().equals("Python")));
        var match = RequirementCoverage.assess(Set.of("English"), Map.of("original_content", content));
        assertTrue((double) match.get("score") < 1);
        assertTrue((int) match.get("unverified_requirements") > 0);
    }

    @Test
    void sourceWithNoExtractableRequirementsCannotBecomeFullCoverageFromOneTag() {
        var match = RequirementCoverage.assess(
            Set.of("English"),
            Map.of(
                "original_content",
                "An unsegmented description with no reliable requirement boundaries.",
                "skills",
                List.of(Map.of("name", "English", "required", true))
            )
        );
        assertNull(match.get("requirement_coverage"));
        assertEquals(false, match.get("requirement_coverage_available"));
        assertEquals("DECLARED_SKILL_COVERAGE_ONLY", match.get("score_kind"));
    }

    @Test
    void slashAndMixedLogicAreUnverifiedInsteadOfIndependentMandatorySkills() {
        var result = JobRequirementExtractor
            .extract("", "<p>Java/SQL required.</p>" + "<p>Java and SQL or Python required.</p>", "");
        assertEquals("AMBIGUOUS", result.requirements().getFirst().relation());
        assertEquals("MIXED", result.requirements().getLast().relation());
        assertTrue(result.legacySkills().stream().noneMatch(s -> s.required()));
        var coverage = RequirementCoverage.assess(Set.of("Java"), Map.of("parsed_requirements", "Java/SQL required."));
        assertEquals(List.of(), coverage.get("skill_gaps"));
        assertEquals(1, coverage.get("unverified_requirements"));
    }

    @Test
    void structuredTagsCannotOverrideOptionalityOrAlternativeGroupsInTheActualRequirements() {
        var extracted = JobRequirementExtractor.extract(
            "Java, Docker, SQL, Excel",
            "<p>Java required.</p><p>Docker preferred.</p><p>SQL or Excel required.</p>",
            ""
        );
        var skills = extracted.legacySkills().stream()
            .collect(java.util.stream.Collectors.toMap(skill -> skill.name(), skill -> skill.required()));
        assertEquals(true, skills.get("Java"));
        assertEquals(false, skills.get("Docker"));
        assertEquals(false, skills.get("SQL"));
        assertEquals(false, skills.get("Excel"));
    }
}
