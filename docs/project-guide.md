# Hướng dẫn toàn bộ backend EduNexus: code, luồng chức năng và cách sử dụng

Tài liệu đối chiếu với repository `INTELLIGENT_CV_MATCHING_AI_AGENT` ngày **13/10/2026**, bao gồm toàn bộ backend: API, bảo mật, database, CV, AI, việc làm, học tập, tư vấn, thanh toán và quản trị. Repository cung cấp Swagger UI; frontend web/mobile cần gọi các API này.

Cấu hình AI runtime được cập nhật ngày **04/11/2026**: Spring AI kết nối Gemini: chat qua giao thức tương thích OpenAI, embedding qua native REST,
chat `gemini-2.5-flash-lite`, embedding `gemini-embedding-001` 1024 chiều, cần GEMINI_API_KEY.
Xem [hướng dẫn Gemini](gemini.md) và [schema V10](schema.md). Các số liệu kiểm chứng có mốc cũ
và mô tả schema V1/V5 là lịch sử, không thay cho kết quả kiểm thử runtime hiện tại.

Ví dụ dùng `http://localhost:8080`. Thay UUID, token, email, mật khẩu và key mẫu bằng dữ liệu của môi trường chạy. Giữ đúng tên trường DTO; dự án dùng cả `camelCase` và `snake_case` tùy API.

## Mục lục

