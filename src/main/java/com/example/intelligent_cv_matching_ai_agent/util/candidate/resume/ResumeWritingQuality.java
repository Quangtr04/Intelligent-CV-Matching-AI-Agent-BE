package com.example.intelligent_cv_matching_ai_agent.util.candidate.resume;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.object;

/** Versioned, evidence-grounded writing rubric. Model-provided aggregate scores are never trusted. */
public final class ResumeWritingQuality {

    public static final String RUBRIC_VERSION = "cv-writing-v1";

    public static final List<String> CODES = List.of(
        "BULLET_STRUCTURE",
        "PUNCTUATION_CONSISTENCY",
        "PROFESSIONAL_WORDING",
        "CONCISENESS",
        "ACHIEVEMENT_CLARITY"
    );

    private static final BigDecimal CONTENT_WEIGHT = new BigDecimal("0.80");

    private static final BigDecimal WRITING_WEIGHT = new BigDecimal("0.20");

    private ResumeWritingQuality() {
    }

    public static void validateModelResponse(
        Map<String, Object> result) {
        score(result.get("content_score"), "content_score");
        score(result.get("ats_score"), "ats_score");
        text(result.get("summary"), "summary", 100000);
        strings(result.get("strengths"), "strengths", 100, 100000);
        strings(result.get("issues"), "issues", 100, 100000);
        if (!(result.get("suggestions") instanceof List<?> suggestions) || suggestions.size() > 100)
            throw invalid("suggestions must be an array with at most 100 items");
        for (Object suggestion : suggestions) {
            if (!(suggestion instanceof Map<?, ?> row))
                throw invalid("CV suggestion must be an object");
            for (String field : List
                .of("category", "severity", "original_content", "suggested_content", "explanation")) {
                int maximum = "category".equals(field) ? 100 : "severity".equals(field) ? 30 : 100000;
                if (row.get(field) != null && (!(row.get(field) instanceof String value) || value.length() > maximum))
                    throw invalid("CV suggestion " + field + " must be text up to " + maximum + " characters");
            }
        }
        if (!(result.get("writing_quality") instanceof Map<?, ?> quality))
            throw invalid("writing_quality must be an object");
        if (!RUBRIC_VERSION.equals(quality.get("rubric_version")))
            throw invalid("writing_quality rubric_version is unsupported");
        if (!(quality.get("criteria") instanceof List<?> criteria) || criteria.size() != CODES.size())
            throw invalid("writing_quality must contain exactly five criteria");
        List<String> seen = new ArrayList<>();
        for (Object item : criteria) {
            if (!(item instanceof Map<?, ?> criterion))
                throw invalid("writing criterion must be an object");
            Object code = criterion.get("code");
            if (!(code instanceof String name) || !CODES.contains(name) || seen.contains(name))
                throw invalid("writing criterion code is unknown or duplicated");
            seen.add(name);
            Object status = criterion.get("status");
            if (!(status instanceof String)
                || !List.of("ASSESSED", "NOT_APPLICABLE", "INSUFFICIENT_EVIDENCE").contains(status))
                throw invalid("writing criterion status is invalid");
            if ("ASSESSED".equals(status))
                score(criterion.get("score"), "writing criterion score");
            else if (!criterion.containsKey("score") || criterion.get("score") != null)
                throw invalid("unassessed writing criterion score must be null");
            text(criterion.get("feedback"), "writing criterion feedback", 4000);
            strings(criterion.get("evidence"), "writing criterion evidence", 8, 2000);
        }
        strings(quality.get("limitations"), "writing limitations", 20, 2000);
    }

