package com.example.intelligent_cv_matching_ai_agent.util.shared.ai;

import java.util.List;
import java.util.Map;

/** Keeps generated CV commentary in the supported response languages; source quotations remain verbatim. */
public final class CvAnalysisLanguage {

    public static final String VERSION = "cv-language-v1";

    // Keep the native grammar JSON-safe: source quotes use their own verbatim enums.
    private static final String TEXT_CHARACTERS =
        "[A-Za-z0-9\u00a0-\u024f\u1e00-\u1eff\u2000-\u22ff\u2500-\u27bf .,;:!?()/&%+@#'_=*\\-\\[\\]]";

    private CvAnalysisLanguage() {
    }

    public static String textPattern(
        int minimum,
        int maximum) {
        // The model's schema converter handles pattern bounds separately from minLength/maxLength.
        return "^" + TEXT_CHARACTERS + "{" + minimum + "," + maximum + "}$";
    }

    public static String instructions(
        String language) {
        return "en".equals(language)
            ? "MANDATORY RESPONSE LANGUAGE: Write the summary, strengths, issues, criterion feedback, limitations, "
                + "suggestion explanations and suggested_content in English only. Do not write Chinese. "
                + "Keep JSON keys, criterion codes, original_content and evidence quotations unchanged."
            : "YÊU CẦU NGÔN NGỮ BẮT BUỘC: Viết phần tóm tắt, điểm mạnh, vấn đề, nhận xét từng tiêu chí, "
                + "hạn chế, giải thích đề xuất và suggested_content hoàn toàn bằng tiếng Việt. Không viết tiếng Trung. "
                + "Giữ nguyên khóa JSON, mã tiêu chí, original_content và các đoạn trích dẫn evidence từ CV.";
    }

    public static void validate(
        Map<String, Object> result) {
        if (!isValid(result)) {
            throw new IllegalArgumentException("CV analysis contains Chinese text in generated commentary");
        }
    }

    public static boolean isValid(
        Map<String, Object> result) {
        if (containsHan(result.get("summary"))) {
            return false;
        }
        for (String field : List.of("strengths", "issues", "strengths_json", "issues_json")) {
            if (containsHan(result.get(field))) {
                return false;
            }
        }
        for (String field : List
            .of("content_quality", "writing_quality", "content_quality_json", "writing_quality_json")) {
            if (result.get(field) instanceof Map<?, ?> quality) {
                if (containsHan(quality.get("limitations"))) {
                    return false;
                }
                if (quality.get("criteria") instanceof List<?> criteria) {
                    for (Object item : criteria) {
                        if (item instanceof Map<?, ?> row && containsHan(row.get("feedback"))) {
                            return false;
                        }
                    }
                }
            }
        }
        if (result.get("suggestions") instanceof List<?> suggestions) {
            for (Object item : suggestions) {
                if (item instanceof Map<?, ?> row) {
                    for (String field : List.of("category", "explanation", "suggested_content")) {
                        if (containsHan(row.get(field))) {
                            return false;
                        }
                    }
                }
            }
        }
        return true;
    }

    private static boolean containsHan(
        Object value) {
        if (value instanceof String text) {
            return text.codePoints()
                .anyMatch(point -> Character.UnicodeScript.of(point) == Character.UnicodeScript.HAN);
        }
        if (value instanceof List<?> rows) {
            return rows.stream().anyMatch(CvAnalysisLanguage::containsHan);
        }
        return false;
    }
}
