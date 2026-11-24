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

    public static List<List<Map<String, Object>>> recommendationBatches(
        List<Map<String, Object>> jobs) {
        List<List<Map<String, Object>>> result = new ArrayList<>();
        List<Map<String, Object>> current = new ArrayList<>();
        Set<UUID> seen = new LinkedHashSet<>();
        for (var job : jobs) {
            if (!seen.add(id(job.get("id"))))
                throw invalid("Retrieved jobs contain duplicate IDs");
            var extraction =
                com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.JobRequirementExtractor.forJob(job);
            var compact = new LinkedHashMap<String, Object>();
            compact.put("id", job.get("id"));
            compact.put("job_title", excerpt(job.get("job_title"), 255));
            StringBuilder evidence = new StringBuilder(text(compact.get("job_title")));
            compact.put(
                "skills",
                com.example.intelligent_cv_matching_ai_agent.util.shared.Values.array(job.get("skills")).stream()
                    .limit(16).map(raw -> {
                        var skill = com.example.intelligent_cv_matching_ai_agent.util.shared.Values.object(raw);
                        return com.example.intelligent_cv_matching_ai_agent.util.shared.Values.fields(
                            "name",
                            excerpt(skill.get("name"), 100),
                            "required",
                            !Boolean.FALSE.equals(skill.get("required"))
                        );
                    }).toList()
            );
            List<Map<String, Object>> requirements = new ArrayList<>();
            int clause = 0;
            for (var requirement : extraction.requirements().stream().limit(12).toList()) {
                String reference = "R" + ++clause;
                evidence.append('\n').append(reference).append(": ").append(excerpt(requirement.text(), 220));
                requirements.add(
                    com.example.intelligent_cv_matching_ai_agent.util.shared.Values.fields(
                        "clause_id",
                        reference,
                        "importance",
                        requirement.importance(),
                        "relation",
                        requirement.relation(),
                        "skills",
                        requirement.skills().stream().map(skill -> skill.name()).distinct().limit(10).toList()
                    )
                );
            }
            if (requirements.isEmpty()) {
                String raw = text(job.get("parsed_requirements"));
                if (raw.isBlank())
                    raw = text(job.get("raw_content"));
                evidence.append('\n').append(excerpt(org.jsoup.Jsoup.parse(raw).text(), 2200));
            }
            evidence.append('\n')
                .append(excerpt(org.jsoup.Jsoup.parse(text(job.get("parsed_description"))).text(), 500));
            evidence.append('\n')
                .append(excerpt(org.jsoup.Jsoup.parse(text(job.get("parsed_responsibilities"))).text(), 400));
            compact.put("jd_evidence", excerpt(evidence, 4000));
            compact.put("jd_truncated", extraction.requirements().size() > 12 || evidence.length() > 4000);
            compact.put("requirement_extraction", requirements);
            var rules =
                com.example.intelligent_cv_matching_ai_agent.util.shared.Values.object(job.get("rule_assessment"));
            var compactRules = new LinkedHashMap<String, Object>();
            for (String key : List.of(
                "requirement_coverage",
                "skill_coverage",
                "experience_status",
                "candidate_years",
                "required_years",
                "preferred_coverage",
                "unverified_requirements",
                "education_assessment",
                "seniority_assessment"
            ))
                compactRules.put(key, rules.get(key));
            compact.put("rule_assessment", compactRules);
            current.add(compact);
            if (current.size() == BATCH_SIZE) {
                result.add(List.copyOf(current));
                current.clear();
            }
        }
        if (!current.isEmpty())
            result.add(List.copyOf(current));
        return List.copyOf(result);
    }

    public static Map<UUID, Map<String, Object>> validateRecommendations(
        List<Map<String, Object>> batch,
        Map<String, Object> generated,
        String cvEvidence) {
        var results = validate(batch, generated);
        Map<Object, Map<String, Object>> jobs = new LinkedHashMap<>();
        batch.forEach(job -> jobs.put(job.get("id"), job));
        for (var entry : results.entrySet()) {
            var row = entry.getValue();
            if (!"SUFFICIENT_EVIDENCE".equals(row.get("assessment_status"))) {
                row.put("usable", false);
                row.put(
                    "validation_status",
                    "INSUFFICIENT_EVIDENCE".equals(row.get("assessment_status"))
                        ? "INSUFFICIENT_EVIDENCE"
                        : "INVALID_RESPONSE"
                );
                continue;
            }
            boolean valid =
                row.get("evidence") instanceof List<?> evidence && !evidence.isEmpty() && evidence.size() <= 3;
            var job = jobs.get(entry.getKey());
            String jd = text(job.get("jd_evidence"));
            if (jd.isBlank())
                jd = text(job.get("job_title")) + "\n" + text(job.get("raw_content")) + "\n"
                    + job.get("requirement_extraction");
            for (Object value : com.example.intelligent_cv_matching_ai_agent.util.shared.Values
                .array(row.get("evidence"))) {
                var citation = com.example.intelligent_cv_matching_ai_agent.util.shared.Values.object(value);
                String cvQuote = text(citation.get("cv_quote")), jdQuote = text(citation.get("jd_quote"));
                valid &= containsQuote(cvEvidence, cvQuote) && containsQuote(jd, jdQuote);
            }
            row.put("usable", valid);
            row.put("validation_status", valid ? "SCORED" : "INVALID_EVIDENCE");
        }
        return results;
    }

    private static boolean containsQuote(
        String source,
        String quote) {
        if (quote.isBlank() || quote.length() > 400)
            return false;
        String normalizedSource = normalizeQuote(source), normalizedQuote = normalizeQuote(quote);
        return !normalizedQuote.isBlank() && normalizedSource.contains(normalizedQuote);
    }

    private static String normalizeQuote(
        String value) {
        return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFC).replaceAll("[\\s\\p{Z}]+", " ")
            .strip();
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
