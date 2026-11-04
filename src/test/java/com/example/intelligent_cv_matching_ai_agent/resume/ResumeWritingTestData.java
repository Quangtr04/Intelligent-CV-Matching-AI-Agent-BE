package com.example.intelligent_cv_matching_ai_agent.resume;

import com.example.intelligent_cv_matching_ai_agent.util.candidate.resume.ResumeWritingQuality;
import com.example.intelligent_cv_matching_ai_agent.util.candidate.resume.ResumeContentQuality;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Synthetic provider response shared by assessment integration tests. */
public final class ResumeWritingTestData {

    private ResumeWritingTestData() {
    }

    public static Map<String, Object> writing() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rubric_version", ResumeWritingQuality.RUBRIC_VERSION);
        result.put("criteria", ResumeWritingQuality.CODES.stream().map(code -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", code);
            row.put("status", "ASSESSED");
            row.put("score", 80);
            row.put("feedback", "Review this criterion against verified CV facts.");
            row.put("evidence", List.of());
            return row;
        }).toList());
        result.put("limitations", List.of("Synthetic text-only test assessment."));
        return result;
    }

    public static Map<String, Object> report() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content_score", 85);
        result.put("ats_score", 82);
        result.put("summary", "Review writing quality using verified candidate facts.");
        result.put("strengths", List.of("Relevant experience"));
        result.put("issues", List.of("Some results need clearer evidence"));
        result.put("suggestions", List.of());
        result.put("content_quality", content(85, "Reviewed Java experience"));
        result.put("writing_quality", writing());
        result.put("_model", "api-test");
        return result;
    }

    public static Map<String, Object> content(
        int score,
        String evidence) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rubric_version", ResumeContentQuality.RUBRIC_VERSION);
        result.put("criteria", ResumeContentQuality.CODES.stream().map(code -> {
            boolean assessed = "CONTENT_COMPLETENESS".equals(code);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", code);
            row.put("status", assessed ? "ASSESSED" : "INSUFFICIENT_EVIDENCE");
            row.put("score", assessed ? score : null);
            row.put("feedback", assessed ? "Evaluate the supplied reviewed CV text." : "Not assessed in this fixture.");
            row.put("evidence", assessed ? List.of(evidence) : List.of());
            return row;
        }).toList());
        return result;
    }
}
