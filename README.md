# Intelligent-CV-Matching-AI-Agent-BE

Backend EduNexus triển khai bằng Java 25, Spring Boot 4.1.1, JPA/JDBC, PostgreSQL/pgvector và Flyway, dựa trên DBML và các core-flow được cung cấp. API dùng tiền tố chính `/api/v1`; tài liệu tương tác ở `http://localhost:8080/swagger-ui/index.html`.

Mẫu CV hỗ trợ lưu HTML/CSS; Admin nhập HTML hoặc chuyển PDF/DOC/DOCX thành bản nháp để xem trước,
chỉnh sửa và đánh dấu các vị trí dữ liệu trước khi lưu. Candidate dùng mẫu với nội dung CV thực tế.
Từ V19, template chỉ lưu bố cục HTML/CSS trong `html_content`; FE lấy HTML từ API và điền dữ liệu CV vào các slot.
Xem [ba mẫu HTML, JSON Postman và cách sử dụng](docs/html-resume-templates.md).

Mã nguồn chia thành `controller`, `dto`, `entity`, `repository`, `service` và `util`. Trong mỗi lớp, mã được nhóm theo `guest`, `candidate`, `consultant`, `admin` và `shared`, rồi theo chức năng. DTO tách rõ `dto/request/<role>/<chức năng>` và `dto/response/<role>/<chức năng>`; tên lớp có hậu tố `Request` hoặc `Response`. Controller nhận HTTP và gọi service; SQL nằm trong repository. Bộ entity/JPA repository sinh tự động bao phủ 76 bảng từ V1/V3; các bảng vận hành bổ sung ở V5/V7 dùng workflow repository SQL. Xem [cấu trúc thư mục và ví dụ luồng xử lý](docs/architecture.md).

Các helper kiểm tra, chuẩn hóa và chuyển đổi dữ liệu được tách theo `util/<role>/<chức năng>`. Ví dụ,
[`RagDocumentSupport`](src/main/java/com/example/intelligent_cv_matching_ai_agent/util/admin/rag/RagDocumentSupport.java)
chia nội dung RAG thành chunk; [`ResumeInput`](src/main/java/com/example/intelligent_cv_matching_ai_agent/util/candidate/resume/ResumeInput.java)
kiểm tra dữ liệu CV; [`AiResponseData`](src/main/java/com/example/intelligent_cv_matching_ai_agent/util/shared/ai/AiResponseData.java)
giữ schema và kiểm tra phản hồi AI. Service giữ luồng nghiệp vụ, phân quyền, transaction, truy cập repository
và điều phối AI. `util` cũng chứa tiện ích kỹ thuật có I/O như trích xuất tệp CV. Xem
[hướng dẫn đọc service và helper tương ứng](docs/service-code-guide.md).

## Chạy trên Windows

Yêu cầu Java 25, Maven và PostgreSQL có extension pgvector. Compose cung cấp PostgreSQL đã có pgvector khi Docker Desktop đang chạy.

```powershell
if (!(Test-Path -LiteralPath '.env')) {
    Copy-Item -LiteralPath '.env.example' -Destination '.env'
}
# Sửa POSTGRES_PASSWORD và đặt APP_JWT_SECRET trong .env trước khi chạy.
# Để đăng ký thử khi chưa có SMTP, đặt AUTH_EXPOSE_DEV_OTP=true.
```

`APP_JWT_SECRET` bắt buộc, không có giá trị mặc định. Tạo secret một lần bằng bộ sinh số ngẫu nhiên
mật mã, rồi lưu kết quả vào `APP_JWT_SECRET` trong `.env`:

```powershell
$jwtBytes = New-Object byte[] 32
$jwtRng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
try { $jwtRng.GetBytes($jwtBytes) } finally { $jwtRng.Dispose() }
[Convert]::ToBase64String($jwtBytes)
```

