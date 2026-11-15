package com.example.intelligent_cv_matching_ai_agent.util.shared.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class BloomRuleEngineTest {

    private void level(
        String sentence,
        String expected) {
        assertEquals(expected, BloomRuleEngine.classifyRequirement(sentence).getFirst().level(), sentence);
    }

    @Test
    void vietnameseLevels() {
        level("Liệt kê các kiểu dữ liệu trong Java", "Remember");
        level("Giải thích nguyên lý hoạt động của REST API", "Understand");
        level("Sử dụng Postman để kiểm thử API", "Apply");
        level("Xây dựng API bằng Spring Boot", "Apply");
        level("Phân tích dữ liệu khách hàng", "Analyze");
        level("Xác định nguyên nhân gây suy giảm hiệu năng hệ thống", "Analyze");
        level("Đánh giá chất lượng phần mềm theo tiêu chí", "Evaluate");
        level("Thiết kế kiến trúc mới cho hệ thống", "Create");
    }

    @Test
    void englishLevels() {
        level("Explain the principles of API design", "Understand");
        level("Use Postman for API testing", "Apply");
        level("Diagnose performance bottlenecks", "Analyze");
        level("Compare security alternatives", "Evaluate");
        level("Design system architecture", "Create");
    }

    @Test
    void doNotInventBloomFromSeniorityOrMetadata() {
        level("Minimum 4 years of experience testing API", "UNDETERMINED");
        level("Có 3 năm kinh nghiệm kiểm thử API", "UNDETERMINED");
        level("Thành thạo Java và Spring Boot", "UNDETERMINED");
        level("Bachelor's degree in IT", "UNDETERMINED");
        level("Working hours: Monday to Friday", "UNDETERMINED");
        level("Tối ưu hiệu năng SQL", "UNDETERMINED");
    }

    @Test
    void separateActionsWithoutDoubleCounting() {
        var results = BloomRuleEngine.classifyRequirement("Phân tích nguyên nhân lỗi và thiết kế kiến trúc hệ thống");
        assertEquals(2, results.size());
        assertEquals("Analyze", results.get(0).level());
        assertEquals("Create", results.get(1).level());
        level("Thiết kế giao diện web", "Create");
    }
}
