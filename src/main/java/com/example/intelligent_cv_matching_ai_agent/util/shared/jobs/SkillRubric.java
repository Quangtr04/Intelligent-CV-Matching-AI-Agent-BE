package com.example.intelligent_cv_matching_ai_agent.util.shared.jobs;

import com.fasterxml.jackson.annotation.JsonAlias;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.*;

public record SkillRubric(
    String rubricVersion,
    Rubric defaultRubric,
    List<Rubric> rubrics,
    Map<String, Rubric> skillRubrics
) {

    public SkillRubric {
        rubrics = rubrics == null ? List.of() : List.copyOf(rubrics);
        skillRubrics = skillRubrics == null ? Map.of() : Map.copyOf(skillRubrics);
    }

    public record Level(
        int level,
        String label,
        String description,
        List<String> indicators
    ) {
    }

    public record Rubric(
        @JsonAlias("skillId")
        String id,
        @JsonAlias("catalogDomain")
        String domain,
        @JsonAlias("skillName")
        String name,
        List<Level> levels
    ) {

        public Level level(
            int value) {
            return levels.get(value);
        }
    }

    private static final class Catalog {

        private static final SkillRubric INSTANCE = load();
    }

    public static SkillRubric get() {
        return Catalog.INSTANCE;
    }

    public Rubric select(
        String skillId,
        String domain) {
        Rubric configured = skillRubrics.get(skillId);
        if (configured != null)
            return configured;
        return rubrics.stream().filter(rubric -> rubric.domain().equals(domain)).findFirst().orElse(defaultRubric);
    }

    public static Object currentAnalysis(
        Map<String, Object> job) {
        var analysis = object(job.get("skill_proficiency_analysis"));
        return get().rubricVersion().equals(analysis.get("rubric_version"))
            && SkillCatalog.get().version().equals(analysis.get("catalog_version"))
            && text(job.get("raw_content_hash")).equals(analysis.get("source_hash")) ? analysis : null;
    }

    private static SkillRubric load() {
        try (var source = SkillRubric.class.getResourceAsStream("/catalogs/skill-rubric.json")) {
            if (source == null)
                throw new IllegalStateException("Skill rubric is missing");
            SkillRubric catalog = JsonMapper.builder().build().readValue(source, SkillRubric.class);
            if (catalog.rubricVersion().isBlank())
                throw new IllegalStateException("Skill rubric version is missing");
            var ids = new HashSet<String>();
            var domains = new HashSet<String>();
            var rubrics = new java.util.ArrayList<>(catalog.rubrics());
            rubrics.add(catalog.defaultRubric());
            for (Rubric rubric : rubrics) {
                if (!domains.add(rubric.domain()))
                    throw new IllegalStateException("Duplicate domain rubric " + rubric.domain());
            }
            rubrics.addAll(catalog.skillRubrics().values());
            for (Rubric rubric : rubrics) {
                if (!ids.add(rubric.id()) || rubric.levels().size() != 5)
                    throw new IllegalStateException("Invalid skill rubric " + rubric.id());
                for (int index = 0; index < 5; index++) {
                    if (rubric.level(index).level() != index
                        || !rubric.level(index).label().equals(catalog.defaultRubric().level(index).label()))
                        throw new IllegalStateException("Invalid level in skill rubric " + rubric.id());
                }
            }
            return catalog;
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot load skill rubric", failure);
        }
    }
}
