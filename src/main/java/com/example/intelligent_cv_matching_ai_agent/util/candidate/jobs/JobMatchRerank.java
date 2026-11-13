package com.example.intelligent_cv_matching_ai_agent.util.candidate.jobs;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.util.shared.FlowSupport;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.text;

/** Small independent model requests preserve source IDs without sending internal ranking state. */
public final class JobMatchRerank {

    private static final int BATCH_SIZE = 3;

    private JobMatchRerank() {
    }

    public static List<List<Map<String, Object>>> batches(
        List<Map<String, Object>> jobs) {
        if (jobs == null) {
            throw invalid("Missing retrieved jobs");
        }
        List<List<Map<String, Object>>> batches = new ArrayList<>();
        List<Map<String, Object>> current = new ArrayList<>();
        Set<UUID> seen = new LinkedHashSet<>();
        for (Map<String, Object> job : jobs) {
            if (job == null || !seen.add(id(job.get("id")))) {
                throw invalid("Retrieved jobs contain a missing or duplicate ID");
            }
            Map<String, Object> compact = new LinkedHashMap<>();
            compact.put("id", job.get("id"));
            compact.put("job_title", excerpt(job.get("job_title"), 255));
            compact.put("location", excerpt(job.get("location"), 255));
            compact.put("salary_text", excerpt(job.get("salary_text"), 255));
            compact.put("raw_content", excerpt(job.get("raw_content"), 1500));
            List<Map<String, Object>> skills = new ArrayList<>();
            if (job.get("skills") instanceof List<?> values) {
                for (Object value : values) {
                    if (skills.size() == 20) {
                        break;
                    }
                    if (value instanceof Map<?, ?> skill && !text(skill.get("name")).isBlank()) {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("name", excerpt(skill.get("name"), 120));
                        item.put("required", !Boolean.FALSE.equals(skill.get("required")));
                        skills.add(Collections.unmodifiableMap(item));
                    }
                }
            }
            compact.put("skills", List.copyOf(skills));
            compact.put(
                "requirement_extraction",
                com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.JobRequirementExtractor.forJob(job)
                    .requirements().stream().limit(20)
                    .map(
                        r -> Map.of(
                            "text",
                            excerpt(r.text(), 500),
                            "importance",
                            r.importance(),
                            "relation",
                            r.relation(),
                            "status",
                            r.status(),
                            "skills",
                            r.skills()
                        )
                    ).toList()
            );
            current.add(Collections.unmodifiableMap(compact));
            if (current.size() == BATCH_SIZE) {
                batches.add(List.copyOf(current));
                current.clear();
            }
        }
        if (!current.isEmpty()) {
            batches.add(List.copyOf(current));
        }
        return List.copyOf(batches);
    }

    public static Map<UUID, Map<String, Object>> validate(
        List<Map<String, Object>> batch,
        Map<String, Object> generated) {
        if (batch == null || generated == null || !(generated.get("results") instanceof List<?> rows)) {
            throw invalid("AI ranking must return a results array");
        }
        Set<UUID> expected = new LinkedHashSet<>();
        for (Map<String, Object> job : batch) {
            if (job == null || !expected.add(id(job.get("id")))) {
                throw invalid("Reranking batch contains a missing or duplicate ID");
            }
        }
        Map<UUID, Map<String, Object>> result = new LinkedHashMap<>();
        for (Object raw : rows) {
            if (!(raw instanceof Map<?, ?> value)) {
                throw invalid("AI ranking contains an invalid result");
            }
            UUID jobId = id(value.get("job_id"));
            if (!expected.contains(jobId)) {
                throw invalid("AI ranking returned a job outside its batch");
            }
            if (result.containsKey(jobId)) {
                throw invalid("AI returned duplicate job IDs");
            }
            FlowSupport.score(value.get("score"), 1, "match score");
            Map<String, Object> item = new LinkedHashMap<>();
            for (var entry : value.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw invalid("AI ranking contains an invalid result field");
                }
                item.put(key, entry.getValue());
            }
            result.put(jobId, item);
        }
        if (!result.keySet().equals(expected)) {
            throw invalid("AI ranking omitted a retrieved job");
        }
        return result;
    }

    private static UUID id(
        Object value) {
        try {
            return UUID.fromString(text(value));
        } catch (IllegalArgumentException ex) {
            throw invalid("AI ranking contains an invalid job ID");
        }
    }

    private static String excerpt(
        Object value,
        int maximum) {
        String source = text(value);
        if (source.length() <= maximum) {
            return source;
        }
        int end = maximum;
        if (Character.isHighSurrogate(source.charAt(end - 1))) {
            end--;
        }
        return source.substring(0, end);
    }

    private static ApiException invalid(
        String message) {
        return new ApiException(502, message);
    }
}
