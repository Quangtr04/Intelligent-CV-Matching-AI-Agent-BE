package com.example.intelligent_cv_matching_ai_agent.resume;

import com.example.intelligent_cv_matching_ai_agent.util.shared.resume.AtsScoring;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
class AtsScoringTests {

    @Test
    void sameInputAlwaysGetsTheSameScore() {
        Map<String, Object> first = AtsScoring.score(strongCv(), javaJob(), javaSkills());
        Map<String, Object> second = AtsScoring.score(strongCv(), javaJob(), javaSkills());

        assertEquals(first, second);
        assertEquals("ats-v1", first.get("rubric_version"));
        assertEquals("JOB_MATCH", first.get("mode"));
    }

    @Test
    void tailoredCvScoresHigherThanAnUnrelatedCv() {
        double strong = score(AtsScoring.score(strongCv(), javaJob(), javaSkills()));
        double weak = score(AtsScoring.score(weakCv(), javaJob(), javaSkills()));

        assertTrue(strong >= 70, "strong CV scored " + strong);
        assertTrue(weak < 50, "weak CV scored " + weak);
    }

    @Test
    void missingRequiredSkillsAreListedFirstAndMatchingIsAccentAndAliasInsensitive() {
        Map<String, Object> cv = strongCv();
        Map<String, Object> data = new LinkedHashMap<>((Map<String, Object>) cv.get("structured_data"));
        data.put("skills", List.of(Map.of("name", "postgres"), Map.of("name", "SpringBoot")));
        cv.put("structured_data", data);

        Map<String, Object> report = AtsScoring.score(cv, javaJob(), javaSkills());

        List<Map<String, Object>> matched = (List<Map<String, Object>>) report.get("matched_skills");
        assertTrue(matched.stream().anyMatch(item -> "PostgreSQL".equals(item.get("name"))));
        List<Map<String, Object>> missing = (List<Map<String, Object>>) report.get("missing_skills");
        assertEquals("Kubernetes", missing.getFirst().get("name"));
        assertEquals(true, missing.getFirst().get("required"));
    }

    @Test
    void skillsOnlyListedEarnLessThanSkillsUsedInExperience() {
        Map<String, Object> listed = new LinkedHashMap<>(
            Map.of(
                "language",
                "vi",
                "source",
                "MANUAL",
                "structured_data",
                Map.of("summary", "Lập trình viên", "skills", List.of("Java"))
            )
        );
        Map<String, Object> used = new LinkedHashMap<>(
            Map.of(
                "language",
                "vi",
                "source",
                "MANUAL",
                "structured_data",
                Map.of(
                    "summary",
                    "Lập trình viên",
                    "skills",
                    List.of("Java"),
                    "experience",
                    List.of(Map.of("job_title", "Developer", "description", "- Xây dựng API bằng Java"))
                )
            )
        );
        List<Map<String, Object>> skills = List.of(Map.of("skill_name", "Java", "is_required", true));
        Map<String, Object> job = Map.of("job_title", "Developer");

        assertTrue(
            category(AtsScoring.score(used, job, skills), "HARD_SKILLS") > category(
                AtsScoring.score(listed, job, skills),
                "HARD_SKILLS"
            )
        );
    }

    @Test
    void vietnameseAccentsDoNotCreateFalsePronounsOrMissVerbs() {
        Map<String, Object> report = AtsScoring.score(strongCv());

        Map<String, Object> impact = categoryRow(report, "IMPACT");
        Map<String, Object> pronouns = check(impact, "PRONOUNS");
        assertEquals(100.0, ((Number) pronouns.get("score")).doubleValue(), "\"Tối ưu\" is not the pronoun \"tôi\"");
        assertTrue(((Number) check(impact, "ACTION_VERBS").get("score")).doubleValue() >= 70);
    }

    @Test
    void generalModeSkipsJobCategoriesAndContactChecksNeedRawText() {
        Map<String, Object> report = AtsScoring.score(weakCv());

        assertEquals("GENERAL", report.get("mode"));
        List<Map<String, Object>> categories = (List<Map<String, Object>>) report.get("categories");
        assertTrue(categories.stream().noneMatch(item -> "HARD_SKILLS".equals(item.get("code"))));
        assertTrue(
            ((List<?>) categoryRow(report, "SECTIONS").get("checks")).stream()
                .noneMatch(item -> "EMAIL".equals(((Map<?, ?>) item).get("code")))
        );
    }

