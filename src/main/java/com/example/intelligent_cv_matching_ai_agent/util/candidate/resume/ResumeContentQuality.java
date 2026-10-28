
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

public final class ResumeContentQuality {

    public static final String RUBRIC_VERSION = "cv-content-v1";

    public static final List<String> CODES = List.of(
        "SKILL_RELEVANCE",
        "EXPERIENCE_DEPTH",
        "PROJECT_COMPLEXITY",
        "ACHIEVEMENT_IMPACT",
        "EDUCATION_RELEVANCE",
        "CONTENT_COMPLETENESS"
    );

    private ResumeContentQuality() {
    }

    public static Map<String, Object> normalize(
        Map<String, Object> generated,
        Map<String, Object> resume) {
        try {
            Map<String, Object> incoming = object(generated.get("content_quality"));

            if (!RUBRIC_VERSION.equals(incoming.get("rubric_version"))) {
                throw invalid("Unsupported content rubric version");
            }

            Object rawCriteria = incoming.get("criteria");

            if (!(rawCriteria instanceof List<?> supplied) || supplied.size() != CODES.size()) {
                throw invalid("Content quality must contain exactly six criteria");
            }

            List<String> sourceText = new ArrayList<>();

            Map<String, Object> data = object(resume.get("structured_data"));

            boolean reviewed = !Objects.toString(data.get("summary"), "").isBlank()
                || List.of("experience", "education", "projects", "certifications", "skills").stream()
                    .anyMatch(key -> data.get(key) instanceof List<?> rows && !rows.isEmpty());

            if (reviewed) {
                collectText(data, sourceText);
            } else {
                sourceText.add(Objects.toString(resume.get("raw_content"), ""));
            }

            sourceText.replaceAll(ResumeContentQuality::searchText);

            List<Map<String, Object>> criteria = new ArrayList<>();

            BigDecimal total = BigDecimal.ZERO;
            int assessed = 0;

            for (String code : CODES) {

                Map<String, Object> suppliedRow = null;

                for (Object item : supplied) {
                    Map<String, Object> candidate = object(item);

                    if (code.equals(candidate.get("code"))) {
                        if (suppliedRow != null) {
                            throw invalid("Duplicated content criterion: " + code);
                        }

                        suppliedRow = candidate;
                    }
                }

                if (suppliedRow == null) {
                    throw invalid("Missing content criterion: " + code);
                }

                String status = Objects.toString(suppliedRow.get("status"), "");

                if (!List.of("ASSESSED", "NOT_APPLICABLE", "INSUFFICIENT_EVIDENCE").contains(status)) {
                    throw invalid("Invalid status for " + code);
                }

                Object rawScore = suppliedRow.get("score");

                BigDecimal criterionScore = null;

                if ("ASSESSED".equals(status)) {
                    criterionScore = score(rawScore, code);
                    total = total.add(criterionScore);
                    assessed++;
                } else if (rawScore != null) {
                    throw invalid("Unassessed criterion score must be null: " + code);
                }

                String feedback = Objects.toString(suppliedRow.get("feedback"), "");

                if (feedback.isBlank() || feedback.length() > 4000) {
                    throw invalid("Invalid feedback for " + code);
                }

                Object rawEvidence = suppliedRow.get("evidence");

                if (!(rawEvidence instanceof List<?> evidence) || evidence.size() > 8) {
                    throw invalid("Invalid evidence for " + code);
                }

                List<String> verifiedEvidence = new ArrayList<>();

                for (Object value : evidence) {

                    if (!(value instanceof String quote) || quote.isBlank() || quote.length() > 2000) {
                        throw invalid("Invalid evidence text for " + code);
                    }

                    String normalizedQuote = searchText(quote);

                    boolean exists = sourceText.stream().anyMatch(text -> text.contains(normalizedQuote));

                    if (!exists) {
                        throw invalid("Content evidence is not a quote " + "from reviewed CV: " + code);
                    }

                    verifiedEvidence.add(quote);
                }

                if ("ASSESSED".equals(status) && verifiedEvidence.isEmpty()) {
                    throw invalid("Assessed criterion requires evidence: " + code);
                }

                Map<String, Object> row = new LinkedHashMap<>();
                row.put("code", code);
                row.put("status", status);
                row.put("score", criterionScore);
                row.put("feedback", feedback);
                row.put("evidence", verifiedEvidence);

                criteria.add(row);
            }

            BigDecimal contentScore =
                assessed == 0 ? null : total.divide(BigDecimal.valueOf(assessed), 2, RoundingMode.HALF_UP);

            if (contentScore == null) {
                throw invalid("No content criteria were assessed");
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("rubric_version", RUBRIC_VERSION);
            result.put("criteria", criteria);
            result.put("score", contentScore);
            result.put("assessed_criteria", assessed);

            return result;

        } catch (IllegalArgumentException ex) {
            throw new ApiException(502, "AI CV content assessment is invalid: " + ex.getMessage());
        }
    }

    private static BigDecimal score(
        Object value,
        String field) {
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
            throw invalid(field + " score must be a finite number");
        }

        BigDecimal result = new BigDecimal(number.toString());

        if (result.signum() < 0 || result.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw invalid(field + " score must be between 0 and 100");
        }

        return result.setScale(2, RoundingMode.HALF_UP);
    }

    private static void collectText(
        Object value,
        List<String> target) {
        if (value instanceof String text) {
            target.add(text);
        } else if (value instanceof Map<?, ?> map) {
            map.values().forEach(item -> collectText(item, target));
        } else if (value instanceof List<?> list) {
            list.forEach(item -> collectText(item, target));
        }
    }

    private static String searchText(
        String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFC).replaceAll("\\s+", " ").strip();
    }

    private static IllegalArgumentException invalid(
        String message) {
        return new IllegalArgumentException(message);
    }
}
