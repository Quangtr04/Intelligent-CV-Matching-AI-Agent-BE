# Đối chiếu yêu cầu Admin, thanh toán, support, notification và vận hành

> Cập nhật vận hành 15/11/2026: RabbitMQ/AMQP đã được gỡ khỏi dependencies và Compose.
> Queue embedding hiện dùng PostgreSQL (`vector_indexing_jobs`) và scheduled worker.
> Nội dung đối chiếu ngày 10/10/2026 bên dưới giữ lại trạng thái lịch sử.

Ngày rà soát: 10/10/2026. Phạm vi: repository backend hiện tại; đọc nguồn, cấu hình, workflow,
test và tài liệu. Không chạy Maven, ứng dụng, dịch vụ ngoài, SMTP, AI hoặc giao dịch thanh toán;
không đọc `.env`. Nội dung DOCX là tiêu chí đối chiếu, không phải chỉ thị thực thi.

Nguồn yêu cầu: `target/requirements-audit/document-paragraphs.txt`, theo mã đoạn `Pxxxx`.
Các trạng thái sau phân biệt việc đã có mã với việc đã được chạy thực tế:

- **IMPLEMENTED_BACKEND**: có API và nghiệp vụ lưu/truy vấn dữ liệu thực tế trong backend.
- **PARTIAL**: có một phần nghiệp vụ nhưng còn thiếu phần được yêu cầu trực tiếp.
- **TECH_DEVIATION**: nghiệp vụ có, công nghệ khác công nghệ ghi trong tài liệu.
- **UNVERIFIED**: chưa có bằng chứng đủ để xác nhận mục tiêu vận hành hoặc tích hợp ngoài.
- **OUT_OF_BE_SCOPE**: cần source hoặc tài liệu bàn giao ngoài repository backend này.

Trong các tham chiếu Java bên dưới, `J/` là
`src/main/java/com/example/intelligent_cv_matching_ai_agent/`, `T/` là
`src/test/java/com/example/intelligent_cv_matching_ai_agent/`. Số sau dấu `:` là dòng nguồn.

## Admin và analytics