Ứng dụng dùng nguyên chuỗi secret dưới dạng UTF-8 để ký HS256, không giải mã Base64. Khi thiếu biến,
secret trống, dưới 32 byte UTF-8 hoặc là placeholder đã biết, ứng dụng từ chối khởi động. Kiểm tra này
không chứng minh độ ngẫu nhiên; hãy dùng giá trị được sinh như trên, giữ bí mật và dùng cùng secret
giữa các instance. Chạy backend sau khi đã lưu cấu hình:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/run-local.ps1 -WithInfrastructure
```

Script đọc `.env` vào môi trường của tiến trình, khởi động PostgreSQL rồi chạy ứng dụng. Khi chưa chọn profile, backend dùng `dev`; có thể chọn rõ bằng `-RuntimeProfile dev|prod|docker`. Nếu bỏ tham số này, script giữ lựa chọn `SPRING_PROFILES_ACTIVE` đang có trong môi trường hoặc `.env`. Flyway tự tạo schema và danh mục ban đầu. `.env` và file upload được loại khỏi Git. Với PostgreSQL riêng, đặt `DATABASE_URL`, `POSTGRES_USER`, `POSTGRES_PASSWORD` và chạy script không có `-WithInfrastructure`.

Ứng dụng cũng tự nạp `.env` bằng `spring.config.import` của Spring Boot khi chạy từ IDE,
`mvn spring-boot:run` hoặc `java -jar`. Đặt working directory là thư mục chứa `pom.xml` và `.env`.
Muốn dùng file khác, đặt biến môi trường `APP_ENV_FILE` trước khi chạy hoặc truyền `-EnvFile` cho script local.
`application.yaml` giữ cấu hình chung; `application-dev.yaml`, `application-prod.yaml` và
`application-docker.yaml` giữ các mặc định theo môi trường. Giá trị runtime được ánh xạ qua `${VAR}` trong `.env`,
và [`.env.example`](.env.example) liệt kê đầy đủ các key để bổ sung khi cập nhật dự án. Giữ giá trị
đang có trong `.env` khi bổ sung key mới.

File dùng UTF-8, mỗi dòng `KEY=value` không bọc giá trị bằng dấu nháy, không có `export` hoặc
comment sau giá trị; comment đặt trên dòng riêng. Dùng dấu `/` cho đường dẫn Windows, ví dụ
`UPLOAD_DIR=C:/edunexus/uploads`. Biến môi trường hệ điều hành/container được ưu tiên hơn file.
Import là optional để Docker vẫn chạy khi chỉ được cấp biến môi trường; các biến bắt buộc vẫn phải đủ.
Sửa cấu hình rồi khởi động lại backend.

Trong môi trường phát triển, `AUTH_EXPOSE_DEV_OTP=true` trả `developmentCode` khi đăng ký để thử bước xác minh email. Khi dùng SMTP, đặt `AUTH_EXPOSE_DEV_OTP=false`, `MAIL_ENABLED=true` và các biến `MAIL_*`. Google login nhận ID token từ frontend khi đã cấu hình `GOOGLE_CLIENT_ID`.

### Chọn môi trường chạy

| Spring profile | Mục đích và mặc định |
| --- | --- |
| `dev` | IDE/Maven/JAR chạy trên máy phát triển; PostgreSQL và Redis tại `localhost`, upload `./uploads` |
| `docker` | Backend trong Compose local kết nối PostgreSQL trên host qua `host.docker.internal:5432/edunexus`, Redis `redis:6379`, upload `/app/uploads` |
| `prod` | Kết nối PostgreSQL/Redis được cấp từ môi trường triển khai; bắt buộc `DATABASE_URL`, `REDIS_HOST`, `REDIS_PORT`; tắt devtools, mã OTP development, chi tiết lỗi và health |

Chạy Maven với profile cụ thể:

```powershell
mvn spring-boot:run "-Dspring-boot.run.profiles=dev"
```

Có thể chọn profile bằng biến `SPRING_PROFILES_ACTIVE` hoặc `--spring.profiles.active` khi chạy JAR. Profile `docker` phục vụ Compose local; deployment dùng `prod`. Xem [cấu hình profile, thứ tự ưu tiên và các lệnh chạy](docs/runtime-profiles.md).

Muốn chạy backend trong Compose local, chuẩn bị PostgreSQL có pgvector trên host tại port 5432/database `edunexus`, tạo JAR rồi khởi động app, Redis và Ollama:

```powershell
mvn verify
docker compose --profile app --profile optional up -d --build app
```

Service `app` chờ Redis/Ollama healthy và `ollama-init` tải đủ hai model rồi mới khởi động; lần đầu cần mạng để tải image và weights. CV lưu trong volume `resume_uploads`, model lưu trong `ollama_models`. Lệnh không khởi động Qdrant/RabbitMQ. PostgreSQL trong Compose thuộc profile `database`, không tự khởi động cùng app. Để dùng PostgreSQL container cho backend chạy trên host, gọi `docker compose --profile database up -d --wait postgres` hoặc `run-local.ps1 -WithInfrastructure`. Compose profile `app` chọn service, còn Spring profile `docker` chọn cấu hình bên trong backend.

Compose gán rõ `SPRING_PROFILES_ACTIVE=docker`, `DATABASE_URL=jdbc:postgresql://host.docker.internal:5432/edunexus`, Redis `redis:6379` với username/password trống, Ollama `http://ollama:11434` và upload `/app/uploads`. Các key cùng tên trong `.env` không ghi đè các giá trị này. Ollama mặc định không publish cổng ra host. Xem [hướng dẫn profile](docs/runtime-profiles.md) và [hướng dẫn Ollama](docs/ollama.md).