1. [Tổng quan](#1-tổng-quan)
2. [Cấu trúc code và vòng đời request](#2-cấu-trúc-code-và-vòng-đời-request)
3. [Database và migration](#3-database-và-migration)
4. [Chạy dự án và cấu hình](#4-chạy-dự-án-và-cấu-hình)
5. [Tài khoản, phiên, guest và Mailtrap](#5-tài-khoản-phiên-guest-và-mailtrap)
6. [Career profile và CV](#6-career-profile-và-cv)
7. [AI và quota](#7-ai-và-quota)
8. [Việc làm, crawling, matching và ứng tuyển](#8-việc-làm-crawling-matching-và-ứng-tuyển)
9. [Assessment và roadmap](#9-assessment-và-roadmap)
10. [Assistant và RAG](#10-assistant-và-rag)
11. [Consultation và SSE](#11-consultation-và-sse)
12. [Subscription và thanh toán](#12-subscription-và-thanh-toán)
13. [Admin, thông báo, hỗ trợ và monitoring](#13-admin-thông-báo-hỗ-trợ-và-monitoring)
14. [Hướng dẫn sử dụng từ đầu đến cuối](#14-hướng-dẫn-sử-dụng-từ-đầu-đến-cuối)
15. [Danh mục API](#15-danh-mục-api)
16. [Kiểm thử, build, Docker và CI/CD](#16-kiểm-thử-build-docker-và-cicd)
17. [Tra lỗi và giới hạn hiện tại](#17-tra-lỗi-và-giới-hạn-hiện-tại)
18. [Cách mở rộng và nguồn tra cứu](#18-cách-mở-rộng-và-nguồn-tra-cứu)

## 1. Tổng quan

EduNexus hỗ trợ chuẩn bị CV, tìm việc phù hợp, đánh giá kỹ năng, lập lộ trình học và tư vấn nghề nghiệp. Các module cùng chạy trong một ứng dụng Spring Boot.

| Thành phần | Cấu hình trong repository | Vai trò |
| --- | --- | --- |
| Java / Maven | Java 25 | Runtime và build |
| Spring Boot | 4.1.1 | REST, dependency injection, cấu hình, scheduling |
| Spring AI | 2.0.1, adapter chat giao thức OpenAI và adapter embedding native để gọi Gemini | `ChatModel`/`EmbeddingModel` gọi Gemini API |
| Spring Security | JWT bearer + kiểm tra database | Xác thực, phân quyền, thu hồi phiên |
| PostgreSQL / pgvector | Compose PostgreSQL 17, embedding hiện tại 1024 chiều | Dữ liệu nghiệp vụ, semantic matching, RAG; V10 giữ vector legacy 1536 |
| Flyway | V1–V10 | Schema, constraint, seed, ingestion automation và chuyển embedding |
| JDBC / JPA | Cùng tồn tại | Workflow SQL và truy cập entity có kiểu |
| Redis | Tùy chọn | Cache JSON response ATS/matching/phân tích CV; ATS/matching có database fallback |
| PDFBox / POI / Tesseract | Trích xuất tệp/OCR | Đọc CV và scan khi OCR được cấu hình |
| Jsoup | Fetch và parse HTML | Discovery keyword và detail parsing ITviec/TopCV |
| SMTP | `JavaMailSender` | Gửi OTP; dùng được với Mailtrap |
| SSE | `SseEmitter` | Tin nhắn tư vấn trong cùng process |
| Docker / GitHub Actions | JAR, image, CI/CD | Đóng gói và triển khai |

Có container/dependency Qdrant nhưng workflow hiện tại lưu vector trong PostgreSQL, phát sự kiện tư vấn qua SSE. Chỉ bật chúng khi cần adapter tương ứng.

| Người dùng | Xác thực | Công việc |
| --- | --- | --- |
| Guest | `X-Guest-Session` | CV demo/template, builder/recommendations/chat dùng thử |
| `CANDIDATE` | Bearer | CV, matching, học tập, assistant, mua gói, yêu cầu tư vấn |
| `CAREER_CONSULTANT` | Bearer | Thread được phân công, hồ sơ và báo cáo ứng viên trong thread |
| `ADMIN` | Bearer | User, gói/quota, job, AI/prompt, RAG, support, monitoring |

Vai trò thư mục không tự quyết định quyền. Một số API yêu cầu role cụ thể; một số kiểm tra đăng nhập và ownership. Xem `SecurityConfig`, `CurrentUser` và service.

Xem [use case diagram](usecase-diagram.md) để đọc các mục tiêu người dùng bằng sơ đồ, gồm hình tổng quan và ba hình chi tiết có PNG/SVG và nguồn draw.io/PlantUML.

## 2. Cấu trúc code và vòng đời request

### 2.1. Bản đồ thư mục

Thư mục để chạy lệnh là nơi chứa `pom.xml`.

~~~text
INTELLIGENT_CV_MATCHING_AI_AGENT/
├── pom.xml
├── .env.example
├── compose.yaml                    PostgreSQL và dịch vụ optional
├── compose.production.yaml         app container trên server
├── Dockerfile                      JAR đã build → image
├── .github/workflows/ci-cd.yml
├── scripts/                        run-local, bootstrap admin, test DB, dev tools
├── docs/
└── src/
    ├── main/
    │   ├── java/com/example/intelligent_cv_matching_ai_agent/
    │   │   ├── controller/<role>/<feature>/
    │   │   ├── dto/request/<role>/<feature>/
    │   │   ├── dto/response/<role>/<feature>/
    │   │   ├── service/<role>/<feature>/
    │   │   ├── repository/<role>/<feature>/       SQL workflow
    │   │   ├── repository/<role>/<feature>/jpa/   JPA repositories
    │   │   ├── entity/<role>/<feature>/
    │   │   ├── security/shared/
    │   │   ├── config/shared/
    │   │   ├── exception/shared/
    │   │   └── util/<role>/<feature>/
    │   └── resources/
    │       ├── application.yaml
    │       └── db/migration/
    └── test/
~~~

| Lớp | Trách nhiệm | Ví dụ |
| --- | --- | --- |
| Controller | Route, body/query, `@Valid`, gọi service | `ResumeController` |
| Request DTO | Hợp đồng đầu vào/validation | `ResumeWriteRequest` |
| Service | Quyền, nghiệp vụ, transaction, điều phối | `ResumeService` |
| Repository workflow | SQL có tham số | `ResumeRepository` |
| `SqlStore` | Bọc `JdbcTemplate`, bind/read JSON và SQL | `repository/shared/SqlStore.java` |
| Entity/JPA | Mapping bảng và truy cập có kiểu | `User`/`UserJpaRepository` |
| Response DTO | Chọn dữ liệu công khai | `PersonalProfileResponse` |
| Util | Validation, chuẩn hóa, dựng text/hash/projection và xử lý tệp/URL/webhook | `ResumeContent`/`JobEmbeddingSupport`/`WebhookVerifier` |
| Exception | Lỗi nghiệp vụ và HTTP response | `ApiException`/`ApiExceptionHandler` |

Các hàm hỗ trợ được nhóm trong `util/admin`, `util/candidate`, `util/consultant` và `util/shared`,
theo feature tương ứng. Service gọi util để kiểm tra hoặc biến đổi dữ liệu; kiểm tra quyền,
transaction, truy cập repository và điều phối các bước nghiệp vụ vẫn nằm trong service.
Một số hàm public cũ như `ResumeService.canonicalText`, `JobService.embeddingText`,
`RagDocumentService.split` và `TokenService.hash` được giữ làm delegate để tương thích với caller hiện có.

Phần lớn workflow dùng JDBC cho `FOR UPDATE`, `ON CONFLICT`, advisory lock, JSON và pgvector. Có JPA repository không có nghĩa tất cả API đều dùng JPA.

Ví dụ JPA thực tế: `GET /me → ProfileController → ProfileService → ProfileRepository.getProfile → UserJpaRepository.findById → PersonalProfileResponse`. Response này không chứa password hash, Google subject hoặc token.

### 2.2. Request và bảo mật

~~~mermaid
flowchart LR
    Client["Frontend / Swagger / HTTP client"] --> Sec["SecurityConfig + BearerFilter"]
    Sec --> C["Controller + DTO validation"]
    C --> S["Service: role, ownership, transaction"]
    S --> R["Workflow repository / JPA"]
    R --> DB[("PostgreSQL + pgvector")]
    S --> AI["AiService"]
    S --> Mail["SMTP"]
    S --> Files["Upload storage"]
    S --> Cache["Redis"]
    S --> Out["Response DTO / Map / JSON"]
    Out --> Client
~~~

1. `IntelligentCvMatchingAiAgentApplication.main` khởi động Spring, load configuration và bean; Flyway áp dụng migration.
2. Client gọi `/api/v1/...` bằng JSON/multipart.
3. `BearerFilter` đọc header nếu có. Token sai trả `401` ngay, kể cả route công khai nhưng client gửi bearer sai.
4. `TokenService.authenticate` kiểm tra JWT rồi hash token trong DB, tài khoản active và token chưa revoked/hết hạn.
5. `SecurityConfig` cho các route public đi qua; `/admin/**` cần admin, route còn lại mặc định cần đăng nhập.
6. Controller validate DTO. Service lấy chủ sở hữu từ `CurrentUser`, kiểm tra role và tài nguyên; không dùng user ID do client tự chọn để quyết định ownership.
7. Repository đọc/ghi database; service gọi AI/mail/file/cache khi cần.
8. Response trả DTO/Map/JSON. Lỗi được xử lý theo lớp phát sinh.

Lỗi nghiệp vụ là `application/problem+json`, thường có `status`, `detail`, `instance`; validation thêm `errors`. Lỗi security filter trả JSON `status`/`message`. Frontend đọc `detail ?? message`.

~~~json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "Invalid or expired verification code",
  "instance": "/api/v1/auth/verify-email"
}
~~~

`@Transactional` bảo vệ database, không thu hồi được email đã gửi, file đã ghi hay lượt AI đã gọi. OTP gửi trước commit DB; usage AI có transaction riêng. Không coi tác vụ bên ngoài và database là một giao dịch nguyên tử.

### 2.3. Cách lần theo một chức năng

Với `POST /resumes/{id}/confirm`: đọc route trong controller → DTO → `ResumeService.confirm` → các hàm `ResumeRepository` → constraint migration → test liên quan.

~~~powershell
rg -n 'confirm|/resumes' src/main/java
rg -n 'email_verification_codes' src/main/resources/db/migration
rg -n 'registrationVerification' src/test/java
~~~

## 3. Database và migration

`spring.jpa.hibernate.ddl-auto=none`; Flyway quản lý schema thay vì Hibernate tự tạo bảng.

| Migration | Nội dung |
| --- | --- |
| `V1__edunexus_complete_schema.sql` | DBML gốc: 74 bảng, PK/FK, unique, index và constraint |
| `V2__workflow_integrity_and_vector_indexes.sql` | Constraint workflow, HNSW cosine, index, trigger |
| `V3__ai_token_reservations.sql` | `ai_token_reservations` và `payment_webhook_events` |
| `V4__initial_catalog.sql` | Gói/quota, skill, template, CV demo, tài nguyên học |
| `V5__job_collection_automation.sql` | Metadata/deadline/source aliases, collection leases và durable embedding queue |

Sau V5 có 78 bảng ứng dụng, ngoài lịch sử Flyway (V1 có74, V3 thêm2, V5 thêm2). UUID dùng `gen_random_uuid()`, JSON dùng `jsonb`, thời gian dùng `timestamptz`, embedding `vector(1536)`. Enum nghiệp vụ thường là varchar có `CHECK`.

Đó là mốc schema V5. Hiện tại V1–V19 có **83 bảng ứng dụng**; V10 giữ vector cũ trong
`embedding_legacy_1536` và thêm cột `embedding vector(1024)`. Vector cũ không còn current;
RAG đã hoàn tất với vector cũ chuyển về `PENDING`, cần index lại bằng model Gemini mới.
V11 từng thêm mô tả và cấu hình trình bày cho mẫu CV; V19 chuyển toàn bộ mẫu sang HTML/CSS trong `html_content`,
bỏ cấu hình layout JSON và giữ nguyên tham chiếu CV cũ. Chi tiết migration hiện tại nằm ở [schema.md](schema.md).

| Module | Các bảng quan trọng |
| --- | --- |
| Identity | `users`, `email_verification_codes`, `refresh_tokens` |
| Guest | `guest_sessions`, `trial_usage_events` |
| Profile/CV | `candidate_profiles`, `candidate_profile_skills`, `resumes`, `resume_experiences`, `resume_educations`, `resume_projects`, `resume_certifications`, `resume_skills`, `resume_parse_results`, `resume_analyses`, `resume_embeddings` |
| Job/crawl | `market_job_posts`, `market_job_sources`, `market_job_skills`, `market_job_embeddings`, `market_crawl_runs`, `market_crawl_items`, `job_collection_leases` |
| Matching/ứng tuyển | `job_matching_runs`, `matching_run_resumes`, `job_match_results`, `job_match_feedback`, `job_resume_analyses`, `application_drafts`, `external_apply_events`, `salary_estimations` |
| Học tập | `assessment_sessions`, `assessment_questions`, `assessment_answers`, `gap_analyses`, `gap_analysis_skills`, `roadmaps`, `roadmap_nodes`, `roadmap_node_prerequisites`, `roadmap_node_resources`, `learning_resources` |
| AI/RAG | `ai_configurations`, `ai_prompt_templates`, `ai_usage_logs`, `ai_token_reservations`, `rag_documents`, `rag_chunks`, `rag_chunk_embeddings` |
| Assistant | `career_assistant_conversations`, `career_assistant_messages`, `career_assistant_message_sources`, `career_assistant_feedback` |
| Consultation | `consultation_threads`, `consultation_messages` |
| Billing | `subscription_tiers`, `subscription_quotas`, `user_subscriptions`, `subscription_renewal_notifications`, `payment_orders`, `payment_webhook_events`, `invoices`, `payment_reports` |
| Vận hành | `announcements`, `announcement_targets`, `notifications`, `support_tickets`, `support_ticket_messages`, `audit_logs`, `admin_actions`, `system_logs`, `vector_indexing_jobs`, `vector_store_metrics` |

Seed có FREE/BASIC/PRO, 15 skill, 2 template trial, một CV demo hệ thống, 7 learning resources. BASIC/PRO giá 99.000/199.000 VND là dữ liệu mẫu phát triển. Đọc API gói để biết giá/quota đang có.

Constraint bảo vệ một CV primary hợp lệ, một subscription active, answer đúng assessment, prerequisite đúng roadmap, một invoice/order và ngân sách không vượt trần. Xóa user là soft delete để giữ FK/lịch sử.

Vector chỉ so sánh cùng provider/model; đổi model cần index lại. File gốc nằm tại `UPLOAD_DIR`, mặc định `./uploads`. Khi chuyển môi trường cần giữ cả database và thư mục/volume upload.

## 4. Chạy dự án và cấu hình

### 4.1. Chạy local trên Windows

Cần Java 25, Maven và PostgreSQL có pgvector; Docker Desktop chạy PostgreSQL Compose. Script local gọi `mvn`, cần Maven trong PATH.

~~~powershell
java -version
mvn -version
docker version
if (!(Test-Path -LiteralPath '.env')) {
    Copy-Item -LiteralPath '.env.example' -Destination '.env'
}
~~~

Sửa `POSTGRES_PASSWORD` và các placeholder. Bắt buộc sinh và lưu `APP_JWT_SECRET` vào `.env`
theo [README](../README.md) trước khi chạy; không có secret mặc định. Nếu chưa có SMTP, thử local bằng
`AUTH_EXPOSE_DEV_OTP=true`.

~~~powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/run-local.ps1 -WithInfrastructure
~~~

Script chuyển về thư mục dự án → đọc `.env` vào process → kiểm tra mật khẩu DB → khởi động/chờ PostgreSQL nếu có switch → `mvn spring-boot:run`. Với PostgreSQL riêng, sửa URL/user/password rồi chạy script không có `-WithInfrastructure`.

- Swagger: `http://localhost:8080/swagger-ui/index.html`.
- OpenAPI: `http://localhost:8080/v3/api-docs`.
- Health: `http://localhost:8080/actuator/health`.
- Base API: `http://localhost:8080/api/v1`.

Dừng backend bằng Ctrl+C. Database/cache là process/container riêng.

### 4.2. Biến cấu hình

| Nhóm | Biến | Mục đích |
| --- | --- | --- |
| DB | `DATABASE_URL` | JDBC URL; giá trị mẫu localhost:5432/edunexus |
| DB | `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_PORT` | DB/credentials/port mapping Compose |
| Security | `APP_JWT_SECRET` | Bắt buộc; secret HS256 từ ít nhất 32 byte ngẫu nhiên mật mã, dùng nguyên chuỗi UTF-8 |
| Security | `CORS_ALLOWED_ORIGINS` | Origin frontend cách nhau bằng dấu phẩy; mẫu localhost 3000/5173 |
| Identity | `AUTH_EXPOSE_DEV_OTP`, `GOOGLE_CLIENT_ID` | Dev OTP và Google audience |
| Mail | `MAIL_ENABLED`, `MAIL_FROM`, `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_SMTP_AUTH`, `MAIL_STARTTLS` | SMTP OTP |
| AI | `AI_ENABLED`, `AI_PROVIDER`, `AI_CHAT_PROVIDER`, `AI_EMBEDDING_PROVIDER`, `GEMINI_BASE_URL` | Bật AI/cấu hình provider `gemini` và endpoint API |
| AI | `AI_CHAT_MODEL`, `AI_EMBEDDING_MODEL`, `AI_EMBEDDING_DIMENSIONS`, `AI_MAX_OUTPUT_TOKENS` | Model, embedding 1024 chiều và output limit |
| Guest | `GUEST_TOKEN_LIMIT` | Ngân sách session mới; giá trị mẫu 5000 |
| Payment | `PAYMENT_PROVIDER`, `PAYMENT_WEBHOOK_SECRET` | Provider generic và HMAC secret |
| Billing | `BILLING_MAINTENANCE_ENABLED`, `BILLING_MAINTENANCE_BATCH_SIZE` | Bật expiry/reminder job và giới hạn mỗi lượt |
| Storage | `UPLOAD_DIR` | Thư mục file |
| OCR | `APP_OCR_ENABLED`, `APP_OCR_TESSDATA`, `APP_OCR_LANGUAGE` | Bật OCR, thư mục tessdata và ngôn ngữ |
| Redis | `REDIS_HOST`, `REDIS_PORT`, `REDIS_USERNAME`, `REDIS_PASSWORD`, `REDIS_DATABASE` | Cache connection |
| Redis | `REDIS_TIMEOUT`, `REDIS_CONNECT_TIMEOUT`, `REDIS_SSL_ENABLED` | Giá trị mẫu timeout 500 ms, SSL false |

Ứng dụng tự nạp `.env` UTF-8 từ working directory bằng `spring.config.import` của Spring Boot;
IDE, `mvn spring-boot:run` và `java -jar` đều dùng được khi working directory là thư mục chứa `pom.xml`
và `.env`. `application.yaml` ánh xạ các giá trị runtime qua `${VAR}`; các key và giá trị mẫu được
liệt kê trong [.env.example](../.env.example). Nếu đã có `.env`, bổ sung key mới và giữ cấu hình hiện tại.
Muốn dùng file khác, đặt biến môi trường `APP_ENV_FILE` trước khi khởi động hoặc truyền `-EnvFile` cho script local.

Biến môi trường hệ điều hành/container được ưu tiên hơn file. Import optional cho phép chạy Docker
chỉ bằng biến môi trường khi đã cấp đủ các key bắt buộc. File dùng `KEY=value` không bọc nháy, không có
`export` hoặc comment cuối dòng; comment đặt trên dòng riêng. Dùng `/` cho đường dẫn Windows và tránh
key trùng. Sửa `.env` rồi restart backend.

`DEBUG`/`TRACE` là tùy chọn logging của Spring Boot; mức log HTTP/SQL được cấu hình bằng các key trong `.env.example`.

### 4.3. Bật AI

Đây là giá trị mẫu của repository:

~~~dotenv
AI_ENABLED=true
AI_PROVIDER=gemini
AI_CHAT_PROVIDER=openai
AI_EMBEDDING_PROVIDER=gemini
AI_CHAT_MODEL=gemini-2.5-flash-lite
AI_EMBEDDING_MODEL=gemini-embedding-001
AI_EMBEDDING_DIMENSIONS=1024
GEMINI_BASE_URL=https://generativelanguage.googleapis.com/v1beta/openai
AI_MAX_OUTPUT_TOKENS=2000
~~~

`AI_ENABLED=true` cần `GEMINI_API_KEY` hợp lệ, model được tài khoản cấp quyền và bean chat/embedding tương ứng.
Runtime dùng `spring.ai.model.chat=openai`, `spring.ai.model.embedding=none`,
`spring.ai.openai.base-url`, `spring.ai.openai.chat.model` và `app.ai.embedding-model`;
cần `GEMINI_API_KEY`. Constructor chỉ nhận provider `gemini` và embedding dimension 1024.
Lưu config admin không kiểm tra hoặc thay thế API key. Đổi model embedding cần restart/index lại;
model có số chiều khác cần migration phù hợp. Xem lệnh Docker/init/smoke tại [gemini.md](gemini.md).

### 4.4. Redis, container và OCR

~~~powershell
docker compose up -d --wait redis
docker compose exec redis redis-cli PING
~~~

`PONG` xác nhận Redis. Local Redis không auth/TLS; credentials trống, SSL false. Switch `-WithInfrastructure` chỉ chạy PostgreSQL.

Redis giữ JSON envelope `schema_version/identity/response`, gồm toàn bộ kết quả FE. ATS TTL tối đa 24 giờ dùng `edunexus:ats:response:v1:<userId>:<resumeId>:<jobId>:<inputHash>`; matching tối đa 1 giờ dùng `edunexus:matching:response:v1:<userId>:<requestHash>` và không vượt tuổi run/deadline job. Phân tích CV dùng `edunexus:resume-analysis:response:v1:<userId>:<resumeId>:<inputHash>`, tối đa 24 giờ. Cache hit không đọc lại analysis/run/results nhưng vẫn kiểm tra owner, đầu vào và hiệu lực tài nguyên bằng DB. Hash xét nội dung/ngôn ngữ/model/prompt tương ứng; matching còn xét catalog/vector/chính sách lương. ATS/matching Redis miss có DB fallback và DB hit ghi lại cache; phân tích CV Redis miss tạo báo cáo mới, không có DB lookup theo hash. Ghi Redis sau commit, rollback không publish; `cached=true` của ATS/matching có thể đến từ DB.

Trong app container, `localhost` là container app; cần host DB/cache truy cập được. `compose.production.yaml` chỉ tạo app, không tạo PostgreSQL/Redis.

OCR trong mẫu cấu hình tắt; `app.ocr.enabled/tessdata/language` ánh xạ từ các key sau trong `.env`:

~~~dotenv
APP_OCR_ENABLED=true
APP_OCR_TESSDATA=C:/path/to/tessdata
APP_OCR_LANGUAGE=eng+vie
~~~

Cần Tesseract/native libraries và language data tương thích. Các key này đã có trong `.env.example`.
Bật AI không tự bật OCR.

## 5. Tài khoản, phiên, guest và Mailtrap

### 5.1. Register → OTP → verify → login

Luồng code: `AuthController → AuthFlowService → AuthService → AuthRepository → SqlStore`; `SecurityConfig` cung cấp BCrypt strength 12, `TokenService` quản lý token.

~~~mermaid
sequenceDiagram
    participant Client
    participant Auth as AuthController / AuthFlowService
    participant Service as AuthService
    participant DB as PostgreSQL
    participant SMTP as SMTP / Mailtrap
    Client->>Auth: POST /auth/register
    Auth->>Service: Validation + rate limit
    Service->>DB: User PENDING, hash OTP, expiry
    alt Development OTP
        Service-->>Client: developmentCode
    else SMTP enabled
        Service->>SMTP: SimpleMailMessage
        SMTP-->>Service: SMTP response
        Service-->>Client: 200, expiresIn 600
    end
    Client->>Auth: POST /auth/verify-email
    Auth->>Service: email + code
    Service->>DB: Lock OTP, tăng attempt, so hash
    Service->>DB: ACTIVE + verified_at + profile
    Service-->>Client: Email verified
    Client->>Auth: POST /auth/login
    Service->>DB: Password/ACTIVE + lưu token hashes
    Service-->>Client: accessToken + refreshToken
~~~

Register yêu cầu email hợp lệ/max255, password 10–72 ký tự và tối đa **72 byte UTF-8**, fullName không trống/max255, language tùy chọn `vi|en`. Service trim/lowercase email, trim tên; user mới `EMAIL/CANDIDATE/PENDING`, language mặc định vi.

`issueCode` kiểm tra mã mới trong 60 giây → expire mã chưa verified cũ → sinh chuỗi 6 số → BCrypt mã → lưu expiry 10 phút → gửi SMTP hoặc trả development code.

Thư plain text: subject `EduNexus email verification`, body `Your verification code is <code>. It expires in 10 minutes.`. Hiện không đổi ngôn ngữ theo profile.

~~~json
{"message":"If this account is pending, a verification code will be sent","expiresIn":600}
~~~

Dev mode thêm `developmentCode`. Register chưa active và chưa cấp token.

Verify tìm mã mới nhất chưa verified/chưa hết hạn của user pending, khóa `FOR UPDATE OF c`, tăng attempt trước so hash. Đúng mã → verified timestamp, user ACTIVE, tạo candidate profile nếu chưa có:

~~~json
{"message":"Email verified; you can log in"}
~~~

Sau đó gọi login riêng. Password đúng nhưng pending trả 403.

### 5.2. Resend, giới hạn và transaction

`POST /auth/resend-code` nhận email, chỉ gửi cho pending. User không tồn tại/không pending nhận thông điệp chung, không có expiresIn và không gửi. Nhánh response/cooldown không hoàn toàn giống nhau.

| Quy tắc | Giá trị/hành vi |
| --- | --- |
| OTP lifetime/cooldown | 10 phút / 60 giây mỗi user |
| Nhập sai | 5 lần đầu 400; request tiếp theo 429 dù mã đúng |
| Register/resend | Mỗi API 10 request/IP/giờ |
| Verify | 30/IP/10 phút |
| Login | 100/IP/15 phút + 15/email/15 phút |
| Refresh | 100/IP/15 phút |
| Google | 30/IP/15 phút |

Limiter `AuthThrottle` là memory từng process; IP từ `request.getRemoteAddr()`, không chia sẻ replica, restart mất count. Cooldown dùng query DB nhưng chưa khóa user để chống resend đồng thời tuyệt đối.

Register/resend transaction rollback nếu mail disabled/thiếu sender/gửi lỗi. Verify dùng `noRollbackFor=ApiException.class` để giữ attempt nhập sai; DTO sai định dạng chưa vào service nên không tăng attempt DB. Verify đúng cũng tăng attempt trước khi mark verified.

### 5.3. Token và profile cá nhân

Access là JWT HS256 hạn 15 phút; refresh ngẫu nhiên `r_` hạn 30 ngày. **Hash SHA-256 của cả access và refresh** nằm trong `refresh_tokens`. Mỗi bearer kiểm tra DB user/role hiện tại và revoke/expiry.

`TokenService` dùng `NimbusJwtEncoder`/`NimbusJwtDecoder` của Spring Security, chỉ chấp nhận HS256,
yêu cầu `sub` UUID, `exp`, `iat`, `jti` và kiểm hạn dùng, `nbf` nếu có, thời điểm phát hành.
`sub` phải trùng user sở hữu token trong database; tài khoản phải `ACTIVE` và chưa xóa.

~~~json
{"accessToken":"<JWT>","refreshToken":"r_<random>","tokenType":"Bearer","expiresIn":900}
~~~

`POST /auth/refresh` nhận `refreshToken`, xoay refresh và trả cặp mới; refresh cũ không dùng lại. Rotate refresh không tự revoke access cũ. `POST /auth/logout` revoke mọi token.

Rotation khóa account trước token, đối chiếu chủ sở hữu và yêu cầu UPDATE thu hồi có điều kiện cập nhật
đúng một bản ghi. UPDATE kiểm hạn bằng `clock_timestamp()` tại thời điểm sử dụng, kể cả sau khi chờ lock.
Thu hồi token cũ và lưu cặp mới nằm trong cùng transaction.

`GET/PUT /me` xem/sửa fullName/avatarUrl/preferredLanguage/githubUrl. `PUT /me/password` nhận currentPassword/newPassword, chỉ EMAIL, revoke mọi token. `GET /me/sessions` liệt kê token rows còn hiệu lực, không phải mô hình session thiết bị hay cặp token. `DELETE /me/sessions/{id}` revoke row thuộc user. `DELETE /me` soft delete; bảo vệ admin active cuối cùng.

### 5.4. Google và guest

Google: `POST /auth/google` với idToken, cần GOOGLE_CLIENT_ID. `GoogleTokenVerifier` kiểm tra signature/issuer/audience/expiry/sub/email_verified. Code coi Gmail hoặc token có claim hd là authoritative → user mới ACTIVE. Email bên thứ ba cần OTP hệ thống. Email đã có account khác trả409, không tự link. Google pending đã tồn tại trả emailVerificationRequired=true; gọi resend riêng. Sau OTP, Google user đăng nhập tiếp bằng Google.

Guest: `GuestController → GuestSessionService/GuestAccess → GuestSessionRepository/GuestAccessRepository`. Tạo `POST /guest/sessions` không body: key ngẫu nhiên `g_`, DB giữ hash, hạn24 giờ/quota5000 mặc định, 3 session/IP/ngày. Lưu sessionKey lúc trả về; gửi `X-Guest-Session` ở API trial.

Guest chỉ dùng demo/template hệ thống. Trial builder cần AI; recommendations rule-based tối đa500 jobs/top3, thu100 credit cố định khi thành công. Chat guest dùng quota trial; không RAG cá nhân. Session hết hạn/đã converted không được dùng. Hiện không thấy API chuyển history guest sang tài khoản đăng ký; register là luồng riêng.

### 5.5. Mailtrap và SMTP

Project đã có starter mail. `spring.mail` cấu hình connection; `app.mail.enabled` và `app.auth.expose-development-code` quyết định gửi. `MAIL_FROM` map vào app.mail.from và app.auth.mail-from; OTP dùng property thứ hai.

| AUTH_EXPOSE_DEV_OTP | MAIL_ENABLED | Kết quả |
| --- | --- | --- |
| true | false/true | Trả code, **bỏ qua SMTP** |
| false | false | Luồng cần gửi OTP 503 và rollback |
| false | true | Gửi SMTP; lỗi 503 |

**Sandbox:** theo [Mailtrap](https://docs.mailtrap.io/email-sandbox/overview), giữ email kiểm thử trong Mailtrap, không gửi tới hộp thư thật và không cần verify domain. Chọn **Sandboxes → sandbox → Integration → SMTP**, lấy credentials.

~~~dotenv
MAIL_ENABLED=true
MAIL_HOST=sandbox.smtp.mailtrap.io
MAIL_PORT=2525
MAIL_USERNAME=your-sandbox-username
MAIL_PASSWORD=your-sandbox-password
MAIL_SMTP_AUTH=true
MAIL_STARTTLS=true
MAIL_FROM=noreply@edunexus.test
AUTH_EXPOSE_DEV_OTP=false
~~~

Host/port/STARTTLS theo [Sandbox setup](https://docs.mailtrap.io/email-sandbox/setup) và [Sandbox help](https://docs.mailtrap.io/email-sandbox/help). Restart backend, register email mới, đọc OTP trong đúng sandbox, verify rồi login.

**Gửi thật:** Mailtrap **API/SMTP → Sending Setup → domain → Transactional Stream → SMTP**. Verify domain/DNS/compliance, dùng token được quyền gửi. Domain Demo chỉ gửi tới email đăng ký Mailtrap. Xem [SMTP integration](https://docs.mailtrap.io/email-api-smtp/setup/smtp-integration) và [Sending domain](https://docs.mailtrap.io/email-api-smtp/setup/sending-domain).

~~~dotenv
MAIL_ENABLED=true
MAIL_HOST=live.smtp.mailtrap.io
MAIL_PORT=587
MAIL_USERNAME=api
MAIL_PASSWORD=your-mailtrap-api-token
MAIL_SMTP_AUTH=true
MAIL_STARTTLS=true
MAIL_FROM=noreply@your-verified-domain.com
AUTH_EXPOSE_DEV_OTP=false
~~~

Không dùng credentials sandbox cho live. Code hiện map STARTTLS, chưa có biến riêng bật implicit SSL; dùng 2525/587 thay vì chỉ đổi sang465. Timeout connection/read5000ms. Gửi đồng bộ trước commit DB; chưa queue/outbox/auto retry. SMTP success chưa bảo đảm inbox delivery; kiểm tra Mailtrap logs/hộp nhận. Health mail indicator đang tắt.

SMTP hiện chỉ gửi OTP. Thông báo nghiệp vụ nằm trong database; draft email ứng tuyển chưa được gửi.

## 6. Career profile và CV

### 6.1. Hồ sơ nghề nghiệp

`ProfileController → ProfileService → ProfileRepository` đọc/ghi `candidate_profiles` và `candidate_profile_skills`.

~~~json
{
  "targetJobTitle": "Java Backend Developer",
  "careerGoal": "Phát triển backend bằng Spring Boot",
  "yearsExperience": 1,
  "expectedSalary": 7500000,
  "salaryCurrency": "VND",
  "preferredLocation": "Ho Chi Minh",
  "skills": [
    {
      "skillId": "<UUID từ GET /skills>",
      "proficiencyLevel": "intermediate",
      "yearsExperience": 1
    }
  ]
}
~~~

`PUT /me/career-profile` tạo profile nếu thiếu → kiểm tra một mức lương kỳ vọng theo tháng/skill active và không trùng → ghi scalar → thay skill links nếu có list → invalidate profile embeddings và đánh dấu mọi roadmap user `is_outdated=true`.

Đây là PUT: cần gửi đủ scalar muốn giữ vì field bỏ qua thường được ghi null. `skills` bỏ qua/null giữ links cũ; `skills: []` xóa links. Currency VND/USD/EUR, experience0–99.9, tối đa100 skill. GET trả SQL fields snake_case. Không có API profile embedding riêng; matching xử lý vector từ input profile.

### 6.2. Ba đường tạo CV

Luồng chung: `ResumeController → ResumeService → ResumeRepository`. `ResumeData` chuẩn hóa JSON CV;
`ResumeInput` kiểm tra đầu vào; `ResumeContent` dựng nội dung chuẩn, hash và kết quả kiểm tra cơ bản;
`ResumeFileExtractor` trích xuất tệp. Các helper này nằm trong `util` theo vai trò/chức năng.

| Cách | API | Source/status sau tạo |
| --- | --- | --- |
| Manual | `POST /resumes` | MANUAL / DRAFT |
| AI builder | `POST /resumes/build` | AI_BUILDER / REVIEW_REQUIRED |
| Upload | `POST /resumes/upload` multipart | UPLOAD / REVIEW_REQUIRED |

Body manual tối thiểu:

~~~json
{
  "title": "CV Java Backend",
  "language": "vi",
  "structured_data": {
    "full_name": "Nguyễn An",
    "summary": "Đã xây dựng REST API với Java và PostgreSQL",
    "experience": [],
    "education": [],
    "projects": [
      {
        "project_name": "CV Matching",
        "role": "Backend",
        "description": "Các công việc thực tế đã thực hiện",
        "technologies": ["Java", "Spring Boot", "PostgreSQL"]
      }
    ],
    "certifications": [],
    "skills": ["Java", "SQL"]
  }
}
~~~

`experience`/`education` dùng số ít; `projects`/`certifications`/`skills` dùng số nhiều. Title bắt buộc/max255; language vi/en; raw_content tối đa200000 ký tự. `template_id` tùy chọn phải active.

Manual validate/normalize/hash dữ liệu rồi tạo DRAFT; các bảng section chi tiết được ghi khi confirm. AI builder thêm career profile vào facts input → `AiService.generate("CV_BUILD",...)` → normalize output → lưu REVIEW_REQUIRED. Template ID được kiểm tra và chuyển vào input; hiện chưa load nội dung template để render PDF/DOCX.

`ResumeData` giữ field allowlisted, chuẩn hóa skill string thành object, giới hạn100 entry/section và100 technologies, kiểm tra ngày ISO/đầu cuối, experience current không có end_date. Chỉ thông tin user cung cấp là dữ kiện cho AI.

### 6.3. Upload và parsing

1. Controller nhận `file`, `title` tùy chọn, `language` mặc định vi.
2. Extractor kiểm tra size/extension/MIME/signature và đọc text.
3. Ghi bytes với UUID storage key, tạo file mới trong UPLOAD_DIR.
4. Nếu chat AI bật và text không rỗng, gọi `CV_PARSE`.
5. Lưu `resumes` và `resume_parse_results`; nếu AI parse phát sinh ApiException, vẫn giữ CV để review và lưu trạng thái/lỗi parsing.
6. Nếu database transaction rollback, xóa file mới ghi.

| Loại | Điều kiện |
| --- | --- |
| Chung | File tối đa10MiB, multipart request tối đa11MB, text tối đa200000 ký tự |
| PDF | Đúng signature, không encrypted, tối đa50 trang; PDFBox lấy text |
| PDF scan | OCR nếu bật và text rỗng; nếu OCR chưa bật có thể giữ CV với text rỗng để nhập thủ công |
| DOCX | ZIP chứa word/document.xml; tối đa2000 entries, expanded50MiB; POI đọc |
| TXT | UTF-8 hợp lệ, không NUL |
| PNG/JPEG | OCR phải bật; signature/image hợp lệ, tối đa25 triệu pixels; OCR tắt trả503 |

MIME phải tương thích extension; application/octet-stream được chấp nhận theo extractor. Tạo CV/upload thành công không đồng nghĩa AI đã hiểu CV; xem parse_results và nội dung thực tế trước confirm.

### 6.4. Review, confirm, primary và archive

~~~mermaid
stateDiagram-v2
    [*] --> DRAFT: Manual
    [*] --> REVIEW_REQUIRED: Upload / AI build
    DRAFT --> READY: Confirm
    REVIEW_REQUIRED --> READY: Review + confirm
    READY --> REVIEW_REQUIRED: PATCH đổi nội dung/title/language
    DRAFT --> ARCHIVED: DELETE
    REVIEW_REQUIRED --> ARCHIVED: DELETE
    READY --> ARCHIVED: DELETE
~~~

GET detail trả CV và sections/skills/parse_results. PATCH sửa title/language/raw/structured; nội dung thay đổi đưa về REVIEW_REQUIRED, bỏ primary, invalidate embedding và roadmap liên quan.

`POST /resumes/{id}/confirm` nhận body tùy chọn:

~~~json
{"is_primary":true}
~~~

Có thể gửi structured_data đã sửa. Confirm không sửa title/language; dùng PATCH cho các field đó. Cần nội dung có ý nghĩa: summary/raw hoặc một section/skill; chỉ full_name/email chưa đủ.

Service normalize → khóa user để primary an toàn → thay `resume_experiences`, `resume_educations`, `resume_projects`, `resume_certifications`, `resume_skills` → resolve/upsert skill theo catalog/slug, deduplicate → READY. Section relational sau PATCH có thể vẫn là snapshot lần confirm cũ đến khi confirm lại.

`POST /resumes/{id}/primary` chỉ chọn READY, bỏ primary cũ. DELETE soft archive, bỏ primary, invalidate, trả204; không xóa file gốc/vật lý. List lọc archived nhưng owner vẫn có thể GET/download archived bằng ID trong code hiện tại.

### 6.5. Analysis, embedding và download

**Analysis:** READY → nếu chat bật gọi CV_ANALYZE, lưu analysis/suggestions; nếu AI tắt chạy RULE_BASED. Baseline cho20 điểm mỗi nhóm summary/experience/education/skills/projects có nội dung: đo độ đầy đủ, không đo chất lượng thực tế và `ats_score=null`. Báo cáo CV chung không thay thế JOB_ATS so CV–job.

**Embedding:** READY + embedding provider → canonical text/hash → dùng lại vector current cùng provider/model/hash nếu có → nếu chưa có tạo indexing job, gọi embed, kiểm tra lại CV sau network →409 nếu CV đổi → lưu vector/current và indexing status. Raw vector không trả API. Failure giữ indexing job FAILED để tra cứu.

**Canonical/invalidation:** canonical ưu tiên structured content đã review, fallback raw extraction, có title. Hash phụ thuộc title/language/raw/structured. Sửa/confirm đổi nội dung/archive làm vector noncurrent và roadmap phụ thuộc outdated.

**Download:** default trả original upload nếu có; nếu không có trả UTF-8 TXT. `?text=true` trả canonical text. Có ownership/path validation, attachment và nosniff. Chưa render PDF/DOCX theo template.

Các API CV/assistant private kiểm tra đăng nhập và ownership; không nên mô tả tất cả đều có gate CANDIDATE như matching hoặc create assessment.

## 7. AI và quota

### 7.1. Luồng gọi dùng chung

`AiService` là điểm điều phối chat/embedding; `AiRepository` đọc config/prompt, `EntitlementService` hoặc `GuestAccess` đặt ngân sách, `AiUsageRecorder` ghi telemetry.

~~~text
Service nghiệp vụ
→ chọn feature + input
→ AiService kiểm tra provider/enabled
→ chọn model/config/prompt active + schema JSON
→ tính budget và reserve
→ ChatModel / EmbeddingModel
→ parse + validate output
→ settle actual usage / cancel khi chưa có response
→ ghi ai_usage_logs
→ service lưu kết quả nghiệp vụ
~~~

Các feature: `CV_BUILD`, `CV_PARSE`, `CV_ANALYZE`, `JOB_MATCH_RERANK`, `JOB_ATS`, `ASSESSMENT_GENERATE`, `ROADMAP_GENERATE`, `APPLICATION_EMAIL`, `CAREER_CHAT`, `SALARY_ESTIMATE`; embedding dùng `EMBEDDING`.

Chat dùng system guidance yêu cầu JSON/facts/language, coi CV/job/history/document là dữ liệu không đáng tin cậy. Top-level language vi/en quyết định ngôn ngữ, mặc địnhvi. Output được validate theo feature: score, required strings/arrays, salary range...

Model chat lấy từ CHAT config active phù hợp provider process hoặc fallback env. Prompt active theo feature được nối vào guidance. `userPromptTemplate` hiện là text hướng dẫn, chưa có engine nội suy placeholder.

### 7.2. Tính ngân sách và settlement

| Bước | Hiện thực |
| --- | --- |
| Chat reserve | UTF-8 bytes(system+input) +512 + maxOutputTokens |
| Embedding reserve | UTF-8 bytes(text) +128 |
| Output limit | Cấu hình clamp256–16000, mặc định2000 |
| Input limits | Chat input/output string250000 ký tự; embedding text200000 |
| User reservation | REQUIRES_NEW, khóa user, effective tier, ghi ai_token_reservations |
| Guest reservation | REQUIRES_NEW, atomic budget increment, trial_usage_events PROCESSING |
| Total quota | AI_TOTAL_TOKENS bắt buộc; quota feature0 chặn feature |
| Period | DAILY/MONTHLY/YEARLY/LIFETIME |
| Usage tính quota | Budget RESERVED + actual COMPLETED; CANCELLED không tính |

Budget byte là upper estimate bảo thủ, không phải tokenization thực tế. Entitlement giới hạn reservation1–200000; input Unicode lớn có thể vượt budget trước khi chạm char limit.

Nếu provider đã trả response, settle usage thực tế không vượt reservation; thiếu/zero token metadata tính cả budget đặt chỗ. Output JSON sai sau khi nhận response vẫn có thể bị tính phí. Nếu chưa nhận response, cancel/refund phần reservation theo code. Guest settlement khóa event, chống refund lần hai.

`AiUsageRecorder` ghi độc lập transaction, có thể còn log/usage khi nghiệp vụ ngoài rollback. Không phải mọi request bị từ chối trước provider đều có ai_usage_log.

### 7.3. Chạy khi AI tắt

| Chức năng | Hành vi |
| --- | --- |
| Manual CV/upload text/review/confirm/download | Hoạt động |
| CV analysis | RULE_BASED completeness |
| Matching | SKILL_OVERLAP |
| Trial recommendations | Keyword overlap |
| AI builder/ATS/email draft/assessment/roadmap/chat/salary | Cần chat provider, thường503 khi chưa bật |
| CV/job/RAG index, RAG retrieval | Cần embedding provider |
| Tư vấn người thật/billing/admin/support | Không cần AI |

AI dùng adapter chat giao thức OpenAI và adapter embedding native để gọi Gemini; chat mặc định `gemini-2.5-flash-lite`, embedding `gemini-embedding-001`.
Embedding phải có đúng 1024 giá trị hữu hạn để khớp schema V10; vector legacy 1536 được giữ riêng
và không dùng trong truy vấn hiện tại. Runtime không nhận provider khác chỉ bằng đổi tên cấu hình.

## 8. Việc làm, crawling, matching và ứng tuyển

### 8.1. Nạp dữ liệu việc làm

Job public đi `JobController → JobService → JobRepository`. GET jobs tìm q/location bằng ILIKE, chỉ ACTIVE/chưa expired; GET skills chỉ active. GET job có sources aliases. Chưa import/crawl thì danh sách job có thể trống.

**Manual import admin:** `POST /admin/jobs` nhận:

~~~json
{
  "sourceSite": "ITVIEC",
  "sourceUrl": "https://itviec.com/it-jobs/example",
  "jobTitle": "Java Developer",
  "companyName": "Example",
  "location": "Ho Chi Minh",
  "salaryText": "Thỏa thuận",
  "roleCategory": "Backend",
  "content": "Nội dung tuyển dụng thực tế từ nguồn, có yêu cầu Java và SQL.",
  "employmentType": "FULL_TIME",
  "requirements": "Có kinh nghiệm Java và SQL.",
  "responsibilities": "Xây dựng REST API và bảo trì backend.",
  "benefits": "Quyền lợi có trong nguồn.",
  "postedAt": "2026-10-01T08:00:00+07:00",
  "validThrough": "2030-12-31T23:59:59+07:00",
  "skills": [
    {"name":"Java","required":true},
    {"name":"SQL","required":true}
  ]
}
~~~

URL ví dụ phải thay bằng URL chi tiết nguồn thực. Content normalize HTML thành text tối thiểu30 ký tự; giữ HTML gốc. `JobSourceValidator` chỉ chấp nhận HTTPS đúng host ITVIEC/TOPCV, không credentials/explicit port.

Import nhận15 fields trên, dates có offset; bỏ metadata tùy chọn nếu nguồn không có và dùng ngày/nội dung thực. Normalize location/employment/skills và HTML → text; hash gồm metadata/skills/dates. Unchanged ACTIVE refresh source alias/deduplicated=true; inactive cùng hash trả409. Source đổi version, canonical cũ chỉ REMOVED khi không còn alias ACTIVE. Fingerprint bảo thủ có company và cùng title/location/content/requirements chuẩn hóa để link nguồn khác; không fuzzy merge. Lưu source aliases/skills/audit và enqueue embedding tự động.

### 8.2. Crawling

`JobCrawlController → JobCrawlService → JobCrawlRepository/JobService`:

~~~json
{
  "sourceSite": "ITVIEC",
  "keyword": "Java",
  "roleCategory": "Backend",
  "location": "Ho Chi Minh",
  "limit": 20,
  "maxPages": 3
}
~~~

Crawl bỏ urls/null/[] tự tìm detail URL từ keyword trên nguồn. Nếu có urls, dùng tối đa20 URL chi tiết đã biết. Source ITVIEC/TOPCV, keyword/roleCategory bắt buộc; limit1–20 mặc định20, maxPages1–5 mặc định3. API crawl vẫn tuần tự; collection định kỳ dùng cùng pipeline, mặc định tắt, cần admin thật và PostgreSQL lease. Xem [cấu hình collection](job-collection-setup.md).

Fetch timeout5s mỗi connection, body≤2MiB, tối đa3 redirect kiểm tra host. Ưu tiên một JSON-LD JobPosting, hỗ trợ @graph/array; thiếu thì fallback HTML selector theo nguồn. Không chạy JavaScript/headless browser/bypass challenge. Fixture tests không chứng minh website nguồn hiện tại tương thích.

Location lọc substring không dấu sau alias normalization; không dịch mọi địa điểm. Lấy requirements/responsibilities/benefits/employmentType/dates qua metadata/sections và skills qua dictionary; imported_skills có thể tăng. Dictionary không bao quát mọi skill; admin có thể cung cấp dữ liệu đúng bằng import manual. Expiry ngày-only dùng cuối ngày Asia/Ho_Chi_Minh; HTTP404/410/deadline quá hạn chỉ hết hạn alias nguồn, giữ canonical nếu alias khác còn hiệu lực.

Mỗi item xử lý độc lập; lỗi một URL không rollback job khác. Search outcomes SEARCH_FETCHED/SEARCH_FETCH_FAILED; detail outcomes IMPORTED/DUPLICATE/EXPIRED/WRONG_REGION/REJECTED/FETCH_FAILED/IMPORT_FAILED; run COMPLETED/PARTIAL/FAILED. GET crawl list/detail xem items và search_pages_checked/candidate_urls_discovered/imported_skills. Scheduler expiration mặc định15 phút, chỉ vô hiệu canonical/vector khi không còn source ACTIVE/unexpired. Tin không có deadline không tự hết hạn theo tuổi crawl.

### 8.3. Index và vòng đời job

Import/crawl/status ACTIVE enqueue embedding tự động vào vector_indexing_jobs theo job/provider/model/content_hash. Worker claim lease/SKIP LOCKED, có retry hữu hạn và quota admin; kiểm lại ACTIVE/expiry/hash/lease trước publish. Provider tắt để task chờ, không sinh vector giả. QUEUED → PROCESSING → COMPLETED; retry lỗi rồi FAILED hoặc dữ liệu không hợp lệ CANCELLED.

`GET /admin/jobs/{id}/index` dành ADMIN chỉ đọc, trả job_id/indexing_jobs tối đa20 task mới nhất. `POST` cùng path enqueue/reuse và thử xử lý khi claim được; HTTP200 có thể QUEUED/PROCESSING/FAILED/CANCELLED, không luôn COMPLETED. Dùng error_message/attempt_count/provider/model/content_hash để kiểm tra trước semantic matching.

`PATCH /admin/jobs/{id}/status` nhận ACTIVE/EXPIRED/REMOVED/INVALID. Non-ACTIVE làm vector noncurrent và cập nhật alias; ACTIVE enqueue lại, không restore vector ngay. Deadline đã qua không được activate; cần source version mới. Chi tiết parsing/dedup/queue ở [crawling.md](crawling.md).

### 8.4. Matching

`CareerToolsController → CareerToolsService → MatchingService → MatchingRepository`, yêu cầu candidate.

~~~json
{"basis":"SELECTED_CV","resumeIds":["<READY_RESUME_UUID>"],"limit":20}
~~~

`limit` bắt buộc1–100; bỏ field làm giá trị mặc định0 bị validation từ chối. Basis:

- CAREER_PROFILE: dùng profile/skills.
- SELECTED_CV: đúng1 CV READY thuộc user.
- ALL_CVS: tất cả CV READY của user, tối đa100, không dùng demo.

AI tắt: so required-skill keyword overlap, không required skill thì score0; kết quả ghi SKILL_OVERLAP, không giả vector/LLM score.

AI bật: canonical candidate text → embed → retrieve job ACTIVE/vector current cùng provider/model, top min(100,max(limit,20)) → JOB_MATCH_RERANK → validate/save run/results. Chưa có job indexed phù hợp trả409. Kết quả có score0–1, reason, matched/missing skills.

Lưu job_matching_runs, matching_run_resumes, job_match_results. Hash cache gồm input CV/profile, metadata/nguồn job hiện hành, catalog/vector/provider/model/prompt/basis/limit và chính sách lương; Redis giữ response tối đa 1 giờ, DB fallback tìm run hoàn tất trong 1 giờ. GET matching history/detail chỉ user. Feedback PUT LIKE/DISLIKE, rating1–5/comment lưu job_match_feedback.

### 8.5. ATS và công cụ ứng tuyển

| Chức năng | Luồng và output |
| --- | --- |
| ATS | READY owned CV + ACTIVE job → hash toàn bộ input/ngôn ngữ/provider/model/prompt → Redis response TTL24h hoặc tái dùng DB cùng hash rồi rewarm; DB không có TTL riêng → JOB_ATS khi chưa có kết quả → job_resume_analyses, score0–100/strengths/missing/suggestions |
| CV hướng job | Job requirements + dữ kiện thật user → AI CV → REVIEW_REQUIRED → review/confirm |
| Email/cover letter | APPLICATION_EMAIL → hai application_drafts loại APPLICATION_EMAIL và COVER_LETTER |
| Sửa draft | PATCH content, kiểm tra ownership |
| External apply | Validate job source + optional READY CV → external_apply_events REDIRECT → event_id/target_url |
| Salary | SALARY_ESTIMATE với career profile + job hoặc target vị trí → salary_estimations min/max/currency/reasoning |

Frontend mở target_url để người dùng ứng tuyển trên trang nguồn. Backend chưa tự submit hồ sơ và chưa gửi email draft qua SMTP. Salary là ước lượng AI, không phải dữ liệu lương đã kiểm chứng.

## 9. Assessment và roadmap

`LearningController → LearningService/AssessmentService → LearningRepository/AssessmentRepository`. Tạo assessment/roadmap yêu cầu candidate; các thao tác khác kiểm tra owner.

### 9.1. Assessment: sinh → trả lời → nộp

~~~json
{"targetJobTitle":"Java Developer","jobId":"<OPTIONAL_JOB_UUID>"}
~~~

Target lấy từ request, job hoặc career profile. `ASSESSMENT_GENERATE` nhận mục tiêu/job/profile và yêu cầu câu hỏi foundation+technical. Code validate2–30 câu, có cả part1 và part2; không bảo đảm đúng10 câu dù prompt đề nghị5+5.

Lưu `assessment_sessions` và `assessment_questions`, set current, phiên cũ cùng user/target noncurrent. GET chưa completed ẩn correct_option/explanation.

~~~json
{"questionId":"<QUESTION_UUID>","selectedOption":"B"}
~~~

PUT answers khóa session, kiểm tra in_progress và question đúng session, upsert A/B/C/D. Có thể đổi lựa chọn trước submit.

POST submit yêu cầu trả lời đủ → chấm theo đáp án server → nhóm skill/score0–100 → completed/skill_scores/submitted_at → đánh dấu roadmap cùng target outdated. Submit lại trả kết quả đã có; không sửa answer sau completed. Chấm/nộp không gọi AI mới. Sau completed có thể đọc đáp án/lời giải.

### 9.2. Tạo skill gap và roadmap

~~~json
{
  "targetJobTitle": "Java Developer",
  "jobId": "<OPTIONAL_JOB_UUID>",
  "resumeId": "<OPTIONAL_READY_RESUME_UUID>",
  "assessmentId": "<OPTIONAL_COMPLETED_ASSESSMENT_UUID>"
}
~~~

Nếu chọn assessment phải owned/completed/cùng target; nếu bỏ chọn, tìm completed current assessment của target. CV nếu truyền phải owned/READY, job phải active.

Assemble profile/skills/job/CV/assessment → ROADMAP_GENERATE → validate gaps/nodes → lock user → version mới theo target, gap cũ nonlatest → ghi gap_analyses/gap_analysis_skills → roadmaps/nodes → liên kết skill/prerequisites/resources catalog.

Nodes1–50, hours nguyên1–1000/node; prerequisites là số thứ tự1-based trỏ node trước, không cycle; gaps≤100, urgency0–100. Tổng giờ roadmap tính từ tổng giờ node. input_source ưu tiên ASSESSMENT → RESUME → JOB → PROFILE.

Resource lấy từ `learning_resources` active với title chứa tên skill (`ILIKE`), free trước, tối đa3/node; không lấy URL AI tự bịa. Resources rỗng là kết quả hợp lệ. Roadmap là API riêng, submit assessment không tự sinh roadmap.

### 9.3. Tiến độ và outdated

~~~json
{"status":"completed"}
~~~

Node status: not_started/in_progress/completed. Muốn học/complete node phải xong prerequisites. Reset hoặc lùi trạng thái node yêu cầu dependent nodes về not_started trước; sai thứ tự409.

Progress=floor(100 × completed/count); tất cả node completed thì roadmap completed, còn lại ready. Sửa profile/CV hoặc nộp assessment mới đánh dấu outdated. Outdated báo input đã đổi, không tự xóa progress và không chặn progress API; user tạo roadmap mới khi cần.

## 10. Assistant và RAG

### 10.1. Career assistant

`CareerAssistantController → CareerAssistantService → CareerAssistantRepository`.

Tạo conversation không gọi AI:

~~~json
{
  "title": "Luyện phỏng vấn Java",
  "context_type": "resume",
  "related_resume_id": "<OPTIONAL_UUID>",
  "related_job_id": "<OPTIONAL_UUID>",
  "related_gap_analysis_id": "<OPTIONAL_UUID>",
  "related_roadmap_id": "<OPTIONAL_UUID>"
}
~~~

Kiểm tra ownership CV/gap/roadmap và job active. Assistant CV reference không có gate READY như matching. Context_type là chuỗi, mặc định general; conversation ACTIVE.

Gửi:

~~~json
{"message":"Mình nên cải thiện CV thế nào?","retrieve":true}
~~~

1. Kiểm tra conversation ACTIVE/owned, message≤8000.
2. Lấy20 tin gần nhất theo thứ tự thời gian; thêm career profile/CV/job/gap/roadmap linked.
3. Nếu người dùng đăng nhập gửi retrieve=true, embed message và retrieve tối đa5 chunks cosine≥0.55 cùng provider/model; guest không đi qua nhánh này.
4. Chỉ dùng document COMPLETED, chưa disabled, do user sở hữu hoặc admin upload.
5. CAREER_CHAT → validate answer.
6. Khóa conversation, kiểm tra ACTIVE lại sau network.
7. Lưu USER/ASSISTANT messages và sources gắn đúng assistant reply, update conversation.
8. Trả reply và sources.

Retrieve true gọi embedding rồi chat, có quota hai lần. Không source vẫn có thể chat. Sources là chunks đã đưa vào model, chưa bảo đảm model sử dụng/trích dẫn từng nguồn.

Generate lỗi hoặc conversation bị archive trong lúc chờ thì không lưu reply; usage đã phát sinh vẫn có thể được tính/log. DELETE conversation soft archive/204; GET list hiện vẫn có archived. Feedback chỉ assistant message owned, rating nguyên1–5 tùy chọn, is_helpful boolean, feedback_text≤4000; insert feedback mới.

Guest assistant cùng service nhưng context trial và history của session, không dùng dữ liệu CV cá nhân; retrieve=true không bật RAG cho guest.

### 10.2. RAG admin

`RagAdminController → RagDocumentService → RagDocumentRepository`:

~~~json
{
  "title": "Hướng dẫn phỏng vấn Java",
  "content": "Nội dung tài liệu thực tế...",
  "sourceType": "ADMIN",
  "metadata": {"topic":"java"}
}
~~~

POST document → PENDING → chia chunks4000 ký tự, overlap300. Content tối đa100000. sourceType nếu có phải ADMIN; metadata nếu có phải object; không truyền explicit null cho hai field này.

POST index cần embedding provider → PROCESSING → bỏ qua chunk đã indexed đúng model/provider → embed/lưu rag_chunk_embeddings và indexing jobs → COMPLETED/metrics. Bước index commit riêng; lỗi FAILED có thể retry.

POST archive đặt embedding_status=FAILED và metadata.disabled=true/archived_at. Không có enum ARCHIVED cho document. Vectors giữ lại nhưng retrieval bỏ document disabled; không index document đã archive. Archive đồng thời không bị bước index kích hoạt lại.

## 11. Consultation và SSE

`CandidateConsultationController` tạo thread, `AdminConsultationController` phân công, `ConsultationController` xử lý message/SSE, `ConsultantConsultationController` cung cấp hồ sơ/báo cáo. Service chính `ConsultationService`; đọc báo cáo dùng `ConsultationAnalysisService`.

1. Candidate POST consultations với subject và description bắt buộc, requestType tùy chọn → OPEN thread.
2. Admin PUT consultantId → consultant phải ACTIVE/CAREER_CONSULTANT, thread OPEN.
3. Candidate owner, consultant hiện được assign hoặc admin đọc/gửi.
4. POST messages khóa thread, yêu cầu OPEN, lưu consultation_messages.
5. Publish SSE sau commit; đọc lịch sử qua GET messages.
6. Close giữ timestamp đầu tiên và không cho gửi tiếp.

Người không có quyền nhận404 để không lộ thread. Reassign ngắt SSE và quyền consultant cũ. Message read không mark tin của chính mình. List tối đa100 gần nhất; message có limit/offset.

### SSE

GET events phát connected/message, timeout5 phút. Registry trong memory process; không replay và không phát xuyên replicas. Frontend dùng fetch streaming để gửi bearer; EventSource thuần không hỗ trợ tùy ý Authorization header. Khi reconnect, tải lại messages để bù tin; adapter sự kiện chung cần được bổ sung nếu chạy nhiều app instances.

### Hồ sơ và báo cáo ứng viên

GET candidate-profile cung cấp career profile/skills và CV READY chỉ định bằng resumeId hoặc primary READY. CV phải thuộc candidate trong thread.

GET candidate-analyses đọc **7 nhóm đã lưu**: resume_analyses, ats_analyses, matching_results, salary_estimations, assessments completed metadata, gap_analyses, roadmaps. Không gọi AI mới/không tiêu quota.

Filter resumeId/jobId và limit1–100(default20)/offset≥0. Pagination riêng mỗi mảng. resumeId không lọc salary/assessment; jobId không lọc generic resume analysis. Thread khóa chia sẻ để assignment ổn định; candidate deleted trả404. API không thay thế endpoint tải file CV.

## 12. Subscription và thanh toán

### 12.1. Quyền/quota và tạo đơn

`BillingController → BillingQueryService → EntitlementService/BillingQueryRepository` đọc quyền. Effective tier dùng subscription ACTIVE/chưa hết hạn/tier active; quá hạn fallback FREE ngay, không chờ scheduler.

GET tiers công khai; GET me trả tier/quotas/tokensUsedThisMonth; history chỉ owner. Cancel subscription dừng quyền ngay, auto_renew=false; chưa tự refund.

`BillingService.order` nhận:

~~~json
{"tierId":"<PAID_TIER_UUID>","durationMonths":1}
~~~

Duration1–12; số tiền=priceMonthly trên server×months, tối đa99999999.99. Order PENDING, provider_order_id mặc định UUID order. FREE không cần mua. POST order chưa là thanh toán thành công.

### 12.2. Webhook

~~~mermaid
sequenceDiagram
    participant C as Candidate
    participant B as Billing backend
    participant G as Gateway adapter
    participant D as PostgreSQL
    C->>B: POST payments/orders
    B->>D: PENDING order, amount từ tier
    B-->>C: Order metadata
    Note over C,G: Checkout/provider adapter cần tích hợp
    G->>B: Raw webhook + timestamp + HMAC
    B->>B: Verify signature/time/body
    B->>D: Lock order, check amount/currency/provider, receipt
    B->>D: COMPLETED + ACTIVE subscription + PAID invoice + notification
    B-->>G: Accepted
~~~

Webhook `POST /payments/webhook` là server-to-server, không cần bearer nhưng cần chữ ký:

~~~json
{
  "orderId": "<ORDER_UUID>",
  "providerOrderId": "<PROVIDER_ORDER_ID>",
  "eventId": "<UNIQUE_EVENT_ID>",
  "status": "COMPLETED",
  "amount": 99000,
  "currency": "VND"
}
~~~

`WebhookVerifier` kiểm chữ ký **trước parse JSON**; raw body tối đa65536 ký tự Java. PAYMENT_WEBHOOK_SECRET ít nhất32 byte UTF-8. Header X-Payment-Timestamp là Unix seconds, lệch tối đa300s. Header X-Payment-Signature là64 ký tự hex HMAC-SHA256 của:

~~~text
timestamp + "." + rawBody
~~~

Ký đúng UTF-8 nguyên văn body; không format/serialize JSON lại sau ký. Status COMPLETED/FAILED/CANCELLED.

`BillingService` lock order → kiểm provider/providerOrderId/amount/currency → unique receipt(provider,event_id). Duplicate đúng order không tạo kết quả lần hai; event dùng cho order khác409; order finalized không đổi terminal status khác.

COMPLETED khóa user → cancel gói ACTIVE cũ → gói mới bắt đầu tại thời điểm thanh toán, không cộng dư hạn cũ → order completed → invoice PAID/subtotal=amount/tax0 → PAYMENT_SUCCESS notification, cùng transaction. FAILED/CANCELLED chỉ cập nhật order.

Hiện MANUAL_GATEWAY là hợp đồng webhook generic: chưa adapter tạo QR/checkout, truy vấn ngân hàng, refund hay tự thu tiền. Callback URL từ browser không kích hoạt gói.

### 12.3. Hóa đơn, report và maintenance

GET invoices/orders/reports chỉ owner. Payment report POST theo order có subject/description; admin resolve bằng resolution, không tự refund.

`SubscriptionMaintenance` chạy mặc định08:00 Asia/Ho_Chi_Minh, bật bằng BILLING_MAINTENANCE_ENABLED. Property app.billing.maintenance-cron/zone/batch-size có thể override; batch mặc định500, clamp1–1000 mỗi nhóm.

Expired ACTIVE → EXPIRED. Nhắc7 ngày (>1..7ngày),1 ngày (>now..1ngày), hết hạn; locks SKIP LOCKED và receipt tránh notify trùng. Chỉ tạo in-app notifications, không SMTP, không auto-renew charge.

## 13. Admin, thông báo, hỗ trợ và monitoring

### 13.1. Admin đầu tiên và tài khoản

Không seed user/mật khẩu admin. Đăng ký/xác minh account rồi chạy bootstrap SQL bằng quyền vận hành database:

~~~powershell
Get-Content -Raw scripts/bootstrap-admin.sql | docker compose exec -T postgres psql -U edunexus -d edunexus -v admin_email='you@example.com'
~~~

Thay DB user/database/email nếu khác mẫu. Script chỉ promote ACTIVE/verified account khi chưa có active admin, ghi audit BOOTSTRAP_ADMIN. Role bearer được đọc từ DB mỗi request.

`AdminController → AdminService → AdminRepository/AuditService`. GET users search/page/size(size1–100). POST user nhận email/fullName/role/temporaryPassword, tạo EMAIL ACTIVE và verified theo trusted admin flow, thêm career profile nếu candidate. Không gửi temporary password qua SMTP và chưa có forced-change flag.

PUT user cần fullName/role/status ACTIVE|SUSPENDED; không kích hoạt pending chưa verified hoặc reactivate deleted. Đổi role/inactive và DELETE revoke tokens; delete soft. Advisory/row locks bảo vệ active admin cuối cùng.

### 13.2. Tier/quota, AI configuration và prompt

Admin tạo/sửa tier và upsert quotas theo tier/feature. Quota không âm, period DAILY/MONTHLY/YEARLY/LIFETIME, tối đa100/item batch, không trùng feature. Field omitted trong quota batch không bị xóa. FREE phải giữ code FREE/active/giá0.

Config input:

~~~json
{
  "configType":"CHAT",
  "provider":"gemini",
  "modelName":"gemini-2.5-flash-lite",
  "configuration": {}
}
~~~

Prompt input:

~~~json
{
  "featureCode":"CAREER_CHAT",
  "name":"Career assistant",
  "systemPrompt":"Hướng dẫn hệ thống cho feature",
  "userPromptTemplate":"Hướng dẫn bổ sung cho input"
}
~~~

Không lưu API key/password/secret vào configuration; code stringify toàn bộ configuration rồi từ chối nội dung chứa `api_key`, `apikey`, `secret`, `password`, gồm cả tên key và value. Credentials qua env.

Save/restore lock group, deactivate phiên bản cũ, tạo UUID mới với version=max+1 và active. Restore v1 khi có v2 tạo v3 chứa nội dung v1, không ghi đè v1/v2. oldVersionId là UUID record, không phải số version.

Lưu/restore không gọi model, không bật AI, không kiểm tra hoặc thay thế API key. CHAT config đúng
provider `gemini` áp dụng lần chat kế tiếp; model phải được tài khoản cấp quyền. Prompt active theo feature áp dụng lần sinh tiếp.

### 13.3. Announcement và notification

`AdminAnnouncementController/AnnouncementController → AnnouncementService → AnnouncementRepository`.

~~~json
{
  "title":"Thông báo hệ thống",
  "content":"Nội dung thông báo",
  "targets":[{"type":"ALL"}]
}
~~~

Target ALL/ROLE/USER; ROLE nhận CANDIDATE|CAREER_CONSULTANT|ADMIN, USER cần active userId. Title≤255/content≤50000, targets1–100, expiresAt tùy chọn phải tương lai.

Create DRAFT → chỉ draft edit/publish → publish lock, kiểm expiry/target → PUBLISHED và tạo notifications cho distinct ACTIVE audience trong transaction. Archive ARCHIVED. GET announcements trả published/còn hạn/đúng audience. Notification own user, unreadOnly tùy chọn, mark read từng cái/all. Không tự gửi mail.

### 13.4. Support

`SupportController/AdminSupportController → SupportService → SupportRepository`.

~~~json
{"subject":"Cần hỗ trợ CV","category":"RESUME","description":"Mô tả vấn đề cụ thể"}
~~~

Tạo ticket OPEN/priority NORMAL. Owner hoặc admin đọc/gửi message; admin đọc toàn bộ. Message content≤10000. CLOSED không nhận message; row lock giữ thứ tự close/send.

Admin PATCH ticket nhận status OPEN|IN_PROGRESS|RESOLVED|CLOSED, priority LOW|NORMAL|HIGH|URGENT, assignedAdminId tùy chọn phải admin hợp lệ. Admin reply user khác tạo SUPPORT_REPLY notification; không gửi Mailtrap.

### 13.5. Dashboard và monitoring

Dashboard trả users chưa deleted, subscription còn hiệu lực, payment revenue theo currency, AI usage tháng và open/in-progress tickets.

Monitoring category: ai-usage/vector-stores/indexing-jobs/logs/audit/billing/crawl-runs; thường100 bản ghi gần nhất. Audit/admin_actions ghi các thay đổi quản trị. Không coi dashboard/health là bằng chứng toàn bộ dịch vụ ngoài hoạt động.

## 14. Hướng dẫn sử dụng từ đầu đến cuối

### 14.1. Thử qua Swagger

1. Khởi động backend, mở Swagger UI.
2. Gọi route public mà không bearer.
3. Register → lấy OTP từ dev response hoặc Mailtrap → verify → login.
4. Copy **accessToken**, bấm Authorize trong Swagger và nhập token theo ô bearer.
5. Dùng UUID từ response/API danh mục, không gửi nguyên placeholder.
6. Dùng token riêng cho candidate, consultant, admin khi đổi vai trò.
7. Với API guest, nhập X-Guest-Session; bearer không thay guest key.
8. Upload dùng multipart file; endpoint download nhận bytes/attachment, không xử lý như JSON.

Không gọi lại register cùng email đã có rồi coi409 là lỗi hệ thống. Cần email mới cho mỗi vòng đăng ký hoặc login account đã có.

### 14.2. Candidate: ví dụ chạy local bằng PowerShell

Hàm dưới đây chỉ gửi request khi bạn gọi; dùng cho môi trường local đã chạy backend. Nó encode JSON UTF-8 và giữ nested fields bằng Depth20.

~~~powershell
$baseUrl = 'http://localhost:8080/api/v1'
function Send-JsonRequest {
    param(
        [string]$Method,
        [string]$Path,
        [object]$Payload,
        [hashtable]$Headers = @{}
    )
    $requestJson = ConvertTo-Json -InputObject $Payload -Depth 20
    $requestBytes = [Text.Encoding]::UTF8.GetBytes($requestJson)
    Invoke-RestMethod -Method $Method -Uri ($baseUrl + $Path) -Headers $Headers -ContentType 'application/json;charset=utf-8' -Body $requestBytes
}
~~~

**Bước1 — register/verify/login.** Email example.com dùng cho sandbox/dev; live sending thay bằng email nhận được thư.

~~~powershell
$testEmail = 'candidate.' + [guid]::NewGuid().ToString('N') + '@example.com'
$testPassword = 'DemoPassword2026!'
$registration = Send-JsonRequest -Method Post -Path '/auth/register' -Payload @{
    email = $testEmail
    password = $testPassword
    fullName = 'Candidate Demo'
    preferredLanguage = 'vi'
}
$registration
$verificationCode = Read-Host 'Nhap OTP 6 chu so tu response dev hoac Mailtrap'
Send-JsonRequest -Method Post -Path '/auth/verify-email' -Payload @{
    email = $testEmail
    code = $verificationCode
}
$session = Send-JsonRequest -Method Post -Path '/auth/login' -Payload @{
    email = $testEmail
    password = $testPassword
}
$authHeaders = @{ Authorization = 'Bearer ' + $session.accessToken }
Invoke-RestMethod -Method Get -Uri "$baseUrl/me" -Headers $authHeaders
~~~

**Bước2 — profile/CV.** Mẫu sau tạo CV manual, không cần AI.

~~~powershell
Send-JsonRequest -Method Put -Path '/me/career-profile' -Headers $authHeaders -Payload @{
    targetJobTitle = 'Java Developer'
    careerGoal = 'Backend development'
    yearsExperience = 1
    salaryCurrency = 'VND'
    expectedSalary = 7500000
    preferredLocation = 'Ho Chi Minh'
}
$manualCv = Send-JsonRequest -Method Post -Path '/resumes' -Headers $authHeaders -Payload @{
    title = 'Java Backend CV'
    language = 'vi'
    structured_data = @{
        full_name = 'Candidate Demo'
        summary = 'Built REST APIs with Java and PostgreSQL'
        experience = @()
        education = @()
        projects = @(
            @{
                project_name = 'CV Matching'
                role = 'Backend'
                description = 'Implemented REST API and database queries'
                technologies = @('Java', 'Spring Boot', 'PostgreSQL')
            }
        )
        certifications = @()
        skills = @('Java', 'SQL')
    }
}
$resumeId = $manualCv.id
Send-JsonRequest -Method Post -Path "/resumes/$resumeId/confirm" -Headers $authHeaders -Payload @{ is_primary = $true }
~~~

Profile sample không gửi skills; nếu muốn matching từ CAREER_PROFILE, lấy UUID GET skills rồi thêm links bằng PUT. CV sample có kỹ năng riêng cho SELECTED_CV.

**Bước3 — browse/matching/analysis.** Admin phải có ACTIVE job; semantic matching còn cần index.

~~~powershell
Invoke-RestMethod -Method Get -Uri "$baseUrl/jobs?q=Java&limit=20&offset=0"
Send-JsonRequest -Method Post -Path '/matching' -Headers $authHeaders -Payload @{
    basis = 'SELECTED_CV'
    resumeIds = @($resumeId)
    limit = 20
}
Send-JsonRequest -Method Post -Path "/resumes/$resumeId/analyses" -Headers $authHeaders -Payload @{}
Invoke-RestMethod -Method Get -Uri "$baseUrl/resumes/$resumeId/download?text=true" -Headers $authHeaders -OutFile 'cv-reviewed.txt'
~~~

Với job UUID thực, tiếp tục ATS/application drafts/salary khi chat AI đã được cấu hình. External apply lấy URL để tự mở; không coi response là đã nộp đơn.

### 14.3. Candidate: học tập, assistant, tư vấn và mua gói

| Mục tiêu | Thứ tự thao tác |
| --- | --- |
| Đánh giá kỹ năng | POST assessments → GET detail → PUT answers cho từng question → POST submit |
| Lộ trình học | POST roadmaps với target/CV/job/assessment phù hợp → GET detail/resources → PATCH nodes theo prerequisites |
| Chat AI | POST assistant/conversations → POST messages → GET conversation → feedback |
| RAG chat | Admin tạo/index tài liệu → user message retrieve=true với cả chat/embedding providers |
| Tư vấn người thật | POST consultations → chờ admin assign → GET/POST messages hoặc subscribe events → close |
| Support | POST support/tickets → GET detail → POST messages |
| Thông báo | GET announcements/notifications → PATCH read hoặc POST read-all |
| Mua gói | GET tiers/quota → POST order → checkout qua adapter → webhook verified → GET subscription/invoice |
| Khi CV đổi | PATCH → review/confirm lại → analysis/matching mới; kiểm outdated roadmap |
| Khi access hết hạn | Refresh bằng refreshToken hiện tại; giữ cặp token mới |
| Đổi mật khẩu/logout | Đăng nhập lại sau khi mọi token bị revoke |

### 14.4. Guest

~~~powershell
$guest = Invoke-RestMethod -Method Post -Uri "$baseUrl/guest/sessions"
$guestHeaders = @{ 'X-Guest-Session' = $guest.sessionKey }
Invoke-RestMethod -Method Get -Uri "$baseUrl/guest/sessions/me" -Headers $guestHeaders
Invoke-RestMethod -Method Get -Uri "$baseUrl/guest/demo-resumes" -Headers $guestHeaders
Invoke-RestMethod -Method Get -Uri "$baseUrl/guest/templates" -Headers $guestHeaders
~~~

Lấy demo/template UUID trả về để gọi trial build/recommendations. Không truyền CV cá nhân vào guest builder và mong lưu CV riêng. Hết quota/hạn cần session/account workflow phù hợp; đăng ký chưa tự chuyển history guest.

### 14.5. Admin và consultant

Admin nên làm theo thứ tự:

1. Bootstrap account verified đầu tiên; login/Authorize bằng token admin.
2. Kiểm tiers/quotas và tạo consultant nếu cần.
3. Manual import hoặc crawl keyword/URL job thực; xem run/items và source aliases.
4. Nếu dùng semantic, bật embedding rồi xem GET job index status; automatic queue phải COMPLETED, retry POST index nếu cần.
5. Cấu hình prompt/model active; tạo/index RAG documents.
6. Phân công thread candidate cho consultant ACTIVE.
7. Publish announcement, xử lý tickets/payment reports.
8. Xem dashboard/usage/indexing/crawl/audit để tra lỗi.

Consultant login bằng account được admin tạo → GET consultations được assign → đọc candidate-profile/candidate-analyses → trao đổi messages/SSE → đóng thread khi hoàn tất. Consultant không tự đọc toàn bộ CV/báo cáo của ứng viên ngoài thread được phân công.

### 14.6. Kết nối frontend

Chức năng quản trị mẫu CV đã có trang `/admin/resume-templates` gọi backend thật. Đăng nhập ở
`/login?mode=system`, chọn **Tài khoản hệ thống** bằng tài khoản Admin đã xác minh/cấp quyền.
Trang hỗ trợ thêm, sửa, bật/tắt và preview HTML/CSS. Nội dung trình bày chỉ lưu trong `html_content`;
Admin gửi `htmlContent` bắt buộc, FE nhận HTML qua `GET /api/v1/resumes/templates` hoặc `template.html_content`
trong chi tiết CV và điền dữ liệu vào các slot. Candidate chọn mẫu qua `template_id` như trước.
Phiên demo có lựa chọn đăng nhập hệ thống riêng; không tự giả lập lưu template thành công.
Các màn Candidate còn dùng mock không được coi là đã tích hợp cùng thay đổi này. API download CV
vẫn chưa xuất PDF theo mẫu. Xem [hướng dẫn quản trị mẫu và Postman](admin-resume-templates.md).

Ví dụ helper fetch JSON đọc được cả lỗi ProblemDetail và lỗi filter:

~~~javascript
const API_BASE = "http://localhost:8080/api/v1";

async function requestJson(path, { method = "GET", body, accessToken, guestKey } = {}) {
  const headers = {};
  if (body !== undefined) headers["Content-Type"] = "application/json";
  if (accessToken) headers.Authorization = "Bearer " + accessToken;
  if (guestKey) headers["X-Guest-Session"] = guestKey;

  const response = await fetch(API_BASE + path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (response.status === 204) return null;

  const data = await response.json();
  if (!response.ok) {
    const error = new Error(data.detail ?? data.message ?? "Request failed");
    error.status = response.status;
    error.validationErrors = data.errors ?? [];
    throw error;
  }
  return data;
}
~~~

Helper dành JSON; upload dùng FormData, download xử lý Blob/bytes, SSE dùng streaming riêng. FormData không tự set Content-Type vì browser cần tạo boundary.

Khi refresh, chỉ dùng một request refresh đang chạy cho cùng token rồi cập nhật cặp mới; gọi đồng thời cùng refresh có thể có request401. Không refresh khi lỗi400/403/409/429/502/503. Header bearer sai trên public route cũng gây401, nên bỏ token đã hết hạn ở request public nếu chưa refresh.

CORS chỉ allow origins cấu hình và headers Authorization/Content-Type/X-Guest-Session. Header ký thanh toán thuộc server-to-server; không đặt signing secret trên frontend.

## 15. Danh mục API

Danh mục dưới đây được đối chiếu **138 cặp HTTP method/route trong 23 controller**. Bảng bỏ tiền tố `/api/v1`. Query/body đầy đủ xem DTO/Swagger; luồng sử dụng đã giải thích ở từng module.

Các route `/admin/**` cần ADMIN. Route phân công consultant và PATCH support ticket không có tiền tố admin nhưng vẫn gate admin trong service. Public jobs/skills/templates/tiers không cần bearer; guest API trừ tạo session cần X-Guest-Session. Route private kiểm tra ownership và role theo service, không suy quyền chỉ từ controller package.

### 15.1. Tài khoản và hồ sơ

[AuthController](../src/main/java/com/example/intelligent_cv_matching_ai_agent/controller/shared/identity/AuthController.java), [ProfileController](../src/main/java/com/example/intelligent_cv_matching_ai_agent/controller/shared/identity/ProfileController.java).

| Controller | Method | Path |
| --- | --- | --- |
| AuthController | POST | `/auth/register` |
| AuthController | POST | `/auth/verify-email` |
| AuthController | POST | `/auth/resend-code` |
| AuthController | POST | `/auth/login` |
| AuthController | POST | `/auth/refresh` |
| AuthController | POST | `/auth/logout` |
| AuthController | POST | `/auth/google` |
| ProfileController | GET | `/me` |
| ProfileController | PUT | `/me` |
| ProfileController | GET | `/me/career-profile` |
| ProfileController | PUT | `/me/career-profile` |
| ProfileController | DELETE | `/me` |
| ProfileController | PUT | `/me/password` |
| ProfileController | GET | `/me/sessions` |
| ProfileController | DELETE | `/me/sessions/{id}` |

### 15.2. Guest và trial

[GuestController](../src/main/java/com/example/intelligent_cv_matching_ai_agent/controller/guest/identity/GuestController.java), [GuestTrialController](../src/main/java/com/example/intelligent_cv_matching_ai_agent/controller/guest/ai/GuestTrialController.java), [GuestAssistantController](../src/main/java/com/example/intelligent_cv_matching_ai_agent/controller/guest/ai/GuestAssistantController.java).

| Controller | Method | Path |
| --- | --- | --- |
| GuestController | POST | `/guest/sessions` |
| GuestController | GET | `/guest/sessions/me` |
| GuestController | GET | `/guest/demo-resumes` |
| GuestController | GET | `/guest/templates` |
| GuestTrialController | POST | `/guest/trials/build` |
| GuestTrialController | POST | `/guest/trials/recommendations` |
| GuestAssistantController | POST | `/guest/assistant/conversations` |
| GuestAssistantController | GET | `/guest/assistant/conversations/{id}` |
| GuestAssistantController | POST | `/guest/assistant/conversations/{id}/messages` |

### 15.3. CV

[ResumeController](../src/main/java/com/example/intelligent_cv_matching_ai_agent/controller/candidate/resume/ResumeController.java).

| Controller | Method | Path |
| --- | --- | --- |
| ResumeController | GET | `/resumes/templates` |
| ResumeController | GET | `/resumes` |
| ResumeController | GET | `/resumes/{id}` |
| ResumeController | POST | `/resumes` |
| ResumeController | POST | `/resumes/build` |
| ResumeController | POST | `/resumes/upload` |
| ResumeController | PATCH | `/resumes/{id}` |
| ResumeController | POST | `/resumes/{id}/confirm` |
| ResumeController | POST | `/resumes/{id}/primary` |
| ResumeController | DELETE | `/resumes/{id}` |
| ResumeController | POST | `/resumes/{id}/analyses` |
| ResumeController | GET | `/resumes/{id}/analyses` |
| ResumeController | GET | `/resumes/{id}/analyses/{analysisId}` |
| ResumeController | POST | `/resumes/{id}/embedding` |
| ResumeController | GET | `/resumes/{id}/download` |

### 15.4. Jobs và công cụ nghề nghiệp

[JobController](../src/main/java/com/example/intelligent_cv_matching_ai_agent/controller/shared/jobs/JobController.java), [CareerToolsController](../src/main/java/com/example/intelligent_cv_matching_ai_agent/controller/candidate/jobs/CareerToolsController.java).

| Controller | Method | Path |
| --- | --- | --- |
| JobController | GET | `/skills` |
| JobController | GET | `/jobs` |
| JobController | GET | `/jobs/{id}` |
| CareerToolsController | POST | `/matching` |
| CareerToolsController | GET | `/matching/{id}` |
| CareerToolsController | GET | `/matching` |
| CareerToolsController | GET | `/ai/usage` |
| CareerToolsController | PUT | `/matching/results/{id}/feedback` |
| CareerToolsController | POST | `/jobs/{jobId}/ats` |
| CareerToolsController | POST | `/jobs/{jobId}/resume-draft` |
| CareerToolsController | POST | `/jobs/{jobId}/application-drafts` |
| CareerToolsController | GET | `/application-drafts` |
| CareerToolsController | PATCH | `/application-drafts/{id}` |
| CareerToolsController | POST | `/jobs/{id}/external-apply` |
| CareerToolsController | POST | `/jobs/{id}/salary-estimation` |
| CareerToolsController | POST | `/salary-estimations` |

### 15.5. Học tập và assistant

[LearningController](../src/main/java/com/example/intelligent_cv_matching_ai_agent/controller/candidate/learning/LearningController.java), [CareerAssistantController](../src/main/java/com/example/intelligent_cv_matching_ai_agent/controller/candidate/ai/CareerAssistantController.java).

| Controller | Method | Path |
| --- | --- | --- |
| LearningController | POST | `/assessments` |
| LearningController | GET | `/assessments` |
| LearningController | GET | `/assessments/{id}` |
| LearningController | PUT | `/assessments/{id}/answers` |
| LearningController | POST | `/assessments/{id}/submit` |
| LearningController | POST | `/roadmaps` |
| LearningController | GET | `/roadmaps` |
| LearningController | GET | `/roadmaps/{id}` |
| LearningController | PATCH | `/roadmaps/{id}/nodes/{nodeId}` |
| CareerAssistantController | GET | `/assistant/conversations` |
| CareerAssistantController | POST | `/assistant/conversations` |
| CareerAssistantController | GET | `/assistant/conversations/{id}` |
| CareerAssistantController | POST | `/assistant/conversations/{id}/messages` |
| CareerAssistantController | DELETE | `/assistant/conversations/{id}` |
| CareerAssistantController | POST | `/assistant/messages/{id}/feedback` |

### 15.6. Consultation

[CandidateConsultationController](../src/main/java/com/example/intelligent_cv_matching_ai_agent/controller/candidate/consultation/CandidateConsultationController.java), [ConsultationController](../src/main/java/com/example/intelligent_cv_matching_ai_agent/controller/shared/consultation/ConsultationController.java), [AdminConsultationController](../src/main/java/com/example/intelligent_cv_matching_ai_agent/controller/admin/consultation/AdminConsultationController.java), [ConsultantConsultationController](../src/main/java/com/example/intelligent_cv_matching_ai_agent/controller/consultant/consultation/ConsultantConsultationController.java).

| Controller | Method | Path |
| --- | --- | --- |
| CandidateConsultationController | POST | `/consultations` |
| ConsultationController | GET | `/consultations` |
| ConsultationController | GET | `/consultations/{id}` |
| ConsultationController | GET | `/consultations/{id}/messages` |
| ConsultationController | POST | `/consultations/{id}/messages` |
| ConsultationController | PATCH | `/consultations/{id}/messages/{messageId}/read` |
| ConsultationController | POST | `/consultations/{id}/close` |
| ConsultationController | GET | `/consultations/{id}/events` |
| AdminConsultationController | PUT | `/consultations/{id}/consultant` |
| ConsultantConsultationController | GET | `/consultations/{id}/candidate-profile` |
| ConsultantConsultationController | GET | `/consultations/{id}/candidate-analyses` |

### 15.7. Billing

[BillingController](../src/main/java/com/example/intelligent_cv_matching_ai_agent/controller/shared/billing/BillingController.java).

| Controller | Method | Path |
| --- | --- | --- |
| BillingController | GET | `/subscriptions/tiers` |
| BillingController | GET | `/subscriptions/me` |
| BillingController | GET | `/subscriptions/history` |
| BillingController | POST | `/subscriptions/{id}/cancel` |
| BillingController | POST | `/payments/orders` |
| BillingController | GET | `/payments/orders` |
| BillingController | GET | `/payments/orders/{id}` |
| BillingController | POST | `/payments/orders/{id}/reports` |
| BillingController | GET | `/payments/reports` |
| BillingController | GET | `/invoices` |
| BillingController | GET | `/invoices/{id}` |
| BillingController | POST | `/payments/webhook` |

### 15.8. Admin users, tiers, AI và monitoring

[AdminController](../src/main/java/com/example/intelligent_cv_matching_ai_agent/controller/admin/management/AdminController.java).

| Controller | Method | Path |
| --- | --- | --- |
| AdminController | GET | `/admin/dashboard` |
| AdminController | GET | `/admin/users` |
| AdminController | GET | `/admin/users/{id}` |
| AdminController | POST | `/admin/users` |
| AdminController | PUT | `/admin/users/{id}` |
| AdminController | DELETE | `/admin/users/{id}` |
| AdminController | GET | `/admin/subscription-tiers` |
| AdminController | POST | `/admin/subscription-tiers` |
| AdminController | PUT | `/admin/subscription-tiers/{id}` |
| AdminController | PUT | `/admin/subscription-tiers/{id}/quotas` |
| AdminController | GET | `/admin/ai/configurations` |
| AdminController | POST | `/admin/ai/configurations` |
| AdminController | POST | `/admin/ai/configurations/{oldVersionId}/restore` |
| AdminController | GET | `/admin/ai/prompts` |
| AdminController | POST | `/admin/ai/prompts` |
| AdminController | POST | `/admin/ai/prompts/{oldVersionId}/restore` |
| AdminController | GET | `/admin/monitoring/{category}` |
| AdminController | GET | `/admin/payment-reports` |
| AdminController | POST | `/admin/payment-reports/{id}/resolve` |

### 15.9. Admin jobs và RAG

[AdminJobController](../src/main/java/com/example/intelligent_cv_matching_ai_agent/controller/admin/jobs/AdminJobController.java), [JobCrawlController](../src/main/java/com/example/intelligent_cv_matching_ai_agent/controller/admin/jobs/JobCrawlController.java), [RagAdminController](../src/main/java/com/example/intelligent_cv_matching_ai_agent/controller/admin/rag/RagAdminController.java).

| Controller | Method | Path |
| --- | --- | --- |
| AdminJobController | POST | `/admin/jobs` |
| AdminJobController | POST | `/admin/jobs/{id}/index` |
| AdminJobController | GET | `/admin/jobs/{id}/index` |
| AdminJobController | PATCH | `/admin/jobs/{id}/status` |
| JobCrawlController | POST | `/admin/jobs/crawl` |
| JobCrawlController | GET | `/admin/jobs/crawl` |
| JobCrawlController | GET | `/admin/jobs/crawl/{id}` |
| RagAdminController | GET | `/admin/rag/documents` |
| RagAdminController | POST | `/admin/rag/documents` |
| RagAdminController | GET | `/admin/rag/documents/{id}` |
| RagAdminController | POST | `/admin/rag/documents/{id}/index` |
| RagAdminController | POST | `/admin/rag/documents/{id}/archive` |

### 15.10. Thông báo và support

[AnnouncementController](../src/main/java/com/example/intelligent_cv_matching_ai_agent/controller/shared/announcement/AnnouncementController.java), [AdminAnnouncementController](../src/main/java/com/example/intelligent_cv_matching_ai_agent/controller/admin/announcement/AdminAnnouncementController.java), [SupportController](../src/main/java/com/example/intelligent_cv_matching_ai_agent/controller/shared/support/SupportController.java), [AdminSupportController](../src/main/java/com/example/intelligent_cv_matching_ai_agent/controller/admin/support/AdminSupportController.java).

| Controller | Method | Path |
| --- | --- | --- |
| AnnouncementController | GET | `/announcements` |
| AnnouncementController | GET | `/notifications` |
| AnnouncementController | PATCH | `/notifications/{id}/read` |
| AnnouncementController | POST | `/notifications/read-all` |
| AdminAnnouncementController | GET | `/admin/announcements` |
| AdminAnnouncementController | POST | `/admin/announcements` |
| AdminAnnouncementController | PUT | `/admin/announcements/{id}` |
| AdminAnnouncementController | POST | `/admin/announcements/{id}/publish` |
| AdminAnnouncementController | POST | `/admin/announcements/{id}/archive` |
| SupportController | GET | `/support/tickets` |
| SupportController | POST | `/support/tickets` |
| SupportController | GET | `/support/tickets/{id}` |
| SupportController | POST | `/support/tickets/{id}/messages` |
| AdminSupportController | PATCH | `/support/tickets/{id}` |

## 16. Kiểm thử, build, Docker và CI/CD

### 16.1. Kiểm tra phù hợp từng phần

| Loại | Kiểm được | Điều kiện |
| --- | --- | --- |
| Unit/mock tests | Validation, quyền, quota, token, workflow với phản hồi kiểm soát | Không cần dịch vụ ngoài cho nhóm unit |
| Testcontainers | Flyway/pgvector/toán tử vector | Docker daemon khả dụng |
| CoreFlowIntegrationTests | API và nghiệp vụ PostgreSQL | EDUNEXUS_TEST_DATABASE_URL tới DB thử nghiệm riêng |
| SMTP integration thực | OTP gửi/nhận | MAIL_ENABLED, dev OTP false, Mailtrap credentials |
| AI integration thực | Chat/embedding/provider usage | Provider/key/quota, job/RAG indexed phù hợp |
| Gateway integration thực | Checkout và signed webhook | Adapter/gateway bên ngoài chưa có sẵn |

Các test AI hiện dùng mock có kiểm soát; crawl tests dùng fixture/mock fetch. Không coi unit pass là đã kiểm thử SMTP, provider AI trả phí, gateway hoặc website nguồn đang hoạt động.

Báo cáo [verification.md](verification.md) ghi kết quả một lần chạy ngày10/10/2026; đó là lịch sử, không phải kết quả vừa chạy khi viết tài liệu này.

~~~powershell
mvn verify
~~~

`verify` gồm formatter validate. Với Maven wrapper trên Windows có thể dùng `.\mvnw.cmd verify`. Một số integration test sẽ skipped khi thiếu Docker/env; đọc số skipped và báo cáo thay vì chỉ nhìn BUILD SUCCESS.

### 16.2. PostgreSQL thử nghiệm riêng

~~~powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/start-local-test-postgres.ps1
$env:EDUNEXUS_TEST_DATABASE_URL = 'jdbc:postgresql://127.0.0.1:55439/edunexus_test'
$env:EDUNEXUS_TEST_DATABASE_USER = 'edunexus_test'
$env:EDUNEXUS_TEST_DATABASE_PASSWORD = ''
$env:EDUNEXUS_TEST_FLYWAY = 'false'
mvn verify
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/stop-local-test-postgres.ps1
~~~

Script dùng cluster riêng trong target/local-pg và PostgreSQL binary đã cài trên máy; xem tham số PgBinPath trong script nếu đường dẫn khác. Schema test sinh riêng dùng real[] thay vector và bỏ HNSW khi local PostgreSQL không có extension. Cách này kiểm nghiệp vụ quan hệ, **không kiểm semantic search/vector dimension/cosine/HNSW**.

Để kiểm vector thật, dùng DB thử nghiệm PostgreSQL+pgvector, đặt URL/user/password riêng và EDUNEXUS_TEST_FLYWAY=true. Không trỏ bộ test tạo fixture vào database đang dùng cho người dùng thật.

### 16.3. Các ca kiểm tra chức năng nên chạy

| Luồng | Ca đúng | Ca lỗi có ý nghĩa |
| --- | --- | --- |
| Auth/mail | register → mail → verify → login | dev flag bỏ gửi; mail disabled503; email trùng409; pending login403 |
| OTP | resend sau cooldown, mã mới verify | resend sớm429; mã cũ400; sai5 lần rồi request tiếp429 |
| CV | manual/upload → review → confirm READY | CV user khác404; file quá lớn413; ảnh không OCR503; chưa đủ nội dung confirm400 |
| Matching | profile/CV READY + job ACTIVE | SELECTED nhiều CV400; limit thiếu400; semantic chưa có indexed job409 |
| ATS/cache | Gọi cùng input lần2 tái dùng | CV/job đổi hash phải tạo mới; Redis down vẫn dùng DB |
| Assessment | đủ answers → submit | question sai session; chưa đủ answers; không sửa completed |
| Roadmap | progress đúng prerequisite | bỏ qua prerequisite409; reset sai dependency409 |
| Assistant/RAG | owned conversation + source indexed | conversation user khác404; archive lúc chờ; document disabled không retrieved |
| Consultation | đúng assigned consultant | consultant cũ sau reassign404; gửi khi closed409; reconnect lấy history |
| Billing | signed COMPLETED → một invoice/gói | sai chữ ký/timestamp/amount; event replay; event dùng cho order khác |
| Admin/support | trusted create/assign/publish/reply | non-admin403; last admin guard; CLOSED ticket không gửi |
| Ownership | Dữ liệu user A chỉ user A đọc | user B không đọc CV/run/invoice/ticket/thread không có quyền |

### 16.4. Build và Docker

~~~powershell
mvn verify
docker build -t edunexus-backend:local .
~~~

Dockerfile dùng Java25 JRE, user spring, upload directory và COPY target/*.jar. Nó **không chạy Maven để build source**. Cần JAR tồn tại trước docker build, build context phải là thư mục pom.xml. .dockerignore cho phép JAR và bỏ output khác.

Production compose đọc .env của server, env profile prod, mount resume_uploads, expose APP_PORT mặc định8080. Cần IMAGE reference và database/cache/secret runtime. Tạo lại app container sau đổi env; restart container đơn thuần không cập nhật env đã đóng trong cấu hình container.

### 16.5. CI/CD

[.github/workflows/ci-cd.yml](../.github/workflows/ci-cd.yml):

1. PR vào main, push main/chore/ci-cd hoặc workflow_dispatch → Maven verify với Java25 → upload JAR.
2. Push main thành công → download JAR → validate một JAR → build/push GHCR image tag SHA và latest.
3. Deploy chỉ chạy push main khi DEPLOY_HOST được cấu hình.
4. SCP production compose tới server → SSH pull image SHA → compose up app.

Cấu hình deploy: DEPLOY_HOST, DEPLOY_USER, DEPLOY_PATH và secret DEPLOY_SSH_KEY; server giữ .env runtime, auth GHCR nếu image private. Production compose không cung cấp TLS/reverse proxy hoặc tự tạo database. Repository cấu hình pipeline không đồng nghĩa môi trường production đã được thiết lập.

## 17. Tra lỗi và giới hạn hiện tại

### 17.1. Tra lỗi theo tầng

| Dấu hiệu | Kiểm tra |
| --- | --- |
| Không start | Java25/Maven PATH, DB URL/password, container health, Flyway log |
| .env đã sửa nhưng không đổi | Kiểm working directory chứa đúng file, restart backend và kiểm biến môi trường đang ghi đè |
| Health UP nhưng mail/Redis lỗi | Health indicators mail/Redis tắt; kiểm request thực và kết nối riêng |
| Register503 | Dev flag false + mail disabled, thiếu sender hoặc SMTP lỗi |
| Không thấy Mailtrap thư | Đúng sandbox/credentials; AUTH_EXPOSE_DEV_OTP=false; quota Mailtrap |
| SMTP530/535 | Auth bật chưa, credentials đúng sandbox/live, token có quyền domain |
| Live domain rejected | MAIL_FROM thuộc domain Verified/compliance của token |
| Kết nối mail timeout | Host/port/firewall; kiểm TCP, không suy TCP success là auth success |
| CV upload thành công nhưng rỗng | PDF scan/OCR, parse_results, tự review nhập dữ liệu |
| 401 | Access expiry, refresh dùng sai, revoked/session invalid; đọc message filter |
| 403 | Role, account pending/inactive hoặc feature quota0 |
| 404 | ID không tồn tại hoặc không có ownership/assignment |
| 409 | Duplicate/conflicting state, CV đổi lúc index, thiếu vector job, prerequisite/order terminal conflict |
| 429 | Auth cooldown/attempts/IP hoặc AI/guest quota |
| 502 | Provider AI/output structured không hợp lệ |
| 503 AI | Enabled + bean/key/provider, DB/config đang unavailable |
| Job list trống/score0 | Chưa nạp ACTIVE job hoặc thiếu structured required skills |
| RAG không sources | Doc COMPLETED/not disabled, provider/model đúng, similarity ngưỡng, quyền |
| Docker COPY target/*.jar lỗi | JAR chưa build, sai build context hoặc không tải CI artifact |

~~~powershell
Test-NetConnection sandbox.smtp.mailtrap.io -Port 2525
Test-NetConnection live.smtp.mailtrap.io -Port 587
~~~

TCP test chỉ kiểm khả năng kết nối port, không kiểm SMTP authentication hay delivery. Lỗi SMTP bị AuthService đổi thành thông điệp503 chung; không có log nguyên nhân exception SMTP ngay tại catch block hiện tại. Xem provider logs và đối chiếu cấu hình. [Mailtrap troubleshooting](https://docs.mailtrap.io/email-sandbox/help/troubleshooting).

Thiếu `APP_JWT_SECRET` khiến ứng dụng không khởi động; secret trống, dưới 32 byte UTF-8 hoặc placeholder
đã biết cũng bị từ chối. Sinh secret bằng bộ sinh ngẫu nhiên mật mã theo [README](../README.md), giữ bí mật
và cấu hình cùng giá trị giữa các instance. Secret được dùng nguyên dưới dạng UTF-8, không giải mã Base64;
kiểm tra độ dài/placeholder không chứng minh độ ngẫu nhiên. IDE/JAR/Maven tự nạp `.env` từ working
directory; kiểm biến môi trường đang ghi đè nếu giá trị có hiệu lực không giống cấu hình trong file.

Nếu Flyway báo checksum sau thay migration đã áp dụng, kiểm migration history và phiên bản SQL trước; không tự sửa/xóa lịch sử để ép chạy mà chưa xác định dữ liệu/môi trường cần giữ. Schema thay đổi mới nên có migration mới.

### 17.2. Phạm vi hiện tại

| Phần | Giới hạn cần hiểu khi dùng |
| --- | --- |
| Frontend | Backend và Swagger có sẵn; web/mobile sản phẩm ngoài repository này |
| CV template | Admin tạo/sửa/bật-tắt và preview cấu hình; chưa render PDF/DOCX từ backend theo template |
| SMTP | OTP plain text đồng bộ; chưa reset-password email, notification mail, send application draft |
| Payment | Generic signed webhook; chưa checkout/QR/provider SDK/refund/auto charge |
| Guest conversion | Chưa có workflow chuyển history trial sang user |
| AI | Kết quả phụ thuộc provider; mock tests không kiểm chứng chất lượng thật |
| Matching | Rule overlap cần structured skills; semantic cần indexed vector cùng model |
| Salary | Ước lượng AI có reasoning, không số liệu lương đã xác minh |
| Crawling | Discovery/JSON-LD/HTML/dictionary/scheduler có sẵn; không JS/challenge bypass hoặc cam kết tương thích HTML nguồn chưa kiểm thực tế |
| OCR | Native dependency local, mặc định tắt |
| SSE/limiter | Memory từng instance; cần adapter/limiter chung khi nhiều replica |
| Cache | Redis optional; PostgreSQL là nguồn kết quả và quyền |
| Usage | Có thể bị tính khi provider đã trả nhưng output/nghiệp vụ sau đó lỗi |
| Tài liệu cũ | Có chỗ mô tả random access token; code hiện tại là JWT + DB revocation |

## 18. Cách mở rộng và nguồn tra cứu

### 18.1. Thêm hoặc thay một chức năng

1. Xác định route, vai trò và dữ liệu đầu vào.
2. Tạo/sửa request DTO đúng layer/role/feature và validation.
3. Controller chỉ nhận HTTP/gọi service.
4. Service kiểm CurrentUser/ownership và trạng thái, chọn transaction phù hợp.
5. SQL đặt trong repository; cập nhật schema bằng migration mới nếu cần.
6. Chọn response DTO hoặc projection, không trả secret entity field.
7. Nếu gọi AI, thêm feature schema/quota/prompt và accounting qua AiService.
8. Nếu thay dữ liệu CV/profile/job, xem cache hash/vector current/roadmap outdated liên quan.
9. Kiểm tra lỗi/quyền/cạnh tranh thực sự của thay đổi, cập nhật docs/Swagger.
10. Tuân thủ AGENTS.md và formatter Java.

~~~powershell
mvn formatter:format
mvn formatter:validate
mvn verify
~~~

Đây là lệnh cho thay đổi Java; thay Markdown không cần chạy formatter trên toàn source. Không đặt API key/SMTP password/secret vào doc, prompt config hoặc source.

Để bổ sung gửi mail ngoài OTP, cần xác định trigger/content/audience, service gửi dùng chung và cách xử lý lỗi/transaction; chỉ bật MAIL_ENABLED không tự tạo các luồng đó. Để bổ sung gateway/SSE nhiều instance/render CV, cần adapter thật và kiểm thử tích hợp tương ứng.

### 18.2. Bản đồ file bắt đầu đọc

Các file dưới đây dùng link tương đối từ thư mục docs; các lớp khác nằm cùng prefix Java `src/main/java/com/example/intelligent_cv_matching_ai_agent`.

| Muốn hiểu | Điểm bắt đầu |
| --- | --- |
| Cấu hình và chạy | [application.yaml](../src/main/resources/application.yaml), [.env.example](../.env.example), [run-local.ps1](../scripts/run-local.ps1), [pom.xml](../pom.xml) |
| Security/token | [SecurityConfig.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/config/shared/SecurityConfig.java), [BearerFilter.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/security/shared/BearerFilter.java), [TokenService.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/shared/identity/TokenService.java) |
| OTP/mail | [AuthService.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/shared/identity/AuthService.java), [AuthRepository.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/repository/shared/identity/AuthRepository.java) |
| Profile | [ProfileService.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/shared/identity/ProfileService.java) |
| CV/extraction | [ResumeService.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/candidate/resume/ResumeService.java), [ResumeFileExtractor.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/util/shared/resume/ResumeFileExtractor.java), [ResumeData.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/util/shared/resume/ResumeData.java) |
| AI/quota | [AiService.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/shared/ai/AiService.java), [EntitlementService.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/shared/billing/EntitlementService.java) |
| Matching/ATS | [MatchingService.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/candidate/jobs/MatchingService.java), [CareerToolsService.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/candidate/jobs/CareerToolsService.java) |
| Job/crawl | [JobService.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/shared/jobs/JobService.java), [JobCrawlService.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/admin/jobs/JobCrawlService.java) |
| Learning | [LearningService.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/candidate/learning/LearningService.java), [AssessmentService.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/candidate/learning/AssessmentService.java) |
| Assistant/RAG | [CareerAssistantService.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/candidate/ai/CareerAssistantService.java), [RagDocumentService.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/admin/rag/RagDocumentService.java) |
| Tư vấn | [ConsultationService.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/shared/consultation/ConsultationService.java), [ConsultationAnalysisService.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/consultant/consultation/ConsultationAnalysisService.java) |
| Billing/webhook | [BillingService.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/shared/billing/BillingService.java), [WebhookVerifier.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/util/shared/billing/WebhookVerifier.java), [SubscriptionMaintenance.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/shared/billing/SubscriptionMaintenance.java) |
| Admin/support | [AdminService.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/admin/management/AdminService.java), [SupportService.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/shared/support/SupportService.java) |
| Hàm hỗ trợ của service | [Thư mục util](../src/main/java/com/example/intelligent_cv_matching_ai_agent/util), [hướng dẫn service và util](service-code-guide.md) |
| SQL nền | [SqlStore.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/repository/shared/SqlStore.java), [migration directory](../src/main/resources/db/migration) |
| CI/Docker | [ci-cd.yml](../.github/workflows/ci-cd.yml), [Dockerfile](../Dockerfile), [compose.production.yaml](../compose.production.yaml) |

Tài liệu chuyên sâu có sẵn: [architecture.md](architecture.md), [schema.md](schema.md), [api.md](api.md), [identity-billing.md](identity-billing.md), [resume-ai.md](resume-ai.md), [crawling.md](crawling.md), [swagger-testing.md](swagger-testing.md), [java-coding-style.md](java-coding-style.md), [verification.md](verification.md).

File hướng dẫn này tổng hợp code hiện tại và cách dùng. Khi thay implementation/DTO, cập nhật phần liên quan và dùng source/Swagger để giải quyết khác biệt với tài liệu lịch sử.