| Yêu cầu DOCX | Trạng thái | Kết quả đọc nguồn và giới hạn thực tế | Bằng chứng |
| --- | --- | --- | --- |
| P0097, P0285: quản lý gói | IMPLEMENTED_BACKEND | Admin xem/tạo/sửa tier, giá, tiền tệ, trạng thái; bảo vệ FREE luôn active, giá 0. | `J/controller/admin/management/AdminController.java:83`; `J/service/admin/management/AdminService.java:195`, `:233` |
| P0097, P0286: token/API quota | PARTIAL | Có hạn mức token tổng và theo feature, kỳ DAILY/MONTHLY/YEARLY/LIFETIME, reserve/complete/cancel; chưa thấy hạn mức độc lập theo số lần gọi API. `requests` trong dashboard là số thống kê, không phải quota request. | `J/service/admin/management/AdminService.java:259`; `J/service/shared/billing/EntitlementService.java:26`, `:40`, `:77`, `:104`; `J/repository/shared/billing/EntitlementRepository.java:23`; `J/repository/admin/management/AdminRepository.java:70` |
| P0098: token consumption và billing statistics | IMPLEMENTED_BACKEND cho token/revenue; AI cost chưa tính | AI usage log ghi token/success/latency; dashboard tổng hợp token/requests tháng và doanh thu order COMPLETED theo tiền tệ. Trường `estimated_cost` có trong schema và dashboard nhưng AiService không ghi, không thấy bộ tính giá; NULL được SUM/COALESCE thành 0. Đây chưa phải estimated cost thực hay đối soát hóa đơn provider. | `J/service/shared/ai/AiService.java:339`; `J/service/shared/ai/AiUsageRecorder.java:19`; `J/repository/admin/management/AdminRepository.java:61`, `:70`; `src/main/resources/db/migration/V1__edunexus_complete_schema.sql:781` |
| P0099: cấu hình AI model, prompt, embedding | PARTIAL | Có lưu phiên bản config/prompt, một bản active mỗi loại/feature, restore tạo phiên bản mới, audit. Runtime CHAT chỉ đọc `model_name` cho provider đã cấu hình; prompt active được đưa vào lời gọi. Provider, embedding model vẫn lấy từ constructor/env; `config_json` và bản EMBEDDING trong DB chưa được áp dụng vào runtime. | `J/service/admin/management/AdminService.java:287`, `:332`, `:372`, `:413`; `J/service/shared/ai/AiService.java:66`, `:97`, `:102`, `:106`, `:146`; `J/repository/shared/ai/AiRepository.java:23` |
| P0100: system logs, audit, error reports | PARTIAL | Có endpoint đọc các bảng, audit thực được ghi cho thao tác quản trị; AI và crawl lưu thông tin lỗi riêng. `system_logs` có entity/repository và API SELECT, nhưng không tìm thấy writer hoặc pipeline thu gom lỗi ứng dụng vào bảng này trong main source. Chưa có bằng chứng dashboard system log sẽ có log runtime tự động. | `J/service/admin/management/AdminService.java:446`; `J/repository/admin/management/AdminRepository.java:256`, `:261`; `J/service/admin/monitoring/AuditService.java:25`, `:40`; `J/service/shared/ai/AiService.java:338`; `J/service/admin/jobs/JobCrawlService.java:188` |
| P0101: quản lý payment và invoice | PARTIAL | Admin xem payment orders qua monitoring/billing và đọc/resolve báo lỗi payment. Invoice được phát hành tự động và người sở hữu có API xem; chưa thấy API Admin xem/điều hành invoice của toàn bộ người dùng. | `J/service/admin/management/AdminService.java:456`, `:462`, `:468`; `J/service/shared/billing/BillingService.java:152`; `J/service/shared/billing/BillingQueryService.java:111`; `J/repository/shared/billing/BillingQueryRepository.java:83` |
| P0102: Vector DB và indexing jobs | PARTIAL | Có đọc trạng thái indexing job và metric lưu trong DB. Metric `AVAILABLE`/vector_count được ghi sau khi index RAG thành công, không phải probe live liên tục; không thấy scheduler làm mới health Vector DB. | `J/service/admin/management/AdminService.java:452`; `J/repository/admin/management/AdminRepository.java:246`, `:251`; `J/service/admin/rag/RagDocumentService.java:130`, `:171` |
| P0103: announcement/notification | IMPLEMENTED_BACKEND | Admin tạo/sửa draft, chọn audience ALL/ROLE/USER, publish/archive, kiểm tra expiry; publish tạo notification cho user active thuộc audience. User xem và đánh dấu đã đọc. Đây là notification trong DB/in-app. | `J/controller/admin/announcement/AdminAnnouncementController.java:22`; `J/service/shared/announcement/AnnouncementService.java:47`, `:123`, `:159`; `J/repository/shared/announcement/AnnouncementRepository.java:103`, `:121`, `:135` |
| P0104, P0213, P0214: analytics/report/dashboard | IMPLEMENTED_BACKEND | `/admin/dashboard` thực hiện SQL tổng hợp users, active subscriptions, revenue, AI usage, open support tickets; monitoring trả dữ liệu chi tiết. Có backend JSON cho dashboard/report cơ bản. DOCX không bắt buộc CSV/PDF/XLSX nên không đánh dấu thiếu export là thiếu yêu cầu; giao diện dashboard không nằm trong BE này. | `J/controller/admin/management/AdminController.java:31`, `:157`; `J/service/admin/management/AdminService.java:56`; `J/repository/admin/management/AdminRepository.java:47`, `:61`, `:70`, `:80`; `docs/system-diagram.md:15` |
| P0105: quản lý feedback và support ticket | PARTIAL | Ticket có quản trị assignment/status/priority và phản hồi; người dùng có gửi feedback/rating matching và assistant. Chưa thấy Admin API tổng hợp, đọc hoặc xử lý các feedback matching/assistant; không nên coi việc có bảng feedback là đã có quản trị feedback. | `J/service/shared/support/SupportService.java:37`, `:94`, `:139`; `J/controller/candidate/jobs/CareerToolsController.java:48`; `J/controller/candidate/ai/CareerAssistantController.java:63`; `J/controller/admin/management/AdminController.java:157` |

