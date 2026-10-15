# Intelligent-CV-Matching-AI-Agent-BE

Backend EduNexus triển khai bằng Java 25, Spring Boot 4.1.1, JPA/JDBC, PostgreSQL/pgvector và Flyway, dựa trên DBML và các core-flow được cung cấp. API dùng tiền tố chính `/api/v1`; tài liệu tương tác ở `http://localhost:8080/swagger-ui/index.html`.

Mã nguồn chia thành `controller`, `dto`, `entity`, `repository`, `service` và `util`. Trong mỗi lớp, mã được nhóm theo `guest`, `candidate`, `consultant`, `admin` và `shared`, rồi theo chức năng. DTO tách rõ `dto/request/<role>/<chức năng>` và `dto/response/<role>/<chức năng>`; tên lớp có hậu tố `Request` hoặc `Response`. Controller nhận HTTP và gọi service; SQL nằm trong repository. Bộ entity/JPA repository sinh tự động bao phủ 76 bảng từ V1/V3; các bảng vận hành bổ sung ở V5/V7 dùng workflow repository SQL. Xem [cấu trúc thư mục và ví dụ luồng xử lý](docs/architecture.md).

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

Script đọc `.env` vào môi trường của tiến trình, khởi động PostgreSQL rồi chạy ứng dụng. Flyway tự tạo schema và danh mục ban đầu. `.env` và file upload được loại khỏi Git. Với PostgreSQL riêng, đặt `DATABASE_URL`, `POSTGRES_USER`, `POSTGRES_PASSWORD` và chạy script không có `-WithInfrastructure`.

Ứng dụng cũng tự nạp `.env` bằng `spring.config.import` của Spring Boot khi chạy từ IDE,
`mvn spring-boot:run` hoặc `java -jar`. Đặt working directory là thư mục chứa `pom.xml` và `.env`.
Muốn dùng file khác, đặt biến môi trường `APP_ENV_FILE` trước khi chạy hoặc truyền `-EnvFile` cho script local.
`application.yaml` ánh xạ các thiết lập runtime qua `${VAR}`; giá trị cấu hình nằm trong `.env`,
và [`.env.example`](.env.example) liệt kê đầy đủ các key để bổ sung khi cập nhật dự án. Giữ giá trị
đang có trong `.env` khi bổ sung key mới.

File dùng UTF-8, mỗi dòng `KEY=value` không bọc giá trị bằng dấu nháy, không có `export` hoặc
comment sau giá trị; comment đặt trên dòng riêng. Dùng dấu `/` cho đường dẫn Windows, ví dụ
`UPLOAD_DIR=C:/edunexus/uploads`. Biến môi trường hệ điều hành/container được ưu tiên hơn file.
Import là optional để Docker vẫn chạy khi chỉ được cấp biến môi trường; các biến bắt buộc vẫn phải đủ.
Sửa cấu hình rồi khởi động lại backend.

Trong môi trường phát triển, `AUTH_EXPOSE_DEV_OTP=true` trả `developmentCode` khi đăng ký để thử bước xác minh email. Khi dùng SMTP, đặt `AUTH_EXPOSE_DEV_OTP=false`, `MAIL_ENABLED=true` và các biến `MAIL_*`. Google login nhận ID token từ frontend khi đã cấu hình `GOOGLE_CLIENT_ID`.

Admin đầu tiên được tạo từ tài khoản đã đăng ký và xác minh, bằng thao tác quản trị database:

```powershell
Get-Content -Raw scripts/bootstrap-admin.sql | docker compose exec -T postgres psql -U edunexus -d edunexus -v admin_email='you@example.com'
```

Script chỉ nâng quyền khi tài khoản đã xác minh và hệ thống chưa có admin hoạt động. Những admin tiếp theo được tạo qua API quản trị. Hệ thống không có mật khẩu admin mặc định.

## Các luồng đã triển khai

| Core-flow | API chính và hành vi |
| --- | --- |
| Guest trial / đăng ký | Session guest, CV demo, template trial, quota, đăng ký, OTP, đăng nhập và refresh token |
| Resume processing / builder | Upload PDF/DOCX/TXT, trích xuất nội dung, xem lại/sửa/xác nhận, nhiều CV, CV chính, phân tích, tải về và embedding |
| Job discovery / matching | Danh sách/detail job, matching profile/CV, cache, feedback, ATS CV–job, draft email/cover letter, ghi nhận mở trang ứng tuyển |
| Assessment / learning roadmap | Sinh câu hỏi, lưu đáp án, chấm khách quan, skill gaps, roadmap, prerequisite và tiến độ học |
| Career consultation | Tạo thread, admin phân công consultant, lịch sử tin nhắn, SSE và đóng hội thoại |
| Subscription / payment | Gói/quota, đơn thanh toán, webhook HMAC, kích hoạt gói, invoice, báo lỗi và nhắc gia hạn |
| Admin / system | Tài khoản, gói/quota, AI config/prompt, thông báo theo đối tượng, audit, monitoring, support và RAG |
| External job processing | Admin import/crawl URL ITviec hoặc TopCV, chuẩn hóa JSON-LD, chống trùng, xử lý phiên bản job và index vector |

