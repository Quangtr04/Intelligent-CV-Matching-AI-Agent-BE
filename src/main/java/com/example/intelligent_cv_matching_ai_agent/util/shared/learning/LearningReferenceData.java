package com.example.intelligent_cv_matching_ai_agent.util.shared.learning;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.*;

/** Prepares learning focus data and selects trusted, distinct resource URLs. */
public final class LearningReferenceData {

    private LearningReferenceData() {
    }

    public static List<String> focusSkills(
        Collection<String> skills) {
        return skills.stream().filter(s -> s != null && !s.isBlank()).map(String::strip).distinct().limit(20).toList();
    }

    public static List<String> assessmentFocus(
        Map<String, Object> session) {
        return array(session.get("skill_scores")).stream().map(raw -> object(raw))
            .filter(
                score -> score.get("score") instanceof Number n && Double.isFinite(n.doubleValue())
                    && n.doubleValue() >= 0 && n.doubleValue() < 70
            ).sorted(Comparator.comparingDouble(score -> ((Number) score.get("score")).doubleValue()))
            .map(score -> text(score.get("skill")))
            .filter(skill -> !skill.isBlank() && !"General".equalsIgnoreCase(skill)).distinct().toList();
    }

    public static String skillPattern(
        String skill) {
        return "%" + skill.strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    public static List<Map<String, Object>> trustedResources(
        List<Map<String, Object>> resources,
        int limit) {
        Map<String, Map<String, Object>> unique = new LinkedHashMap<>();
        List<Map<String, Object>> ordered = new ArrayList<>(resources);
        ordered.sort(Comparator.comparing(resource -> !object(resource.get("metadata_json")).containsKey("course_id")));
        for (Map<String, Object> resource : ordered) {
            String url = text(resource.get("url"));
            try {
                URI uri = URI.create(url);
                if (resource.get("id") != null && "https".equals(uri.getScheme()) && uri.getHost() != null
                    && uri.getUserInfo() == null) {
                    unique.putIfAbsent(url, resource);
                }
            } catch (IllegalArgumentException ex) {
                // Ignore invalid legacy catalog URLs rather than linking untrusted schemes.
            }
            if (unique.size() >= limit) {
                break;
            }
        }
        return List.copyOf(unique.values());
    }
}