Không rà lại authentication hoặc CRUD user trong phần này; nhóm đó được kiểm tra riêng.

## Subscription và thanh toán

### Nghiệp vụ đã có

- P0205/P0285/P0286: tier active được trả cùng danh sách quota; user xem gói hiện tại, lịch sử,
  tự hủy subscription. `J/controller/shared/billing/BillingController.java:25`, `:30`, `:35`, `:40`;
  `J/repository/shared/billing/BillingQueryRepository.java:23`, `:44`.
- Quota được kiểm tra trước provider call và tính cả token đang reserve. Dùng transaction
  `REQUIRES_NEW`, khóa user/reservation, trả 429 khi vượt global/feature budget; zero feature quota
  chặn feature. Đây là triển khai thực, không phải bộ đếm giả trong response.
  `J/service/shared/billing/EntitlementService.java:26`, `:34`, `:40`, `:104`, `:117`;
  `J/repository/shared/billing/EntitlementRepository.java:33`, `:86`.
- P0288: user tạo/read order của mình và gửi/read báo lỗi; Admin resolve lưu resolution và audit.
  `J/service/shared/billing/BillingQueryService.java:68`, `:73`, `:82`, `:107`;
  `J/repository/shared/billing/BillingQueryRepository.java:63`;
  `J/service/admin/management/AdminService.java:468`.
- Order lấy giá từ tier active, tự tính `price_monthly * durationMonths`, chặn FREE và duration
  ngoài 1..12; client không truyền tổng tiền để tự kích hoạt. Mặc định order PENDING,
  provider `MANUAL_GATEWAY`, provider_order_id dùng UUID order.
  `J/service/shared/billing/BillingService.java:23`, `:30`, `:42`, `:48`.
- Webhook kiểm tra raw body HMAC-SHA256 trên `timestamp + "." + body`, secret tối thiểu 32 byte,
  timestamp Unix giây ±300 giây, signature hex 64 ký tự; kiểm tra order/provider/amount/currency,
  khóa order và chống replay theo event ID. `J/controller/shared/billing/BillingController.java:94`;
  `J/util/shared/billing/WebhookVerifier.java:39`, `:50`, `:56`;
  `J/service/shared/billing/BillingQueryService.java:120`;
  `J/service/shared/billing/BillingService.java:76`;
  `J/repository/shared/billing/BillingRepository.java:38`, `:43`.
- Chỉ event COMPLETED hợp lệ mới cùng transaction kích hoạt gói, hủy gói ACTIVE cũ, cập nhật order,
  tạo invoice PAID với tax 0 và notification PAYMENT_SUCCESS. FAILED/CANCELLED chỉ finalize order.
  `J/service/shared/billing/BillingService.java:113`, `:121`, `:152`, `:176`.
- Scheduler nhắc trước 7 ngày/1 ngày và đánh dấu EXPIRED, lưu notification receipt để tránh gửi
  trùng. Gói mới `auto_renew=false`; không có job tự động charge thẻ.
  `J/service/shared/billing/SubscriptionMaintenance.java:36`, `:45`, `:76`;
  `J/repository/shared/billing/SubscriptionMaintenanceRepository.java:23`, `:58`;
  `J/service/shared/billing/BillingService.java:143`.

### Khoảng trống trực tiếp so với P0287/P0206

**PARTIAL – online payment processing.** `BillingService.order()` chỉ lưu order local và trả record.
Không có lời gọi tạo checkout tới gateway, checkout URL, QR payment do gateway phát hành,
card flow/provider SDK hoặc adapter đối soát giao dịch thực trong main source. HMAC webhook generic
đã hoạt động về mặt nghiệp vụ khi nhận event phù hợp, nhưng tự ký event trong Swagger/test chỉ
chứng minh backend nhận event và cập nhật DB, chưa chứng minh tiền được chuyển qua ngân hàng/thẻ.

