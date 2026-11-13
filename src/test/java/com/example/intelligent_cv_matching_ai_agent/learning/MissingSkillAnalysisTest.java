package com.example.intelligent_cv_matching_ai_agent.learning;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.util.candidate.learning.MissingSkillAnalysis;
import com.example.intelligent_cv_matching_ai_agent.util.candidate.learning.SkillNames;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.*;

class MissingSkillAnalysisTest {

    @Test
    void richAlternativeEvidenceOverridesLossyLegacyMandatoryBooleansWithoutLosingSkills() {
        var extracted = com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.JobRequirementExtractor
            .extract("", "Thành thạo một trong các cơ sở dữ liệu: PostgreSQL, MongoDB", "");
        var snapshot = tools.jackson.databind.json.JsonMapper.builder().build().convertValue(extracted, Map.class);
        var result = MissingSkillAnalysis.analyze(
            fields("requirement_extraction", snapshot),
            List.of(required("PostgreSQL"), required("MongoDB")),
            List.of(),
            List.of(),
            Map.of()
        );
        assertEquals(2, result.learning().size());
        assertEquals(2, result.skills().size());
        assertTrue(result.skills().stream().allMatch(row -> row.get("status").equals("MISSING")));
    }

    @Test
    void comparesOnlyRequiredJdSkillsAgainstDeclaredEvidenceAndSameJdAssessment() {
        var found = MissingSkillAnalysis.find(
            List.of(
                required("Java"),
                required("Docker"),
                required("SQL"),
                required("Git"),
                fields("skill_name", "Kubernetes", "is_required", false)
            ),
            List.of(fields("name", "java"), fields("name", "Docker")),
            List.of(fields("name", "Git")),
            fields("skill_scores", List.of(fields("skill", "Docker", "score", 50)))
        );

        assertEquals(List.of("Docker", "SQL"), found.stream().map(row -> row.get("skill_name")).toList());
        assertEquals("NEEDS_PRACTICE", found.getFirst().get("gap_status"));
        assertEquals(50.0, found.getFirst().get("assessment_score"));
        assertEquals("MISSING_EVIDENCE", found.getLast().get("gap_status"));
        assertTrue(text(found.getLast().get("reasoning")).contains("does not establish"));
    }

    @Test
    void seventyAndHigherAreEvidenceWhileSixtyNineStillRequiresPractice() {
        var found = MissingSkillAnalysis.find(
            List.of(required("Java"), required("SQL")),
            List.of(),
            List.of(),
            fields(
                "skill_scores",
                List.of(fields("skill", "Java", "score", 70), fields("skill", "SQL", "score", 69.99))
            )
        );
        assertEquals(List.of("SQL"), found.stream().map(row -> row.get("skill_name")).toList());
    }

    @Test
    void invalidAndUnrelatedScoresNeverEstablishEvidence() {
        var found = MissingSkillAnalysis.find(
            List.of(required("Java"), required("SQL")),
            List.of(),
            List.of(),
            fields(
                "skill_scores",
                List.of(
                    fields("skill", "Java", "score", 101),
                    fields("skill", "SQL", "score", "100"),
                    fields("skill", "Docker", "score", 10)
                )
            )
        );
        assertEquals(2, found.size());
        assertTrue(found.stream().allMatch(gap -> "MISSING_EVIDENCE".equals(gap.get("gap_status"))));
    }

    @Test
    void exactCanonicalComparisonPreservesDistinctPunctuationAndDeduplicatesWhitespace() {
        var found = MissingSkillAnalysis.find(
            List.of(required("C"), required("C++"), required("C#"), required("Spring  Boot"), required("spring boot")),
            List.of(fields("name", "C++")),
            List.of(),
            Map.of()
        );
        assertEquals(List.of("C", "C#", "Spring  Boot"), found.stream().map(row -> row.get("skill_name")).toList());
        assertEquals("spring boot", SkillNames.normalize("  Spring   BOOT  "));
    }

