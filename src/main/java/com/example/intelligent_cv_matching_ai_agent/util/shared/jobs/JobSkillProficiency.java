package com.example.intelligent_cv_matching_ai_agent.util.shared.jobs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.*;

public final class JobSkillProficiency {

    private JobSkillProficiency() {
    }

    public record Candidate(
        SkillCatalog.Skill skill,
        SkillRubric.Rubric rubric,
        List<Map<String, Object>> clauses
    ) {
    }

    public static Map<String, Candidate> candidates(
        Map<String, Object> job) {
        Map<String, Candidate> result = new LinkedHashMap<>();
        for (var clause : JobRequirementExtractor.forJob(job).requirements()) {
            for (var recognized : clause.skills()) {
                SkillCatalog.get().resolve(recognized.name()).filter(skill -> skill.id().equals(recognized.id()))
                    .ifPresent(skill -> {
                        var candidate = result.computeIfAbsent(
                            skill.id(),
                            id -> new Candidate(skill, SkillRubric.get().select(id, skill.domain()), new ArrayList<>())
                        );
                        candidate.clauses().add(
                            fields(
                                "text",
                                clause.text(),
                                "importance",
                                clause.importance(),
                                "relation",
                                clause.relation()
                            )
                        );
                    });
            }
        }
        return result;
    }

    public static Map<String, Object> input(
        Map<String, Object> job,
        List<Candidate> batch) {
        Map<String, SkillRubric.Rubric> rubrics = new LinkedHashMap<>();
        List<Map<String, Object>> skills = new ArrayList<>();
        for (var candidate : batch) {
            var skill = candidate.skill();
            var rubric = candidate.rubric();
            rubrics.putIfAbsent(rubric.id(), rubric);
            skills.add(
                fields(
                    "skill_id",
                    skill.id(),
                    "skill_name",
                    skill.name(),
                    "domain",
                    skill.domain(),
                    "type",
                    skill.type(),
                    "rubric_id",
                    rubric.id(),
                    "requirements",
                    candidate.clauses().stream().limit(6)
                        .map(
                            clause -> fields(
                                "text",
                                excerpt(text(clause.get("text")), 800),
                                "importance",
                                clause.get("importance"),
                                "relation",
                                clause.get("relation")
                            )
                        ).toList()
                )
            );
        }
        String jd = text(job.get("original_content"));
        if (jd.isBlank())
            jd = text(job.get("raw_content"));
        return fields("jd_text", excerpt(jd, 20000), "rubrics", rubrics.values(), "skills", skills);
    }

    public static List<Map<String, Object>> validate(
        List<Candidate> batch,
        Map<String, Object> response) {
        Map<String, Candidate> remaining = new LinkedHashMap<>();
        batch.forEach(candidate -> remaining.put(candidate.skill().id(), candidate));
        List<Map<String, Object>> results = new ArrayList<>();
        for (var raw : array(response.get("results"))) {
            var row = object(raw);
            String id = text(row.get("skill_id"));
            Candidate candidate = remaining.remove(id);
            if (candidate == null || !row.containsKey("required_level"))
                throw new IllegalArgumentException("Unexpected, duplicate or incomplete skill result");
            Object rawLevel = row.get("required_level");
            Integer level = null;
            if (rawLevel != null) {
                double value = number(rawLevel, -1);
                if (value < 0 || value > 4 || value != Math.rint(value))
                    throw new IllegalArgumentException("Invalid proficiency level");
                level = (int) value;
            }
            String evidence = text(row.get("evidence")).strip();
            String reason = text(row.get("reason")).strip();
            double confidence = number(row.get("confidence"), -1);
            if (reason.isBlank() || reason.length() > 1000 || evidence.isBlank() || evidence.length() > 800
                || !Double.isFinite(confidence) || confidence < 0 || confidence > 1 || candidate.clauses().stream()
                    .limit(6).noneMatch(clause -> excerpt(text(clause.get("text")), 800).contains(evidence))) {
                throw new IllegalArgumentException("Skill result lacks valid evidence or confidence");
            }
            if (level != null
                && candidate.clauses().stream().allMatch(clause -> onlySkillNames(text(clause.get("text"))))) {
                level = null;
                confidence = 0;
                reason = "The JD lists skills without describing the proficiency required.";
            }
            results.add(
                fields(
                    "skill_id",
                    id,
                    "skill_name",
                    candidate.skill().name(),
                    "required_level",
                    level,
                    "level_label",
                    level == null ? null : candidate.rubric().level(level).label(),
                    "status",
                    level == null ? "INSUFFICIENT_EVIDENCE" : "ASSESSED",
                    "evidence",
                    evidence,
                    "reason",
                    reason,
                    "confidence",
                    confidence,
                    "rubric_id",
                    candidate.rubric().id()
                )
            );
        }
        if (!remaining.isEmpty())
            throw new IllegalArgumentException("Missing skill results");
        return results;
    }

    private static String excerpt(
        String value,
        int limit) {
        return value.substring(0, Math.min(value.length(), limit));
    }

    private static boolean onlySkillNames(
        String clause) {
        String normalized = SkillCatalog.fold(clause);
        var parts = normalized.replaceAll("\\b(?:skills?|ky nang|required|preferred|mandatory|bat buoc|uu tien)\\b", "")
            .split("[,;|]|\\b(?:and|or|va|hoac)\\b");
        if (java.util.Arrays.stream(parts).map(part -> part.strip().replaceFirst("^[:() -]+|[.(): -]+$", ""))
            .allMatch(part -> part.isBlank() || SkillCatalog.get().resolve(part).isPresent()))
            return true;
        StringBuilder remainder = new StringBuilder(normalized);
        var mentions = SkillCatalog.get().mentions(clause);
        for (var mention : mentions.reversed()) {
            remainder.delete(mention.start(), mention.end());
        }
        return !mentions.isEmpty() && remainder.toString()
            .replaceAll("\\b(?:and|or|va|hoac|skills?|ky nang|required|preferred|mandatory|bat buoc|uu tien)\\b", "")
            .replaceAll("[\\p{Punct}\\s]", "").isEmpty();
    }
}