Bằng chứng: `J/service/shared/billing/BillingService.java:30`, `:48`, `:73`;
`J/service/shared/billing/BillingQueryService.java:120`; `.env.example:31`;
`docs/swagger-testing.md:682`; `docs/verification.md:21`.

Invoice hiện là record JSON phát hành sau COMPLETED. User chỉ xem invoice của chính mình;
không thấy nghiệp vụ quản trị invoice toàn hệ thống trong controller admin. Không có yêu cầu
PDF invoice/tax provider/refund trong những đoạn DOCX được giao audit, nên không thêm các yêu cầu đó.

## Support, feedback, notification và realtime

| Yêu cầu | Trạng thái | Hành vi và bằng chứng |
| --- | --- | --- |
| P0290: support ticket management | IMPLEMENTED_BACKEND | User tạo/list/detail/message; Admin list toàn bộ, đổi status/priority/assignedAdminId, reply. Đọc tài nguyên người khác bị chặn; CLOSED không nhận message mới. `J/controller/shared/support/SupportController.java:25`; `J/controller/admin/support/AdminSupportController.java:21`; `J/service/shared/support/SupportService.java:69`, `:94`, `:139` |
| Phản hồi support | IMPLEMENTED_BACKEND | Admin reply ghi message và notification SUPPORT_REPLY cho chủ ticket. Đây là lưu DB để client đọc, chưa phải mobile push. `J/service/shared/support/SupportService.java:101`, `:115` |
| P0289: feedback/rating user | IMPLEMENTED_BACKEND cho matching/assistant | Matching có LIKE/DISLIKE, rating 1..5, comment; assistant có rating 1..5, is_helpful, feedback_text. DOCX không nêu riêng rating support nên không coi thiếu rating support là vi phạm. `J/dto/request/candidate/jobs/MatchingFeedbackRequest.java:10`; `J/service/candidate/jobs/CareerToolsService.java:74`; `J/service/candidate/ai/CareerAssistantService.java:243` |
| P0291/P0207: system notifications | IMPLEMENTED_BACKEND | Announcement, payment, renewal và support đều ghi notification; list unreadOnly và read/read-all có kiểm tra chủ sở hữu. `J/controller/shared/announcement/AnnouncementController.java:26`; `J/repository/shared/announcement/AnnouncementRepository.java:121`, `:130`; `J/service/shared/billing/SubscriptionMaintenance.java:76` |
| P0185: Socket.IO/SignalR/WebSockets | TECH_DEVIATION | Consultation có realtime qua SSE `GET /consultations/{id}/events`, message POST + publish after commit. Không có WebSocket handler/broker/config hoặc Socket.IO/SignalR implementation; starter websocket trong POM không chứng minh WebSocket đã được dùng. `J/controller/shared/consultation/ConsultationController.java:72`; `J/service/shared/consultation/ConsultationService.java:166`, `:194`; `pom.xml:131` |
| P0186: Firebase Cloud Messaging | PARTIAL / thiếu tích hợp | Chỉ có dependency firebase-admin; không thấy FirebaseApp/FirebaseMessaging, sender, đăng ký device token hoặc token storage được sử dụng trong main source. Notification in-app vẫn đáp ứng nghiệp vụ P0103/P0291, nhưng chưa đáp ứng công nghệ push FCM của P0186. `pom.xml:44`; `J/service/shared/announcement/AnnouncementService.java:159`; `J/repository/shared/announcement/AnnouncementRepository.java:103` |

Giới hạn scale đáng kể: SSE subscriber nằm trong `ConcurrentHashMap` của một JVM;
message publish chỉ đi tới subscriber của process xử lý POST. Hai replica phía sau load balancer
chưa có shared pub/sub để fan-out sự kiện giữa các JVM. Tin nhắn vẫn lưu PostgreSQL; vấn đề này
thuộc vận chuyển realtime giữa replica, không có nghĩa logic hội thoại chưa triển khai.
`J/service/shared/consultation/ConsultationService.java:37`, `:194`, `:222`.

## NFR và công nghệ vận hành

