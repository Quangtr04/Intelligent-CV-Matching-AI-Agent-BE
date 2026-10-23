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
    void absentOrExcessiveStructuredRequirementsCannotBecomeAnInventedRoleRoadmap() {
        assertEquals(
            409,
            assertThrows(ApiException.class, () -> MissingSkillAnalysis.find(List.of(), List.of(), List.of(), Map.of()))
                .status()
        );
        List<Map<String, Object>> many = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            many.add(required("Skill " + i));
        }
        assertThrows(ApiException.class, () -> MissingSkillAnalysis.find(many, List.of(), List.of(), Map.of()));
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
    void omittingAMissingSkillOrAddingKnownSkillGapsIsRejected() {
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
    void aGapWithoutAnyTargetedNodeAndDuplicateGapsAreRejected() {
        var allowed = MissingSkillAnalysis.find(List.of(required("Docker")), List.of(), List.of(), Map.of());
        var missingNode = generated("Docker", "Docker");
        missingNode.put("nodes", List.of());
        assertThrows(ApiException.class, () -> MissingSkillAnalysis.constrainGenerated(missingNode, allowed));
        var duplicate = generated("Docker", "Docker");
        duplicate.put("skill_gaps", List.of(gap("Docker"), gap("docker")));
        assertThrows(ApiException.class, () -> MissingSkillAnalysis.constrainGenerated(duplicate, allowed));
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
}
