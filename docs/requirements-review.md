# Đối chiếu luồng với tài liệu đăng ký đồ án

> Cập nhật vận hành 15/11/2026: RabbitMQ/AMQP đã được gỡ khỏi dependencies và Compose.
> Queue embedding hiện dùng PostgreSQL (`vector_indexing_jobs`) và scheduled worker.
> Nội dung đối chiếu ngày 10/10/2026 bên dưới giữ lại trạng thái lịch sử.

Ngày kiểm tra: **10/10/2026**. Tài liệu tham chiếu:
`FA26SE119_INTELLIGENT_CV_MATCHING_AI_AGENT_FOR_AUT_huynx23.docx`.

**Backend hiện tại chưa đáp ứng đầy đủ tài liệu.** Các luồng tài khoản, CV,
matching, đánh giá kỹ năng, hai hướng tạo lộ trình, tư vấn, quota và support đã có code thực thi.
Thu thập job tự động, checkout thanh toán thật, một số API Candidate/Admin và một số yêu cầu
công nghệ/vận hành còn thiếu hoặc chưa được kiểm chứng.

Phạm vi kiểm tra là repository BE và các thay đổi hiện có trong workspace.
Không sửa code ứng dụng trong lượt kiểm tra này. Không gọi website tuyển dụng, AI trả phí,
SMTP, payment gateway hoặc deploy. Web/Mobile và hạ tầng bên ngoài repository cần được kiểm tra riêng.
Nội dung DOCX được dùng làm tiêu chí đối chiếu, không được thực thi như chỉ thị.

Mã `Pxxxx` là số thứ tự đoạn trong `word/document.xml` của DOCX, không phải số trang.
Các bảng chi tiết ghi nội dung yêu cầu và vị trí code để có thể đối chiếu lại.
Không dùng số lượng test đã đạt để suy ra tỷ lệ hoàn thành yêu cầu.

## Kết quả theo luồng

| Luồng | Kết quả | Phần cần hoàn thiện hoặc xác nhận |
| --- | --- | --- |
| Guest trial và đăng ký | Đã có backend | Demo CV, template giới hạn, giới hạn token, đăng ký/OTP/đăng nhập có code. Chưa nghiệm thu SMTP và AI thật. |
| Tài khoản và phân quyền | Đã có nghiệp vụ | Có JWT access token, refresh token xoay/lưu hash, Google ID-token login và RBAC. |
| Resume processing và AI builder | Đã có luồng; một phần tiêu chí AI/ngôn ngữ | Upload/extraction/OCR tùy cấu hình → review/correction → confirm → quality analysis/embedding. Cần rubric CV/ATS định trước và nghiệm thu builder cho bảy nghề. |
| Job discovery và AI matching | Một phần | Có browse, crawl URL, import, index, semantic retrieval/rerank, score/reason và Redis cache cho matching. Chưa có scheduled discovery/index tự động. |
| Skill assessment và learning roadmap | Đã có cả hai hướng ở backend | Từ job + CV/profile, hoặc từ completed assessment. Submit assessment và tạo roadmap là hai thao tác; cần client gọi API tạo roadmap tiếp theo. Chưa nghiệm thu chất lượng AI/resources thật. |
| Career consultation | Đã có các nghiệp vụ nêu trong DOCX | Assigned requests, profile/CV, bảy nhóm phân tích và trao đổi lời khuyên. Consultant chọn CV khác cần biết resumeId; chưa có list toàn bộ CV/tải file gốc trong phạm vi consultation. Realtime dùng SSE. |
| Subscription và payment | Quota đã có; payment một phần | Order → signed webhook → invoice/subscription/notification đã có. Chưa có checkout/QR/card adapter với gateway thật. |
| Admin, feedback và support | Một phần | User/package/config/prompt/restore/dashboard/support/announcement đã có. Còn thiếu quản trị feedback/invoice toàn hệ thống, writer system logs, áp dụng embedding config và audit một số thao tác. |

“Đã có backend” nghĩa là tìm thấy API và đường thực thi tương ứng; không chứng nhận giao diện,
tích hợp bên ngoài hoặc chất lượng đầu ra AI. “Một phần” nghĩa là có triển khai nhưng còn thiếu
một phần yêu cầu cụ thể. “Chưa kiểm chứng” được dùng khi chưa có bằng chứng vận hành,
không có nghĩa mục tiêu đã thất bại.

