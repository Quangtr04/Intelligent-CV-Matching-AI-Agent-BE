package com.example.intelligent_cv_matching_ai_agent.util.consultant.consultation;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.json.JsonMapper;

/** Bounded, typed advice content; server-owned author and timestamp fields are never accepted from clients. */
public final class ConsultationAdviceContent {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ConsultationAdviceContent() {
    }

    public static Map<String, Object> normalize(
        Map<String, Object> input,
        boolean publishing) {
        Map<String, Object> root = object(
            input,
            "advice",
            Set.of(
                "cvAdvice",
                "careerAdvice",
                "salaryAdvice",
                "interviewAdvice",
                "applicationAdvice",
                "lastUpdated",
                "consultantName"
            )
        );
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> cv = object(
            root.get("cvAdvice"),
            "cvAdvice",
            Set.of("summary", "formattingTips", "starImprovements", "missingKeywordsToInject", "actionItems")
        );
        Map<String, Object> checkedCv = new LinkedHashMap<>();
        checkedCv.put("summary", text(cv.get("summary"), "cvAdvice.summary", 10000));
        strings(cv, checkedCv, "formattingTips", "missingKeywordsToInject", "actionItems");
        checkedCv.put("starImprovements", stars(cv.get("starImprovements")));
        result.put("cvAdvice", checkedCv);

        Map<String, Object> career = object(
            root.get("careerAdvice"),
            "careerAdvice",
            Set.of(
                "currentLevel",
                "targetLevel",
                "recommendedPositioning",
                "shortTermRoadmap",
                "longTermRoadmap",
                "strategicRecommendations"
            )
        );
        Map<String, Object> checkedCareer = new LinkedHashMap<>();
        for (String key : List.of("currentLevel", "targetLevel", "recommendedPositioning")) {
            checkedCareer.put(key, text(career.get(key), "careerAdvice." + key, 10000));
        }
        strings(career, checkedCareer, "shortTermRoadmap", "longTermRoadmap", "strategicRecommendations");
        result.put("careerAdvice", checkedCareer);

        if (root.get("salaryAdvice") != null) {
            result.put("salaryAdvice", salary(root.get("salaryAdvice")));
        }
        if (root.get("interviewAdvice") != null) {
            result.put("interviewAdvice", interview(root.get("interviewAdvice")));
        }
        if (root.get("applicationAdvice") != null) {
            Map<String, Object> application = object(
                root.get("applicationAdvice"),
                "applicationAdvice",
                Set.of("summary", "coverLetterTips", "emailTips", "applicationChecklist")
            );
            Map<String, Object> checked = new LinkedHashMap<>();
            checked.put("summary", text(application.get("summary"), "applicationAdvice.summary", 10000));
            strings(application, checked, "coverLetterTips", "emailTips", "applicationChecklist");
            result.put("applicationAdvice", checked);
        }
        if (publishing) {
            requireText(checkedCv.get("summary"), "A published report needs a CV advice summary");
            requireText(
                checkedCareer.get("recommendedPositioning"),
                "A published report needs career direction advice"
            );
            Object application = result.get("applicationAdvice");
            if (!(application instanceof Map<?, ?> map)) {
                throw ApiException.badRequest("A published report needs job application advice");
            }
            requireText(map.get("summary"), "A published report needs job application advice");
        }
        if (JSON.writeValueAsString(result).length() > 100000) {
            throw ApiException.badRequest("Advice content must not exceed 100000 characters");
        }
        return result;
    }

    private static Map<String, Object> salary(
        Object value) {
        Map<String, Object> source = object(
            value,
            "salaryAdvice",
            Set.of(
                "minSalary",
                "targetSalary",
                "maxSalary",
                "currency",
                "marketPercentile",
                "negotiationTactics",
                "compensationPackageTips",
                "sampleScript"
            )
        );
        Map<String, Object> checked = new LinkedHashMap<>();
        BigDecimal min = number(source.get("minSalary"), "minSalary", new BigDecimal("1000000000000000"));
        BigDecimal target = number(source.get("targetSalary"), "targetSalary", new BigDecimal("1000000000000000"));
        BigDecimal max = number(source.get("maxSalary"), "maxSalary", new BigDecimal("1000000000000000"));
        if (min.compareTo(target) > 0 || target.compareTo(max) > 0) {
            throw ApiException.badRequest("Salary advice must satisfy minSalary <= targetSalary <= maxSalary");
        }
        checked.put("minSalary", min);
        checked.put("targetSalary", target);
        checked.put("maxSalary", max);
        String currency = text(source.get("currency"), "currency", 3);
        if (!currency.isEmpty() && !currency.matches("[A-Z]{3}")) {
            throw ApiException.badRequest("Salary advice currency must use a three-letter uppercase code");
        }
        checked.put("currency", currency);
        checked.put("marketPercentile", text(source.get("marketPercentile"), "marketPercentile", 200));
        checked.put("sampleScript", text(source.get("sampleScript"), "sampleScript", 10000));
        strings(source, checked, "negotiationTactics", "compensationPackageTips");
        return checked;
    }

