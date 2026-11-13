package com.example.intelligent_cv_matching_ai_agent.util.candidate.learning;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import java.util.*;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.*;

/** Derives a conservative JD-specific learning whitelist from explicit skill evidence. */
public final class MissingSkillAnalysis {

    public static final String SCOPE = "MISSING_SKILLS_ONLY";

    private MissingSkillAnalysis() {
    }

    public static List<Map<String, Object>> find(
        List<Map<String, Object>> requirements,
        List<Map<String, Object>> profileSkills,
        List<Map<String, Object>> resumeSkills,
        Map<String, Object> assessment) {
        Map<String, String> required = new LinkedHashMap<>();
        for (var row : requirements) {
            String name = text(row.get("skill_name")).strip();
            if (!Boolean.FALSE.equals(row.get("is_required")) && !name.isBlank()) {
                required.putIfAbsent(SkillNames.normalize(name), name);
            }
        }
        if (required.isEmpty()) {
            throw ApiException.conflict("The selected JD has no structured required skills to compare");
        }
        Set<String> evidence = new HashSet<>();
        for (var row : profileSkills) {
            evidence.add(SkillNames.normalize(text(row.get("name"))));
        }
        for (var row : resumeSkills) {
            evidence.add(SkillNames.normalize(text(row.get("name"))));
        }
        Map<String, Double> scores = new HashMap<>();
        for (Object raw : array(assessment.get("skill_scores"))) {
            var row = object(raw);
            double score = number(row.get("score"), -1);
            if (row.get("score") instanceof Number && Double.isFinite(score) && score >= 0 && score <= 100) {
                scores.merge(SkillNames.normalize(text(row.get("skill"))), score, Math::min);
            }
        }
        List<Map<String, Object>> result = new ArrayList<>();
        required.forEach(
            (
                canonical,
                name) -> {
                Double score = scores.get(canonical);
                boolean weak = score != null && score < 70;
                if (weak || (score == null && !evidence.contains(canonical))) {
                    var gap = fields(
                        "skill_name",
                        name,
                        "gap_status",
                        weak ? "NEEDS_PRACTICE" : "MISSING_EVIDENCE",
                        "reasoning",
                        weak
                            ? "The completed assessment for this JD scored below 70; practice this required skill."
                            : "This JD requires the skill, but the selected profile/CV has no declared evidence; "
                                + "this does not establish that the candidate lacks the skill."
                    );
                    if (score != null) {
                        gap.put("assessment_score", score);
                    }
                    result.add(gap);
                }
            }
        );
        return result;
    }

    /** Complete evidence ledger, separate from the mandatory learning-node whitelist. */
    public record Analysis(
        List<Map<String, Object>> learning,
        List<Map<String, Object>> skills
    ) {
    }

    public static Analysis analyze(
        Map<String, Object> job,
        List<Map<String, Object>> requirements,
        List<Map<String, Object>> profileSkills,
        List<Map<String, Object>> resumeSkills,
        Map<String, Object> assessment) {
        Map<String, Map<String, Object>> all = new LinkedHashMap<>();
        for (var row : requirements) {
            String name = text(row.get("skill_name"));
            if (!name.isBlank()) {
                var entry = all.computeIfAbsent(
                    SkillNames.normalize(name),
                    key -> fields(
                        "skill_name",
                        name,
                        "is_required",
                        false,
                        "importance",
                        "UNVERIFIED",
                        "requirements",
                        new ArrayList<Map<String, Object>>()
                    )
                );
                if (!Boolean.FALSE.equals(row.get("is_required"))) {
                    entry.put("is_required", true);
                    entry.put("importance", "MANDATORY");
                }
            }
        }
        var extraction =
            com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.JobRequirementExtractor.forJob(job);
        if (job.get("requirement_extraction") instanceof Map<?, ?>) {
            // A rich snapshot can express OR/conditional requirements which the legacy boolean loses.
            for (var row : extraction.requirements()) {
                for (var skill : row.skills()) {
                    var entry = all.get(SkillNames.normalize(skill.name()));
                    if (entry != null) {
                        entry.put("is_required", false);
                        entry.put("importance", "UNVERIFIED");
                    }
                }
            }
        }
        for (var row : extraction.requirements()) {
            for (var skill : row.skills()) {
                var entry = all.computeIfAbsent(
                    SkillNames.normalize(skill.name()),
                    key -> fields(
                        "skill_name",
                        skill.name(),
                        "is_required",
                        false,
                        "importance",
                        "UNVERIFIED",
                        "requirements",
                        new ArrayList<Map<String, Object>>()
                    )
                );
                @SuppressWarnings("unchecked")
                var evidence = (List<Map<String, Object>>) entry.get("requirements");
                evidence.add(
                    fields(
                        "text",
                        row.text(),
                        "importance",
                        row.importance(),
                        "relation",
                        row.relation(),
                        "status",
                        row.status()
                    )
                );
                if (row.importance().equals("MANDATORY") && row.relation().equals("AND")) {
                    entry.put("is_required", true);
                    entry.put("importance", "MANDATORY");
                } else if (!Boolean.TRUE.equals(entry.get("is_required"))) {
                    String importance = text(entry.get("importance"));
                    if (row.importance().equals("MANDATORY") || !importance.equals("MANDATORY")) {
                        entry.put("importance", row.importance());
                    }
                }
            }
        }
        Set<String> declared = new HashSet<>();
        if (all.isEmpty()) {
            throw ApiException.conflict("The selected JD has no structured skills to compare");
        }
        profileSkills.forEach(row -> declared.add(SkillNames.normalize(text(row.get("name")))));
        resumeSkills.forEach(row -> declared.add(SkillNames.normalize(text(row.get("name")))));
        Map<String, Double> scores = new HashMap<>();
        for (Object raw : array(assessment.get("skill_scores"))) {
            var row = object(raw);
            double score = number(row.get("score"), -1);
            if (row.get("score") instanceof Number && Double.isFinite(score) && score >= 0 && score <= 100) {
                scores.merge(SkillNames.normalize(text(row.get("skill"))), score, Math::min);
            }
        }
        List<Map<String, Object>> learning = new ArrayList<>();
        List<Map<String, Object>> ledger = new ArrayList<>();
        for (var entry : all.entrySet()) {
            var state = new LinkedHashMap<>(entry.getValue());
            String key = entry.getKey();
            boolean required = Boolean.TRUE.equals(state.get("is_required"));
            Double score = scores.get(key);
            String status = score != null
                ? score >= 70 ? "SATISFIED" : "NEEDS_IMPROVEMENT"
                : declared.contains(key) ? "DECLARED" : "MISSING";
            state.put("status", status);
            state.put("declared_evidence", declared.contains(key));
            state.put("assessment_score", score);
            state.put(
                "priority",
                required ? "REQUIRED" : "PREFERRED".equals(state.get("importance")) ? "OPTIONAL" : "JD_RELATED"
            );
            state.put("action", status.equals("SATISFIED") || status.equals("DECLARED") ? "NONE" : "LEARN_OR_PRACTICE");
            state.put(
                "reasoning",
                score != null
                    ? score < 70
                        ? "Same-context assessment scored below 70; practice is needed."
                        : "Same-context assessment met the threshold of 70."
                    : declared.contains(key)
                        ? "Declared in CV/profile; proficiency is not independently verified."
                        : required
                            ? "No documented evidence; this is not verified lack of ability."
                            : "JD skill recommended for learning; optional or alternative scope is preserved."
            );
            ledger.add(state);
            if (status.equals("MISSING") || status.equals("NEEDS_IMPROVEMENT")) {
                var gap = new LinkedHashMap<>(state);
                gap.put("evidence_status", status);
                gap.put("gap_status", status.equals("NEEDS_IMPROVEMENT") ? "NEEDS_PRACTICE" : "MISSING_EVIDENCE");
                learning.add(gap);
            }
        }
        return new Analysis(learning, ledger);
    }