Admin đầu tiên được tạo từ tài khoản đã đăng ký và xác minh, bằng thao tác quản trị đúng database backend đang dùng. Với PostgreSQL trên host:

```powershell
Get-Content -Raw scripts/bootstrap-admin.sql | psql -h localhost -p 5432 -U edunexus -d edunexus -v admin_email='you@example.com'
```

Nếu backend dùng PostgreSQL container, có thể thay phần `psql ...` bằng `docker compose exec -T postgres psql -U edunexus -d edunexus -v admin_email='you@example.com'`. Script chỉ nâng quyền khi tài khoản đã xác minh và hệ thống chưa có admin hoạt động. Những admin tiếp theo được tạo qua API quản trị. Hệ thống không có mật khẩu admin mặc định.

## Các luồng đã triển khai

| Core-flow | API chính và hành vi |
| --- | --- |
| Guest trial / đăng ký | Session guest, CV demo, template trial, quota, đăng ký, OTP, đăng nhập và refresh token |
| Resume processing / builder | Upload PDF/DOCX/TXT, trích xuất nội dung, xem lại/sửa/xác nhận, nhiều CV, CV chính, phân tích, tải về và embedding |
| CV / career profile alignment | Candidate chủ động đối chiếu CV READY lần đầu; thay đổi hồ sơ nghề nghiệp tự xếp hàng đánh giá lại các CV READY đã từng đối chiếu. Có trích dẫn, độ phủ, lịch sử snapshot và trạng thái chạy nền; độc lập với ATS. Lương và ưu tiên công việc thiếu dữ liệu so sánh giữ điểm null. |
| Job discovery / matching | Danh sách/detail job, matching profile/CV, cache, feedback, ATS CV–job, draft email/cover letter, ghi nhận mở trang ứng tuyển |
| Assessment / learning roadmap | Chọn JD, đọc kiến thức chuẩn bị, làm bài AI theo JD và học từng kỹ năng cần bổ sung; có prerequisite và tiến độ học |
| Career consultation | Tạo thread, admin phân công consultant, chọn READY CV, AI đánh giá CV và ATS CV–job, xem báo cáo, lịch sử tin nhắn, SSE và đóng hội thoại |
| Subscription / payment | Gói/quota, đơn thanh toán, webhook HMAC, kích hoạt gói, invoice, báo lỗi và nhắc gia hạn |
| Admin / system | Tài khoản, gói/quota, AI config/prompt, thông báo theo đối tượng, audit, monitoring, support và RAG |
| External job processing | Admin import/crawl URL ITviec hoặc TopCV, chuẩn hóa JSON-LD, chống trùng, xử lý phiên bản job và index vector |