| Yêu cầu DOCX | Trạng thái | Đối chiếu |
| --- | --- | --- |
| P0155: 5.000 concurrent; normal p95 <300ms; vector <500ms; matching <8s | UNVERIFIED | Có test nghiệp vụ/unit/integration, có ai_usage_logs latency, nhưng không thấy load-test scenario, benchmark ở mức 5.000 concurrent hoặc báo cáo percentile. Không kết luận các mục tiêu đã thất bại; chưa đủ bằng chứng đạt. `docs/verification.md:19`, `:21`; `T/CoreFlowIntegrationTests.java:42`, `:52`, `:85`; `J/service/shared/ai/AiService.java:338` |
| P0156: auto-restart | IMPLEMENTED_CONFIG cho stack chính | App production, PostgreSQL và Redis có restart unless-stopped. Qdrant/RabbitMQ ở profile optional chưa có restart policy; không phải stack bắt buộc đang dùng. `compose.production.yaml:4`; `compose.yaml:19`, `:23`, `:32`, `:57` |
| P0156: daily DB backup, RTO <4h, uptime ≥99.9% | UNVERIFIED / chưa có automation trong repo | Có PG volume, app volume, healthchecks và local test DB scripts; không thấy backup schedule, pg_dump/restore script, bản ghi restore drill/RTO hoặc uptime measurement. Volume bền vững không thay cho daily backup. `compose.yaml:12`, `:14`; `compose.production.yaml:11`; `scripts/start-local-test-postgres.ps1:1`; `scripts/stop-local-test-postgres.ps1:1` |
| P0157: replicate monolith/load balancer | PARTIAL | Một app container/JAR đúng mô hình scale toàn bộ app, nhưng compose production chỉ có một service app, chưa có load balancer/replica manifest. SSE fan-out trong process và file CV local cần cấu hình bổ sung khi chạy nhiều máy. `Dockerfile:3`, `:6`, `:11`; `compose.production.yaml:1`; `J/service/shared/consultation/ConsultationService.java:37`; `J/service/candidate/resume/ResumeService.java:48`, `:548` |
| P0157/P0177: Redis cache | IMPLEMENTED_BACKEND, optional runtime | Redis dùng thật cho ATS CV–job TTL 24h và matching TTL 1h; backend vẫn kiểm tra/fallback PostgreSQL khi Redis không sẵn sàng. `J/service/candidate/jobs/CareerToolsService.java`; `J/service/candidate/jobs/MatchingService.java`; `src/main/resources/application.yaml:23`; `compose.yaml:44` |
| P0157: async background crawl/embedding/AI | PARTIAL | Crawl do Admin gọi chạy tuần tự trong request; index/AI gọi đồng bộ. Scheduler thực tế được tìm thấy là subscription maintenance. Chưa thấy async queue worker/listener cho crawl/embedding/AI. `J/service/admin/jobs/JobCrawlService.java:64`, `:97`, `:104`; `J/service/admin/rag/RagDocumentService.java:151`; `J/service/shared/ai/AiService.java:254`; `J/service/shared/billing/SubscriptionMaintenance.java:36` |
| P0158/P0182/P0183/P0184/P0210/P0218: Web/Mobile/UI/offline | OUT_OF_BE_SCOPE | Backend/Swagger có API để client dùng, chưa có source React/Tailwind/Redux hay Flutter/Riverpod, offline sync hoặc kết quả browser/device testing trong repo BE. Cần kiểm tra repo FE/mobile và tài liệu bàn giao tương ứng; không suy ra chúng không tồn tại ngoài workspace. `docs/system-diagram.md:15`; `README.md:3`, `:110` |
| P0159: modular monolith, domain boundaries, API versioning | IMPLEMENTED_BACKEND về cấu trúc | Một SpringBootApplication/JAR; packages layer/role/feature và API /api/v1, DI controller/service/repository, transaction tại service. Những điều này chứng minh cấu trúc tổ chức; không đủ để tuyên bố mọi nguyên tắc SOLID/Clean Architecture đã được chứng nhận. `J/IntelligentCvMatchingAiAgentApplication.java:6`; `J/controller/shared/billing/BillingController.java:15`; `docs/architecture.md:3`, `:56`, `:74` |
| P0159: centralized logging | PARTIAL | Có audit trong PostgreSQL, log mức INFO và container stdout. Không thấy agent/collector/logback sink hoặc cấu hình hệ thống log tập trung trong manifest; system_logs API hiện chưa có writer runtime. `J/service/admin/monitoring/AuditService.java:40`; `src/main/resources/application.yaml:97`; `J/repository/admin/management/AdminRepository.java:256`; `compose.production.yaml:1` |
| P0159: automated CI/CD | IMPLEMENTED_CONFIG, deployment UNVERIFIED | Workflow verify/build JAR, artifact, image push GHCR khi push main, deploy SSH/Compose khi DEPLOY_HOST có giá trị. Có pipeline thật; audit này không truy cập GitHub để xác nhận run hoặc production deployment thành công. `.github/workflows/ci-cd.yml:24`, `:35`, `:45`, `:99`, `:102`, `:121` |
| P0175: single Spring Boot + external LLM/embedding APIs | IMPLEMENTED_BACKEND, provider-dependent | Internal AiService điều phối Spring AI ChatModel/EmbeddingModel; không phải AI microservice riêng. AI disabled/none theo env template; lời gọi provider thực cần cấu hình. `J/IntelligentCvMatchingAiAgentApplication.java:6`; `J/service/shared/ai/AiService.java:24`, `:57`, `:123`, `:254`; `.env.example:23` |
| P0176: PostgreSQL và Spring Data JPA/Hibernate | IMPLEMENTED_BACKEND | PostgreSQL driver/Flyway, entity/JpaRepository thực; workflow phần lớn JDBC. Không nên mô tả tất cả nghiệp vụ dùng JPA. `pom.xml:87`, `:185`; `J/repository/shared/identity/jpa/UserJpaRepository.java:8`; `src/main/resources/application.yaml:4`, `:8`; `docs/architecture.md:74`, `:76` |
| P0178: Qdrant hoặc pgvector | IMPLEMENTED_BACKEND bằng pgvector | Migration tạo extension vector, vector(1536), HNSW cosine và query <=>. Qdrant optional Compose nhưng auto config bị disable, không phải storage đang chạy cho workflow. Native vector kiểm chứng còn tùy môi trường Docker. `src/main/resources/db/migration/V1__edunexus_complete_schema.sql:3`, `:114`; `src/main/resources/db/migration/V2__workflow_integrity_and_vector_indexes.sql:106`; `J/repository/candidate/jobs/MatchingRepository.java:117`; `src/main/resources/application.yaml:69`; `docs/verification.md:19` |
| P0179: RabbitMQ/Kafka queue optional | OPTIONAL_NOT_IMPLEMENTED | Có RabbitMQ container optional và AMQP/Batch dependencies; chưa có queue producer/listener hoặc Kafka flow. Vì DOCX ghi optional, không coi việc chưa dùng broker là lỗi bắt buộc độc lập; async yêu cầu P0157 vẫn chưa được thực hiện. `compose.yaml:32`; `pom.xml:64`, `:68`; `src/main/resources/application.yaml:15` |
| P0180: unified REST API; optional Nginx HTTPS/LB | REST IMPLEMENTED; Nginx optional | REST /api/v1 có sẵn. Manifest chỉ expose HTTP app port; không có Nginx/TLS/LB config trong repo. Nginx optional không phải một thiếu hụt bắt buộc tự thân. `J/controller/shared/billing/BillingController.java:15`; `compose.production.yaml:5`; `Dockerfile:9` |
| P0154: TLS 1.3 trong transit, giới hạn phần ops | UNVERIFIED / chưa có cấu hình trong repo | Không có server.ssl/TLS terminator hoặc minimum TLS version trong application/compose/workflow hiện tại. Cần bằng chứng TLS 1.3 từ môi trường triển khai; audit này không đánh giá JWT/OAuth/AES, thuộc nhóm identity/security riêng. `src/main/resources/application.yaml:93`; `compose.production.yaml:5`; `.github/workflows/ci-cd.yml:121` |