    public static Map<String, Object> source(
        Map<String, Object> resume) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("title", Objects.toString(resume.get("title"), ""));
        result.put("language", Objects.toString(resume.get("language"), "vi"));
        result.put("source", Objects.toString(resume.get("source"), ""));
        result.put("structured_data", object(resume.get("structured_data")));
        result.put("raw_text", Objects.toString(resume.get("raw_content"), ""));
        result.put("content_authority", reviewed(resume) ? "REVIEWED_STRUCTURED" : "RAW_TEXT");
        result.put("formatting_limitations", sourceLimitations(resume));
        return result;
    }

    public static Map<String, Object> normalize(
        Map<String, Object> generated,
        Map<String, Object> resume) {
        try {
            validateModelResponse(generated);
            Map<String, Object> result = new LinkedHashMap<>(generated);
            Map<String, Object> incomingQuality = object(generated.get("writing_quality"));
            Map<String, Object> quality = new LinkedHashMap<>();
            quality.put("rubric_version", RUBRIC_VERSION);
            List<?> supplied = (List<?>) incomingQuality.get("criteria");
            List<Map<String, Object>> criteria = new ArrayList<>();
            BigDecimal total = BigDecimal.ZERO;
            int assessed = 0;
            List<String> sourceText = authoritativeText(resume).stream().map(ResumeWritingQuality::searchText).toList();
            for (String code : CODES) {
                Map<String, Object> suppliedRow = object(
                    supplied.stream().filter(value -> code.equals(object(value).get("code"))).findFirst().orElseThrow()
                );
                Map<String, Object> row = new LinkedHashMap<>();
                for (String field : List.of("code", "status", "score", "feedback", "evidence"))
                    row.put(field, suppliedRow.get(field));
                for (Object evidence : (List<?>) row.get("evidence")) {
                    String quoted = searchText(evidence.toString());
                    if (sourceText.stream().noneMatch(value -> value.contains(quoted)))
                        throw invalid("writing evidence is not a quote from reviewed CV content");
                }
                if ("ASSESSED".equals(row.get("status"))) {
                    BigDecimal value = score(row.get("score"), "writing criterion score");
                    row.put("score", value);
                    total = total.add(value);
                    assessed++;
                }
                criteria.add(row);
            }
            List<Map<String, Object>> suggestions = new ArrayList<>();
            for (Object item : (List<?>) generated.get("suggestions")) {
                Map<String, Object> incoming = object(item);
                Map<String, Object> suggestion = new LinkedHashMap<>();
                for (String field : List
                    .of("category", "severity", "original_content", "suggested_content", "explanation"))
                    if (incoming.get(field) != null)
                        suggestion.put(field, incoming.get(field));
                String original = Objects.toString(suggestion.get("original_content"), "");
                if (!original.isBlank() && sourceText.stream().noneMatch(value -> value.contains(searchText(original))))
                    throw invalid("suggestion original_content is not a quote from reviewed CV content");
                suggestions.add(suggestion);
            }
            BigDecimal content = score(generated.get("content_score"), "content_score");
            BigDecimal writing =
                assessed == 0 ? null : total.divide(BigDecimal.valueOf(assessed), 2, RoundingMode.HALF_UP);
            BigDecimal contentWeight = assessed == 0 ? BigDecimal.ONE : CONTENT_WEIGHT;
            BigDecimal writingWeight = assessed == 0 ? BigDecimal.ZERO : WRITING_WEIGHT;
            BigDecimal overall =
                content.multiply(contentWeight).add(writing == null ? BigDecimal.ZERO : writing.multiply(writingWeight))
                    .setScale(2, RoundingMode.HALF_UP);
            List<String> limitations =
                new ArrayList<>(strings(incomingQuality.get("limitations"), "writing limitations", 20, 2000));
            for (String limitation : sourceLimitations(resume))
                if (!limitations.contains(limitation))
                    limitations.add(limitation);
            quality.put("criteria", criteria);
            quality.put("score", writing);
            quality.put("assessed_criteria", assessed);
            quality.put("content_score", content);
            quality.put("content_weight", contentWeight);
            quality.put("writing_weight", writingWeight);
            quality.put("limitations", limitations);
            result.put("content_score", content);
            result.put("writing_quality", quality);
            result.put("overall_score", overall);
            result.put("suggestions", suggestions);
            return result;
        } catch (IllegalArgumentException ex) {
            throw new ApiException(502, "AI CV writing assessment is invalid: " + ex.getMessage());
        }
    }

    public static Map<String, Object> expose(
        Map<String, Object> analysis) {
        Map<String, Object> result = new LinkedHashMap<>(analysis);
        Object quality = analysis.get("writing_quality_json");
        result.put("writing_quality", quality instanceof Map<?, ?> ? quality : null);
        result.put("content_score", quality instanceof Map<?, ?> map ? map.get("content_score") : null);
        return result;
    }

    private static boolean reviewed(
        Map<String, Object> resume) {
        Map<String, Object> data = object(resume.get("structured_data"));
        return !Objects.toString(data.get("summary"), "").isBlank()
            || List.of("experience", "education", "projects", "certifications", "skills").stream()
                .anyMatch(key -> data.get(key) instanceof List<?> rows && !rows.isEmpty());
    }

    private static List<String> authoritativeText(
        Map<String, Object> resume) {
        List<String> result = new ArrayList<>();
        if (reviewed(resume))
            collectText(object(resume.get("structured_data")), result);
        else
            result.add(Objects.toString(resume.get("raw_content"), ""));
        return result;
    }

    private static void collectText(
        Object value,
        List<String> target) {
        if (value instanceof String text)
            target.add(text);
        else if (value instanceof Map<?, ?> map)
            map.values().forEach(item -> collectText(item, target));
        else if (value instanceof List<?> list)
            list.forEach(item -> collectText(item, target));
    }

    private static List<String> sourceLimitations(
        Map<String, Object> resume) {
        List<String> result = new ArrayList<>();
        result.add(
            "Text-only assessment cannot verify fonts, visual alignment, rendered bullet indentation "
                + "or PDF layout."
        );
        if (reviewed(resume) && !Objects.toString(resume.get("raw_content"), "").isBlank())
            result.add(
                "Reviewed structured content is authoritative; original raw extraction may differ "
                    + "and cannot override reviewed wording."
            );
        return result;
    }

    private static String searchText(
        String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFC).replaceAll("(?U)\\s+", " ").strip();
    }

    private static BigDecimal score(
        Object value,
        String field) {
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue()))
            throw invalid(field + " must be a finite number");
        BigDecimal score = new BigDecimal(number.toString());
        if (score.signum() < 0 || score.compareTo(BigDecimal.valueOf(100)) > 0)
            throw invalid(field + " must be between 0 and 100");
        return score.setScale(2, RoundingMode.HALF_UP);
    }

    private static String text(
        Object value,
        String field,
        int maximum) {
        if (!(value instanceof String text) || text.isBlank() || text.length() > maximum)
            throw invalid(field + " must be nonblank text up to " + maximum + " characters");
        return text;
    }

    private static List<String> strings(
        Object value,
        String field,
        int maximumItems,
        int maximumLength) {
        if (!(value instanceof List<?> list) || list.size() > maximumItems)
            throw invalid(field + " must be an array with at most " + maximumItems + " items");
        List<String> result = new ArrayList<>();
        for (Object item : list)
            result.add(text(item, field, maximumLength));
        return result;
    }

    private static IllegalArgumentException invalid(
        String message) {
        return new IllegalArgumentException(message);
    }
}