Danh mục mẫu gồm FREE/BASIC/PRO, kỹ năng, template/CV demo và tài nguyên học từ tài liệu chính thức. Giá và quota seed là dữ liệu phát triển, có thể sửa qua API admin.

## AI chat và embedding chạy bằng Ollama

Backend dùng model chat và embedding cục bộ qua adapter Ollama native của Spring AI. Không cần API key hoặc dịch vụ inference thương mại. Với backend chạy trong IDE/Maven trên Windows, khởi động model server và kiểm tra cả hai model bằng:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/start-ollama.ps1 -ExposeToHost -SmokeTest
```

Lệnh chỉ khởi động Ollama và tải model, không khởi động database hoặc backend. `-ExposeToHost` bật override chỉ bind `127.0.0.1:11434`; cổng thay được bằng `OLLAMA_PORT`. Smoke test thực hiện chat JSON và embedding ở lượt cold/warm, xác nhận vector 1024 chiều và ghi bằng chứng vào `target/ollama-smoke.json`. Bỏ `-ExposeToHost -SmokeTest` khi chỉ cần model server riêng trong mạng Docker.

Mặc định dùng CPU để chạy được trên các máy khác. Với NVIDIA GPU đã được Docker nhận, thêm `-Gpu`:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/start-ollama.ps1 -ExposeToHost -Gpu -SmokeTest
```

