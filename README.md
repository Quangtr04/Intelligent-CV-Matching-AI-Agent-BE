# Intelligent-CV-Matching-AI-Agent-BE

Backend EduNexus triển khai bằng Java 25, Spring Boot 4.1.1, JPA/JDBC, PostgreSQL/pgvector và Flyway, dựa trên DBML và các core-flow được cung cấp. API dùng tiền tố `/api/v1`; tài liệu tương tác ở `http://localhost:8080/swagger-ui/index.html`.

Mã nguồn chia thành `controller`, `dto`, `entity`, `repository`, `service` và `util`. Trong mỗi lớp, mã được nhóm theo `guest`, `candidate`, `consultant`, `admin` và `shared`, rồi theo chức năng. DTO tách rõ `dto/request/<role>/<chức năng>` và `dto/response/<role>/<chức năng>`; tên lớp có hậu tố `Request` hoặc `Response`. Controller nhận HTTP và gọi service; SQL nằm trong repository. Có 76 entity và 76 JPA repository tương ứng toàn bộ bảng. Xem [cấu trúc thư mục và ví dụ luồng xử lý](docs/architecture.md).

## Chạy trên Windows

Yêu cầu Java 25, Maven và PostgreSQL có extension pgvector. Compose cung cấp PostgreSQL đã có pgvector khi Docker Desktop đang chạy.

```powershell
Copy-Item .env.example .env
# Sửa POSTGRES_PASSWORD trong .env.
# Để đăng ký thử khi chưa có SMTP, đặt AUTH_EXPOSE_DEV_OTP=true.
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/run-local.ps1 -WithInfrastructure
```

Script đọc `.env` vào môi trường của tiến trình, khởi động PostgreSQL rồi chạy ứng dụng. Flyway tự tạo schema và danh mục ban đầu. `.env` và file upload được loại khỏi Git. Với PostgreSQL riêng, đặt `DATABASE_URL`, `POSTGRES_USER`, `POSTGRES_PASSWORD` và chạy script không có `-WithInfrastructure`.

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

Model embedding phải trả đúng 1536 chiều. Import job, gọi `POST /api/v1/admin/jobs/{id}/index`, rồi mới chạy semantic matching. Provider/model được lưu cùng embedding; thay model cần index lại dữ liệu. AI đặt trước ngân sách token, đối soát usage sau phản hồi và ghi log. Prompt và cấu hình CHAT cùng provider có thể cập nhật qua admin; thay provider hoặc cấu hình embedding cần khởi động lại với cấu hình môi trường tương ứng.

Redis cung cấp cache ATS khi có dịch vụ; database vẫn cung cấp cache khi Redis không khả dụng. Chạy Redis bằng `docker compose up -d redis`. Qdrant và RabbitMQ được giữ trong profile `optional` để mở rộng; luồng hiện tại lưu vector trong PostgreSQL và dùng SSE cho tư vấn.

Thanh toán hiện có đơn hàng và webhook bridge HMAC, chưa kết nối SDK cụ thể của VNPay/PayOS/Stripe. Gateway của bạn phải cung cấp checkout và gửi webhook đúng hợp đồng trong [hướng dẫn identity/billing](docs/identity-billing.md). Backend chỉ kích hoạt subscription sau khi xác minh chữ ký, timestamp, số tiền và currency; callback từ trình duyệt không kích hoạt gói.

Ứng tuyển trả URL gốc của ITviec/TopCV và lưu sự kiện. Email/cover letter được sinh thành draft để người dùng sửa/copy; API hiện chưa gửi email ứng tuyển. OCR cần bật cấu hình và có Tesseract tương thích trên máy; file scan không có text được yêu cầu nhập/sửa thủ công nếu OCR chưa sẵn sàng. Crawling nhận URL do admin cung cấp và phân tích JSON-LD thực tế; trang chặn truy cập hoặc thiếu dữ liệu được ghi nhận thất bại, chi tiết ở [crawling](docs/crawling.md).

## Tài liệu và kiểm thử

- [Hợp đồng API và ví dụ core-flow](docs/api.md)
- [Cấu trúc controller / DTO / entity / repository / service / util](docs/architecture.md)
- [Quy ước Java và lệnh định dạng/kiểm tra](docs/java-coding-style.md)
- [Schema, migration và ràng buộc](docs/schema.md)
- [Identity, billing và quản trị](docs/identity-billing.md)
- [CV, AI, assistant, trial và RAG](docs/resume-ai.md)
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

On the server, create `DEPLOY_PATH/.env`. It must contain all runtime Spring secrets and configuration. Do not commit this file. For a private GHCR image, authenticate the server once with a GitHub token that has `read:packages`:

```sh
echo "$GHCR_TOKEN" | docker login ghcr.io -u GITHUB_USERNAME --password-stdin
```

The deployment exposes port `8080` by default. Override it by setting `APP_PORT` in the server's `.env` file. Set any other Spring properties there as standard environment variables.