`/actuator/health` không đủ chứng minh tất cả integration healthy: exposure chỉ health/info,
Redis/Rabbit/mail health bị disable, không có Prometheus endpoint exposure trong YAML dù POM có
registry dependency. `src/main/resources/application.yaml:76`, `:80`, `:86`; `pom.xml:181`.

## Bàn giao và bằng chứng kiểm thử

| DOCX | Trạng thái trong phạm vi BE | Bằng chứng và phần cần bổ sung/đối chiếu |
| --- | --- | --- |
| P0199/P0205/P0206/P0207/P0208: deployable backend modules | Có app/JAR, service thật; mức hoàn thiện theo các mục trên | `Dockerfile:6`, `:11`; `J/service/shared/billing/BillingService.java:30`; `J/service/shared/support/SupportService.java:41`; `J/service/admin/management/AdminService.java:56` |
| P0210–P0216: Admin Dashboard sản phẩm UI | Backend API có, UI ngoài phạm vi | Dashboard/package/AI usage/billing/crawl/monitoring APIs có; không coi chúng tự thay thế UI Admin. `J/controller/admin/management/AdminController.java:31`, `:83`, `:157`; `J/controller/admin/jobs/JobCrawlController.java:23`; `docs/system-diagram.md:15` |
| P0218–P0225: Mobile App | OUT_OF_BE_SCOPE | Cần source Flutter/package hoặc demo build Android/iOS; trong BE chỉ có API và tài liệu hệ thống. `docs/system-diagram.md:15` |
| P0228/P0229: URS/SRS | Chưa xác định bộ bàn giao trong BE | Không thấy tài liệu URS/SRS riêng trong `docs`; DOCX yêu cầu là tài liệu tham chiếu đã cung cấp, cần đối chiếu thêm bộ hồ sơ bên ngoài repo. Danh mục tài liệu đang có được liệt kê `README.md:121`. |
| P0230: Architecture Design | Có tài liệu | `docs/architecture.md:1`; `docs/system-diagram.md:1`; diagram SVG/PNG/drawio trong `docs/diagrams/`. |
| P0231: UML Design | Chưa có bằng chứng bộ UML trong BE | Có sơ đồ flow/architecture, không thấy bộ UML use-case/class/sequence riêng trong danh mục repo. Cần đối chiếu file nộp ngoài BE; không coi diagram kiến trúc tự động là toàn bộ UML. `docs/architecture.md:60`; `docs/system-diagram.md:1`. |
| P0232: Swagger/OpenAPI | Có config/dependency/doc/test | `pom.xml:138`; `T/CoreFlowIntegrationTests.java:696`; `docs/api.md:1`; `docs/swagger-testing.md:1`. |
| P0233: Deployment Guide | Có hướng dẫn local/CI/deploy | `README.md:145`, `:153`; `scripts/run-local.ps1:1`; `compose.production.yaml:1`. Backup/restore/monitoring vận hành chưa có automation/checklist chứng minh, đối chiếu thêm P0172. |
| P0234: Testing Report | Có báo cáo kiểm chứng trước đó; chưa đủ NFR/external | `docs/verification.md:3`, `:19`, `:21`. Hướng dẫn Swagger vẫn đánh dấu NOT_RUN và ghi rõ là kỳ vọng, không phải báo cáo đã execute. `docs/swagger-testing.md:5`, `:1225`. |
| P0235: User Manual | Có hướng dẫn test API, chưa xác định manual cho sản phẩm UI | Swagger guide hướng tới thao tác API; cần manual Web/Mobile và bộ bàn giao ngoài BE. `docs/swagger-testing.md:1`, `:130`; `docs/system-diagram.md:15`. |
| P0238: Docker Compose package | Có | `compose.yaml:1`; `compose.production.yaml:1`; `Dockerfile:1`. |
| P0239: sample database | Có development catalog seed | FREE/BASIC/PRO + quota, skills, CV template/demo, learning resources; không phải snapshot full users/jobs/payments. `src/main/resources/db/migration/V4__initial_catalog.sql:3`, `:8`, `:40`, `:65`, `:69`, `:82`. |
| P0240: AI configuration template | Có env/config schema | `.env.example:23`; `src/main/resources/application.yaml:49`; `docs/api.md:185`. Cần provider API key/enablement cho integration thật. |
| P0241: deployment scripts | Có workflow + manifest/local scripts | `.github/workflows/ci-cd.yml:99`; `compose.production.yaml:1`; `scripts/run-local.ps1:1`; không có standalone production backup/restore automation. |

