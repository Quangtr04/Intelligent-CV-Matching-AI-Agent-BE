# Prompt AI để cấu hình trong trang Admin

Chọn đúng mã tính năng trong trang cấu hình AI. Sao chép nội dung tệp vào ô **User prompt template**, giữ trường **System prompt** theo vai trò mong muốn, rồi lưu và kích hoạt phiên bản.

Các mẫu dưới đây chứa hướng dẫn nghiệp vụ trước đây được viết trong Java. Chúng cũng là mẫu mặc định khi tính năng chưa có phiên bản prompt được kích hoạt. Nếu tính năng đang dùng một phiên bản cũ, hãy sao chép nội dung cần giữ vào phiên bản mới trước khi kích hoạt.

- [APPLICATION_EMAIL](../src/main/resources/ai-prompts/APPLICATION_EMAIL.txt)
- [ASSESSMENT_GENERATE](../src/main/resources/ai-prompts/ASSESSMENT_GENERATE.txt)
- [CAREER_CHAT](../src/main/resources/ai-prompts/CAREER_CHAT.txt)
- [CV_AI_SUPPORT](../src/main/resources/ai-prompts/CV_AI_SUPPORT.txt)
- [CV_ANALYZE](../src/main/resources/ai-prompts/CV_ANALYZE.txt)
- [CV_PROFILE_ALIGNMENT](../src/main/resources/ai-prompts/CV_PROFILE_ALIGNMENT.txt)
- [JOB_ATS](../src/main/resources/ai-prompts/JOB_ATS.txt)
- [JOB_MATCH_RERANK](../src/main/resources/ai-prompts/JOB_MATCH_RERANK.txt)
- [JOB_REQUIREMENT_EVIDENCE](../src/main/resources/ai-prompts/JOB_REQUIREMENT_EVIDENCE.txt)
- [ROADMAP_GENERATE](../src/main/resources/ai-prompts/ROADMAP_GENERATE.txt)

Backend gửi nội dung CV, JD và các dữ liệu khác theo từng yêu cầu. Backend quản lý schema đầu ra, ngôn ngữ và việc kiểm tra kết quả.

Với `ASSESSMENT_GENERATE`, chỉnh số câu trong cấu hình model **ASSESSMENT**. Nếu prompt đang hoạt động có ghi cứng số câu hoặc số câu từng phần, thay bằng hướng dẫn dùng số câu từ cấu hình.
