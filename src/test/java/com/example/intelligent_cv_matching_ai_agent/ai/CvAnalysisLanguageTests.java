package com.example.intelligent_cv_matching_ai_agent.ai;

import com.example.intelligent_cv_matching_ai_agent.util.shared.ai.AiResponseData;
import com.example.intelligent_cv_matching_ai_agent.util.shared.ai.CvAnalysisLanguage;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class CvAnalysisLanguageTests {

    @ParameterizedTest
    @ValueSource(
        strings = {"summary", "strengths", "issues", "explanation", "suggested_content", "feedback", "limitations"}
    )
    void rejectsChineseCommentaryEvenWhenTheResultIsOtherwiseValid(
        String field) {
        String chinese = "增加项目细节有助于更全面地展示候选人的能力。";
        Map<String, Object> result = switch (field) {
            case "summary" -> Map.of(field, chinese);
            case "strengths", "issues" -> Map.of(field, List.of(chinese));
            case "explanation", "suggested_content" -> Map.of("suggestions", List.of(Map.of(field, chinese)));
            case "feedback" -> Map.of("content_quality", Map.of("criteria", List.of(Map.of(field, chinese))));
            default -> Map.of("writing_quality", Map.of(field, List.of(chinese)));
        };
        assertFalse(CvAnalysisLanguage.isValid(result));
        assertThrows(IllegalArgumentException.class, () -> CvAnalysisLanguage.validate(result));
    }

    @Test
    void preservesChineseSourceQuotesWhileRequiringVietnameseOrEnglishCommentary() {
        Map<String, Object> result = Map.of(
            "summary",
            "CV trình bày rõ kỹ năng Java và Spring Boot.",
            "suggestions",
            List.of(
                Map.of(
                    "original_content",
                    "项目经验",
                    "suggested_content",
                    "Mô tả rõ vai trò trong dự án.",
                    "explanation",
                    "Bổ sung thông tin thực tế về phần việc đã thực hiện."
                )
            ),
            "writing_quality",
            Map.of("criteria", List.of(Map.of("feedback", "The description is clear.", "evidence", List.of("项目经验"))))
        );
        assertDoesNotThrow(() -> CvAnalysisLanguage.validate(result));
        assertEquals("项目经验", ((Map<?, ?>) ((List<?>) result.get("suggestions")).getFirst()).get("original_content"));
    }

    @Test
    void nativeTextPatternBlocksChineseWithoutRestrictingLiteralCvQuotes() {
        Map<?, ?> schema =
            (Map<?, ?>) AiResponseData.outputFormat("CV_ANALYZE", Map.of("resume", Map.of("raw_text", "项目经验")));
        Map<?, ?> fields = (Map<?, ?>) schema.get("properties");
        Map<?, ?> summary = (Map<?, ?>) fields.get("summary");
        String pattern = (String) summary.get("pattern");
        assertTrue("Nhận xét về kỹ năng Java & Spring Boot.".matches(pattern));
        assertTrue("Review Java and Spring Boot skills.".matches(pattern));
        assertFalse("增加项目细节".matches(pattern));
        assertFalse("Unsafe \"JSON quote\"".matches(pattern));
        assertFalse("Unsafe \\ escape".matches(pattern));
        assertFalse("x".repeat(1001).matches(pattern));
        Map<?, ?> suggestion = (Map<?, ?>) ((Map<?, ?>) fields.get("suggestions")).get("items");
        Map<?, ?> original = (Map<?, ?>) ((Map<?, ?>) suggestion.get("properties")).get("original_content");
        assertEquals(List.of("项目经验", ""), original.get("enum"));
        assertFalse(original.containsKey("pattern"));
    }
}
