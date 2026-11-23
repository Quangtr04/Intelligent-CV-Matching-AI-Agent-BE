package com.example.intelligent_cv_matching_ai_agent.util.shared.jobs;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.*;
import static org.junit.jupiter.api.Assertions.*;

class JobSkillProficiencyTests {

    @Test
    void selectsDomainRubricsAndKeepsTheOriginalRequirementRelations() {
        var job = fields("parsed_requirements", "Required: API design or Redux.\nPreferred: Ansible.");
        var candidates = JobSkillProficiency.candidates(job);
        var catalog = SkillRubric.get();
        assertSame(catalog.select("api-design", "BACKEND"), candidates.get("api-design").rubric());
        assertSame(catalog.select("redux", "FRONTEND"), candidates.get("redux").rubric());
        assertSame(catalog.select("ansible", "DEVOPS"), candidates.get("ansible").rubric());
        assertEquals("OR", candidates.get("api-design").clauses().getFirst().get("relation"));
        assertEquals("PREFERRED", candidates.get("ansible").clauses().getFirst().get("importance"));
        var input = JobSkillProficiency.input(job, List.of(candidates.get("api-design")));
        assertEquals(1, ((java.util.Collection<?>) input.get("rubrics")).size());
    }

    @Test
    void generalAndUnknownDomainsUseUniversalAndExplicitOverrideWins() {
        var catalog = SkillRubric.get();
        assertSame(catalog.defaultRubric(), catalog.select("unconfigured-general-skill", "GENERAL"));
        assertSame(catalog.defaultRubric(), catalog.select("unknown", "OTHER"));
        var backend = new SkillRubric.Rubric("backend", "BACKEND", "Backend", catalog.defaultRubric().levels());
        var custom =
            new SkillRubric("test", catalog.defaultRubric(), List.of(backend), Map.of("java", catalog.defaultRubric()));
        assertSame(catalog.defaultRubric(), custom.select("java", "BACKEND"));
        assertSame(backend, custom.select("unconfigured-backend-skill", "BACKEND"));
    }

    @Test
    void aListOfSkillNamesDoesNotJustifyAProficiencyLevel() {
        var candidate = JobSkillProficiency.candidates(fields("parsed_requirements", "Java, React")).get("java");
        var result = result(candidate, "java", 2, "Java");
        assertNull(result.get("required_level"));
        assertEquals("INSUFFICIENT_EVIDENCE", result.get("status"));
    }

    @Test
    void validatesExpectedIdentityLiteralEvidenceAndLevels() {
        String clause = "Independently design and optimize Java services";
        var candidate = JobSkillProficiency.candidates(fields("parsed_requirements", clause)).get("java");
        var labels = List.of("No Experience", "Basic", "Intermediate", "Advanced", "Expert");
        for (int level = 0; level <= 4; level++) {
            var result = result(candidate, "java", level, clause);
            assertEquals(level, result.get("required_level"));
            assertEquals(labels.get(level), result.get("level_label"));
        }
        assertNull(result(candidate, "java", null, clause).get("required_level"));
        assertThrows(IllegalArgumentException.class, () -> result(candidate, "java", 5, clause));
        assertThrows(IllegalArgumentException.class, () -> result(candidate, "react", 2, clause));
        assertThrows(IllegalArgumentException.class, () -> result(candidate, "java", 3, "invented evidence"));
    }

    @Test
    void hidesOldRubricAndSourceVersions() {
        var analysis = fields(
            "rubric_version",
            SkillRubric.get().rubricVersion(),
            "catalog_version",
            SkillCatalog.get().version(),
            "source_hash",
            "a"
        );
        var job = fields("raw_content_hash", "a", "skill_proficiency_analysis", analysis);
        assertSame(analysis, SkillRubric.currentAnalysis(job));
        analysis.put("rubric_version", "old");
        assertNull(SkillRubric.currentAnalysis(job));
        analysis.put("rubric_version", SkillRubric.get().rubricVersion());
        job.put("raw_content_hash", "b");
        assertNull(SkillRubric.currentAnalysis(job));
    }

    private static Map<String, Object> result(
        JobSkillProficiency.Candidate candidate,
        String id,
        Integer level,
        String evidence) {
        return JobSkillProficiency.validate(
            List.of(candidate),
            fields(
                "results",
                List.of(
                    fields(
                        "skill_id",
                        id,
                        "required_level",
                        level,
                        "evidence",
                        evidence,
                        "reason",
                        "The JD contains this requirement.",
                        "confidence",
                        0.8
                    )
                )
            )
        ).getFirst();
    }
}