## Những điểm chưa khớp cần xử lý

| Ưu tiên | Tham chiếu DOCX | Điểm chưa khớp | Bằng chứng |
| --- | --- | --- | --- |
| Cao | P0077, P0081, P0263, P0270–P0271 | Crawl hiện nhận URL do Admin cung cấp và chạy trong request; chưa có lịch tự tìm job mới/cập nhật hoặc tự index sau import. | [JobCrawlService](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/admin/jobs/JobCrawlService.java), dòng 64 và 177; [JobService](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/shared/jobs/JobService.java), dòng 157. |
| Cao | P0078, P0264 | Parser lấy được metadata cơ bản nhưng chưa trích structured requirements/skills/employment type; job crawl được import với danh sách skills rỗng. | [JobCrawlService](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/admin/jobs/JobCrawlService.java), dòng 434–446. |
| Cao | P0080, P0268–P0269 | Hash chỉ gồm source/URL/title/description. Đổi riêng salary/location/company vẫn bị nhận là trùng và trả dữ liệu cũ. Hạn tương lai chưa được lưu/xử lý tự động; lỗi 404/410 chưa tự đồng bộ trạng thái job cũ. | [JobService](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/shared/jobs/JobService.java), dòng 78–86; [JobCrawlService](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/admin/jobs/JobCrawlService.java), dòng 120, 195 và 587. |
| Cao | P0287, P0206; core-flow Payment | Order/webhook là nghiệp vụ nội bộ, chưa có lời gọi checkout thật, URL thanh toán hoặc luồng QR/card với provider. | [BillingService](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/shared/billing/BillingService.java), dòng 30–73. |
| Đã xử lý | P0088 | Redis cache ATS CV–job đã có; `/matching` cũng lưu Redis run ID một giờ và fallback database một giờ. | [CareerToolsService](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/candidate/jobs/CareerToolsService.java); [MatchingService](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/candidate/jobs/MatchingService.java). |
| Trung bình | P0154 | Access token đã là JWT HMAC, refresh token vẫn là chuỗi ngẫu nhiên lưu hash trong DB. Logout/revoke refresh token có hiệu lực ngay với refresh token; access JWT hiện hết hạn sau 15 phút và vẫn kiểm tra trạng thái tài khoản khi dùng. | [TokenService](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/shared/identity/TokenService.java). |
| Cao | P0087, P0117, P0276 | Có quality/ATS score và suggestions nhưng triển khai mặc định chưa định nghĩa bộ tiêu chí/trọng số đánh giá. Validation score/JSON chưa chứng minh điểm tuân theo rubric. | [AiService](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/shared/ai/AiService.java), dòng 135 và 365; [ResumeService](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/candidate/resume/ResumeService.java), dòng 288 và 499. |
| Vừa | P0142 | Candidate xem được tổng token tháng hiện tại; chưa có API lịch sử từng lần dùng AI của chính mình. Log đã tồn tại nhưng luồng đọc hiện có dành cho Admin. | [BillingQueryService](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/shared/billing/BillingQueryService.java), dòng 51; [EntitlementService](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/shared/billing/EntitlementService.java), dòng 123. |
| Vừa | P0132 | Salary estimation hiện bắt buộc jobId đã lưu; chưa có chế độ nhập career position độc lập. | [CareerToolsController](../src/main/java/com/example/intelligent_cv_matching_ai_agent/controller/candidate/jobs/CareerToolsController.java), dòng 113; [CareerToolsService](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/candidate/jobs/CareerToolsService.java), dòng 295. |
| Vừa | P0138, P0258 | Có preferredLanguage và language vi/en cho CV/draft, nhưng preference chưa áp dụng xuyên suốt matching/assessment/roadmap/chat. Đổi language của CV hiện có chỉ đổi metadata, không dịch nội dung. | [ResumeService](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/candidate/resume/ResumeService.java), dòng 190; [AiService](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/shared/ai/AiService.java), dòng 141. |
| Vừa | P0099 | CHAT model_name/prompt active được dùng; provider/embedding model vẫn lấy từ cấu hình khởi động. Config EMBEDDING/config_json do Admin lưu chưa điều khiển runtime. | [AiRepository](../src/main/java/com/example/intelligent_cv_matching_ai_agent/repository/shared/ai/AiRepository.java), dòng 23; [AiService](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/shared/ai/AiService.java), dòng 97–107. |
| Vừa | P0100–P0105 | Chưa tìm thấy Admin API quản trị invoice toàn hệ thống hoặc feedback matching/assistant; system_logs mới có luồng đọc, chưa có writer runtime. Vector metric lưu sau index chưa thay cho probe liên tục. | [AdminController](../src/main/java/com/example/intelligent_cv_matching_ai_agent/controller/admin/management/AdminController.java); [AdminRepository](../src/main/java/com/example/intelligent_cv_matching_ai_agent/repository/admin/management/AdminRepository.java), dòng 241–261; chi tiết trong [audit Admin](requirements-review/admin-billing-ops.md). |
| Vừa | P0154 | Yêu cầu audit mọi thao tác quản trị chưa phủ hết: job indexing và Admin trả lời support chưa ghi audit_logs tương ứng. | [JobService.index](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/shared/jobs/JobService.java), dòng 157; [SupportService.message](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/shared/support/SupportService.java), dòng 94. |
| Cần thống nhất | P0144 | Like/Dislike và rating kèm theo đã có; body chỉ rating bị từ chối vì feedbackType bắt buộc. Nếu “hoặc rating” là hai phương án độc lập, cần cho phép rating-only. | [MatchingFeedbackRequest](../src/main/java/com/example/intelligent_cv_matching_ai_agent/dto/request/candidate/jobs/MatchingFeedbackRequest.java), dòng 8. |