Switch này ghép `compose.ollama-gpu.yaml`; cần Docker Compose 2.30+ và GPU passthrough. Nếu Docker chưa cấp GPU,
chạy lại không có `-Gpu` để dùng CPU. Xem [điều kiện NVIDIA/WSL 2](docs/ollama.md#gpu-nvidia-tùy-chọn).

Career Consultant được phân công có thể gọi AI đánh giá chất lượng CV và độ phù hợp CV–job ngay trong consultation đang mở. Kết quả được lưu vào lịch sử của candidate; usage/quota tính cho consultant thực hiện. Chức năng này dùng nội dung CV đã xác nhận và JD, không yêu cầu embedding. Xem [API và các bước đánh giá CV/ATS cho consultant](docs/consultant-ai-evaluation.md).

Các biến trong `.env` cho backend chạy trực tiếp trên host:

```dotenv
AI_ENABLED=true
AI_PROVIDER=ollama
AI_CHAT_PROVIDER=ollama
AI_EMBEDDING_PROVIDER=ollama
OLLAMA_BASE_URL=http://localhost:11434
AI_CHAT_MODEL=qwen2.5:7b
AI_EMBEDDING_MODEL=qwen3-embedding:0.6b
AI_EMBEDDING_DIMENSIONS=1024
```

Compose app tự dùng `http://ollama:11434`, ghi đè địa chỉ host ở trên. Ollama đặt `OLLAMA_NO_CLOUD=1`; weights được tải về volume và inference chạy cục bộ. Khi đặt `AI_ENABLED=false`, nhập/sửa/xác nhận CV và so khớp kỹ năng cơ bản vẫn hoạt động; chức năng sinh nội dung trả 503. Giá/quota ứng dụng có thể vẫn được tính theo cấu hình quản trị, không phải hóa đơn provider.

Model embedding mới phải trả đúng **1024 chiều**. Migration bảo toàn vector cũ để chuyển đổi an toàn; cần index lại CV, job và RAG theo provider/model mới trước khi đánh giá semantic matching. Không cắt/pad vector cũ để tái sử dụng. Import/crawl job tự enqueue embedding; theo dõi bằng `GET /api/v1/admin/jobs/{id}/index`, dùng POST cùng path để index/retry thủ công. Prompt/config CHAT có thể cập nhật qua admin; thay provider hoặc embedding model cần khởi động lại với môi trường tương ứng. Xem [model, readiness, migration và kiểm tra vận hành](docs/ollama.md).

### Redis cho cache ATS và matching

Cấu hình Redis chung được [`spring.data.redis` trong application.yaml](src/main/resources/application.yaml)
ánh xạ từ các key trong [`.env.example`](.env.example); host/port do từng Spring profile chọn mặc định. Với backend chạy trực tiếp trên Windows và
Redis local trong Compose, thêm các dòng sau vào `.env` rồi khởi động lại backend bằng `scripts/run-local.ps1`:

```dotenv
REDIS_HOST=localhost
REDIS_PORT=6379
REDIS_USERNAME=
REDIS_PASSWORD=
REDIS_DATABASE=0
REDIS_TIMEOUT=500ms
REDIS_CONNECT_TIMEOUT=500ms
REDIS_SSL_ENABLED=false
```

Khởi động Redis và kiểm tra kết nối từ container:

```powershell
docker compose up -d --wait redis
docker compose exec redis redis-cli PING
```

Kỳ vọng `PONG`. `run-local.ps1 -WithInfrastructure` chỉ khởi động PostgreSQL; Redis phải được
khởi động riêng. Nếu port 6379 trên máy đã được sử dụng, đổi `REDIS_PORT`, ví dụ `6380`;
Compose ánh xạ port đó trên máy vào port 6379 trong container. Redis local này không bật AUTH/TLS,
nên để username/password trống và SSL false. Redis ngoài cần thông tin kết nối và AUTH/TLS đúng
theo server. Khi backend chạy trong container, dùng host Redis mà container truy cập được
(chẳng hạn `redis` trong cùng mạng Compose), cùng port nội bộ; `localhost` khi đó chỉ là container backend.

Redis lưu toàn bộ response trong JSON envelope `schema_version/identity/response`, thay cho chỉ lưu ID.
ATS dùng key `edunexus:ats:response:v1:<userId>:<resumeId>:<jobId>:<inputHash>`, TTL tối đa 24 giờ;
phân tích CV dùng `edunexus:resume-analysis:response:v1:<userId>:<resumeId>:<inputHash>`, tối đa 24 giờ.
Matching dùng `edunexus:matching:response:v1:<userId>:<requestHash>`, tối đa 1 giờ và không vượt
tuổi run hoặc hạn tuyển dụng. Cache hit không đọc lại các bảng kết quả; backend vẫn kiểm tra
quyền, CV/JD hiện tại và fingerprint model/prompt để tránh dùng kết quả sai đầu vào.
ATS/matching có DB fallback và ghi lại Redis khi tìm được kết quả DB; CV analysis Redis miss
tạo phân tích mới. Cache chỉ được ghi sau commit. Namespace mới không đọc các key UUID cũ.
`cached=true` có thể đến từ Redis hoặc database; GET key/TTL xác nhận Redis đã lưu response.
`/actuator/health` UP không xác nhận Redis vì Redis health indicator đang tắt.
Xem [các bước kiểm thử Redis/ATS trên Swagger](docs/swagger-testing.md#redis-ats).

Qdrant, RabbitMQ và Redis nằm trong profile `optional`; luồng hiện tại lưu vector trong
PostgreSQL và dùng SSE cho tư vấn. Redis là dịch vụ tùy chọn để cache kết quả CV, ATS và matching.

### Thanh toán và tích hợp khác

Thanh toán hiện có đơn hàng và webhook bridge HMAC, chưa kết nối SDK cụ thể của VNPay/PayOS/Stripe. Gateway của bạn phải cung cấp checkout và gửi webhook đúng hợp đồng trong [hướng dẫn identity/billing](docs/identity-billing.md). Backend chỉ kích hoạt subscription sau khi xác minh chữ ký, timestamp, số tiền và currency; callback từ trình duyệt không kích hoạt gói.

Ứng tuyển trả URL gốc của ITviec/TopCV và lưu sự kiện. Email/cover letter được sinh thành draft để người dùng sửa/copy; API hiện chưa gửi email ứng tuyển. OCR cần bật cấu hình và có Tesseract tương thích trên máy; file scan không có text được yêu cầu nhập/sửa thủ công nếu OCR chưa sẵn sàng. Crawling tìm URL theo keyword hoặc nhận URL admin cung cấp, parse JSON-LD/HTML, chuẩn hóa dữ liệu và enqueue embedding. Collection định kỳ cần cấu hình admin thật; trang bị chặn/không tương thích có item lỗi, không cam kết HTML nguồn thật qua fixture tests. Xem [crawling](docs/crawling.md) và [cấu hình collection](docs/job-collection-setup.md).

## Tài liệu và kiểm thử

Dockerfile đóng gói JAR đã được Maven tạo. Khi build image cục bộ, chạy từ thư mục chứa `pom.xml`:

```powershell
mvn verify
docker build -t edunexus-backend:local .
```

CI chạy kiểm thử, upload JAR rồi tải artifact vào `target` trước khi build image. `.dockerignore` cho phép `target/*.jar` và loại các output Maven khác. Nếu gặp `COPY target/*.jar` báo không tìm thấy thư mục, kiểm tra JAR đã được tạo hoặc tải về và build context đang là thư mục dự án.

- [Hướng dẫn kiểm thử API bằng Swagger](docs/swagger-testing.md)
- [Postman: dữ liệu mẫu và thứ tự test với Ollama](docs/postman-test-order.md)
- [Postman: danh mục API, body, quyền và script](docs/postman-api-guide.md)
- [Admin quản lý template CV: API, HTML/CSS và thứ tự test](docs/admin-resume-templates.md)
- [Một mức lương kỳ vọng và gợi ý job theo ngưỡng cấu hình](docs/salary-based-job-recommendations.md)
- [Admin chỉnh biên độ lương khi gợi ý việc làm](docs/admin-salary-policy.md)
- [Hợp đồng API và ví dụ core-flow](docs/api.md)
- [Cấu trúc controller / DTO / entity / repository / service / util](docs/architecture.md)
- [Hướng dẫn đọc từng service và helper trong util](docs/service-code-guide.md)
- [Profile runtime dev/prod/docker và cách chạy local/Compose/production](docs/runtime-profiles.md)
- [Ollama self-hosted cho chat và embedding, cold/warm smoke test](docs/ollama.md)
- [Quy ước Java và lệnh định dạng/kiểm tra](docs/java-coding-style.md)
- [Schema, migration và ràng buộc](docs/schema.md)
- [Conceptual Diagram EduNexus: sơ đồ tổng thể một trang](docs/conceptual-diagram.md)
- [16 Sequence diagram và Class diagram tương ứng, kèm ảnh PNG/SVG](docs/sequence-diagrams.md)
- [Trang xem và phóng to toàn bộ Sequence/Class diagram](docs/diagrams/sequence/index.html)
- [Identity, billing và quản trị](docs/identity-billing.md)
- [CV, AI, assistant, trial và RAG](docs/resume-ai.md)
- [Đánh giá cách viết CV: bullet, dấu câu, ngôn từ và dữ liệu Postman](docs/cv-writing-quality.md)
- [Đối chiếu CV với mục tiêu/hồ sơ nghề nghiệp: tiêu chí, phần trăm, lịch sử và dữ liệu Postman](docs/cv-profile-alignment.md)
- [Career Consultant: đánh giá AI chất lượng CV và ATS CV–job](docs/consultant-ai-evaluation.md)
- [Khóa học YouTube/Coursera do Admin duyệt và gợi ý trong lộ trình](docs/curated-course-links.md)
- [Nguồn tham khảo roadmap học tập và gợi ý sau assessment](docs/learning-roadmap-sources.md)
- [Chọn JD, chuẩn bị bài đánh giá và học từng kỹ năng còn thiếu: dữ liệu/thứ tự Postman](docs/jd-assessment-missing-skills.md)
- [Discovery, parsing, deduplicate và embedding việc làm](docs/crawling.md)
- [Cấu hình collection qua API admin, lịch sử và expiration](docs/job-collection-setup.md)
- [Kết quả kiểm chứng và phạm vi đã kiểm tra](docs/verification.md)

```powershell
mvn verify
```

Unit test chạy độc lập với dịch vụ ngoài. Testcontainers kiểm tra Flyway và pgvector khi Docker khả dụng. Bộ API integration chạy khi bạn cung cấp database thử nghiệm riêng:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/start-local-test-postgres.ps1
$env:EDUNEXUS_TEST_DATABASE_URL='jdbc:postgresql://127.0.0.1:55439/edunexus_test'
mvn verify
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/stop-local-test-postgres.ps1
```

Script cục bộ chỉ dùng cluster trong `target/local-pg`. PostgreSQL cài trên máy chưa có pgvector nên bản schema thử nghiệm dùng `real[]` và bỏ HNSW; các test này xác minh nghiệp vụ quan hệ, không xác minh semantic/vector search. Migration mới dùng vector 1024 chiều cho Ollama và giữ dữ liệu vector cũ để chuyển đổi; không sửa checksum migration đã áp dụng. Để kiểm thử trên PostgreSQL thật có pgvector, dùng database riêng, đặt thêm `EDUNEXUS_TEST_DATABASE_USER`, `EDUNEXUS_TEST_DATABASE_PASSWORD` và `EDUNEXUS_TEST_FLYWAY=true`.

## CI/CD

GitHub Actions is configured in `.github/workflows/ci-cd.yml`.

- Every pull request and push to `main` runs `./mvnw verify` (including the Testcontainers integration test) and produces a JAR artifact.
- A successful push to `main` publishes both an immutable image tagged with the commit SHA and `latest` to GitHub Container Registry (GHCR).
- Deployment is deliberately disabled until a deployment host is configured. Once configured, each successful `main` build pulls the SHA-tagged image and restarts it with Docker Compose.

### Enable production deployment

Create the GitHub **production** environment, then configure the following repository settings:

| Setting | Value |
| --- | --- |
| Environment variable `DEPLOY_HOST` | Hostname or IP of the Docker server |
| Environment variable `DEPLOY_USER` | SSH user on that server |
| Environment variable `DEPLOY_PATH` | Absolute application directory on that server, e.g. `/opt/intelligent-cv` |
| Environment secret `DEPLOY_SSH_KEY` | Private SSH key for `DEPLOY_USER` |

On the server, create `DEPLOY_PATH/.env`. It must contain all runtime Spring secrets and configuration,
including `DATABASE_URL`, `REDIS_HOST`, `REDIS_PORT`, database credentials and a randomly generated `APP_JWT_SECRET`; there is no JWT secret fallback. Use PostgreSQL and Redis addresses reachable from the app container. `compose.production.yaml` selects Spring profile `prod`, starts a private Ollama server with model initialization, and fixes uploads at `/app/uploads` backed by `resume_uploads`. Model weights persist in `ollama_models`; the first deployment downloads the configured local models. Set `AI_ENABLED=true`, `AI_CHAT_MODEL` and `AI_EMBEDDING_MODEL` for this deployment. Ollama cloud features are disabled, and the app uses the internal Ollama URL. Do not commit the server's `.env` file.
For a private GHCR image, authenticate the server once with a GitHub token that has `read:packages`:

```sh
echo "$GHCR_TOKEN" | docker login ghcr.io -u GITHUB_USERNAME --password-stdin
```

The deployment exposes port `8080` by default. Override it by setting `APP_PORT` in the server's `.env` file. Set any other Spring properties there as standard environment variables.