    @Test
    void absentRequirementsAreRejectedButLargeGapListsAreNotCapped() {
        assertEquals(
            409,
            assertThrows(ApiException.class, () -> MissingSkillAnalysis.find(List.of(), List.of(), List.of(), Map.of()))
                .status()
        );
        List<Map<String, Object>> many = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            many.add(required("Skill " + i));
        }
        assertEquals(51, MissingSkillAnalysis.find(many, List.of(), List.of(), Map.of()).size());
        var gaps = MissingSkillAnalysis.find(many, List.of(), List.of(), Map.of());
        var generated = fields(
            "nodes",
            many.stream()
                .map(
                    row -> fields(
                        "skill_name",
                        row.get("skill_name"),
                        "estimated_hours",
                        1,
                        "description",
                        "Practice",
                        "prerequisites",
                        List.of()
                    )
                ).toList()
        );
        MissingSkillAnalysis.constrainGenerated(generated, gaps);
        assertEquals(51, array(generated.get("nodes")).size());
    }

    @Test
    void twentySkillsWithTwoDeclarationsKeepEighteenGapsAndTwoUnverifiedSkills() {
        List<String> names = List.of(
            "Python",
            "FastAPI",
            "Django",
            "API design",
            "Docker",
            "Kubernetes",
            "SQL",
            "Git",
            "Java",
            "JavaScript",
            "TypeScript",
            "React",
            "PostgreSQL",
            "MongoDB",
            "Redis",
            "Linux",
            "Kafka",
            "RabbitMQ",
            "Terraform",
            "Jenkins"
        );
        var analysis = MissingSkillAnalysis.analyze(
            Map.of(),
            names.stream().map(this::required).toList(),
            List.of(fields("name", "SQL"), fields("name", "Git")),
            List.of(),
            Map.of()
        );
        assertEquals(18, analysis.learning().size());
        assertEquals(20, analysis.skills().size());
        assertEquals(2, analysis.skills().stream().filter(row -> "DECLARED".equals(row.get("status"))).count());
        assertTrue(analysis.skills().stream().noneMatch(row -> "SATISFIED".equals(row.get("status"))));
        for (String name : names.subList(0, 6)) {
            assertTrue(analysis.learning().stream().anyMatch(row -> name.equals(row.get("skill_name"))));
        }
    }

    @Test
    void evidenceStatesSeparateAssessmentFromDeclarationsAndOptionalRequirements() {
        var analysis = MissingSkillAnalysis.analyze(
            Map.of(),
            List.of(
                required("Python"),
                required("Docker"),
                required("SQL"),
                required("Git"),
                fields("skill_name", "Django", "is_required", false)
            ),
            List.of(fields("name", "SQL")),
            List.of(),
            fields(
                "skill_scores",
                List.of(fields("skill", "Python", "score", 70), fields("skill", "Docker", "score", 69.99))
            )
        );
        assertEquals(
            List.of("SATISFIED", "NEEDS_IMPROVEMENT", "DECLARED", "MISSING", "MISSING"),
            analysis.skills().stream().map(row -> row.get("status")).toList()
        );
        assertEquals(
            List.of("Docker", "Git", "Django"),
            analysis.learning().stream().map(row -> row.get("skill_name")).toList()
        );
    }

    @Test
    void completelyEvidencedSkillsProduceAnEmptyLearningWhitelist() {
        assertTrue(
            MissingSkillAnalysis.find(List.of(required("Java")), List.of(fields("name", "Java")), List.of(), Map.of())
                .isEmpty()
        );
    }

    @Test
    void generatedGuidanceCannotOverrideTheServersEvidenceClassification() {
        var allowed = MissingSkillAnalysis.find(List.of(required("Docker")), List.of(), List.of(), Map.of());
        var generated = generated(" docker ", "Docker");
        MissingSkillAnalysis.constrainGenerated(generated, allowed);
        var gap = object(array(generated.get("skill_gaps")).getFirst());
        assertEquals("Docker", gap.get("skill_name"));
        assertEquals("MISSING_EVIDENCE", gap.get("gap_status"));
        assertEquals(allowed.getFirst().get("reasoning"), gap.get("reasoning"));
        assertEquals("Docker", object(array(generated.get("nodes")).getFirst()).get("skill_name"));
    }

    @Test
    void unrelatedNodeIsRejectedInsteadOfPresentingAFullCareerCurriculum() {
        var allowed = MissingSkillAnalysis.find(List.of(required("Docker")), List.of(), List.of(), Map.of());
        assertEquals(
            502,
            assertThrows(
                ApiException.class,
                () -> MissingSkillAnalysis.constrainGenerated(generated("Java", "Docker"), allowed)
            ).status()
        );
    }

    @Test
    void omittingAMissingSkillNodeIsRejectedRegardlessOfModelGaps() {
        var allowed =
            MissingSkillAnalysis.find(List.of(required("Docker"), required("SQL")), List.of(), List.of(), Map.of());
        assertThrows(
            ApiException.class,
            () -> MissingSkillAnalysis.constrainGenerated(generated("Docker", "Docker"), allowed)
        );
        assertThrows(
            ApiException.class,
            () -> MissingSkillAnalysis.constrainGenerated(generated("Docker", "Java"), allowed)
        );
    }

    @Test
    void missingAndDuplicateTargetedNodesAreStillRejected() {
        var allowed = MissingSkillAnalysis.find(List.of(required("Docker")), List.of(), List.of(), Map.of());
        var missingNode = generated("Docker", "Docker");
        missingNode.put("nodes", List.of());
        assertThrows(ApiException.class, () -> MissingSkillAnalysis.constrainGenerated(missingNode, allowed));
        var duplicateNodes = generated("Docker", "Docker");
        duplicateNodes.put(
            "nodes",
            List.of(
                fields("skill_name", "Docker", "estimated_hours", 3, "prerequisites", List.of()),
                fields("skill_name", "docker", "estimated_hours", 2, "prerequisites", List.of())
            )
        );
        assertThrows(ApiException.class, () -> MissingSkillAnalysis.constrainGenerated(duplicateNodes, allowed));
    }

    @Test
    void redundantOrForgedModelGapsCannotDiscardValidNodesOrAlterServerEvidence() {
        var allowed = MissingSkillAnalysis.find(
            List.of(required("Docker"), required("SQL")),
            List.of(),
            List.of(),
            fields("skill_scores", List.of(fields("skill", "SQL", "score", 50)))
        );
        var generated = generated("Docker", "Java");
        generated.put(
            "nodes",
            List.of(
                fields("skill_name", "Docker", "estimated_hours", 3, "prerequisites", List.of()),
                fields("skill_name", "SQL", "estimated_hours", 2, "prerequisites", List.of(1))
            )
        );
        generated.put("skill_gaps", List.of(gap("Java"), gap("Java")));

        MissingSkillAnalysis.constrainGenerated(generated, allowed);

        var gaps = array(generated.get("skill_gaps"));
        assertEquals(List.of("Docker", "SQL"), gaps.stream().map(raw -> object(raw).get("skill_name")).toList());
        assertEquals("MISSING_EVIDENCE", object(gaps.getFirst()).get("gap_status"));
        assertEquals("NEEDS_PRACTICE", object(gaps.getLast()).get("gap_status"));
        assertEquals(50.0, object(gaps.getLast()).get("assessment_score"));
        assertEquals(0, object(gaps.getFirst()).get("urgency_score"));
        assertNull(object(gaps.getFirst()).get("current_level"));
        assertEquals(2, array(generated.get("nodes")).size());
    }

    @Test
    void modelOnlyNeedsToSupplyLearningNodesBecauseGapEvidenceIsAlreadyKnown() {
        var allowed = MissingSkillAnalysis.find(List.of(required("Docker")), List.of(), List.of(), Map.of());
        var generated = generated("Docker", "Docker");
        generated.remove("skill_gaps");

        MissingSkillAnalysis.constrainGenerated(generated, allowed);

        assertEquals("Docker", object(array(generated.get("skill_gaps")).getFirst()).get("skill_name"));
        assertEquals(
            allowed.getFirst().get("reasoning"),
            object(array(generated.get("skill_gaps")).getFirst()).get("reasoning")
        );
    }

    private Map<String, Object> required(
        String name) {
        return fields("skill_name", name, "is_required", true);
    }

    private Map<String, Object> generated(
        String node,
        String gap) {
        return fields(
            "title",
            "Targeted learning",
            "skill_gaps",
            List.of(gap(gap)),
            "nodes",
            List.of(fields("skill_name", node, "estimated_hours", 3, "prerequisites", List.of()))
        );
    }

    private Map<String, Object> gap(
        String name) {
        return fields(
            "skill_name",
            name,
            "gap_status",
            "MISSING",
            "urgency_score",
            80,
            "reasoning",
            "AI incorrectly claimed a complete absence of ability"
        );
    }

    @Test
    void aliasesShareIdentityButRelatedSkillsDoNotAutomaticallySatisfyRequirements() {
        assertTrue(
            MissingSkillAnalysis
                .find(List.of(required("PostgreSQL")), List.of(fields("name", "Postgres")), List.of(), Map.of())
                .isEmpty()
        );
        assertEquals(
            1,
            MissingSkillAnalysis
                .find(List.of(required("SQL")), List.of(fields("name", "PostgreSQL")), List.of(), Map.of()).size()
        );
    }

    @Test
    void unresolvedAndUnsatisfiedAlternativesAreVisibleWithoutInventingMissingSkills() {
        var job = fields(
            "parsed_requirements",
            "<p>Excel or SQL required.</p>" + "<p>Experience with NebulaFlux required.</p>"
        );
        var unresolved = MissingSkillAnalysis.unverifiedRequirements(job, Set.of("English"));
        assertEquals(2, unresolved.size());
        assertTrue(unresolved.stream().allMatch(row -> row.get("status").equals("UNVERIFIED")));
        assertEquals(1, MissingSkillAnalysis.unverifiedRequirements(job, Set.of("SQL")).size());
    }
}
