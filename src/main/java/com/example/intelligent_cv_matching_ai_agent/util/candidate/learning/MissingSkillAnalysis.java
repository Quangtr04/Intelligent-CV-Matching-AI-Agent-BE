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
        if (required.size() > 50) {
            throw ApiException
                .conflict("The selected JD has more than 50 required skills; review its skill list first");
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
