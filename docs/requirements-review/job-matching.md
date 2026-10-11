## Thu thập job, vector, matching và Redis

| Tham chiếu DOCX | Yêu cầu | Đối chiếu code hiện tại | Đánh giá |
| --- | --- | --- | --- |
| P0074, P0091, P0175, P0199 | Một Spring Boot modular monolith, API dùng chung | Một ứng dụng/JAR; các controller dùng `/api/v1`, service AI nằm trong cùng ứng dụng. `pom.xml`, `docs/architecture.md`. | Đã có ở backend |
| P0076, P0127, P0261–P0262 | Thu thập TopCV và ITviec qua crawler | `POST /admin/jobs/crawl` nhận tối đa 20 URL trang chi tiết và đọc một `JobPosting` JSON-LD/trang. Có kiểm tra source/redirect/format; chưa tìm link từ trang danh sách hoặc keyword. `JobCrawlController.java:25`, `CrawlRequest.java:8`, `JobCrawlService.java:64`, `:404`. | Một phần; chưa kiểm chứng website thật |
| P0077, P0263 | Thu thập mới/cập nhật theo lịch | Không có scheduler thu thập job. `keyword` chỉ là nhãn của crawl run; admin phải gửi URL. Scheduler tìm thấy trong source hiện chỉ dành cho subscription maintenance. `JobCrawlService.java:69`, `SubscriptionMaintenance.java:36`. | Chưa có luồng tự động |
| P0078, P0264 | Trích title, company, description, requirements, skills, location, salary, employment type, posting date, URL | Title/company/description/location/salary/date/URL đã đọc; crawler truyền `List.of()` cho skills, chưa tách requirements/skills/employment type. `JobCrawlService.java:434`, `ImportJobRequest.java:8`, `JobCrawlRepository.java:63`. | Một phần |
| P0079, P0265–P0267 | Validate, clean, normalize, deduplicate | Có source allowlist, HTML→text, giới hạn dữ liệu, chuẩn hóa URL/location và hash. Hash gồm source, URL, title, description. Không đảm bảo gộp job giống nhau giữa hai source. `JobService.java:70`, `JobCrawlService.java:587`, `JobSourceValidator.java`. | Đã có cơ chế cơ bản; giới hạn phạm vi dedup |
| P0080, P0268–P0269 | Phát hiện job mới/cập nhật/hết hạn/không hợp lệ và đồng bộ | Đổi title/description tạo phiên bản mới, đánh dấu phiên bản cũ REMOVED và vector noncurrent. Nếu chỉ đổi salary/location/company, hash không đổi và import trả row cũ. validThrough đã hết hạn được xử lý khi crawl lại; hạn tương lai không lưu để scheduler hết hạn. HTTP 404/410 ghi lỗi crawl, chưa tự cập nhật job cũ thành REMOVED/INVALID. `JobService.java:78`, `:81`, `JobCrawlService.java:120`, `:195`, `JobRepository.java:88`. | Một phần; có lỗi bỏ sót cập nhật metadata |
| P0081, P0270–P0271 | Sinh và index embedding cho job mới/cập nhật | `POST /admin/jobs/{id}/index` tạo embedding và record indexing job, nhưng crawl/import không tự gọi index hoặc enqueue. Cần thao tác riêng của admin. `JobService.java:157`, `JobCrawlService.java:177`. | Có API, pipeline tự động chưa hoàn chỉnh |
| P0084 | Sinh embedding profile/CV | CV READY có `POST /resumes/{id}/embedding`, lưu và reuse theo content/model. Matching tạo query vector từ profile hoặc CV ngay khi match; chưa tái sử dụng CV vector đã lưu trong MatchingService và chưa có workflow lưu profile vector. `ResumeService.java:347`, `MatchingService.java:164`, `ProfileRepository.java:109`. | Có sinh vector; khác bước tái sử dụng trong CF03 |
| P0085–P0086, P0128–P0129, P0272–P0273, P0193 | Semantic retrieval → LLM rerank → score/reason | Nhánh AI dùng pgvector cosine, lọc ACTIVE/current/provider/model/content_hash, chuyển các job đã retrieve vào `JOB_MATCH_RERANK`, lưu score/reason/strengths/gaps. AI tắt dùng SKILL_OVERLAP được gắn nhãn riêng. `MatchingService.java:105`, `:163`, `:175`, `:210`, `MatchingRepository.java:127`. | Đã có code; chưa kiểm chứng model/pgvector thực |
| P0088, P0177 | Redis reuse kết quả matching | Redis cache ID báo cáo ATS CV–job 24 giờ và matching run ID 1 giờ, có đối chiếu/fallback database. | Đã có backend |
| P0089, P0191, P0194 | AI hỗ trợ và parsing/OCR | Có boundary Spring AI chat/embedding, structured output validation, quota/usage và extraction/OCR tùy cấu hình. Không tự coi AI tắt hoặc phản hồi mock là chứng minh chất lượng AI thật. `AiService.java:110`, `:238`, `ResumeFileExtractor.java`. | Đã có code; chất lượng/provider cần nghiệm thu |
| P0090, P0131 | Mở job gốc để ứng tuyển ngoài hệ thống | `POST /jobs/{id}/external-apply` kiểm tra ACTIVE/source URL và trả URL kèm event. Frontend phải điều hướng bằng URL trả về; backend không gửi HTTP redirect. `CareerToolsService.java:263`, `JobSourceValidator.java`. | Đáp ứng backend; cần nối frontend |
| P0178 so với P0192 | Qdrant hoặc pgvector / semantic search with Qdrant | Runtime dùng pgvector, Qdrant auto-configuration bị tắt. P0178 cho phép pgvector nhưng P0192 vẫn ghi Qdrant; cần thống nhất tài liệu. `application.yaml:68`, `MatchingRepository.java:127`, `V2__workflow_integrity_and_vector_indexes.sql:107`. | Khác câu chữ giữa hai mục DOCX |
| P0190 | text-embedding-3-small / large | Có model từ env, runtime và database cố định vector 1536 chiều; large chỉ tương thích nếu model được cấu hình trả đúng 1536 chiều. `application.yaml:65`, `AiService.java:264`. | Đã có ràng buộc, cần kiểm chứng cấu hình provider |

### Kiểm chứng lỗi cập nhật metadata của job

Đã gọi hàm `JobCrawlService.contentHash()` của class production đã build, với hai
`ImportJobRequest` giữ nguyên source/URL/title/description nhưng đổi company, location và salary.
Kết quả thực tế: `Production contentHash unchanged: true`.

`JobService.importJob()` dùng cùng thành phần hash và trả ngay bản ghi đã có khi hash trùng.
Vì vậy một job đổi lương hoặc địa điểm mà giữ nguyên title/description có thể tiếp tục trả dữ liệu cũ.
Đây là lỗi logic cụ thể của yêu cầu cập nhật job, không phải kết luận từ việc thiếu test.
Phép kiểm tra chỉ dùng dữ liệu giả, không gọi website, AI hoặc database.

### Giới hạn kiểm chứng của matching

`CoreFlowIntegrationTests` mock `AiService` và đặt `enabled=false` trong fixture;
phần matching integration chạy nhánh SKILL_OVERLAP. Hai test Flyway/pgvector
trong `IntelligentCvMatchingAiAgentApplicationTests` bị skip khi thiếu Docker.
123 test / 121 đạt / 2 skip của lần verify trước chứng minh những hợp đồng được test,
chưa chứng minh semantic search/HNSW, chất lượng rerank hoặc các SLA trong DOCX.