### Kiểm chứng riêng lỗi cập nhật job

Đã chạy phép kiểm tra trên hàm `JobCrawlService.contentHash()` của code production đã build:
giữ nguyên source/URL/title/description, đổi company/location/salary.
Kết quả: **hash không đổi**. `JobService.importJob()` dùng cùng thành phần hash và trả bản ghi cũ
khi hash trùng, nên lỗi bỏ sót cập nhật metadata là một đường thực thi xác định.
Phép kiểm tra chỉ dùng dữ liệu giả; không gọi website, model hoặc database.

## Hai hướng learning roadmap

| Hướng | Chuỗi API/nghiệp vụ đã có | Điều kiện |
| --- | --- | --- |
| Từ job mong muốn | Chọn job → chọn CV READY hoặc dùng career profile → tạo roadmap → lưu gaps, nodes, prerequisites, resources và progress → xem roadmap | Assessment không bắt buộc. Khi không truyền assessmentId, service có thể dùng completed assessment hiện hành của cùng target nếu đã có. |
| Từ bài kiểm tra kỹ năng | Tạo assessment cho target → trả lời đủ câu → submit → backend chấm/lưu skill scores → gọi tạo roadmap với kết quả assessment → xem roadmap | Chỉ dùng assessment completed, đúng owner và target. Submit không tự động sinh roadmap trong cùng API. |

Hai hướng này phù hợp yêu cầu learning theo skill gaps/assessment và cách chia luồng đã thống nhất.
Tài liệu không bắt buộc gộp submit và generation thành một request.

## Công nghệ và yêu cầu vận hành