    public static List<Map<String, Object>> unverifiedRequirements(
        Map<String, Object> job,
        Set<String> candidateSkills) {
        Set<String> keys =
            candidateSkills.stream().map(SkillNames::normalize).collect(java.util.stream.Collectors.toSet());
        var extraction =
            com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.JobRequirementExtractor.forJob(job);
        return extraction.requirements().stream()
            .filter(r -> !Set.of("PREFERRED", "FLEXIBLE", "RESPONSIBILITY").contains(r.importance()))
            .filter(
                r -> !r.status().equals("RECOGNIZED") || r.relation().equals("OR")
                    && r.skills().stream().noneMatch(s -> keys.contains(SkillNames.normalize(s.name())))
            )
            .map(
                r -> fields(
                    "text",
                    r.text(),
                    "section",
                    r.section(),
                    "importance",
                    r.importance(),
                    "relation",
                    r.relation(),
                    "status",
                    "UNVERIFIED"
                )
            ).toList();
    }

    public static void constrainGenerated(
        Map<String, Object> generated,
        List<Map<String, Object>> allowed) {
        Map<String, Map<String, Object>> whitelist = new LinkedHashMap<>();
        allowed.forEach(gap -> whitelist.put(SkillNames.normalize(text(gap.get("skill_name"))), gap));
        if (!(generated.get("nodes") instanceof List<?> nodes)) {
            throw new ApiException(502, "Missing-skill guidance must contain a nodes array");
        }
        RoadmapValidator.validateNodes(nodes);
        Set<String> nodeSkills = new HashSet<>();
        List<Map<String, Object>> safeNodes = new ArrayList<>();
        for (Object raw : nodes) {
            var node = new LinkedHashMap<>(object(raw));
            String key = SkillNames.normalize(text(node.get("skill_name")));
            if (!whitelist.containsKey(key)) {
                throw new ApiException(502, "AI returned learning guidance outside the missing JD skills");
            }
            if (!nodeSkills.add(key)) {
                throw new ApiException(502, "AI must return exactly one learning item for each missing JD skill");
            }
            node.put("skill_name", whitelist.get(key).get("skill_name"));
            safeNodes.add(node);
        }
        if (!nodeSkills.equals(whitelist.keySet())) {
            throw new ApiException(502, "Every missing JD skill must have targeted learning guidance");
        }
        // The model may propose links, but only explicit catalog-source prerequisites are published.
        var catalog = com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.SkillCatalog.get();
        for (var node : safeNodes) {
            String target = text(node.get("skill_name"));
            node.put(
                "prerequisites",
                array(node.get("prerequisites")).stream().map(raw -> ((Number) raw).intValue())
                    .filter(
                        position -> catalog.isPrerequisite(text(safeNodes.get(position - 1).get("skill_name")), target)
                    ).toList()
            );
        }
        // The model supplies learning actions, not a second copy of the already established evidence.
        // A malformed/duplicate model gap used to discard otherwise complete and valid learning nodes.
        List<Map<String, Object>> safeGaps = new ArrayList<>();
        for (var evidence : whitelist.values()) {
            var gap = new LinkedHashMap<>(evidence);
            gap.put("current_level", null);
            gap.put("target_level", null);
            // No priority or proficiency measurement was made for missing evidence.
            gap.put("urgency_score", 0);
            safeGaps.add(gap);
        }
        generated.put("nodes", safeNodes);
        generated.put("skill_gaps", safeGaps);
    }
}
