package com.example.intelligent_cv_matching_ai_agent.util.candidate.learning;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.util.shared.FlowSupport;
import java.util.*;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.*;

public final class RoadmapValidator {

    private RoadmapValidator() {
    }

    public static void validateNodes(
        List<?> nodes) {
        if (nodes.isEmpty() || nodes.size() > 50)
            throw new ApiException(502, "Roadmap must have 1..50 nodes");

        for (int i = 0; i < nodes.size(); i++) {
            var node = object(nodes.get(i));
            String name = text(node.get("skill_name"));
            double hours = number(node.get("estimated_hours"), -1);
            if (!(node.get("skill_name") instanceof String) || name.isBlank() || name.length() > 150
                || !Double.isFinite(hours) || hours < 1 || hours > 1000 || hours != Math.floor(hours))
                throw new ApiException(502, "Invalid roadmap node");

            optionalText(node, "description", 30000);
            if (node.get("prerequisites") != null && !(node.get("prerequisites") instanceof List<?>))
                throw new ApiException(502, "Prerequisites must be an array");

            Set<Integer> seen = new HashSet<>();

            for (Object raw : array(node.get("prerequisites"))) {
                if (!(raw instanceof Number n) || n.doubleValue() != n.intValue() || n.intValue() < 1
                    || n.intValue() > i || !seen.add(n.intValue()))
                    throw new ApiException(502, "Prerequisites must reference unique earlier nodes");
            }
        }
    }

    public static void validateGaps(
        List<?> gaps) {
        if (gaps.size() > 100)
            throw new ApiException(502, "Too many skill gaps");

        for (Object raw : gaps) {
            var gap = object(raw);
            if (!(gap.get("skill_name") instanceof String) || text(gap.get("skill_name")).isBlank()
                || text(gap.get("skill_name")).length() > 150 || !(gap.get("gap_status") instanceof String)
                || text(gap.get("gap_status")).isBlank() || text(gap.get("gap_status")).length() > 20)
                throw new ApiException(502, "Invalid skill gap");

            optionalText(gap, "current_level", 20);
            optionalText(gap, "target_level", 20);
            optionalText(gap, "reasoning", 30000);
            FlowSupport.score(gap.get("urgency_score"), 100, "gap urgency");
        }
    }

    private static void optionalText(
        Map<String, Object> value,
        String key,
        int max) {
        Object field = value.get(key);
        if (field != null && (!(field instanceof String) || text(field).length() > max))
            throw new ApiException(502, "Invalid roadmap " + key);
    }
}