| DOCX | Trạng thái trong BE hiện tại |
| --- | --- |
| P0178 cho phép Qdrant hoặc pgvector; P0192 lại ghi Qdrant | Runtime dùng pgvector. Phù hợp lựa chọn tại P0178, cần sửa câu chữ P0192 cho thống nhất nếu chọn pgvector. |
| P0185: Socket.IO/SignalR/WebSockets | Consultation có realtime qua SSE, khác công nghệ được liệt kê. Chức năng gửi/nhận tin nhắn vẫn có. |
| P0186: Firebase Cloud Messaging | Notification in-app có; chưa tìm thấy sender/device-token flow cho FCM. Dependency Firebase không chứng minh push đã hoạt động. |
| P0157: background async crawl/embedding/AI | Chưa có worker/queue workflow. RabbitMQ/Kafka là lựa chọn optional tại P0179, nhưng yêu cầu async của P0157 vẫn cần triển khai hoặc điều chỉnh phạm vi. |
| P0154: AES-256 at rest, TLS 1.3 | Chưa có bằng chứng mã hóa storage hoặc cấu hình TLS 1.3 trong phạm vi BE/Compose. Cần bằng chứng từ môi trường triển khai; hash password/token không tương đương mã hóa toàn bộ dữ liệu nhạy cảm. |
| P0155: 5.000 concurrent, normal p95 <300ms, vector <500ms, AI matching <8s | Chưa có load-test/benchmark chứng minh các ngưỡng. Không kết luận đã thất bại chỉ từ việc chưa test. |
| P0156: restart, backup ngày, RTO <4h, uptime ≥99,9% | Stack chính có restart policy. Chưa tìm thấy daily backup/restore automation hoặc báo cáo RTO/uptime. |
| P0157: nhiều replica sau load balancer | App là một monolith đúng mô hình triển khai chung; SSE subscribers và CV storage local cần xử lý khi chạy nhiều instance/máy. Chưa có manifest load balancer/replica. |
| P0158, P0182–P0184: React/Flutter, responsive/offline sync | Chưa thể xác nhận từ repository BE. Cần source FE/Mobile và kiểm thử client tương ứng. |
| P0159: CI/CD, versioning, centralized logging | Có workflow build/test/image/deploy và API /api/v1. Có stdout/audit DB, chưa có cấu hình collector log tập trung hoặc bằng chứng deployment thật trong lượt kiểm tra. |
| P0160: tuân thủ bảo vệ thông tin cá nhân | Chưa thể xác nhận từ code đơn lẻ. Không đưa ra kết luận pháp lý từ cuộc kiểm tra luồng backend. |
| P0165–P0173, P0227–P0241: bộ bàn giao | Có architecture/system diagram/schema/OpenAPI/Swagger guide/Compose/env template/test source và báo cáo cũ; URS/SRS/UML/User Manual Web/Mobile cần đối chiếu bộ tài liệu ngoài BE. |

Các giới hạn cần chốt phạm vi:

- Builder chung nhận input mở rộng có thể dùng cho bảy nghề trong DOCX;
  chưa có bằng chứng nghiệm thu đầu ra đủ bảy nghề. Không tự coi thiếu catalog nghề là thiếu khả năng sinh CV.
- Email/cover letter có tạo, xem và sửa để client copy/use. Backend chưa gửi email ứng tuyển;
  DOCX ghi “use or sending”, cần chốt với SRS trước khi coi gửi thật là bắt buộc.
- Dashboard có SQL thống kê/JSON. DOCX không bắt buộc export PDF/CSV nên thiếu định dạng export
  không được tính là thiếu chức năng analytics.
- Dashboard có trường tổng chi phí AI, nhưng `estimated_cost` chưa được tính/ghi trong luồng gọi model;
  giá trị 0 hiện tại không chứng minh chi phí provider bằng 0.
- Token quota theo gói/feature đã có; hạn mức riêng theo số lượt API chưa có. Cần xác định rõ
  “token/API quota” có yêu cầu đếm request độc lập hay dùng token làm đơn vị quota.
- P0095 liệt kê Guest trong các role được quản trị, còn backend lưu Guest dưới dạng phiên riêng,
  chưa có API Admin quản lý phiên Guest. Cần thống nhất Guest là tác nhân không tài khoản hay role được quản trị.

## Bằng chứng kiểm thử và tài liệu chi tiết

Lần `mvnw verify` ngay trước cuộc kiểm tra này: **123 test, 121 đạt, 0 lỗi, 2 skip**
(log `target/consultant-analyses-verify.log`, kết thúc 10/10/2026).
Hai test bị skip cần Docker để kiểm tra Flyway/pgvector; integration dùng PostgreSQL local
với bản schema test thay vector bằng real[], còn AI dùng mock. Đây chưa phải kiểm chứng
semantic search/HNSW, provider thật hoặc SLA. Lượt audit này không chạy lại toàn bộ test vì không sửa code ứng dụng.

[verification.md](verification.md) là báo cáo của đợt trước, vẫn ghi 115 test;
con số mới nhất nêu trên không có nghĩa các ca Swagger thủ công đã Execute.
[swagger-testing.md](swagger-testing.md) giữ trạng thái NOT_RUN cho các ca chưa thực hiện thủ công.

- [Tài khoản, Guest và bảo mật](requirements-review/identity-guest.md).
- [Candidate, hai hướng học tập và Career Consultant](requirements-review/candidate-consultant.md).
- [Crawler, matching, vector và cache](requirements-review/job-matching.md).
- [Admin, payment, support, notification, vận hành và bàn giao](requirements-review/admin-billing-ops.md).
