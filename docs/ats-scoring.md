# Chấm điểm ATS (rubric `ats-v1`)

Điểm ATS được tính bằng **luật cố định** trong `util/shared/resume/AtsScoring.java`, không do LLM quyết định.
Cùng một CV và cùng một JD luôn cho cùng một điểm. Mô hình Ollama (nếu bật) chỉ viết lại điểm mạnh và gợi ý
(`service/shared/ai/AtsEvaluation.java`); khi Ollama tắt hoặc lỗi 5xx, hệ thống trả gợi ý của bộ luật.

Áp dụng cho:

- `POST /api/v1/jobs/{jobId}/ats` (ứng viên) và `POST /api/v1/consultations/{id}/jobs/{jobId}/ats` (consultant): chế độ `JOB_MATCH`.
- `POST /api/v1/resumes/{id}/analyze`: chế độ `GENERAL` (không có JD), cột `ats_score` + `ats_report_json`.

## Nhóm tiêu chí và trọng số

| Nhóm | JOB_MATCH | GENERAL | Kiểm tra | Tham khảo |
|---|---|---|---|---|
| HARD_SKILLS | 35 | – | Kỹ năng JD (bảng `market_job_skills` + từ điển ~80 kỹ năng IT, có alias, không phân biệt dấu). Bắt buộc = 1, ưu tiên = 0,5; có trong kinh nghiệm/dự án = 100%, chỉ nằm ở mục Kỹ năng = 75% | Jobscan Hard skills, Enhancv Skills evidence |
| ROLE_FIT | 10 | – | Chức danh JD có trong tiêu đề/tóm tắt/chức danh kinh nghiệm; số năm kinh nghiệm so với "N năm/years" trong JD | Jobscan Job title, Rezi Experience level |
| SOFT_SKILLS | 5 | – | Kỹ năng mềm JD nhắc tới (giao tiếp, làm việc nhóm, tiếng Anh…) | Jobscan Soft skills |
| SECTIONS | 20 | 30 | Họ tên, email, SĐT, link (chỉ khi có văn bản gốc), tóm tắt, kinh nghiệm/dự án, học vấn, ≥5 kỹ năng, ngày tháng | Jobscan Searchability, Enhancv Sections |
| IMPACT | 15 | 30 | % gạch đầu dòng có số liệu (mục tiêu 40%), mở đầu bằng động từ hành động (70%), cụm từ yếu, đại từ nhân xưng, lặp từ mở đầu | Resume Worded Impact, Rezi Content |
| BREVITY | 10 | 20 | 300–1000 từ, gạch đầu dòng 6–35 từ, tóm tắt ≤ 90 từ | Resume Worded Brevity, Rezi Format |
| FORMATTING | 5 | 20 | PDF/DOCX, tỉ lệ đọc được chữ (parse rate), email chuyên nghiệp, định dạng ngày thống nhất | Enhancv ATS essentials, Rezi Best practices |

Điểm tổng = trung bình có trọng số của các nhóm `ASSESSED` (nhóm `NOT_APPLICABLE`, ví dụ JD không nhắc kỹ năng mềm,
được bỏ ra và chia lại trọng số). Mức: `EXCELLENT` ≥ 80, `GOOD` ≥ 65, `FAIR` ≥ 50, còn lại `POOR`; mục tiêu 75 theo khuyến nghị của Jobscan.

## Lưu trữ

- `job_resume_analyses.improvement_suggestions_json` = `{input_hash, items, ats_report}`; API trả thêm trường `ats_report`.
- `resume_analyses.ats_report_json` (migration `V26__ats_reports.sql`).

## Giới hạn

- Chỉ đọc nội dung chữ: không kiểm tra font, màu, cột, bảng hiển thị.
- Từ điển kỹ năng tập trung vào ngành IT (nguồn việc làm TopCV/ITViec); kỹ năng ngoài từ điển chỉ được chấm khi có trong `market_job_skills`.
