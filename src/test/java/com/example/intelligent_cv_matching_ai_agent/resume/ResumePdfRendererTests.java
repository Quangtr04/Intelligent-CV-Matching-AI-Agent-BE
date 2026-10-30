package com.example.intelligent_cv_matching_ai_agent.resume;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.service.candidate.resume.ResumePdfRenderer;
import com.example.intelligent_cv_matching_ai_agent.util.shared.resume.ResumeData;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;

class ResumePdfRendererTests {

    @Test
    void missingBrowserIsARecoverableServiceError() {
        ResumePdfRenderer renderer = new ResumePdfRenderer("/unavailable/chromium", 2, 1, false);
        ApiException error = assertThrows(ApiException.class, () -> renderer.render(sample(), ""));
        assertEquals(503, error.status());
    }

    @Test
    @EnabledIfSystemProperty(named = "edunexus.pdf.smoke", matches = "true")
    void realBrowserPreservesVietnameseAndPaginatesReviewedContent() throws Exception {
        ResumePdfRenderer renderer = new ResumePdfRenderer("", 45, 1, false);
        byte[] bytes = renderer.render(sample(), "");
        Path output = Path.of("target", "pdf-smoke");
        Files.createDirectories(output);
        Files.write(output.resolve("confirmed-cv.pdf"), bytes);
        try (var document = Loader.loadPDF(bytes)) {
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("Nguyễn Minh Anh"));
            assertTrue(text.contains("Phát triển hệ thống"));
            assertTrue(text.contains("Dự án cuối cùng"));
            assertTrue(document.getNumberOfPages() >= 2, "Long CVs must paginate without dropping later sections");
            PDFRenderer rasterizer = new PDFRenderer(document);
            for (int index = 0; index < document.getNumberOfPages(); index++) {
                ImageIO.write(
                    rasterizer.renderImageWithDPI(index, 100),
                    "png",
                    output.resolve("page-" + (index + 1) + ".png").toFile()
                );
            }
        }
    }

    private static Map<String, Object> sample() {
        List<Map<String, Object>> experience = new ArrayList<>();
        for (int index = 0; index < 7; index++) {
            experience.add(
                Map.of(
                    "job_title",
                    "Backend Developer",
                    "company_name",
                    "Công ty công nghệ " + (index + 1),
                    "start_date",
                    "2023-01-01",
                    "end_date",
                    "2025-12-31",
                    "description",
                    "Phát triển hệ thống quản lý hồ sơ và đối chiếu việc làm bằng Java, Spring Boot và PostgreSQL.",
                    "highlights",
                    List.of(
                        "Thiết kế API, kiểm thử và theo dõi lỗi trong quá trình vận hành.",
                        "Tối ưu truy vấn và sử dụng Redis để lưu kết quả phân tích."
                    )
                )
            );
        }
        return Map.of(
            "title",
            "CV Backend - Nguyễn Minh Anh",
            "language",
            "vi",
            "structured_data",
            ResumeData.normalize(
                Map.of(
                    "full_name",
                    "Nguyễn Minh Anh",
                    "position",
                    "Backend Developer",
                    "email",
                    "minhanh@example.test",
                    "location",
                    "Thành phố Hồ Chí Minh",
                    "summary",
                    "Lập trình viên phát triển dịch vụ backend, ưu tiên tính chính xác và khả năng bảo trì.",
                    "experience",
                    experience,
                    "education",
                    List.of(
                        Map.of(
                            "school_name",
                            "Đại học Công nghệ",
                            "degree",
                            "Cử nhân",
                            "major",
                            "Công nghệ thông tin",
                            "start_date",
                            "2019-09-01",
                            "end_date",
                            "2023-06-01"
                        )
                    ),
                    "skills",
                    List.of("Java", "Spring Boot", "PostgreSQL", "Redis", "Docker"),
                    "projects",
                    List.of(
                        Map.of(
                            "project_name",
                            "Dự án cuối cùng",
                            "role",
                            "Backend Developer",
                            "description",
                            "Hệ thống xây dựng CV và đánh giá kỹ năng theo yêu cầu tuyển dụng."
                        )
                    )
                )
            )
        );
    }
}