    private static Map<String, Object> interview(
        Object value) {
        Map<String, Object> source = object(
            value,
            "interviewAdvice",
            Set.of(
                "overallReadiness",
                "behavioralTips",
                "technicalTips",
                "mockInterviewFeedback",
                "reverseQuestions",
                "pitfallsToAvoid"
            )
        );
        Map<String, Object> checked = new LinkedHashMap<>();
        checked.put("overallReadiness", number(source.get("overallReadiness"), "overallReadiness", BigDecimal.TEN));
        strings(
            source,
            checked,
            "behavioralTips",
            "technicalTips",
            "mockInterviewFeedback",
            "reverseQuestions",
            "pitfallsToAvoid"
        );
        return checked;
    }

    private static List<Map<String, Object>> stars(
        Object value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list) || list.size() > 30) {
            throw ApiException.badRequest("starImprovements must contain at most 30 items");
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object entry : list) {
            Map<String, Object> source =
                object(entry, "starImprovements item", Set.of("id", "before", "afterStar", "note"));
            Map<String, Object> checked = new LinkedHashMap<>();
            checked.put("id", text(source.get("id"), "starImprovements.id", 100));
            checked.put("before", text(source.get("before"), "starImprovements.before", 5000));
            checked.put("afterStar", text(source.get("afterStar"), "starImprovements.afterStar", 5000));
            checked.put("note", text(source.get("note"), "starImprovements.note", 2000));
            requireText(checked.get("before"), "A STAR improvement needs the original text");
            requireText(checked.get("afterStar"), "A STAR improvement needs the proposed text");
            result.add(checked);
        }
        return result;
    }

    private static Map<String, Object> object(
        Object value,
        String field,
        Set<String> allowed) {
        if (!(value instanceof Map<?, ?> map)) {
            throw ApiException.badRequest(field + " must be an object");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key) || !allowed.contains(key)) {
                throw ApiException.badRequest(field + " contains an unsupported field");
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static void strings(
        Map<String, Object> source,
        Map<String, Object> target,
        String... keys) {
        for (String key : keys) {
            Object value = source.get(key);
            if (value == null) {
                target.put(key, List.of());
                continue;
            }
            if (!(value instanceof List<?> list) || list.size() > 50) {
                throw ApiException.badRequest(key + " must contain at most 50 text items");
            }
            List<String> checked = new ArrayList<>();
            for (Object item : list) {
                if (!(item instanceof String)) {
                    throw ApiException.badRequest(key + " items must be text");
                }
                String text = text(item, key, 2000);
                if (!text.isEmpty()) {
                    checked.add(text);
                }
            }
            target.put(key, checked);
        }
    }

    private static String text(
        Object value,
        String field,
        int limit) {
        if (value == null) {
            return "";
        }
        if (!(value instanceof String text) || text.length() > limit) {
            throw ApiException.badRequest(field + " must be text of at most " + limit + " characters");
        }
        return text.strip();
    }

    private static BigDecimal number(
        Object value,
        String field,
        BigDecimal max) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (!(value instanceof Number)) {
            throw ApiException.badRequest(field + " must be a number");
        }
        try {
            BigDecimal result = new BigDecimal(value.toString());
            if (result.signum() < 0 || result.compareTo(max) > 0) {
                throw ApiException.badRequest(field + " is outside the allowed range");
            }
            return result;
        } catch (NumberFormatException failure) {
            throw ApiException.badRequest(field + " must be a finite number");
        }
    }

    private static void requireText(
        Object value,
        String message) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw ApiException.badRequest(message);
        }
    }
}