Danh mục mẫu gồm FREE/BASIC/PRO, kỹ năng, template/CV demo và tài nguyên học từ tài liệu chính thức. Giá và quota seed là dữ liệu phát triển, có thể sửa qua API admin.

## Bật AI và các dịch vụ bên ngoài

Mặc định `AI_ENABLED=false`. Nhập/sửa/xác nhận CV, so khớp kỹ năng cơ bản, tư vấn giữa người dùng, quản trị và xử lý đơn/webhook hoạt động độc lập với AI. Kết quả cơ bản ghi rõ `RULE_BASED` hoặc `SKILL_OVERLAP`; chức năng sinh nội dung trả 503 khi chưa có model.

Để dùng adapter OpenAI hiện có, đặt các biến sau trong `.env`, rồi khởi động lại:

```dotenv
AI_ENABLED=true
AI_CHAT_PROVIDER=openai
AI_EMBEDDING_PROVIDER=openai
OPENAI_API_KEY=your-key
AI_CHAT_MODEL=gpt-4.1-mini
AI_EMBEDDING_MODEL=text-embedding-3-small
```

Model embedding phải trả đúng 1536 chiều. Import/crawl job tự enqueue embedding; worker xử lý khi provider bật. Theo dõi bằng `GET /api/v1/admin/jobs/{id}/index`, dùng POST cùng path để index/retry thủ công, rồi chạy semantic matching khi vector đã sẵn sàng. Provider/model được lưu cùng embedding; thay model cần index lại dữ liệu. AI đặt trước ngân sách token, đối soát usage sau phản hồi và ghi log. Prompt và cấu hình CHAT cùng provider có thể cập nhật qua admin; thay provider hoặc cấu hình embedding cần khởi động lại với cấu hình môi trường tương ứng.

### Redis cho cache ATS và matching

Cấu hình kết nối Redis được [`spring.data.redis` trong application.yaml](src/main/resources/application.yaml)
ánh xạ từ các key trong [`.env.example`](.env.example). Với backend chạy trực tiếp trên Windows và
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

Cache ATS dùng key `edunexus:ats:<userId>:<resumeId>:<jobId>:<inputHash>`, giá trị là UUID
`job_resume_analyses`, TTL 24 giờ. Backend vẫn kiểm tra bản ghi database theo người dùng, CV,
job và hash trước khi trả kết quả; database cung cấp cache khi Redis không kết nối được.
`cached=true` có thể đến từ Redis hoặc database, nên cần kiểm tra key/TTL để xác minh Redis
đã lưu cache. `/actuator/health` UP không xác nhận Redis vì Redis health indicator đang tắt.
`POST /api/v1/matching` dùng thêm key Redis `edunexus:matching:<userId>:<requestHash>` với TTL 1 giờ,
và vẫn fallback về cache database một giờ khi Redis không khả dụng.
Xem [các bước kiểm thử Redis/ATS trên Swagger](docs/swagger-testing.md#redis-ats).

Qdrant, RabbitMQ và Redis nằm trong profile `optional`; luồng hiện tại lưu vector trong
PostgreSQL và dùng SSE cho tư vấn. Redis là dịch vụ tùy chọn để tăng tốc ATS và matching.

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
- [Hợp đồng API và ví dụ core-flow](docs/api.md)
- [Cấu trúc controller / DTO / entity / repository / service / util](docs/architecture.md)
- [Quy ước Java và lệnh định dạng/kiểm tra](docs/java-coding-style.md)
- [Schema, migration và ràng buộc](docs/schema.md)
- [Conceptual Diagram EduNexus: sơ đồ tổng thể một trang](docs/conceptual-diagram.md)
- [Identity, billing và quản trị](docs/identity-billing.md)
- [CV, AI, assistant, trial và RAG](docs/resume-ai.md)
- [Khóa học YouTube/Coursera do Admin duyệt và gợi ý trong lộ trình](docs/curated-course-links.md)
- [Nguồn tham khảo roadmap học tập và gợi ý sau assessment](docs/learning-roadmap-sources.md)
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

Script cục bộ chỉ dùng cluster trong `target/local-pg`. PostgreSQL cài trên máy chưa có pgvector nên bản schema thử nghiệm dùng `real[]` và bỏ HNSW; các test này xác minh nghiệp vụ quan hệ, không xác minh semantic/vector search. Migration production giữ nguyên `vector(1536)`. Để kiểm thử trên PostgreSQL thật có pgvector, dùng database riêng, đặt thêm `EDUNEXUS_TEST_DATABASE_USER`, `EDUNEXUS_TEST_DATABASE_PASSWORD` và `EDUNEXUS_TEST_FLYWAY=true`.

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
including a randomly generated `APP_JWT_SECRET`; there is no JWT secret fallback. Do not commit this file.
For a private GHCR image, authenticate the server once with a GitHub token that has `read:packages`:

```sh
echo "$GHCR_TOKEN" | docker login ghcr.io -u GITHUB_USERNAME --password-stdin
```

The deployment exposes port `8080` by default. Override it by setting `APP_PORT` in the server's `.env` file. Set any other Spring properties there as standard environment variables.
