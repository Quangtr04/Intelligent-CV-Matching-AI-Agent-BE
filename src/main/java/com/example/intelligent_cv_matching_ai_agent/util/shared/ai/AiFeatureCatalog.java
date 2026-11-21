package com.example.intelligent_cv_matching_ai_agent.util.shared.ai;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/** Features are implemented by application code; an admin prompt customizes an existing feature. */
public final class AiFeatureCatalog {

    private record Feature(
        String code,
        String name,
        String description
    ) {
    }

    private static final List<Feature> FEATURES = List.of(
        new Feature("CV_BUILD", "Tạo CV", "Soạn nội dung CV từ thông tin ứng viên cung cấp."),
        new Feature("CV_PARSE", "Trích xuất CV", "Chuyển nội dung CV sang cấu trúc dữ liệu chuẩn."),
        new Feature("CV_ANALYZE", "Phân tích CV", "Đánh giá nội dung, cách viết và khả năng đọc CV."),
        new Feature("CV_PROFILE_ALIGNMENT", "Đối chiếu CV với hồ sơ", "So sánh CV với hồ sơ và mục tiêu nghề nghiệp."),
        new Feature("CV_AI_SUPPORT", "Hỗ trợ sửa CV", "Gợi ý sửa một phần CV theo yêu cầu của ứng viên."),
        new Feature("JOB_MATCH_RERANK", "Xếp hạng việc làm", "Đánh giá độ phù hợp của ứng viên với từng việc làm."),
        new Feature(
            "JOB_REQUIREMENT_EVIDENCE",
            "Đối chiếu bằng chứng CV–JD",
            "Đối chiếu từng yêu cầu JD với thông tin có nguồn trong CV hoặc hồ sơ."
        ),
        new Feature("JOB_ATS", "Đối chiếu CV với việc làm", "Phân tích kỹ năng phù hợp và còn thiếu so với công việc."),
        new Feature("ASSESSMENT_GENERATE", "Tạo bài đánh giá", "Tạo câu hỏi kiểm tra kỹ năng theo mô tả công việc."),
        new Feature("ROADMAP_GENERATE", "Lập lộ trình học", "Lập kế hoạch học các kỹ năng ứng viên còn thiếu."),
        new Feature("APPLICATION_EMAIL", "Soạn thư ứng tuyển", "Soạn email và thư ứng tuyển từ dữ liệu thực tế."),
        new Feature("CAREER_CHAT", "Trợ lý nghề nghiệp", "Trả lời câu hỏi và hỗ trợ định hướng nghề nghiệp."),
        new Feature("SALARY_ESTIMATE", "Ước lượng lương", "Ước lượng khoảng lương từ thông tin được cung cấp.")
    );

    private static final Map<String, String> DEFAULT_TASK_GUIDANCE = FEATURES.stream()
        .collect(Collectors.toUnmodifiableMap(Feature::code, feature -> loadTaskGuidance(feature.code())));

    private AiFeatureCatalog() {
    }

    public static String requireSupported(
        String code) {
        String normalized = code == null ? "" : code.strip().toUpperCase(Locale.ROOT);
        if (FEATURES.stream().noneMatch(feature -> feature.code().equals(normalized))) {
            throw ApiException.badRequest("Unsupported AI feature; select a feature from the AI catalog");
        }
        return normalized;
    }

    public static List<Map<String, Object>> descriptors() {
        return FEATURES.stream().map(AiFeatureCatalog::descriptor).toList();
    }

    public static Map<String, Object> defaultPrompt(
        String code) {
        String supported = requireSupported(code);
        Feature feature = FEATURES.stream().filter(item -> item.code().equals(supported)).findFirst().orElseThrow();
        return Map.of(
            "system_prompt",
            feature.description() + " Chỉ sử dụng dữ liệu được cung cấp.",
            "user_prompt_template",
            DEFAULT_TASK_GUIDANCE.get(supported)
        );
    }

    private static String loadTaskGuidance(
        String code) {
        try (var resource = AiFeatureCatalog.class.getResourceAsStream("/ai-prompts/" + code + ".txt")) {
            return resource == null
                ? "Dựa trên dữ liệu được gửi kèm, thực hiện yêu cầu của tính năng này."
                : new String(resource.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot load default AI prompt for " + code, failure);
        }
    }

    private static Map<String, Object> descriptor(
        Feature feature) {
        Map<String, Object> defaults = defaultPrompt(feature.code());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("featureCode", feature.code());
        result.put("name", feature.name());
        result.put("description", feature.description());
        result.put("defaultSystemPrompt", defaults.get("system_prompt"));
        result.put("defaultUserPromptTemplate", defaults.get("user_prompt_template"));
        result.put("responseSchema", AiResponseData.schema(feature.code()));
        result.put("modelConfigType", "ASSESSMENT_GENERATE".equals(feature.code()) ? "ASSESSMENT" : "CHAT");
        result.put("minContext", minimumContext(feature.code()));
        result.put("minOutputTokens", minimumOutputTokens(feature.code()));
        result.put(
            "deterministic",
            List.of("CV_ANALYZE", "CV_PROFILE_ALIGNMENT", "JOB_MATCH_RERANK", "JOB_REQUIREMENT_EVIDENCE")
                .contains(feature.code())
        );
        return result;
    }

    private static int minimumContext(
        String code) {
        if (List
            .of("CV_ANALYZE", "CV_PROFILE_ALIGNMENT", "CV_AI_SUPPORT", "JOB_MATCH_RERANK", "JOB_REQUIREMENT_EVIDENCE")
            .contains(code)) {
            return 16384;
        }
        return List.of("CV_AI_SUPPORT", "ASSESSMENT_GENERATE", "ROADMAP_GENERATE").contains(code) ? 8192 : 2048;
    }

    private static int minimumOutputTokens(
        String code) {
        if (List.of(
            "CV_ANALYZE",
            "CV_PROFILE_ALIGNMENT",
            "ASSESSMENT_GENERATE",
            "ROADMAP_GENERATE",
            "JOB_REQUIREMENT_EVIDENCE"
        ).contains(code)) {
            return 4000;
        }
        return "CV_AI_SUPPORT".equals(code) ? 2000 : 256;
    }
}