    @Test
    void englishCvGetsEnglishFeedback() {
        Map<String, Object> cv = weakCv();
        cv.put("language", "en");

        Map<String, Object> report = AtsScoring.score(cv, javaJob(), javaSkills());

        assertTrue(((List<String>) report.get("issues")).stream().anyMatch(item -> item.startsWith("Missing")));
    }

    @Test
    void emptyCvDoesNotFail() {
        Map<String, Object> report = AtsScoring.score(Map.of("title", "CV"), javaJob(), List.of());

        assertTrue(score(report) < 20);
        assertFalse(((List<?>) report.get("suggestions")).isEmpty());
    }

    private static Map<String, Object> strongCv() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("full_name", "Nguyễn Văn A");
        data.put(
            "summary",
            "Backend Java Developer với 4 năm kinh nghiệm xây dựng hệ thống Spring Boot, PostgreSQL và Docker "
                + "cho thương mại điện tử."
        );
        data.put(
            "experience",
            List.of(
                Map.of(
                    "company_name",
                    "ABC",
                    "job_title",
                    "Java Developer",
                    "start_date",
                    "2021-01-01",
                    "end_date",
                    "2025-01-01",
                    "is_current",
                    false,
                    "description",
                    "- Xây dựng 12 REST API bằng Spring Boot phục vụ 50.000 người dùng mỗi ngày\n"
                        + "- Tối ưu truy vấn PostgreSQL giúp giảm 40% thời gian phản hồi\n"
                        + "- Triển khai CI/CD với GitHub Actions và Docker cho 3 dịch vụ\n"
                        + "- Thiết kế kiến trúc microservices với Kafka xử lý 1 triệu sự kiện mỗi ngày"
                )
            )
        );
        data.put("education", List.of(Map.of("school_name", "FPT University", "major", "Software Engineering")));
        data.put(
            "skills",
            List.of(
                Map.of("name", "Java"),
                Map.of("name", "Spring Boot"),
                Map.of("name", "PostgreSQL"),
                Map.of("name", "Docker"),
                Map.of("name", "Kafka")
            )
        );
        Map<String, Object> cv = new LinkedHashMap<>();
        cv.put("title", "Java Developer CV");
        cv.put("language", "vi");
        cv.put("source", "UPLOAD");
        cv.put("mime_type", "application/pdf");
        cv.put("file_name", "cv.pdf");
        cv.put("raw_content", "Nguyễn Văn A nguyenvana@gmail.com 0912 345 678 github.com/nva " + "Java ".repeat(220));
        cv.put("structured_data", data);
        return cv;
    }

    private static Map<String, Object> weakCv() {
        Map<String, Object> cv = new LinkedHashMap<>();
        cv.put("title", "CV");
        cv.put("language", "vi");
        cv.put("source", "MANUAL");
        cv.put(
            "structured_data",
            Map.of(
                "summary",
                "Tôi là người chăm chỉ",
                "experience",
                List.of(Map.of("job_title", "Nhân viên bán hàng", "description", "- Tôi chịu trách nhiệm bán hàng"))
            )
        );
        return cv;
    }

    private static Map<String, Object> javaJob() {
        return Map.of(
            "job_title",
            "Senior Java Developer (Spring Boot)",
            "parsed_requirements",
            "Ít nhất 3 năm kinh nghiệm Java, Spring Boot, PostgreSQL, Docker, Kubernetes. Giao tiếp tốt."
        );
    }

    private static List<Map<String, Object>> javaSkills() {
        return List.of(
            Map.of("skill_name", "Java", "is_required", true),
            Map.of("skill_name", "Spring Boot", "is_required", true),
            Map.of("skill_name", "PostgreSQL", "is_required", true),
            Map.of("skill_name", "Kubernetes", "is_required", true),
            Map.of("skill_name", "Redis", "is_required", false)
        );
    }

    private static double score(
        Map<String, Object> report) {
        return ((Number) report.get("score")).doubleValue();
    }

    private static double category(
        Map<String, Object> report,
        String code) {
        return ((Number) categoryRow(report, code).get("score")).doubleValue();
    }

    private static Map<String, Object> categoryRow(
        Map<String, Object> report,
        String code) {
        return ((List<Map<String, Object>>) report.get("categories")).stream()
            .filter(item -> code.equals(item.get("code"))).findFirst().orElseThrow();
    }

    private static Map<String, Object> check(
        Map<String, Object> category,
        String code) {
        return ((List<Map<String, Object>>) category.get("checks")).stream()
            .filter(item -> code.equals(item.get("code"))).findFirst().orElseThrow();
    }
}