Các test hiện có chứng minh coverage cần thiết nhưng sự tồn tại của test không phải kết quả chạy
trong lần audit này:

- Payment: signature/amount/replay và invoice/subscription chỉ kích hoạt một lần:
  `T/util/shared/billing/WebhookVerifierTest.java:32`, `:39`, `:50`, `:61`;
  `T/billing/BillingServiceTest.java:21`, `:52`, `:71`;
  `T/CoreFlowIntegrationTests.java:437`, `:480`.
- Quota: in-flight reservation, zero feature quota, budget completion và concurrent reservation:
  `T/billing/EntitlementServiceTest.java:20`, `:40`, `:60`;
  `T/CoreFlowIntegrationTests.java:658`.
- Support/announcement: ownership, CLOSED, role, expiry, targeted audience:
  `T/admin/SupportAccessTest.java:46`, `:57`, `:73`, `:91`;
  `T/CoreFlowIntegrationTests.java:600`, `:647`.
- Renewal scheduler: notification receipt tránh trùng, disabled tránh DB work:
  `T/service/shared/billing/SubscriptionMaintenanceTest.java:18`, `:38`.
- AI config/prompt restore: active version và history:
  `T/CoreFlowIntegrationTests.java:758`, `:860`, `:946`, `:1007`.
- Integration test phụ thuộc `EDUNEXUS_TEST_DATABASE_URL`; AiService mock. Native pgvector
  Testcontainers có thể skip khi Docker unavailable. CI verify có thể bỏ qua bộ integration
  cần env DB nếu không cấu hình env đó; workflow hiện không set EDUNEXUS_TEST_DATABASE_URL.
  `T/CoreFlowIntegrationTests.java:42`, `:52`, `:85`;
  `T/IntelligentCvMatchingAiAgentApplicationTests.java:15`; `.github/workflows/ci-cd.yml:35`.

