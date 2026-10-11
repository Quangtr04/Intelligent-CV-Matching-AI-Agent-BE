# Hướng dẫn kiểm thử API bằng Swagger

Hướng dẫn thao tác trên backend EduNexus hiện tại, theo các core-flow: tài khoản, guest trial,
CV, tìm việc, đánh giá kỹ năng, roadmap, tư vấn, thanh toán và quản trị. Các mã HTTP và kết quả
bên dưới là **kỳ vọng để kiểm thử**, không phải báo cáo đã chạy Swagger thành công.

Đi nhanh đến các phần:

- [Chuẩn bị](#chuan-bi) · [Thứ tự test](#thu-tu) · [Authorize](#swagger).
- [Tài khoản](#tai-khoan) · [Admin/Consultant](#roles) · [Job mẫu](#job-mau).
- [CV và matching](#career-cv-matching) · [Guest](#guest-trial) · [Tư vấn](#tu-van).
- [Thanh toán](#thanh-toan) · [Thông báo](#thong-bao) · [Hỗ trợ](#ho-tro) · [Quản trị](#quan-tri).
- [Cấu hình AI](#cau-hinh-ai) · [Redis/ATS cache](#redis-ats) · [ATS/Assessment/Roadmap/Assistant](#ai-tools) · [RAG/Crawl](#rag-crawl).
- [Lỗi Auth/Guest](#loi-auth) · [Lỗi Candidate](#loi-candidate) · [Ghi kết quả](#ket-qua) · [Gỡ lỗi](#go-loi).

<a id="chuan-bi"></a>

## Chuẩn bị và khởi động backend

Yêu cầu Java 25, Maven và PostgreSQL có extension **pgvector**. Khi dùng Compose, mở Docker
Desktop trước. Chạy lệnh trong thư mục dự án chứa `pom.xml` và `compose.yaml`.

Tạo `.env` nếu chưa có:

```powershell
if (-not (Test-Path -LiteralPath .env)) {
    Copy-Item .env.example .env
}
```

Sửa các dòng tương ứng trong `.env` cho lượt kiểm thử local; giữ các cấu hình khác cần thiết:

```dotenv
POSTGRES_DB=edunexus
POSTGRES_USER=edunexus
POSTGRES_PASSWORD=swagger-local-db-pass
POSTGRES_PORT=5432
DATABASE_URL=jdbc:postgresql://localhost:5432/edunexus
AUTH_EXPOSE_DEV_OTP=true
MAIL_ENABLED=false
AI_ENABLED=false
AI_CHAT_PROVIDER=none
AI_EMBEDDING_PROVIDER=none
PAYMENT_PROVIDER=MANUAL_GATEWAY
PAYMENT_WEBHOOK_SECRET=local-swagger-payment-secret-32-bytes-minimum
DEBUG=false
TRACE=false
```

Mật khẩu database và webhook secret trên là dữ liệu mẫu cho local. `AUTH_EXPOSE_DEV_OTP=true`
giúp lấy OTP ngay trong response mà không cần SMTP; chỉ dùng cờ này khi phát triển.
Nếu PostgreSQL đã chạy, dùng đúng tài khoản, port và database thực tế của bạn.

Khởi động PostgreSQL và backend:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/run-local.ps1 -WithInfrastructure
```

Script nạp `.env` vào môi trường tiến trình, gọi `mvn spring-boot:run`; Flyway tạo schema và
dữ liệu seed. Spring Boot không tự đọc `.env` khi chạy một lệnh Maven riêng. Với PostgreSQL
riêng đã có pgvector, bỏ `-WithInfrastructure`. Giữ terminal chạy backend mở trong lúc test.

| Địa chỉ | Dùng để kiểm tra |
| --- | --- |
| `http://localhost:8080/actuator/health` | Backend và database sẵn sàng; kỳ vọng `status: UP` |
| `http://localhost:8080/swagger-ui/index.html` | Giao diện chạy API |
| `http://localhost:8080/v3/api-docs` | OpenAPI JSON của backend đang chạy |
| `http://localhost:8080/api/v1` | Tiền tố API; không phải trang Swagger |

Nếu đổi port server, thay `8080` ở các địa chỉ tương ứng. Health là bước kiểm tra riêng bằng
trình duyệt; không cần tìm endpoint actuator trong danh sách Swagger.

<a id="thu-tu"></a>

## Thứ tự kiểm thử và dữ liệu cần lưu

Thực hiện trên dữ liệu local/test. Lần chạy đầu, đi theo thứ tự dưới đây để các ID và quyền
đã có trước khi gọi API phụ thuộc:

| Thứ tự | Luồng | Role | Điều kiện đầu vào |
| --- | --- | --- | --- |
| 1 | Đăng ký → OTP → login → `/me` | Candidate A, Candidate B | Hai email khác nhau |
| 2 | Đăng ký admin → bootstrap → tạo consultant | Admin | Email admin đã xác minh |
| 3 | Import một job mẫu | Admin | Skill từ danh mục seed |
| 4 | Career profile → CV thủ công → confirm → phân tích | Candidate A | Skill ID; CV cần READY |
| 5 | Matching → feedback → external-apply | Candidate A | Job ACTIVE và CV READY |
| 6 | Guest session → demo CV → recommendation | Guest | Job đã được import |
| 7 | Tạo tư vấn → gán consultant → trao đổi | Candidate A, Admin, Consultant | Các tài khoản đã ACTIVE |
| 8 | Gói → order → webhook local → invoice | Candidate A | Webhook secret đã cấu hình |
| 9 | Announcements, support, quản lý gói và monitoring | Admin, Candidate A | Đổi đúng token từng bước |
| 10 | ATS, AI builder, assessment, roadmap, assistant, RAG/index | Candidate A, Admin | Bật AI và provider tương ứng |
| 11 | Ca lỗi, quyền sở hữu, logout và đóng dữ liệu test | Candidate B và các role | Thực hiện sau các luồng chính |

Khi AI tắt, CV thủ công, phân tích độ đầy đủ, matching `SKILL_OVERLAP`, tư vấn giữa người
dùng, billing local và quản trị vẫn test được. Chức năng gọi model cần cấu hình riêng;
`503` trong trường hợp AI chưa bật được ghi là **BLOCKED_CONFIG** cho ca thành công cần AI.

Chuẩn bị các tài khoản sau bằng bước đăng ký/xác minh; consultant được admin tạo:

| Tài khoản mẫu | Vai trò | Mục đích |
| --- | --- | --- |
| `candidate.swagger@example.com` | CANDIDATE | Chạy các luồng chính |
| `candidate.b.swagger@example.com` | CANDIDATE | Kiểm tra không đọc/sửa dữ liệu của A |
| `admin.swagger@example.com` | ADMIN sau bootstrap | Import job, phân công, quản trị |
| `consultant.swagger@example.com` | CAREER_CONSULTANT | Phản hồi tư vấn |

Password mẫu ban đầu là `SwaggerDemo2026!`. Nếu email đã tồn tại, login tài khoản đã có
hoặc dùng email test mới. Không đăng ký lại cùng email rồi coi `409` là lỗi của luồng đăng ký.

Lưu dữ liệu trả về để thay placeholder trong các bước sau:

| Dữ liệu | Nguồn lấy |
| --- | --- |
| `accessToken`, `refreshToken` của từng role | Response login; không dùng chung token giữa role |
| `<CANDIDATE_ID>`, `<ADMIN_ID>`, `<CONSULTANT_ID>` | `/me` hoặc response admin tạo tài khoản |
| `<SKILL_ID>`, `<JOB_ID>` | `/skills`, response import job hoặc `/jobs` |
| `<RESUME_ID>`, `<ANALYSIS_ID>` | Response tạo CV, phân tích CV |
| `<MATCHING_RUN_ID>`, `<MATCH_RESULT_ID>` | Response matching: `id` và `results[*].id` |
| `sessionKey`, `<DEMO_RESUME_ID>`, `<TRIAL_TEMPLATE_ID>` | Guest session, demo resumes, templates |
| `<THREAD_ID>`, `<MESSAGE_ID>` | Response tạo tư vấn, gửi tin nhắn |
| `<ORDER_ID>`, `<INVOICE_ID>`, `<SUBSCRIPTION_ID>` | Order, invoice, subscription sau webhook |
| `<ASSESSMENT_ID>`, `<QUESTION_ID>`, `<ROADMAP_ID>` | Response assessment/questions/roadmap |

Request DTO thường dùng **camelCase**, còn CV dạng JSON và nhiều response database dùng
**snake_case**. Copy đúng tên trường trong ví dụ; không đổi mọi trường sang cùng một kiểu.

<a id="swagger"></a>

## Thao tác chung trong Swagger

Mở endpoint → **Try it out** → thay dữ liệu mẫu → **Execute**. Kiểm tra **Response code**,
**Response body** và **Request URL**. Các API tạo dữ liệu trong hướng dẫn trả `200` theo code
hiện tại; không mặc định kỳ vọng `201`.

Trong **Authorize**, có hai cơ chế:

| Cơ chế | Giá trị cần nhập | Sử dụng |
| --- | --- | --- |
| `bearerAuth` | Giá trị `accessToken` trả từ login, bắt đầu bằng `a_` | API tài khoản/candidate/consultant/admin |
| `guestSession` | Giá trị `sessionKey` trả từ tạo guest session, bắt đầu bằng `g_` | API `/api/v1/guest/**` cần session |

Với `bearerAuth`, nhập nguyên token, không thêm tiền tố `Bearer `; Swagger thêm tiền tố HTTP.
Guest dùng header `X-Guest-Session`. Tham khảo cơ chế [HTTP Bearer](https://swagger.io/docs/specification/v3_0/authentication/bearer-authentication/) và [API key trong header](https://swagger.io/docs/specification/v3_0/authentication/api-keys/) của OpenAPI. Nếu **Parameters** của endpoint hiện ô header bắt buộc,
điền cùng `sessionKey` vào ô đó. Không dùng `refreshToken` thay `accessToken`.
Sau logout hoặc đổi mật khẩu, xóa bearer cũ trong **Authorize** trước khi login/refresh lại:
backend kiểm tra mọi header `Authorization` được gửi, kể cả ở API công khai.

Các chuỗi `<...>` dưới đây là chỗ cần thay bằng dữ liệu thật trả từ bước trước; không gửi
nguyên placeholder. Tạo email mới cho mỗi lượt đăng ký để tránh `409` do trùng email.

<a id="tai-khoan"></a>

## Đăng ký, OTP, đăng nhập và phân quyền

### Đăng ký candidate

Gọi `POST /api/v1/auth/register`:

```json
{
  "email": "candidate.swagger@example.com",
  "password": "SwaggerDemo2026!",
  "fullName": "Nguyễn Minh Anh",
  "preferredLanguage": "vi"
}
```

Mong đợi `200`, `message`, `expiresIn: 600`; khi bật `AUTH_EXPOSE_DEV_OTP=true`, có thêm
`developmentCode`. Tài khoản mới có vai trò `CANDIDATE`, trạng thái `PENDING`; chưa có token.
Mật khẩu từ 10 đến 72 ký tự và không quá 72 byte UTF-8. Ngôn ngữ tài khoản là `vi` hoặc `en`.

Nếu cả development OTP và SMTP đều tắt, register trả `503` và không lưu tài khoản đăng ký
dở dang. Development OTP chỉ dành cho môi trường local; khi dùng SMTP, tắt cờ này và bật
`MAIL_ENABLED=true` với cấu hình `MAIL_*` phù hợp.

### Xác minh email rồi đăng nhập

Gọi `POST /api/v1/auth/verify-email`; thay `<OTP_6_CHU_SO>` bằng mã thật vừa nhận:

```json
{
  "email": "candidate.swagger@example.com",
  "code": "<OTP_6_CHU_SO>"
}
```

Mong đợi `200`, `message: Email verified; you can log in`. OTP hết hạn sau 10 phút.
Nếu cần mã mới, `POST /api/v1/auth/resend-code` với `{"email":"candidate.swagger@example.com"}`;
đợi ít nhất 60 giây từ lần cấp mã gần nhất. Mã mới thay mã cũ.

Gọi `POST /api/v1/auth/login`:

```json
{
  "email": "candidate.swagger@example.com",
  "password": "SwaggerDemo2026!"
}
```

Mong đợi `200`, có chính xác các khóa token `accessToken`, `refreshToken`, `tokenType: Bearer`,
`expiresIn: 900`. Access token sống 15 phút; refresh token sống 30 ngày. Lưu cả hai và
Authorize `bearerAuth` bằng access token.

Gọi `GET /api/v1/me`: `200`, lấy `id` làm `<CANDIDATE_ID>` cho các luồng sau; kiểm tra
`role: CANDIDATE`, `status: ACTIVE`, `email_verified_at`. Response profile dùng các khóa như
`full_name`, `preferred_language`; không trả password hash.

### Thông tin tài khoản

Gọi `PUT /api/v1/me` với:

```json
{
  "fullName": "Nguyễn Minh Anh",
  "avatarUrl": null,
  "preferredLanguage": "vi",
  "githubUrl": "https://github.com/example-candidate"
}
```

Mong đợi `200`, kiểm tra giá trị phản ánh ở response và `GET /api/v1/me`.
Hồ sơ nghề nghiệp được kiểm tra ở phần [Candidate: CV và matching](#career-cv-matching).

### Refresh, session và đổi mật khẩu

`POST /api/v1/auth/refresh` với `{"refreshToken":"<REFRESH_TOKEN>"}` trả `200` cùng bộ khóa
token như login. Lưu bộ mới và thay bearer trong Swagger. Gửi lại refresh cũ trả `401`;
access cũ vẫn tồn tại tới khi hết hạn hoặc bị thu hồi.

`GET /api/v1/me/sessions` trả các `id`, `created_at`, `expires_at`; lấy ID thật để thử
`DELETE /api/v1/me/sessions/{id}` → `200`, `message: Session revoked`. Mỗi dòng là một token
session được lưu; API này thu hồi dòng được chọn, logout thu hồi tất cả.

Thử thu hồi session sau các luồng chính, hoặc login lại nếu vừa thu hồi access token đang
dùng trong Swagger; cập nhật bearer mới trước khi tiếp tục.

Test đổi mật khẩu sau khi hoàn thành các luồng còn cần candidate:

```json
{
  "currentPassword": "SwaggerDemo2026!",
  "newPassword": "SwaggerNew2026!"
}
```

Gửi tới `PUT /api/v1/me/password` → `200`, `message: Password changed; log in again`.
Bearer và refresh hiện có bị thu hồi; xóa bearer, login lại bằng mật khẩu mới.
`POST /api/v1/auth/logout` cần bearer, trả `200` và thu hồi tất cả session của tài khoản.
`DELETE /api/v1/me` đóng tài khoản; chỉ thử bằng tài khoản phụ sau khi hoàn thành luồng.

<a id="roles"></a>

## Chuẩn bị tài khoản admin và consultant

Không có mật khẩu admin mặc định. Đăng ký/xác minh một email khác, chẳng hạn
`admin.swagger@example.com`, rồi chạy bootstrap bằng tài khoản database local:

```powershell
Get-Content -Raw scripts/bootstrap-admin.sql |
    docker compose exec -T postgres psql `
        -U edunexus `
        -d edunexus `
        -v admin_email='admin.swagger@example.com'
```

Script chỉ nâng quyền tài khoản đã `ACTIVE`, đã xác minh email, khi chưa có admin hoạt động.
Nếu đã có admin, dùng tài khoản đó. Login email admin, Authorize bằng access token admin,
kiểm tra `GET /api/v1/me` có `role: ADMIN` và `GET /api/v1/admin/dashboard` trả `200`.

Gọi `POST /api/v1/admin/users` để tạo consultant:

```json
{
  "email": "consultant.swagger@example.com",
  "fullName": "Trần Hoàng Lan",
  "role": "CAREER_CONSULTANT",
  "temporaryPassword": "SwaggerDemo2026!"
}
```

Mong đợi `200`, tài khoản `ACTIVE`; lưu `id` làm `<CONSULTANT_ID>` để admin gán tư vấn.
Consultant login được bằng mật khẩu trên mà không cần OTP. Giữ ba bộ token riêng và đổi
`bearerAuth` theo vai trò trước từng luồng. Candidate gọi `/api/v1/admin/dashboard` phải `403`.

<a id="job-mau"></a>

## Tạo việc làm mẫu bằng tài khoản ADMIN

Đăng nhập ADMIN và đổi token trong **Authorize**. Thực hiện bước này trước luồng matching của ứng viên. Import thủ công lưu nội dung JSON vào database; URL bên dưới chỉ là địa chỉ minh họa, endpoint import không tải trang đó.

**POST `/api/v1/admin/jobs`**:

```json
{
  "sourceSite": "ITVIEC",
  "sourceUrl": "https://itviec.com/it-jobs/swagger-java-backend-demo",
  "jobTitle": "Junior Java Backend Developer - Swagger Demo",
  "companyName": "EduNexus Demo",
  "location": "Ho Chi Minh",
  "salaryText": "10 - 15 million VND",
  "roleCategory": "Software Engineer",
  "content": "Demo job for testing only. Build REST APIs with Java, Spring Boot and PostgreSQL. Use Git and understand SQL. No real recruitment is performed.",
  "skills": [
    {"skillId": "20000000-0000-0000-0000-000000000001", "name": "Java", "required": true},
    {"skillId": "20000000-0000-0000-0000-000000000002", "name": "Spring Boot", "required": true},
    {"skillId": "20000000-0000-0000-0000-000000000003", "name": "PostgreSQL", "required": true}
  ]
}
```

Kỳ vọng **200**, `status = ACTIVE`; lưu `id` thành `<JOB_ID>`. Nếu database đã chỉnh danh mục skills, dùng UUID từ `GET /api/v1/skills` thay các UUID seed. `GET /api/v1/jobs` và `GET /api/v1/jobs/{id}` phải tìm thấy việc làm này.

| Kiểm tra | Kỳ vọng |
| --- | --- |
| Import lại nguyên JSON khi job còn ACTIVE | 200; trả lại cùng `id`, không tạo job trùng |
| Đổi sourceUrl thành HTTP, localhost hoặc domain ngoài ITviec/TopCV | 400 |
| `sourceSite = TOPCV` nhưng URL thuộc ITviec | 400 |
| Nội dung sau bỏ HTML ít hơn 30 ký tự | 400 |
| CANDIDATE gọi `/api/v1/admin/jobs` | 403 |

Để kiểm tra trạng thái, gọi **PATCH `/api/v1/admin/jobs/<JOB_ID>/status`** với `{"status":"EXPIRED"}`: 200; job không còn trong danh sách ACTIVE. Import lại cùng nội dung lúc job inactive: **409**. Đổi về `{"status":"ACTIVE"}` để tiếp tục matching. Các trạng thái hợp lệ là `ACTIVE`, `EXPIRED`, `REMOVED`, `INVALID`.

<a id="career-cv-matching"></a>

## Candidate: hồ sơ nghề nghiệp, CV và tìm việc

Đăng nhập bằng tài khoản `CANDIDATE`. Các JSON dưới đây dùng tên trường đúng theo DTO hiện tại: hồ sơ/matching dùng camelCase; nội dung CV và assistant dùng snake_case. Thay các chuỗi `<..._ID>` bằng UUID lấy từ response, không gửi nguyên placeholder.

### Hồ sơ nghề nghiệp

1. Gọi `GET /api/v1/skills`, lấy `id` của kỹ năng Java làm `<SKILL_ID>`; danh sách có thể rỗng nếu chưa có dữ liệu.
2. Gọi `PUT /api/v1/me/career-profile`:

```json
{
  "currentJobTitle": "Sinh viên CNTT",
  "targetJobTitle": "Java Backend Developer",
  "careerGoal": "Phát triển API với Java và Spring Boot",
  "yearsExperience": 1.0,
  "expectedSalaryMin": 10000000,
  "expectedSalaryMax": 18000000,
  "salaryCurrency": "VND",
  "preferredLocation": "Ho Chi Minh",
  "workplacePreference": "HYBRID",
  "employmentType": "FULL_TIME",
  "profileSummary": "Có kinh nghiệm Java, SQL và REST API",
  "preferences": {},
  "skills": [
    {
      "skillId": "<SKILL_ID>",
      "proficiencyLevel": "INTERMEDIATE",
      "yearsExperience": 1.0
    }
  ]
}
```

Kỳ vọng `200`; gọi lại `GET /api/v1/me/career-profile`, đối chiếu `target_job_title`, khoảng lương và `skills`. Nếu chưa có kỹ năng, dùng `"skills": []` để tiếp tục. PUT cập nhật toàn bộ trường hồ sơ; gửi đủ các giá trị muốn giữ.

### Tạo CV thủ công và xác nhận

Gọi `GET /api/v1/resumes/templates` nếu cần tham khảo template; `template_id` trong request tạo CV là tùy chọn.

Gọi `POST /api/v1/resumes`:

```json
{
  "title": "CV Java Backend - Swagger",
  "language": "vi",
  "structured_data": {
    "full_name": "Nguyễn Minh Anh",
    "email": "candidate.swagger@example.com",
    "location": "Ho Chi Minh",
    "summary": "Sinh viên có kinh nghiệm Java, Spring Boot và PostgreSQL",
    "experience": [],
    "education": [
      {"school_name": "Truong Dai hoc Test", "degree": "Cu nhan", "major": "CNTT"}
    ],
    "projects": [
      {
        "project_name": "Career API",
        "role": "Backend Developer",
        "description": "Xay dung REST API va JWT authentication",
        "technologies": ["Java", "Spring Boot", "PostgreSQL"]
      }
    ],
    "certifications": [],
    "skills": [
      {"name": "Java", "proficiency_level": "INTERMEDIATE", "years_experience": 1},
      {"name": "SQL", "proficiency_level": "INTERMEDIATE", "years_experience": 1}
    ]
  }
}
```

Kỳ vọng `200`, `status="DRAFT"`; lưu `id` thành `<RESUME_ID>`. Các trường kỹ năng CV là `name`/`skill_id`/`years_experience`, khác DTO hồ sơ nghề nghiệp.

| Bước | API | Input | Kiểm tra |
| --- | --- | --- | --- |
| Xem nội dung | `GET /api/v1/resumes/{id}` | `<RESUME_ID>` | `200`; có `structured_data`; section top-level được lưu sau confirm |
| Xác nhận | `POST /api/v1/resumes/{id}/confirm` | `{"is_primary": true}` | `200`; `status="READY"`, `is_primary=true`, các section được lưu |
| Danh sách CV | `GET /api/v1/resumes` | Không body | `200`; có CV vừa tạo |
| Đặt CV chính | `POST /api/v1/resumes/{id}/primary` | Không body | `200`; CV chính thuộc tài khoản hiện tại |
| Phân tích | `POST /api/v1/resumes/{id}/analyses` | Không body | `200`; lưu `id` thành `<ANALYSIS_ID>` |
| Lịch sử phân tích | `GET /api/v1/resumes/{id}/analyses` | Không body | `200`; có `<ANALYSIS_ID>` |
| Chi tiết phân tích | `GET /api/v1/resumes/{id}/analyses/{analysisId}` | Hai ID ở trên | `200`; có `suggestions` |
| Tải nội dung | `GET /api/v1/resumes/{id}/download?text=true` | Không body | `200`; file TXT, `Content-Disposition: attachment` |

Khi AI tắt, phân tích CV vẫn chạy với `analysis_type="RULE_BASED"`, `model_name="rules-v1"`, `ats_score=null`. `overall_score` chỉ thể hiện độ đầy đủ của các mục CV; không dùng kết quả này làm điểm ATS theo vị trí.

Để kiểm tra chỉnh sửa, gọi `PATCH /api/v1/resumes/{id}` với `{"title":"CV Java Backend - Updated"}`. Kỳ vọng `200`, `status="REVIEW_REQUIRED"`; phải confirm lại trước matching/ATS/embedding. Nếu thay `structured_data`, gửi lại toàn bộ document muốn giữ vì nội dung này được thay thế.

### Upload và review CV

Gọi `POST /api/v1/resumes/upload`, chọn `multipart/form-data` trong Swagger: `file` là file PDF/DOCX/TXT hợp lệ, `title="CV upload Swagger"`, `language="vi"`. Không tự đặt Content-Type JSON cho request này.

Kỳ vọng `200`, lưu `id` thành `<UPLOADED_RESUME_ID>`, `source="UPLOAD"`, `status="REVIEW_REQUIRED"`; kiểm tra `raw_content` và `parse_results`. Khi AI tắt, hệ thống trích xuất văn bản nhưng không tự điền các mục bằng AI; review rồi PATCH/confirm document thủ công. PDF scan không có text cần OCR hoặc nhập thủ công.

Gọi `GET /api/v1/resumes/{id}/download` với ID CV upload để tải file gốc; thêm `text=true` để tải nội dung văn bản. Sau khi bổ sung dữ liệu, confirm và kiểm tra trạng thái `READY`.

### Job discovery, matching và feedback

1. Gọi `GET /api/v1/jobs?q=Java&location=&limit=20&offset=0`. Kỳ vọng `200`, response là mảng; lưu `id` một job ACTIVE thành `<JOB_ID>`. Không có job thì thực hiện bước admin import trước.
2. Gọi `GET /api/v1/jobs/{id}`; kiểm tra `job_title`, `company_name`, `source_url`, `skills`.
3. Gọi `POST /api/v1/matching` với CV đã `READY`:

```json
{
  "basis": "SELECTED_CV",
  "resumeIds": ["<RESUME_ID>"],
  "limit": 10
}
```

Kỳ vọng `200`; lưu response `id` thành `<MATCHING_RUN_ID>` và `results[0].id` thành `<MATCH_RESULT_ID>`. `results[*].market_job_id` là ID job, khác ID result; không dùng nhầm khi feedback.

Khi AI tắt, kiểm tra `provider="RULE_BASED"`, `model="skill-overlap-v1"`, `match_reason_json.method="SKILL_OVERLAP"`; `final_score` nằm trong `0..1`, `llm_rerank_score=null`. Job không có kỹ năng có thể có điểm 0.

| Bước | API / input | Kỳ vọng |
| --- | --- | --- |
| Theo hồ sơ | `POST /api/v1/matching`, `{"basis":"CAREER_PROFILE","limit":10}` | `200`; sử dụng hồ sơ nghề nghiệp |
| Tất cả CV | `POST /api/v1/matching`, `{"basis":"ALL_CVS","limit":10}` | `200`; sử dụng các CV READY, không dùng CV draft |
| Chi tiết run | `GET /api/v1/matching/{id}` | `200`; response `results` thuộc run |
| Lịch sử | `GET /api/v1/matching?limit=20` | `200`; chỉ run của người đăng nhập |
| Cache | Gửi lại body SELECTED_CV y hệt trong vòng một giờ | `200`, `cached=true`, cùng run `id` nếu dữ liệu/model/catalog không đổi |
| Feedback | `PUT /api/v1/matching/results/{id}/feedback`, `{"feedbackType":"LIKE","rating":5,"comment":"Phu hop Java"}` | `200`; đúng result và user |
| Truy cập ứng tuyển | `POST /api/v1/jobs/{id}/external-apply`, `{"resumeId":"<RESUME_ID>"}` | `200`; `event_id`, `target_url` |

`external-apply` trả URL và ghi nhận sự kiện, không tự gửi đơn/email hay trả HTTP redirect. Không cần mở trang tuyển dụng thật để kiểm tra API.

Sau khi đổi nội dung CV rồi confirm, hoặc admin thay catalog job, gửi lại matching. Kỳ vọng tạo run mới với `cached=false` khi đầu vào khác; cache không được tái dùng kết quả cũ. Lần đầu của một body có thể đã `cached=true` nếu trước đó đã chạy cùng dữ liệu.

<a id="guest-trial"></a>

## Guest trial

1. Chưa cần bearer, gọi `POST /api/v1/guest/sessions`, không có request body.
   Mong đợi `200`, response có `id`, `sessionKey`, `tokenLimit`, `tokenUsed`, `expiresAt`.
   Lưu `sessionKey` và Authorize `guestSession`. Session có hạn 24 giờ; mặc định 5.000 token.
2. Gọi `GET /api/v1/guest/sessions/me`: `200`, thấy `token_limit`, `token_used`, `expires_at`.
   Không kỳ vọng response trả lại khóa session dạng rõ.
3. Gọi `GET /api/v1/guest/demo-resumes`, lấy `id` của CV demo làm `<DEMO_RESUME_ID>`.
4. Gọi `GET /api/v1/guest/templates`, lấy `id` của template trial làm `<TRIAL_TEMPLATE_ID>`.
5. Gọi `POST /api/v1/guest/trials/recommendations`:

```json
{
  "demo_resume_id": "<DEMO_RESUME_ID>"
}
```

Mong đợi `200`, có `matching_run_id`, `mode: SKILL_OVERLAP`, `trial: true`, `results`.
Kết quả tối đa ba việc làm; mảng rỗng hợp lệ nếu chưa import job. Luồng này chạy khi AI tắt,
tốn 100 credit mỗi lần thành công. Gọi lại `sessions/me` để kiểm tra `token_used` tăng 100.

Gọi `POST /api/v1/guest/trials/build` với:

```json
{
  "template_id": "<TRIAL_TEMPLATE_ID>",
  "demo_resume_id": "<DEMO_RESUME_ID>"
}
```

Khi AI được cấu hình, mong đợi `200` với `trial`, `template_id`, `structured_data`,
`registration_prompt`. Khi `AI_ENABLED=false`, mong đợi `503` vì luồng cần AI thực.
Trial builder chỉ nhận CV demo và template trial của hệ thống; không nhận CV cá nhân để lưu.

Thử trợ lý guest: `POST /api/v1/guest/assistant/conversations` không có body, lưu `id`;
`GET /api/v1/guest/assistant/conversations/{id}` trả nội dung và `messages`.
Hai bước này trả `200` khi AI tắt. Để chat, gọi
`POST /api/v1/guest/assistant/conversations/{id}/messages`:

```json
{
  "message": "Tôi muốn bắt đầu học Java backend, nên chuẩn bị gì?",
  "retrieve": false
}
```

Gửi tin nhắn cần AI, nên mặc định mong đợi `503`; khi đã cấu hình AI, response trả một
message với `id`, `sender_type: ASSISTANT`, `message_text` và `sources`. Gọi lại
`GET /api/v1/guest/assistant/conversations/{id}` để kiểm tra lịch sử user/assistant.
Đăng ký tài khoản là luồng riêng; hiện tại không tự chuyển CV trial/lịch sử guest thành
dữ liệu candidate.

<a id="tu-van"></a>

## Tư vấn viên và hội thoại tư vấn

Chuẩn bị tài khoản `CAREER_CONSULTANT` đang ACTIVE theo bước tạo tài khoản quản trị, lưu `<CONSULTANT_ID>` và token của tư vấn viên. Chuyển token CANDIDATE để tạo hội thoại.

**POST `/api/v1/consultations`**:

```json
{
  "subject": "Tư vấn vị trí Java Backend",
  "requestType": "CAREER_GUIDANCE",
  "description": "Tôi muốn được góp ý CV và các kỹ năng cần học cho vị trí Java Backend."
}
```

Kỳ vọng **200**, `status = OPEN`; lưu `id` thành `<THREAD_ID>`. `requestType` là chuỗi mô tả loại yêu cầu, tối đa 50 ký tự.

Đổi token ADMIN rồi gọi **PUT `/api/v1/consultations/<THREAD_ID>/consultant`**:

```json
{"consultantId": "<CONSULTANT_ID>"}
```

Thay placeholder bằng UUID thực. Kỳ vọng **200**, `consultant_user_id` bằng UUID đã chọn. Tư vấn viên phải có role `CAREER_CONSULTANT` và trạng thái ACTIVE; UUID không đáp ứng điều kiện này trả **404**. CANDIDATE gọi thao tác phân công trả **403**.

### Xem các kết quả phân tích đã lưu

Đổi token sang consultant vừa được phân công và mở **GET
`/api/v1/consultations/<THREAD_ID>/candidate-analyses`**. Lần đầu để trống các bộ lọc tùy
chọn, dùng `limit = 20`, `offset = 0`; endpoint không có request body.

Kỳ vọng **200**, `candidate_id = <CANDIDATE_ID>`, `limit = 20`, `offset = 0`, cùng các khóa:

```json
{
  "candidate_id": "<CANDIDATE_ID>",
  "resume_id": null,
  "job_id": null,
  "limit": 20,
  "offset": 0,
  "resume_analyses": [],
  "ats_analyses": [],
  "matching_results": [],
  "salary_estimations": [],
  "assessments": [],
  "gap_analyses": [],
  "roadmaps": []
}
```

JSON trên minh họa hình dạng response khi chưa có phân tích; trên dữ liệu đã chạy các luồng
CV/matching/AI, mảng tương ứng phải chứa bản ghi đã lưu của ứng viên này. Endpoint vẫn đọc
được khi AI tắt, không gọi model, không sinh báo cáo mới hoặc trừ quota AI.

| Nhóm | Kiểm tra dữ liệu khi có kết quả đã lưu |
| --- | --- |
| `resume_analyses` | Báo cáo chất lượng CV, có `suggestions` |
| `ats_analyses` | Báo cáo ATS của CV/job tương ứng |
| `matching_results` | Kết quả matching liên quan của đúng ứng viên |
| `salary_estimations` | Khoảng lương và metadata kết quả ước lượng đã lưu |
| `assessments` | Chỉ assessment completed với metadata/điểm; không có questions/answers |
| `gap_analyses` | Phân tích khoảng cách kỹ năng, có `skills` |
| `roadmaps` | Chỉ roadmap ready/completed; có nodes, `prerequisite_ids`, resources |

Thử thêm `resumeId = <RESUME_ID>`, `jobId = <JOB_ID>`, `limit = 1`, `offset = 0`, rồi tăng
offset lên 1. Các nhóm được phân trang **độc lập**: mỗi mảng có tối đa limit bản ghi, không
phải toàn response chỉ có limit bản ghi. CV dùng để lọc phải READY và thuộc ứng viên trong
hội thoại. `resumeId` chỉ lọc nhóm gắn với CV, **không lọc salary_estimations/assessments**;
`jobId` chỉ lọc nhóm gắn với job, **không lọc resume_analyses**. Nhóm chưa có dữ liệu trả `[]`.

| Ca đọc/phân quyền | Kỳ vọng |
| --- | --- |
| Consultant đang được phân công đọc endpoint | 200; đúng candidate_id và các nhóm dữ liệu của ứng viên |
| Candidate chủ hội thoại hoặc ADMIN đọc endpoint | 200 |
| Bỏ bearer hoặc dùng token hết hạn | 401 |
| Thread ID, resumeId hoặc jobId không đúng định dạng UUID | 400 |
| `limit = 0`/`101` hoặc `offset = -1` | 400 |
| UUID hội thoại không tồn tại | 404 |
| Candidate B hoặc consultant chưa được phân công đọc hội thoại của A | 404 |
| resumeId thuộc Candidate B, không tồn tại, DRAFT/REVIEW_REQUIRED hoặc ARCHIVED | 404 |
| Tài khoản ứng viên của hội thoại đã soft-delete (`status = DELETED`), ADMIN/consultant đọc phân tích | 404; không trả các báo cáo của tài khoản đã xóa |
| Không có phân tích cho các nhóm đang đọc | 200; mảng tương ứng rỗng |

Ca phân công lại cần hội thoại **OPEN**, nên thực hiện trước bước close trong bảng tiếp
theo. Admin tạo một consultant ACTIVE thứ hai rồi PUT
`/api/v1/consultations/<THREAD_ID>/consultant` với UUID người thứ hai. Dùng lại token
consultant cũ đọc `/candidate-analyses`: **404**; consultant mới đọc: **200**. Có thể gán
lại consultant ban đầu để tiếp tục luồng chat bằng token đã chuẩn bị trước đó.

Ca tài khoản đã xóa chỉ thử bằng **ứng viên test riêng** sau khi hoàn thành các ca khác:
tạo/phân công hội thoại cho người đó, xác nhận consultant đọc phân tích được `200`, rồi
admin soft-delete tài khoản qua `DELETE /api/v1/admin/users/{id}`. Consultant được phân
công hoặc ADMIN gọi lại `/candidate-analyses`: **404**. Không xóa Candidate A đang dùng
cho các luồng tiếp theo để thực hiện ca này.

Đối chiếu bản ghi bằng các ID đã lưu từ luồng Candidate; không giả định cả bảy mảng đều
có dữ liệu khi chưa chạy các chức năng tạo phân tích. Những ca trên vẫn là **NOT_RUN** cho
đến khi Execute thủ công và ghi HTTP/body thực tế vào bảng kết quả.

| Token | Thao tác | Kỳ vọng |
| --- | --- | --- |
| CANDIDATE chủ hội thoại | GET `/api/v1/consultations` | 200; có hội thoại vừa tạo |
| Tư vấn viên được phân công | GET `/api/v1/consultations/<THREAD_ID>/candidate-profile?resumeId=<RESUME_ID>` | 200; profile và CV READY của đúng ứng viên |
| CANDIDATE chủ hội thoại | POST `/api/v1/consultations/<THREAD_ID>/messages`, body `{"content":"Anh/chị góp ý giúp CV của tôi."}` | 200; lưu `id` thành `<MESSAGE_ID>` |
| Tư vấn viên được phân công | POST cùng endpoint, body `{"content":"Bạn nên bổ sung kết quả dự án và kinh nghiệm Spring Boot."}` | 200; `sender_user_id` là tư vấn viên |
| Thành viên hội thoại | GET `/api/v1/consultations/<THREAD_ID>/messages?limit=50&offset=0` | 200; thấy tin nhắn của cả hai bên |
| Thành viên hội thoại | PATCH `/api/v1/consultations/<THREAD_ID>/messages/<MESSAGE_ID>/read` | 200; `read = true` |
| CANDIDATE khác/tư vấn viên chưa được phân công | GET hội thoại hoặc POST tin nhắn | 404; không lộ hội thoại người khác |
| Thành viên hội thoại | POST `/api/v1/consultations/<THREAD_ID>/close` | 200; `status = CLOSED` |
| Thành viên hội thoại | POST tin nhắn sau khi đóng | 409 |

`GET /api/v1/consultations/<THREAD_ID>/events` là SSE, giữ kết nối để nhận event `connected` rồi `message`. Swagger có thể giữ trạng thái loading đối với stream; có thể kiểm tra trong terminal bằng `curl.exe -N -H "Authorization: Bearer <TOKEN>" "http://localhost:8080/api/v1/consultations/<THREAD_ID>/events"` và gửi tin nhắn từ tab Swagger khác. Đóng hội thoại hoặc phân công lại sẽ ngắt stream hiện tại. Chỉ kiểm tra stream trước bước đóng hội thoại.

<a id="thanh-toan"></a>

## Gói dịch vụ, đơn thanh toán và hóa đơn

Gọi public **GET `/api/v1/subscriptions/tiers`** để chọn tier đang hoạt động có `price_monthly > 0`. BASIC seed là `10000000-0000-0000-0000-000000000002`, giá 99000 VND/tháng; dùng dữ liệu GET thực tế nếu admin đã chỉnh giá.

Với token CANDIDATE, gọi **POST `/api/v1/payments/orders`**:

```json
{
  "tierId": "10000000-0000-0000-0000-000000000002",
  "durationMonths": 1
}
```

Kỳ vọng **200**, `status = PENDING`. Lưu `id` thành `<ORDER_ID>`, đồng thời lưu nguyên `provider_order_id`, `amount`, `currency` từ response. Tạo đơn **chưa kích hoạt gói**, chưa tạo hóa đơn PAID. Backend tự tính số tiền từ giá tier và số tháng.

| Thao tác | Kỳ vọng |
| --- | --- |
| GET `/api/v1/payments/orders` và `/api/v1/payments/orders/<ORDER_ID>` | 200; thấy đơn của mình |
| POST order với tier FREE | 400; FREE không cần thanh toán |
| `durationMonths = 0` hoặc `13` | 400 |
| CANDIDATE khác đọc đơn/hóa đơn của người tạo | 404 |
| GET `/api/v1/subscriptions/me` trước webhook | 200; quyền gói hiện tại chưa đổi vì tạo đơn |

### Webhook giả lập trong môi trường local

Backend hiện dùng hợp đồng `MANUAL_GATEWAY`; chưa có adapter checkout ngân hàng/thẻ. Phần này chỉ mô phỏng xác nhận của provider trên database test, không thực hiện chuyển tiền. Secret dùng để ký phải trùng `PAYMENT_WEBHOOK_SECRET` mà backend đã đọc lúc khởi động, tối thiểu **32 byte UTF-8**. Nếu secret chưa cấu hình/không đủ dài, webhook trả **503**.

Trong PowerShell, sửa các giá trị theo response của đơn vừa tạo. Secret bên dưới chỉ là ví dụ local; cấu hình backend với cùng secret rồi khởi động lại trước khi thử.

```powershell
$testWebhookSecret = 'local-swagger-payment-secret-32-bytes-minimum'
$testOrderId = '<ORDER_ID>'
$testProviderOrderId = '<PROVIDER_ORDER_ID>'
$testAmount = 99000
$testCurrency = 'VND'
$testEventId = 'swagger-' + [guid]::NewGuid().ToString()

$testRawBody = ([ordered]@{
    orderId = $testOrderId
    providerOrderId = $testProviderOrderId
    eventId = $testEventId
    status = 'COMPLETED'
    amount = $testAmount
    currency = $testCurrency
} | ConvertTo-Json -Compress)

$testTimestamp = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds().ToString()
$testHmac = New-Object System.Security.Cryptography.HMACSHA256
$testHmac.Key = [Text.Encoding]::UTF8.GetBytes($testWebhookSecret)
$testPayload = [Text.Encoding]::UTF8.GetBytes($testTimestamp + '.' + $testRawBody)
$testSignature = -join ($testHmac.ComputeHash($testPayload) | ForEach-Object { $_.ToString('x2') })
$testHmac.Dispose()

$testTimestamp
$testSignature
$testRawBody
```

Mở **POST `/api/v1/payments/webhook`** trong Swagger:

1. `X-Payment-Timestamp`: giá trị `$testTimestamp`, Unix timestamp **theo giây**.
2. `X-Payment-Signature`: giá trị `$testSignature`, 64 ký tự hex.
3. Request body: dán **nguyên văn** `$testRawBody` được in ra, không thêm dấu nháy bao toàn bộ JSON, không pretty-print hoặc đổi khoảng trắng sau khi ký.
4. Execute trong vòng 300 giây từ lúc tạo timestamp. Khi hết thời gian, tạo lại timestamp và chữ ký.

Webhook không cần bearer token. Kỳ vọng **200**, `accepted = true`, `duplicate = false`. Sau đó dùng lại token ứng viên để kiểm tra:

- GET `/api/v1/payments/orders/<ORDER_ID>`: `status = COMPLETED`, có `subscription_id`.
- GET `/api/v1/subscriptions/me` và `/api/v1/subscriptions/history`: gói mới ACTIVE, có ngày hết hạn.
- GET `/api/v1/invoices`: có đúng một hóa đơn của đơn, `status = PAID`, số tiền/tiền tệ bằng đơn; lưu `<INVOICE_ID>` rồi GET `/api/v1/invoices/<INVOICE_ID>`.
- GET `/api/v1/notifications`: có thông báo `PAYMENT_SUCCESS`.
- Gửi lại cùng event với chữ ký/timestamp còn hiệu lực: **200**, `duplicate = true`; không tạo thêm gói, hóa đơn hay thông báo.

| Ca lỗi webhook | Kỳ vọng |
| --- | --- |
| Thiếu một trong hai header bắt buộc | 400 |
| Timestamp hết hạn hoặc chữ ký sai/đổi body sau khi ký | 401; đơn và gói không đổi |
| Body có chữ ký đúng nhưng JSON không hợp lệ | 400 |
| Số tiền/tiền tệ/providerOrderId khác đơn, đã ký lại đúng body | 400 |
| `status = SUCCESS` thay vì `COMPLETED` | 400 |
| Đơn đã COMPLETED nhận event mới với `status = FAILED` | 409 |
| Cùng eventId dùng cho một đơn khác, các trường của đơn đó đúng | 409 |

Muốn kiểm tra thất bại, tạo **đơn mới** rồi ký body có `status = FAILED` hoặc `CANCELLED`. Webhook được chấp nhận 200 nhưng đơn mang trạng thái tương ứng, không tạo subscription ACTIVE/hóa đơn PAID. Không gửi trạng thái khác để cố sửa một đơn đã kết thúc.

Sau khi kiểm tra hóa đơn, có thể gọi **POST `/api/v1/subscriptions/<SUBSCRIPTION_ID>/cancel`**: 200; đọc lại history/me để kiểm tra đã hủy và quyền lợi hiện hành. Thực hiện bước này cuối luồng billing.

### Báo cáo lỗi thanh toán

Token ứng viên, **POST `/api/v1/payments/orders/<ORDER_ID>/reports`**:

```json
{"subject":"Kiểm tra đơn thanh toán demo","description":"Đây là báo cáo lỗi giả lập cho bài kiểm thử Swagger."}
```

Kỳ vọng 200, `status = OPEN`; lưu `<PAYMENT_REPORT_ID>`. GET `/api/v1/payments/reports` thấy báo cáo của mình. Đổi token ADMIN: GET `/api/v1/admin/payment-reports`, sau đó **POST `/api/v1/admin/payment-reports/<PAYMENT_REPORT_ID>/resolve`** với `{"resolution":"Đã đối chiếu đơn demo và xác nhận kết quả kiểm thử."}`. Kỳ vọng 200, báo cáo RESOLVED. Giải quyết báo cáo không tự chuyển trạng thái đơn thành COMPLETED; chỉ webhook đã xác minh thực hiện kích hoạt gói.

<a id="thong-bao"></a>

## Thông báo quản trị và hộp thông báo

Token ADMIN, **POST `/api/v1/admin/announcements`**:

```json
{
  "title": "Thông báo thử nghiệm Swagger",
  "content": "Ứng viên có thể cập nhật hồ sơ và xem việc làm demo.",
  "targets": [{"type":"ROLE","role":"CANDIDATE"}]
}
```

Kỳ vọng 200, `status = DRAFT`; lưu `<ANNOUNCEMENT_ID>`. `expiresAt` có thể bỏ qua; nếu gửi thì dùng ISO 8601 có timezone và thời điểm tương lai. Audience còn hỗ trợ `{"type":"ALL"}` hoặc `{"type":"USER","userId":"<USER_ID>"}`.

| Token/thao tác | Kỳ vọng |
| --- | --- |
| ADMIN: GET `/api/v1/admin/announcements` | 200; có bản DRAFT |
| ADMIN: PUT `/api/v1/admin/announcements/<ANNOUNCEMENT_ID>` bằng đầy đủ body mới | 200; chỉ sửa được khi DRAFT |
| ADMIN: POST `/api/v1/admin/announcements/<ANNOUNCEMENT_ID>/publish` | 200; status PUBLISHED, phát thông báo đến audience |
| CANDIDATE: GET `/api/v1/announcements` | 200; thấy thông báo PUBLISHED dành cho CANDIDATE |
| CANDIDATE: GET `/api/v1/notifications?unreadOnly=true` | 200; lưu `id` thông báo thành `<NOTIFICATION_ID>` |
| CANDIDATE: PATCH `/api/v1/notifications/<NOTIFICATION_ID>/read` | 200; thông báo biến mất khỏi bộ lọc unreadOnly |
| CANDIDATE: POST `/api/v1/notifications/read-all` | 200 |
| ADMIN: publish lần hai hoặc PUT bản đã PUBLISHED | 409 |
| ADMIN: POST `/api/v1/admin/announcements/<ANNOUNCEMENT_ID>/archive` | 200; bản bị ẩn khỏi danh sách hiển thị |
| Người dùng khác đọc notification không thuộc mình | 404 |
| Target ROLE thiếu role hoặc USER thiếu userId | 400 |

<a id="ho-tro"></a>

## Yêu cầu hỗ trợ

Token CANDIDATE, **POST `/api/v1/support/tickets`**:

```json
{
  "subject": "Kiểm tra hỗ trợ CV",
  "category": "RESUME",
  "description": "Tôi đang kiểm thử thao tác tải và sửa CV trên Swagger."
}
```

Kỳ vọng 200, `status = OPEN`, `priority = NORMAL`; lưu `<TICKET_ID>`. GET `/api/v1/support/tickets` thấy ticket của mình; GET `/api/v1/support/tickets/<TICKET_ID>` trả ticket kèm `messages`.

Người tạo và ADMIN có thể **POST `/api/v1/support/tickets/<TICKET_ID>/messages`** với `{"content":"Nội dung trao đổi kiểm thử."}`: 200. ADMIN trả lời ticket của ứng viên phải tạo notification `SUPPORT_REPLY` cho ứng viên.

ADMIN gọi **PATCH `/api/v1/support/tickets/<TICKET_ID>`**:

```json
{"status":"IN_PROGRESS","priority":"HIGH","assignedAdminId":"<ADMIN_ID>"}
```

Kỳ vọng 200; `assignedAdminId` dùng UUID admin đang thử, có thể gửi `null` nếu chưa phân công. Trạng thái hợp lệ: `OPEN`, `IN_PROGRESS`, `RESOLVED`, `CLOSED`; priority: `LOW`, `NORMAL`, `HIGH`, `URGENT`. Đổi status thành CLOSED rồi gửi tin nhắn: **409**. Người dùng khác đọc ticket: **404**; CANDIDATE PATCH ticket: **403**; enum sai: **400**.

<a id="quan-tri"></a>

## Quản lý gói và theo dõi hệ thống

Toàn bộ endpoint dưới đây dùng token ADMIN; CANDIDATE gọi trả 403.

| Endpoint | Cách kiểm tra/kết quả |
| --- | --- |
| GET `/api/v1/admin/dashboard` | 200; thống kê database hiện tại |
| GET `/api/v1/admin/users?search=&page=0&size=20` | 200; dữ liệu tài khoản không chứa password hash |
| GET `/api/v1/admin/subscription-tiers` | 200; tier kèm quotas |
| GET `/api/v1/admin/ai/configurations`, `/api/v1/admin/ai/prompts` | 200; có thể là mảng rỗng khi chưa thiết lập |
| GET `/api/v1/admin/monitoring/{category}` | Dùng `ai-usage`, `vector-stores`, `indexing-jobs`, `logs`, `audit`, `billing`, `crawl-runs`; 200, có thể rỗng |
| GET monitoring với category không hỗ trợ | 400 |

Thử tạo tier riêng bằng **POST `/api/v1/admin/subscription-tiers`**:

```json
{"tierCode":"SWAGGER_TEST","displayName":"Swagger Test","priceMonthly":1000,"currency":"VND","active":true}
```

Kỳ vọng 200; lưu `<TEST_TIER_ID>`. Dùng **PUT `/api/v1/admin/subscription-tiers/<TEST_TIER_ID>`** với toàn bộ body để chỉnh. **PUT `/api/v1/admin/subscription-tiers/<TEST_TIER_ID>/quotas`** nhận mảng JSON như dưới; dùng tier riêng để tránh ảnh hưởng quotas FREE/BASIC/PRO:

```json
[
  {"featureCode":"AI_TOTAL_TOKENS","quotaValue":10000,"quotaPeriod":"MONTHLY"},
  {"featureCode":"CAREER_CHAT","quotaValue":10000,"quotaPeriod":"MONTHLY"}
]
```

Kỳ vọng 200; quotaPeriod hỗ trợ `DAILY`, `MONTHLY`, `YEARLY`, `LIFETIME`; quotaValue không âm. POST lại tierCode đã tồn tại trả 409. Sau các thao tác quản trị, kiểm tra monitoring `audit` để xác nhận có bản ghi tương ứng.

### Chỉnh sửa và khóa tài khoản test

Dùng Candidate B sau khi hoàn thành các ca ownership. ADMIN gọi
`GET /api/v1/admin/users/{id}`, lấy đúng ID và role hiện tại, sau đó
`PUT /api/v1/admin/users/{id}`:

```json
{
  "fullName": "Candidate B Swagger",
  "role": "CANDIDATE",
  "status": "SUSPENDED"
}
```

Kỳ vọng `200`, trạng thái SUSPENDED; access/refresh token của B bị thu hồi.
Đổi `status` về ACTIVE với cùng body để mở lại tài khoản đã xác minh; B cần login lại.
Cuối lượt test có thể `DELETE /api/v1/admin/users/{id}` với ID tài khoản phụ: `200`,
`message: Account deleted`; không thể kích hoạt lại tài khoản đã DELETED.
Chỉ sửa tên và giữ ACTIVE/role hiện tại không thu hồi token; đổi role sẽ thu hồi token.

<a id="luu-va-khoi-phuc-ai"></a>

### Lưu và khôi phục phiên bản cấu hình AI và prompt

Để kiểm tra API lưu cấu hình mà không tác động cấu hình chạy các ca AI, dùng mã riêng
`SWAGGER_TEST` cho hai ví dụ sau. Đổi lại mã feature thực tế theo thiết kế khi cấu hình
tính năng đang sử dụng; không gửi API key trong JSON cấu hình.

ADMIN gọi `POST /api/v1/admin/ai/configurations`:

```json
{
  "configType": "SWAGGER_TEST",
  "provider": "LOCAL_TEST",
  "modelName": "swagger-demo-model",
  "configuration": {
    "temperature": 0.2
  }
}
```

ADMIN gọi `POST /api/v1/admin/ai/prompts`:

```json
{
  "featureCode": "SWAGGER_TEST",
  "name": "Swagger test prompt",
  "systemPrompt": "Bạn hỗ trợ đọc dữ liệu kiểm thử nghề nghiệp.",
  "userPromptTemplate": "Đọc nội dung mẫu và phản hồi bằng tiếng Việt."
}
```

Kỳ vọng `200`, `version` tăng theo từng configType/featureCode, phiên bản mới `is_active=true`.
JSON configuration chứa `api_key`, `apikey`, `secret` hoặc `password` trả `400`.

#### Kiểm tra v1 → v2 → khôi phục nội dung v1

Thực hiện riêng cho cấu hình và prompt. Để kỳ vọng chính xác v1/v2/v3, dùng mã test chưa
tồn tại: nếu `SWAGGER_TEST` đã có lịch sử, đổi mã trong các body sang một mã mới, chẳng hạn
`SWAGGER_RESTORE_TEST`. Nếu dùng lại nhóm có lịch sử, kiểm tra phiên bản tăng từ max hiện tại
thay vì mặc định bắt đầu ở 1. Trong suốt một lượt, giữ nguyên `configType`/`featureCode`.

1. POST body cấu hình ở trên: `200`, `version = 1`, `is_active = true`; lưu **UUID `id`**
   thành `<CONFIG_V1_ID>`. POST body prompt ở trên và lưu `<PROMPT_V1_ID>` tương tự.
2. POST lại body cấu hình cùng `configType`, đổi `configuration.temperature` từ `0.2` thành
   `0.7`; POST lại prompt cùng `featureCode`, đổi `userPromptTemplate` thành
   `"Phản hồi nội dung mẫu bằng tiếng Anh."`. Mỗi POST trả `200`, `version = 2`, UUID mới,
   `is_active = true`. GET danh sách để kiểm tra v1 inactive và v2 active.
3. Với token ADMIN, gọi **POST `/api/v1/admin/ai/configurations/<CONFIG_V1_ID>/restore`**,
   **không gửi request body**. Kỳ vọng `200`, UUID mới khác v1/v2, `version = 3`,
   `is_active = true`, `config_json.temperature = 0.2`; provider/model bằng bản v1.
4. Gọi **POST `/api/v1/admin/ai/prompts/<PROMPT_V1_ID>/restore`**, không body. Kỳ vọng `200`,
   UUID mới, `version = 3`, `is_active = true`; `name`, `system_prompt`,
   `user_prompt_template` bằng bản v1, không còn nội dung tiếng Anh của v2.
5. GET lại `/api/v1/admin/ai/configurations` và `/api/v1/admin/ai/prompts`, lọc đúng nhóm
   test: v1/v2 vẫn tồn tại với nội dung và số phiên bản cũ, cả hai inactive; **chỉ v3 active**.
   GET `/api/v1/admin/monitoring/audit` phải có action `RESTORE_AI_CONFIGURATION` và
   `RESTORE_AI_PROMPT`, tương ứng với thao tác vừa chạy.

`oldVersionId` trên path là UUID của bản ghi cần sao chép; không nhập số `1` hoặc `2`.
Restore không bật lại bản cũ mà tạo bản mới bằng `max(version) + 1`. Nếu đã có v3, restore
v1 tiếp sẽ tạo v4; nội dung v1/v2/v3 được giữ và chỉ bản mới nhất active.

| Ca lỗi restore | Kỳ vọng |
| --- | --- |
| Path dùng `not-a-uuid` | 400 |
| Path dùng UUID hợp lệ nhưng không tồn tại | 404 |
| CANDIDATE hoặc CAREER_CONSULTANT gọi restore | 403 |
| Không có bearer token hợp lệ | 401 |

Các thao tác lưu/restore không gọi model và không xác nhận provider đã hoạt động. Mã test
riêng ở trên không được dùng bởi các feature đang chạy. Với cấu hình thực tế `CHAT` có
provider phù hợp, model name active áp dụng cho lần gọi chat tiếp theo; prompt active của
feature thực tế áp dụng cho lần sinh nội dung tiếp theo. Restore không tự bật `AI_ENABLED`,
thay API key, provider đã khởi tạo, cấu hình embedding hoặc các biến môi trường của backend.

<a id="cau-hinh-ai"></a>

## Bật AI cho các ca kiểm thử bổ sung

Sau khi hoàn thành các luồng cơ bản, thiết lập provider trong `.env` theo mục
[Bật AI và các dịch vụ bên ngoài](../README.md#bật-ai-và-các-dịch-vụ-bên-ngoài), rồi khởi động
lại backend bằng script ở đầu tài liệu. Cấu hình chat và embedding độc lập; bật chat chưa
đủ để test index vector/RAG. API quản trị cấu hình model/prompt không tự thay thế API key
và các biến môi trường của tiến trình.

| Nhóm kiểm thử | Cấu hình cần có |
| --- | --- |
| AI CV builder, ATS theo job, draft, salary, assessment, roadmap, assistant không retrieve | AI bật và chat provider hoạt động |
| CV/job/document embedding | AI bật và embedding provider hoạt động; pgvector đúng schema |
| Semantic matching, assistant `retrieve=true` | Chat + embedding, dữ liệu đã index đúng provider/model |
| Google login | `GOOGLE_CLIENT_ID` và ID token thật |
| OTP qua email | SMTP `MAIL_*` hoạt động; tắt development OTP |

Dùng dữ liệu test ít và kiểm tra quota/tính năng của gói trước mỗi luồng. Các thao tác AI
gọi provider thực; HTTP `200` cần kèm nội dung, ID, trạng thái và usage đúng để ghi PASS.
Nếu chưa có provider, ghi rõ phần chưa kiểm tra trong bảng kết quả cuối tài liệu.

<a id="redis-ats"></a>

## Cấu hình Redis và kiểm tra cache ATS

Redis là cache tùy chọn cho **ATS CV–job**. Kết nối được khai báo trong
[`spring.data.redis`](../src/main/resources/application.yaml), lấy các biến `REDIS_*`
trong [`.env.example`](../.env.example). Backend và Swagger vẫn chạy khi Redis không kết
nối được vì ATS còn tìm cache trong PostgreSQL. Tạo một phân tích ATS mới vẫn cần chat AI.

### Khởi động Redis local

Thêm vào `.env` nếu chưa có các dòng sau, phù hợp với backend chạy trực tiếp trên Windows:

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

Từ thư mục dự án, khởi động dịch vụ Redis rồi kiểm tra:

```powershell
docker compose up -d --wait redis
docker compose exec redis redis-cli PING
```

Kỳ vọng `PONG`. Redis nằm trong profile `optional`, nhưng lệnh chỉ định trực tiếp service
`redis` khởi động được dịch vụ này. `scripts/run-local.ps1 -WithInfrastructure` chỉ khởi
động PostgreSQL; không tự khởi động Redis. Sau khi sửa `.env`, khởi động lại backend bằng
script để nạp các biến môi trường mới.

Redis Compose local không bật AUTH/TLS, nên để `REDIS_USERNAME`/`REDIS_PASSWORD` trống,
`REDIS_SSL_ENABLED=false`. Nếu port trên máy bị chiếm, đổi `REDIS_PORT`, ví dụ `6380`;
Compose ánh xạ port trên máy vào port Redis 6379 trong container. Với Redis bên ngoài,
điền host, port, database, tài khoản/mật khẩu và SSL theo cấu hình của server đó. Khi backend
chạy trong container, `REDIS_HOST` phải truy cập được từ container; có thể dùng `redis`
và port 6379 nếu cùng mạng Compose, không dùng localhost để trỏ sang container khác.

### Xác nhận Redis đã lưu analysis

1. Chuẩn bị token CANDIDATE, CV READY và job ACTIVE; bật chat AI và có quota. Chọn cặp
   CV/job chưa có analysis cùng đầu vào/model trong database, chẳng hạn tạo và confirm
   một CV test mới. Bản ghi đã có DB cache không được tự ghi lại sang Redis khi đọc cache.
2. Gọi `POST /api/v1/jobs/<JOB_ID>/ats` với `{"resumeId":"<RESUME_ID>"}`. Kỳ vọng `200`,
   `cached=false`, có `ats_score`; lưu UUID analysis `id` thành `<ATS_ANALYSIS_ID>`.
3. Tìm key trong Redis bằng terminal; các lệnh dưới dùng database 0 đúng với cấu hình mẫu:

```powershell
docker compose exec redis redis-cli -n 0 --scan --pattern 'edunexus:ats:*'
```

Key có dạng `edunexus:ats:<userId>:<resumeId>:<jobId>:<inputHash>`. Chọn key chứa đúng
UUID người dùng/CV/job đang thử, copy nguyên key để thay `<CACHE_KEY>` trong hai lệnh:

```powershell
docker compose exec redis redis-cli -n 0 GET '<CACHE_KEY>'
docker compose exec redis redis-cli -n 0 TTL '<CACHE_KEY>'
```

Kỳ vọng GET trả đúng `<ATS_ANALYSIS_ID>`; TTL là số giây dương không quá `86400` (24 giờ).
`TTL = -2` nghĩa là không tìm thấy key hoặc key đã hết hạn; `-1` là key không có thời hạn,
khác với cách ghi cache ATS hiện tại. Nếu đổi `REDIS_DATABASE`, dùng cùng số database
trong các lệnh `-n`. Những lệnh này kiểm tra Redis Compose local; với Redis ngoài, kết nối
CLI đến đúng server và dùng cơ chế AUTH/TLS của server đó.

4. Gửi lại ATS với cùng token, CV, job và model: `200`, `cached=true`, cùng analysis `id`.
   Redis chỉ giữ UUID analysis/run; backend vẫn đối chiếu bản ghi PostgreSQL theo owner,
   CV, job và input hash trước khi tái sử dụng, không trả toàn bộ báo cáo chỉ từ Redis.
5. Ghi ID và key/TTL vào bảng kết quả. **`cached=true` một mình không chứng minh Redis hit**
   vì database fallback cũng trả cùng cờ. `/actuator/health` UP cũng không chứng minh
   Redis sẵn sàng: `management.health.redis.enabled=false` trong cấu hình mặc định.

Cache ATS Redis TTL 24 giờ độc lập với **cache matching Redis/database một giờ** của
`POST /api/v1/matching`. Thay nội dung CV/job hoặc provider/model làm thay input hash ATS;
key cũ không được dùng cho dữ liệu mới. TTL chỉ áp dụng cho key Redis; analysis đã lưu trong
database vẫn có thể được tái sử dụng khi hash khớp. Không cần xóa cache toàn bộ để thử một
đầu vào mới.

<a id="ai-tools"></a>

## Candidate: các chức năng cần AI được cấu hình

Thực hiện khi chat/embedding provider đã hoạt động và tài khoản có quyền sử dụng tính năng, còn quota. Không cần bật AI để hoàn thành luồng manual ở trên. Khi AI tắt, các thao tác AI dưới đây trả `503` nếu các bước kiểm tra tài nguyên/quyền trước đó đã hợp lệ; CV chưa confirm vẫn có thể trả `409` trước. ATS có thể trả cache `200` khi AI tắt; dùng CV/job chưa có cache để test `503`.

### ATS theo job, CV theo vị trí và application draft

| API | Body | Kỳ vọng khi AI hoạt động |
| --- | --- | --- |
| `POST /api/v1/resumes/build` | Body tạo CV ở trên, có thông tin thực tế | `200`; CV mới `source="AI_BUILDER"`, `status="REVIEW_REQUIRED"`; review rồi confirm |
| `POST /api/v1/resumes/{id}/embedding` | Không body; CV READY | `200`; embedding của đúng CV/provider/model; gọi lại không sinh bản trùng đầu vào |
| `POST /api/v1/jobs/{jobId}/ats` | `{"resumeId":"<RESUME_ID>"}` | `200`; `ats_score` 0..100, strengths/missing skills/suggestions; lưu analysis `id` |
| ATS cache | Gửi lại ATS cùng CV/job/model | `200`, `cached=true`, cùng analysis `id`; Redis hỏng vẫn có DB cache |
| `POST /api/v1/jobs/{jobId}/resume-draft` | Body tạo CV ở trên | `200`; CV mới theo job, cần review/confirm; dữ liệu cung cấp phải là thông tin thật |
| `POST /api/v1/jobs/{jobId}/application-drafts` | `{"resumeId":"<RESUME_ID>","language":"vi"}` | `200`; mảng hai draft `APPLICATION_EMAIL` và `COVER_LETTER`; lưu từng `id` |
| `GET /api/v1/application-drafts?jobId={jobId}` | Không body | `200`; đúng draft của tài khoản |
| `PATCH /api/v1/application-drafts/{id}` | `{"content":"Noi dung ung tuyen da review"}` | `200`; nội dung draft cập nhật |
| `POST /api/v1/jobs/{jobId}/salary-estimation` | Không body | `200`; `estimated_min <= estimated_max`, `currency`, `reasoning` |

Draft email/thư ứng tuyển chỉ được tạo và sửa nội dung; backend hiện tại không có endpoint gửi email ứng tuyển. ATS theo job khác với `/resumes/{id}/analyses`.

Để kiểm tra semantic matching, admin phải index các job ACTIVE bằng đúng embedding provider/model đang dùng, rồi gửi matching. Kỳ vọng `match_reason_json.method="SEMANTIC_LLM"`, điểm vector/LLM phù hợp response. Chưa có job được index đúng model có thể trả `409`.

### Assessment và roadmap

Gọi `POST /api/v1/assessments`:

```json
{
  "targetJobTitle": "Java Backend Developer",
  "jobId": "<JOB_ID>"
}
```

Kỳ vọng `200`; lưu `id` thành `<ASSESSMENT_ID>`. Kiểm tra `status="in_progress"`, `questions` có phần nền tảng và chuyên môn; trước submit không lộ `correct_option`/`explanation`.

Với **từng** `questions[*].id`, gọi `PUT /api/v1/assessments/{id}/answers`:

```json
{
  "questionId": "<QUESTION_ID>",
  "selectedOption": "A"
}
```

Kỳ vọng `200`, `question_id`/`selected_option`; chọn A/B/C/D dựa trên nội dung, không dựa đáp án bị ẩn. Gọi lại cùng câu hỏi để kiểm tra thay lựa chọn. Khi trả lời đủ, gọi `POST /api/v1/assessments/{id}/submit` không body: `200`, `status="completed"`, `skill_scores`; đáp án/explanation được cung cấp sau hoàn thành. Submit lại trả cùng kết quả, không chấm thành lượt mới.

`GET /api/v1/assessments` và `GET /api/v1/assessments/{id}` kiểm tra lịch sử/chi tiết đúng tài khoản. Assessment mới cùng mục tiêu thay phiên đánh giá hiện hành.

Gọi `POST /api/v1/roadmaps`:

```json
{
  "targetJobTitle": "Java Backend Developer",
  "jobId": "<JOB_ID>",
  "resumeId": "<RESUME_ID>",
  "assessmentId": "<ASSESSMENT_ID>"
}
```

Kỳ vọng `200`; lưu `id` thành `<ROADMAP_ID>`, `nodes[*].id` thành ID node. Kiểm tra `target_job_title`, `estimated_total_hours`, `skill_gaps`, `nodes`, `prerequisites`, `resources`. Resources có thể rỗng nếu chưa có catalog phù hợp; không coi đây là lỗi.

Gọi `GET /api/v1/roadmaps`, `GET /api/v1/roadmaps/{id}`. Với node không có prerequisite chưa hoàn thành, gọi `PATCH /api/v1/roadmaps/{id}/nodes/{nodeId}` bằng `{"status":"in_progress"}`, sau đó `{"status":"completed"}`; kỳ vọng `200`, node đổi trạng thái và roadmap cập nhật `progress_percent`. Hoàn thành prerequisite trước khi bắt đầu node phụ thuộc.

### AI Career Assistant

Tạo thread bằng `POST /api/v1/assistant/conversations` (thao tác này vẫn hoạt động khi AI tắt):

```json
{
  "title": "Tu van Java Backend",
  "context_type": "career",
  "related_job_id": "<JOB_ID>",
  "related_resume_id": "<RESUME_ID>"
}
```

Kỳ vọng `200`, lưu `id` thành `<ASSISTANT_CONVERSATION_ID>`. Có thể thêm `related_roadmap_id`/`related_gap_analysis_id` từ roadmap, hoặc bỏ context IDs để thử thread tổng quát.

Gửi `POST /api/v1/assistant/conversations/{id}/messages` với `{"message":"Toi nen hoc gi de ung tuyen vi tri nay?","retrieve":false}`. Kỳ vọng `200`; response chính là message ASSISTANT, lưu `id` thành `<ASSISTANT_MESSAGE_ID>`, kiểm tra `message_text`, token usage và `sources`. `retrieve=true` cần embedding/RAG; nguồn có thể rỗng khi không tìm được dữ liệu phù hợp.

Gọi `GET /api/v1/assistant/conversations/{id}` để xem `messages`/`sources`; `GET /api/v1/assistant/conversations` để xem lịch sử. Gọi `POST /api/v1/assistant/messages/{id}/feedback` với `{"rating":5,"is_helpful":true,"feedback_text":"Huong dan ro rang"}`; kỳ vọng `200`. Dùng ID message ASSISTANT, không dùng ID conversation.

Cuối luồng, `DELETE /api/v1/assistant/conversations/{id}` trả `204`; gửi tiếp tin nhắn vào thread đã archive phải trả `409`.

<a id="rag-crawl"></a>

## RAG, embedding và crawl: kiểm tra tùy chọn

Phần RAG import không cần bật AI. Token ADMIN, **POST `/api/v1/admin/rag/documents`**:

```json
{
  "title": "Hướng dẫn Java Backend demo",
  "content": "Một ứng viên Java Backend cần nền tảng Java, Spring Boot, REST API, SQL và Git. Nội dung này dùng để kiểm thử truy xuất tài liệu tư vấn.",
  "sourceType": "ADMIN",
  "metadata": {"topic":"java-backend","test":true}
}
```

Kỳ vọng 200, `embedding_status = PENDING`, có `chunks`; lưu `<DOCUMENT_ID>`. GET danh sách và GET `/api/v1/admin/rag/documents/<DOCUMENT_ID>` phải tìm thấy tài liệu. Khi embedding chưa bật, **POST `/api/v1/admin/rag/documents/<DOCUMENT_ID>/index`** trả **503**, không báo index thành công. Khi có provider/pgvector cấu hình đúng, thao tác index gọi dịch vụ embedding thực; đối chiếu chunks, indexing-jobs và vector-stores. Xem cấu hình AI trong README trước khi chạy trường hợp này.

**POST `/api/v1/admin/rag/documents/<DOCUMENT_ID>/archive`**: 200, `metadata.disabled = true`; index tài liệu đã archive trả **409**. **POST `/api/v1/admin/jobs/<JOB_ID>/index`** cũng cần embedding provider đang bật; trạng thái cấu hình tắt trả **503**.

Crawl gọi trang ngoài thật, nên chỉ thử khi có mạng và URL tuyển dụng hiện còn hợp lệ. Dùng **POST `/api/v1/admin/jobs/crawl`** với cấu trúc:

```json
{
  "sourceSite":"ITVIEC",
  "keyword":"Java",
  "roleCategory":"Software Engineer",
  "location":"Ho Chi Minh",
  "urls":["https://itviec.com/it-jobs/<DUONG_DAN_TIN_THAT>"]
}
```

Thay cả URL mẫu bằng một tin thật; không Execute với placeholder. Backend nhận **1–20 URL được cung cấp**, kiểm tra domain theo nguồn và đọc từng trang, không tự tìm URL từ keyword. HTTP 200 chỉ xác nhận run đã xử lý; đọc `status = COMPLETED/PARTIAL/FAILED`, counters và `items` để biết tin nào được import hoặc bị từ chối. Không coi run FAILED trả 200 là crawl thành công. Lưu `<CRAWL_RUN_ID>`; GET `/api/v1/admin/jobs/crawl?limit=20&offset=0` và GET `/api/v1/admin/jobs/crawl/<CRAWL_RUN_ID>` để kiểm tra lịch sử. URL HTTP, domain ngoài danh sách hoặc `sourceSite` sai trả **400** trước khi mở run.

<a id="loi-auth"></a>

## Các ca lỗi nền tảng cần ghi nhận

| Ca thử | HTTP mong đợi |
| --- | --- |
| Register email không hợp lệ hoặc password dưới 10 ký tự | `400` |
| Register email đã tồn tại | `409` |
| Login đúng password khi email chưa xác minh | `403` |
| Login sai password | `401` |
| OTP đúng định dạng nhưng sai/hết hạn/đã dùng | `400` |
| Cấp lại OTP trong vòng 60 giây | `429` |
| OTP sai năm lần; thử thêm lần nữa | `429` |
| `/api/v1/me` không có bearer hoặc bearer hết hạn | `401` |
| Candidate gọi API admin | `403` |
| Career profile có salary min lớn hơn max hoặc skill ID trùng | `400` |
| Đổi mật khẩu với current password sai | `401` |
| Guest API thiếu header `X-Guest-Session` | `400` |
| Guest API có sessionKey sai/hết hạn | `401` |
| Tạo guest session vượt ba lần/IP/ngày | `429` |
| Guest trial vượt quota còn lại | `429` |
| Gửi assistant message rỗng hoặc `retrieve: null` | `400` |
| Guest builder/assistant gửi tin khi AI tắt | `503` |

Không cần tạo hàng loạt request để thử quota: với database test riêng, có thể khởi động
`GUEST_TOKEN_LIMIT=100`, chạy recommendation một lần rồi thử lần thứ hai → `429`.
Google login chỉ test khi đã có `GOOGLE_CLIENT_ID` và ID token thật từ frontend;
`POST /api/v1/auth/google` nhận `{"idToken":"<GOOGLE_ID_TOKEN>"}`. Chưa cấu hình trả `503`.
Lỗi nghiệp vụ thường trả Problem Detail với `status`, `detail`; lỗi security trả `status`,
`message`, nên đọc cả hai kiểu response khi ghi kết quả.

<a id="loi-candidate"></a>

## Kiểm tra lỗi và giới hạn cho luồng Candidate

Chạy trên dữ liệu test; dùng tài khoản Candidate B để kiểm tra quyền sở hữu tài nguyên của Candidate A. Với request cần AI, các kiểm tra lỗi đầu vào dưới đây thực hiện trước gọi provider nếu đúng thứ tự điều kiện.

| Tình huống | Cách thử | Kỳ vọng |
| --- | --- | --- |
| Lương không hợp lệ | Hồ sơ: `expectedSalaryMin > expectedSalaryMax` | `400` |
| Kỹ năng lặp | Hồ sơ: hai entry cùng `skillId` | `400` |
| UUID sai cú pháp | Path/body UUID thành `abc` | `400` |
| CV rỗng | Tạo CV `structured_data={}`, raw rỗng; confirm | `400` |
| Ngày CV ngược | Experience `end_date` trước `start_date` | `400` |
| CV chưa READY | DRAFT/REVIEW_REQUIRED: primary, analyze, embedding, SELECTED_CV matching, ATS | `409` |
| Upload sai | File rỗng, `.exe`, MIME/signature không khớp | `400` |
| Upload quá lớn | File vượt 10 MB | `413` |
| OCR chưa bật | Upload PNG/JPEG hợp lệ | `503` |
| Matching sai basis | `basis="UNKNOWN"` | `400` |
| SELECTED_CV sai số lượng | `resumeIds=[]` hoặc hai ID | `400` |
| Matching sai limit | `limit=0` hoặc `101` | `400` |
| Catalog rỗng | Không có job ACTIVE, gửi matching hợp lệ | `409` |
| Feedback sai | `feedbackType="OTHER"` hoặc `rating=6` | `400` |
| Quyền sở hữu | B GET/PATCH CV, GET matching/assessment/roadmap/assistant hoặc feedback result của A | `404` |
| Tài nguyên không tồn tại | Dùng UUID đúng cú pháp nhưng không có bản ghi | `404` |
| Câu hỏi không thuộc session | Gửi `questionId` của session khác | `404` |
| Submit thiếu câu trả lời | Chưa trả lời hết câu hỏi | `400` |
| Answer sai lựa chọn | `selectedOption="E"` hoặc rỗng | `400` |
| Sửa answer sau submit | PUT answers vào session completed | `409` |
| Assessment chưa completed | Dùng ID in_progress trong POST roadmap | `404` |
| Assessment lệch mục tiêu | Completed nhưng target khác target roadmap | `400` |
| Node bị chặn | Đổi node phụ thuộc sang in_progress/completed khi prerequisite chưa completed | `409` |
| Reset prerequisite đang dùng | Node phụ thuộc đang active/completed, reset node tiên quyết | `409` |
| Node sai status | `status="done"` | `400` |
| Assistant body sai | `message=""`, `retrieve=null`/chuỗi | `400` |
| AI chưa cấu hình | ATS/build/drafts/salary/assessment/roadmap/message/embedding, input hợp lệ, ATS chưa có cache | `503` |

Sau khi hoàn tất, chỉ archive CV thử nghiệm bằng `DELETE /api/v1/resumes/{id}`: `204`. PATCH hoặc confirm CV đã archive phải `409`. Dùng ID riêng cho từng test và ghi status thực tế vào bảng kết quả chung; tài liệu này mô tả kỳ vọng, không xác nhận đã chạy các ca Swagger.

<a id="ket-qua"></a>

## Checklist và mẫu ghi kết quả

Đánh dấu sau khi đã Execute và kiểm tra response thực tế:

- [ ] Backend khởi động; health UP; Swagger và OpenAPI JSON mở được.
- [ ] Đăng ký, OTP và login Candidate A/B; lấy đúng `accessToken`.
- [ ] Bootstrap admin; tạo và login consultant; Candidate gọi admin nhận 403.
- [ ] Import job ACTIVE; đọc được job bằng API công khai.
- [ ] Career profile lưu đúng skill; CV confirm READY; phân tích RULE_BASED khi AI tắt.
- [ ] Matching trả job và điểm hợp lệ; cache và feedback đúng run/result.
- [ ] Guest trial ghi usage; hạn chế session/quota trả đúng mã lỗi.
- [ ] Tư vấn phân công đúng; hai bên thấy tin nhắn; người ngoài nhận 404.
- [ ] Consultant đọc được phân tích đã lưu của đúng ứng viên; bộ lọc/phân trang đúng và mất quyền sau phân công lại.
- [ ] Webhook local có chữ ký hợp lệ kích hoạt gói và tạo invoice đúng một lần.
- [ ] Announcement đến đúng audience; notification đánh dấu đọc; support trao đổi được.
- [ ] Khôi phục cấu hình/prompt cũ tạo phiên bản mới, giữ lịch sử và chỉ một bản active mỗi nhóm.
- [ ] Ca ownership, validation, trạng thái tài nguyên và token hết hạn đúng kỳ vọng.
- [ ] Các ca AI đã chạy hoặc được ghi BLOCKED_CONFIG với cấu hình còn thiếu.
- [ ] Logout/đổi mật khẩu thu hồi session; dữ liệu test được đóng/archive khi cần.

Copy bảng dưới cho mỗi lượt kiểm thử. Ghi build/commit đang chạy, ngày kiểm tra, cấu hình
AI và role; tránh dùng kết quả của bản code khác để xác nhận bản hiện tại.

| Test ID | Role | Endpoint / thao tác | HTTP và dữ liệu kỳ vọng | Thực tế | Kết quả | Ghi chú |
| --- | --- | --- | --- | --- | --- | --- |
| AUTH-01 | Candidate A | Register → verify-email → login | 200; ACTIVE; có accessToken | Chưa chạy | NOT_RUN | |
| ROLE-01 | Candidate A | GET admin/dashboard | 403 | Chưa chạy | NOT_RUN | |
| CV-01 | Candidate A | Create → confirm CV | 200; DRAFT → READY | Chưa chạy | NOT_RUN | |
| MATCH-01 | Candidate A | POST matching | 200; results; score 0..1 | Chưa chạy | NOT_RUN | |
| GUEST-01 | Guest | Recommendation | 200; trial; usage tăng | Chưa chạy | NOT_RUN | |
| CHAT-01 | Candidate/Consultant | Gửi và đọc tin nhắn | 200; đúng sender/thread | Chưa chạy | NOT_RUN | |
| CONSULT-ANALYSES-01 | Consultant | GET consultations/{id}/candidate-analyses | 200; đúng candidate_id; bảy nhóm phân tích đã lưu | Chưa chạy | NOT_RUN | |
| CONSULT-ANALYSES-02 | Consultant cũ/Candidate B | Đọc phân tích sau phân công lại hoặc không có quyền | 404 | Chưa chạy | NOT_RUN | |
| PAY-01 | Candidate A | Order → signed webhook → invoice | 200; COMPLETED; PAID | Chưa chạy | NOT_RUN | |
| AI-RESTORE-01 | Admin | Tạo config/prompt v1 → v2 → restore v1 | 200; v3 chứa nội dung v1; chỉ v3 active | Chưa chạy | NOT_RUN | |
| OWN-01 | Candidate B | GET CV của A | 404 | Chưa chạy | NOT_RUN | |
| AI-01 | Candidate A | ATS theo job | 200 khi đủ cấu hình; có ats_score | Chưa chạy | NOT_RUN | |

Quy ước: **PASS** khi HTTP và dữ liệu đều đúng; **FAIL** khi khác kỳ vọng;
**BLOCKED_CONFIG** khi thiếu dịch vụ/cấu hình bắt buộc; **NOT_RUN** khi chưa Execute.
Một ca chủ động test AI tắt nhận `503` có thể PASS; ca cần kết quả AI thành công vẫn là
BLOCKED_CONFIG. Ghi response lỗi, Request URL, request body và ID dữ liệu để tái hiện lỗi.

<a id="go-loi"></a>

## Xử lý lỗi thường gặp khi dùng Swagger

| Triệu chứng | Cách kiểm tra |
| --- | --- |
| Không mở được Swagger | Xem terminal backend, port server, health và lỗi Flyway/database |
| Register trả 503 | Bật development OTP local hoặc cấu hình SMTP; restart backend sau khi sửa `.env` |
| API tài khoản trả 401 | Dùng accessToken mới; nhập token nguyên dạng trong bearerAuth, không thêm `Bearer ` |
| Guest trả 400/401 | Điền `X-Guest-Session` nếu ô header bắt buộc; dùng sessionKey còn hạn |
| API admin trả 403 | Login admin và đổi token; đối chiếu role ở `/me` |
| API trả 400 | Kiểm tra tên trường, enum, UUID, JSON và các lỗi validation trong response |
| API tài nguyên trả 404 | Dùng ID đúng loại; kiểm tra owner và tài khoản đang Authorize |
| Matching/ATS trả 409 | Confirm CV READY; kiểm tra job ACTIVE, catalog/index và trạng thái tài nguyên |
| AI trả 503 | Kiểm tra AI bật, provider tương ứng, API key và cấu hình có được nạp vào tiến trình |
| Webhook trả 401 | Ký đúng raw body, đúng secret, timestamp theo giây còn hiệu lực; không sửa body sau ký |
| Quota trả 429 | Xem usage và gói hiện tại; không coi giới hạn đã hết là lỗi của Swagger |
| Upload trả 413 | File vượt giới hạn 10 MB; dùng file nhỏ hơn |
| SSE cứ loading | Stream giữ kết nối là hành vi dự kiến; kiểm tra event hoặc API lịch sử messages |

Xem thêm [hợp đồng API](api.md), [identity/billing](identity-billing.md),
[CV và AI](resume-ai.md), [crawl](crawling.md) và [phạm vi kiểm chứng hiện có](verification.md).
