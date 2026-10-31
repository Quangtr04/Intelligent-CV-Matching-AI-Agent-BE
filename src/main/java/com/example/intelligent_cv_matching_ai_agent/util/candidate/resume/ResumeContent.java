package com.example.intelligent_cv_matching_ai_agent.util.candidate.resume;

import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.object;

public final class ResumeContent {

    private ResumeContent() {
    }

    public static String canonicalText(
        ObjectMapper mapper,
        Map<String, Object> resume) {
        // Reviewed structured content is authoritative; raw extraction is only used
        // before review or when no structure exists.
        Map<String, Object> data = object(resume.get("structured_data"));
        boolean reviewed = !Objects.toString(data.get("summary"), "").isBlank()
            || List.of("experience", "education", "projects", "certifications", "skills").stream()
                .anyMatch(k -> data.get(k) instanceof List<?> rows && !rows.isEmpty());
        String text = reviewed ? mapper.writeValueAsString(data) : Objects.toString(resume.get("raw_content"), "");

        return Objects.toString(resume.get("title"), "") + "\n" + text;
    }

    public static Map<String, Object> baseline(
        Map<String, Object> resume) {
        Map<String, Object> data = object(resume.get("structured_data"));
        List<String> strengths = new ArrayList<>(), issues = new ArrayList<>();
        List<Map<String, Object>> suggestions = new ArrayList<>();
        int score = 0;

        for (String key : List.of("summary", "experience", "education", "skills", "projects")) {
            Object value = data.get(key);
            boolean present =
                value instanceof List<?> list ? !list.isEmpty() : value instanceof String s && !s.isBlank();

            if (present) {
                score += 20;
                strengths.add("Có mục " + key);
            } else {
                issues.add("Thiếu mục " + key);
                suggestions.add(
                    Map.of(
                        "category",
                        key,
                        "severity",
                        "MEDIUM",
                        "explanation",
                        "Bổ sung thông tin thực tế cho mục " + key
                    )
                );
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("overall_score", score);
        result.put("ats_score", null);
        result.put(
            "summary",
            ("Kiểm tra mức độ đầy đủ của các mục CV và điểm ATS bằng quy tắc. Chưa đánh giá"
                + " chất lượng nội dung bằng AI hoặc độ phù hợp với vị trí tuyển dụng.")
        );
        result.put("strengths", strengths);
        result.put("issues", issues);
        result.put("suggestions", suggestions);

        return result;
    }

    private static String hash(
        String value) {
        try {
            return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    public static String contentHash(
        ObjectMapper mapper,
        String title,
        String language,
        String raw,
        Map<String, Object> data) {
        return hash(mapper.writeValueAsString(List.of(title, language, raw, stable(data))));
    }

    private static Object stable(
        Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach(
                (
                    key,
                    item) -> sorted.put(key.toString(), stable(item))
            );

            return sorted;
        }

        if (value instanceof List<?> list)
            return list.stream().map(ResumeContent::stable).toList();

        return value;
    }
}