Không thấy load-test runner, backup job, Firebase push handler, WebSocket handler hay queue
producer/consumer trong main source/config/scripts được đọc. Những kết luận không-tìm-thấy này
chỉ áp dụng cho repository BE đã kiểm tra, không khẳng định hạ tầng hoặc repo khác không có chúng.

## Các điểm cần ưu tiên khi tổng hợp gap

1. Online checkout/gateway thực còn thiếu, dù generic signed webhook và nghiệp vụ DB đã có.
2. Admin configuration chưa điều khiển provider/embedding/config_json tại runtime; hiện chỉ CHAT
   model_name của provider cố định và prompt active được áp dụng.
3. Token quota đã có; request-count API quota, Admin quản trị invoice toàn hệ thống và feedback
   chưa có API tương ứng được tìm thấy.
4. FCM chưa có implementation; realtime consultation có SSE, khác công nghệ DOCX và chưa fan-out
   giữa nhiều replica.
5. Background async crawl/index/AI chưa có; Redis đã dùng thật cho ATS/matching cache, không phải queue.
6. Load/SLA, daily backup/restore/RTO/uptime và TLS 1.3 chưa có bằng chứng xác minh vận hành.
7. Analytics JSON backend và notification in-app đã thực hiện; không nên quy chúng thành thiếu
   chức năng chỉ vì chưa có export PDF/CSV hoặc FCM.
8. UI Web/Mobile, offline mode và bộ URS/SRS/UML/User Manual cần đối chiếu bộ bàn giao ngoài BE.
9. Dashboard `estimated_cost` hiện không được tính/ghi từ provider usage, dù có cột và truy vấn
   tổng hợp. Token consumption và revenue từ payment order vẫn là số liệu được ghi thực tế.
