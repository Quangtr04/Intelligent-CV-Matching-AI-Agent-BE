# Hướng dẫn kiểm thử API EduNexus bằng Postman

Đã bổ sung đối chiếu CV–hồ sơ nghề nghiệp và đánh giá lại khi hồ sơ thay đổi ngày **24/10/2026**. Tài liệu tra cứu các API
theo đường dẫn chuẩn `/api/v1`, gồm xác thực, CV, matching, tư vấn, billing, khóa học, RAG và quản trị.
Ba route crawl settings còn có alias `/api/admin`.
Health, Swagger và OpenAPI được ghi riêng ở cuối tài liệu.

**Bắt đầu tại [Dữ liệu mẫu và thứ tự test Postman](postman-test-order.md).** Tài liệu đó hướng dẫn từng
request đăng ký → đăng nhập → tạo CV bằng Ollama → review → confirm → embedding → phân tích, kèm JSON,
script lưu token/ID và các nhánh test mở rộng. Dùng tài liệu này để tra body và quyền của các API còn lại.

Muốn đánh giá CV có thể hiện đúng mục tiêu trong hồ sơ cá nhân, dùng
[dữ liệu mẫu và thứ tự đối chiếu CV–hồ sơ](cv-profile-alignment.md): lưu hồ sơ → tạo/review CV → confirm READY
→ chủ động tạo báo cáo lần đầu → sửa hồ sơ → theo dõi đánh giá lại trong nền. Độc lập với ATS;
điểm lương cần dữ liệu mức trả của job, không được suy ra từ CV.

Mỗi ví dụ dùng dữ liệu thử. Thay các biến bằng giá trị thực từ response; phần hướng dẫn không tự tạo tài khoản,
gọi AI, crawl website hay thực hiện thanh toán. Đây là hướng dẫn gửi request, không phải báo cáo rằng mọi tích hợp
bên ngoài đã được kiểm thử thành công.

## Mục lục

| Mục | Nội dung |
| --- | --- |
| [1](#section-1) | Chuẩn bị backend và Postman |
| [2](#section-2) | Environment, quyền truy cập và script lưu response |
| [3](#section-3) | Xác thực và tài khoản |
| [4](#section-4) | Guest và dùng thử |
| [5](#section-5) | Gói cước và thanh toán |
| [6](#section-6) | CV và phân tích CV |
| [7](#section-7) | Việc làm, matching và hỗ trợ ứng tuyển |
| [8](#section-8) | Assessment, lộ trình và nguồn học |
| [9](#section-9) | Trợ lý AI cho Candidate |
| [10](#section-10) | Tư vấn Candidate–Consultant |
| [11](#section-11) | Thông báo và hỗ trợ |
| [12](#section-12) | Admin: tài khoản, gói và cấu hình AI |
| [13](#section-13) | Admin: thu thập và index việc làm |
| [14](#section-14) | Admin: RAG và khóa học đã duyệt |
| [15](#section-15) | Thứ tự chạy thử, SSE và ký webhook |
| [16](#section-16) | API kỹ thuật, lỗi thường gặp và giới hạn hiện tại |

<a id="section-1"></a>

## 1. Chuẩn bị backend và Postman

### 1.1. Chạy backend

Chạy tại thư mục chứa `pom.xml`. Nếu backend đang chạy, kiểm tra `GET http://localhost:8080/actuator/health`
trước; không khởi động thêm process cùng port. Khi cài mới, chuẩn bị `.env` từ `.env.example`, điền database
và JWT; giữ nguyên file `.env` đã có. AI hiện dùng **Ollama native**, chat `qwen2.5:7b`, embedding
`qwen3-embedding:0.6b` **1024 chiều**, không cần API key thương mại.

Với backend chạy trên Windows/IDE, khởi động model trước, rồi chạy backend:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/start-ollama.ps1 -ExposeToHost -Gpu -SmokeTest
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/run-local.ps1 -RuntimeProfile dev
```

`-Gpu` chỉ dùng khi Docker có NVIDIA runtime; bỏ switch trên máy CPU. `run-local.ps1 -WithInfrastructure`
chỉ thêm PostgreSQL Compose, không khởi động Ollama/Redis. Không dùng switch đó khi database host đang chiếm
cùng port. Backend host dùng `OLLAMA_BASE_URL=http://localhost:11434`; app trong Compose dùng
`http://ollama:11434`. Cách chạy cả backend trong Docker và điều kiện GPU xem [Ollama](ollama.md).

Backend cần Java 25, Maven và PostgreSQL có pgvector. Flyway áp dụng **V1–V19**; V10 giữ vector 1536 cũ
riêng và thêm cột 1024. Database có embedding cũ phải index lại bằng model mới trước khi test semantic
matching/RAG. Hướng dẫn môi trường: [README](../README.md) và [cài đặt dự án](project-guide.md).

| Nhóm cần thử | Điều kiện |
| --- | --- |
| API dữ liệu và quản trị | Backend/database hoạt động; tài khoản có vai trò phù hợp. |
| Đăng ký/OTP cục bộ | Profile `dev` hoặc `docker`, `AUTH_EXPOSE_DEV_OTP=true` để nhận `developmentCode`; không gửi mail. Profile `prod` luôn tắt việc lộ mã. |
| OTP qua email | `AUTH_EXPOSE_DEV_OTP=false`, `MAIL_ENABLED=true` và SMTP được cấu hình. |
| Sinh CV, assessment, roadmap, chat AI | `AI_ENABLED=true`, provider `ollama`, model chat đã tải trong Docker, quota đủ. |
| Index CV/job/RAG, semantic matching | Model `qwen3-embedding:0.6b` đã tải, vector 1024 chiều và schema V10/pgvector. |
| Gợi ý link học tập | Không cần gọi AI; khóa đã được Admin duyệt mới tham gia gợi ý. |
| Khóa học xuất hiện trong chat RAG | Khóa đã duyệt, đã index và chat Candidate gửi `retrieve: true`. |
| Webhook thanh toán thử | Secret test khớp server, ít nhất 32 byte UTF-8; xem [script ký](#webhook-thanh-toan). |

AI tắt vẫn thử được các thao tác CRUD, chat tư vấn người thật, nguồn học trực tiếp và các nhánh rule-based
được mô tả trong từng mục. Không coi phản hồi rule-based là một lần gọi model thành công.

### 1.2. Thiết lập Postman

1. Tạo Environment **EduNexus Local** và nhập các biến ở mục 2.
2. Tạo Collection, chia folder theo các mục 3–14.
3. Có thể đặt Bearer `{{activeToken}}` ở cấp Collection; request công khai và Guest phải chọn **No Auth**.
4. Request JSON chọn **Body → raw → JSON**. Request không có body chọn **none**.
5. Nhập query ở **Params**; bỏ hẳn tham số tùy chọn chưa dùng thay vì gửi chuỗi rỗng cho UUID.
6. Thêm script lưu token/ID trong **Scripts → Post-response** của request tương ứng.

Tạo assessment qua `/assessments/generations` dùng request ngắn và đọc trạng thái; không giữ POST mở để đợi AI.
Với các API inference đồng bộ hoặc endpoint assessment cũ, client/proxy cần timeout phù hợp ngân sách model
đã cấu hình (`AI_REQUEST_TIMEOUT` mặc định 15 phút), có thêm thời gian cho xử lý ứng dụng; lần load đầu có thể lâu.
Gửi tuần tự từng request AI, không chạy load test trong lượt kiểm tra chức năng này. Test JSON/assertion
được viết trong [Post-response](https://learning.postman.com/docs/tests-and-scripts/write-scripts/test-scripts/).
Đổi `.env` chỉ có hiệu lực sau khi process/container backend nạp lại cấu hình; đổi biến Postman không đổi model server.

Postman cung cấp `pm.environment` để đọc/lưu biến Environment; `pm.variables` dùng được cho biến tạm
của lần gửi request. Tham khảo [biến trong Postman scripts](https://learning.postman.com/docs/tests-and-scripts/write-scripts/postman-sandbox-reference/pm-variables/).

Nếu nhập Collection từ OpenAPI, dùng `GET http://localhost:8080/v3/api-docs` của backend đang chạy.
Sau khi nhập, kiểm tra lại Authorization, ví dụ body và các biến ID theo tài liệu này.

<a id="section-2"></a>

## 2. Environment, quyền truy cập và script lưu response

### 2.1. Biến kết nối và tài khoản

| Biến | Giá trị/cách lấy |
| --- | --- |
| `baseUrl` | `http://localhost:8080/api/v1`; đổi port theo `SERVER_PORT`. |
| `candidateEmail` | `candidate.20261022.01@example.com`; đổi email khi tạo lượt test mới; chỉ dùng địa chỉ này với OTP dev. |
| `candidatePassword` | Mật khẩu tài khoản test, ví dụ `PostmanDemo2026!`. |
| `candidateNewPassword` | Mật khẩu mới để thử đổi mật khẩu; cập nhật biến cũ sau khi đổi thành công. |
| `adminEmail`, `adminPassword` | Tài khoản Admin thực sự đã được tạo; không có mật khẩu Admin mặc định. |
| `consultantEmail`, `consultantPassword` | Tài khoản `CAREER_CONSULTANT` do Admin tạo. |
| `temporaryPassword` | Mật khẩu khi Admin tạo tài khoản; tối thiểu 10 ký tự, phù hợp giới hạn UTF-8. |
| `candidateAccessToken`, `candidateRefreshToken` | Từ login/refresh Candidate sau khi xác minh email. |
| `adminAccessToken`, `adminRefreshToken` | Từ login/refresh Admin. |
| `consultantAccessToken`, `consultantRefreshToken` | Từ login/refresh Consultant. |
| `activeToken` | Access token của người đang thử API dùng quyền `USER`/`THREAD`. |
| `otpCode` | Mã 6 chữ số từ `developmentCode` hoặc email; giữ dạng chuỗi để không mất số 0 đầu. |
| `googleIdToken` | Google ID token thật, phù hợp `GOOGLE_CLIENT_ID` của backend. |
| `guestSession` | Giá trị **`sessionKey`** từ `POST /guest/sessions`, dùng trong `X-Guest-Session`. |
| `guestSessionId` | UUID `id` của Guest; không dùng thay cho `sessionKey`. |
| `aiProvider` | `ollama`. Đây là biến đối chiếu kết quả, không thay provider backend. |
| `aiChatModel`, `aiEmbeddingModel` | Mặc định `qwen2.5:7b`, `qwen3-embedding:0.6b`; khớp runtime thực tế. |
| `sourceJobUrl` | URL HTTPS thật của trang chi tiết việc làm ITviec/TopCV khi thử crawl/import. |
| `courseUrl` | Link YouTube/Coursera thực tế đã được Admin kiểm tra. |
| `monitoringCategory` | Ví dụ `audit`; danh sách hợp lệ ở mục 12. |

Với SMTP thật, dùng email bạn nhận thư được. Không có tài khoản Admin bootstrap qua HTTP:
đăng ký và xác thực tài khoản trước, rồi dùng [bootstrap-admin.sql](../scripts/bootstrap-admin.sql)
theo hướng dẫn của script nếu chưa có Admin hoạt động. Đăng nhập lại sau khi được cấp vai trò.

### 2.2. Biến ID và quan hệ giữa request

Để trống ban đầu; chỉ lưu khi request trước thành công. Tên biến chỉ là chỗ giữ dữ liệu, không cấp quyền truy cập.

| Biến | Lấy từ đâu |
| --- | --- |
| `candidateId`, `adminId` | `id` của người dùng tương ứng từ `/me` hoặc tài khoản quản trị đã tạo. |
| `adminUserId`, `consultantId` | `id` từ `POST /admin/users`; dùng `consultantId` khi tạo đúng role Consultant. |
| `sessionId` | Chọn phiên cần thu hồi từ `GET /me/sessions`. |
| `skillId` | `id` của skill phù hợp từ `GET /skills`. |
| `resumeTemplateId` | Chọn từ `GET /resumes/templates`. |
| `resumeId` | `id` từ tạo/upload/build CV hoặc danh sách CV thuộc tài khoản. |
| `analysisId` | `id` từ `/resumes/{id}/analyses`; không phải ID phân tích ATS theo job. |
| `jobId`, `indexingJobId` | ID job từ import/danh sách; ID indexing job lấy riêng từ response index/trạng thái. |
| `matchingRunId`, `matchResultId` | `id` của lần matching và `id` từng phần tử `results`; không thay bằng job ID. |
| `applicationDraftId` | `id` của đúng draft loại email/cover letter được trả về. |
| `assessmentId`, `questionId` | `id` phiên assessment và `id` câu hỏi trong phiên đó. |
| `gapAnalysisId` | `gap_analysis_id` của roadmap đã tạo. |
| `roadmapId`, `roadmapNodeId` | `id` roadmap và `id` node bên trong; chọn node đã đủ prerequisite để cập nhật. |
| `assistantConversationId`, `assistantMessageId` | `id` hội thoại và `id` câu trả lời AI; feedback dùng message ID. |
| `threadId`, `consultationMessageId` | `id` thread tư vấn và `id` tin nhắn trong thread. |
| `demoResumeId`, `trialTemplateId` | Chọn phần tử thực trong `/guest/demo-resumes` và `/guest/templates`. |
| `guestConversationId` | `id` hội thoại Guest, phải sử dụng cùng `guestSession` đã tạo hội thoại. |
| `guestMatchingRunId`, `guestAssistantMessageId` | ID phản hồi từ matching/chat thử; không dùng ở API sở hữu của Candidate. |
| `paidTierId`, `subscriptionId` | Gói active giá > 0 từ `/subscriptions/tiers`; subscription của mình từ `/subscriptions/history`. |
| `managedTierId` | `id` gói test do Admin tạo; dùng khi sửa gói/quota, không chọn gói Free. |
| `paymentOrderId`, `providerOrderId` | `id`, `provider_order_id` từ tạo đơn thanh toán. |
| `paymentReportId`, `invoiceId` | ID phản ánh thanh toán và hóa đơn thực thuộc người dùng. |
| `aiConfigurationId`, `aiPromptId` | UUID bản ghi cấu hình/prompt dùng để restore; không phải số version. |
| `ragDocumentId` | `id` tài liệu nhập vào `/admin/rag/documents`. |
| `courseId`, `courseRagDocumentId`, `courseResourceId` | `id` khóa; sau approve lấy `rag_document_id`, `learning_resource_id`. |
| `crawlRunId` | `id` lần crawl từ response hoặc lịch sử crawl. |
| `announcementId`, `notificationId` | ID thông cáo và thông báo; đây là hai loại bản ghi khác nhau. |
| `ticketId` | `id` từ tạo hoặc danh sách ticket hỗ trợ. |

Các biến riêng cho webhook:

| Biến | Giá trị/cách lấy |
| --- | --- |
| `paymentWebhookSecret` | Secret môi trường test khớp `PAYMENT_WEBHOOK_SECRET` server. |
| `paymentAmount`, `paymentCurrency` | Giá trị `amount`, `currency` chính xác của order. |
| `paymentEventId` | ID riêng cho mỗi sự kiện, ví dụ `postman-20261022-001`. Giữ nguyên khi thử lặp event. |
| `paymentStatus` | `COMPLETED`, `FAILED` hoặc `CANCELLED`. |
| `paymentTimestamp`, `paymentSignature`, `paymentWebhookBody` | Script mục 15 tạo; không nhập chữ ký giả. |

### 2.3. Quy ước quyền, body và response

| Ký hiệu | Cấu hình Postman |
| --- | --- |
| `PUBLIC` | **No Auth**; bỏ Bearer thừa. |
| `GUEST` | **No Auth** + header `X-Guest-Session: {{guestSession}}`. |
| `USER` | Bearer `{{activeToken}}`; các thao tác dữ liệu cá nhân có kiểm tra chủ sở hữu. |
| `CANDIDATE` | Bearer `{{candidateAccessToken}}`; service kiểm tra role Candidate. |
| `ADMIN` | Bearer `{{adminAccessToken}}`. |
| `THREAD` | Bearer `{{activeToken}}` của Candidate chủ thread, Consultant được phân công hoặc Admin theo từng API. |

Trong ô **Bearer Token** chỉ điền biến token, không thêm tiền tố `Bearer` lần nữa.
Các nhóm CV/assistant thường thử bằng Candidate, nhưng nhãn `USER` thể hiện quyền thực tế từ source.
Những API phân công tư vấn và cập nhật ticket yêu cầu Admin dù URL không bắt đầu bằng `/admin`.
Bearer sai/hết hạn có thể làm cả request PUBLIC trả `401`; chọn No Auth cho đúng luồng.

JSON request dùng `Content-Type: application/json`. Không tự đổi snake_case thành camelCase:
`resumeId`, `related_resume_id`, `structured_data` là ba field thuộc các hợp đồng khác nhau.
Response JSON thường là object hoặc array trực tiếp, không có một lớp `data` chung.
Thao tác thành công mặc định trả `200`; các ngoại lệ như xóa CV `204` được ghi ở mục tương ứng.

### 2.4. Script lưu token và ID

Sau request login/refresh thành công, đặt **Post-response** như sau. Đổi `prefix` sang `admin`
hoặc `consultant` khi đăng nhập vai trò tương ứng; biến này chỉ chọn tên biến lưu, không thay đổi role tài khoản.

```javascript
const prefix = "candidate";
if (pm.response.code === 200) {
  const result = pm.response.json();
  pm.expect(result.accessToken, "accessToken").to.be.a("string").and.not.empty;
  pm.expect(result.refreshToken, "refreshToken").to.be.a("string").and.not.empty;
  pm.environment.set(prefix + "AccessToken", result.accessToken);
  pm.environment.set(prefix + "RefreshToken", result.refreshToken);
  pm.environment.set("activeToken", result.accessToken);
}
```

Sau register/resend ở chế độ development:

```javascript
if (pm.response.code === 200) {
  const result = pm.response.json();
  if (typeof result.developmentCode === "string") {
    pm.environment.set("otpCode", result.developmentCode);
  }
}
```

Với request tạo đối tượng trả trực tiếp `{ "id": "..." }`, dùng mẫu dưới và đổi `variableName`
thành `resumeId`, `threadId`, `courseId`, `ragDocumentId`... theo endpoint:

```javascript
const variableName = "resumeId";
if (pm.response.code >= 200 && pm.response.code < 300 && pm.response.code !== 204) {
  const result = pm.response.json();
  pm.expect(result.id, "UUID của đối tượng vừa tạo").to.be.a("string").and.not.empty;
  pm.environment.set(variableName, result.id);
}
```

Đối với response array, chọn đúng bản ghi trước khi lấy `id`; không chạy mẫu object ở trên.
Sau approve khóa học, ngoài `courseId` hãy lưu hai ID liên kết:

```javascript
if (pm.response.code === 200) {
  const result = pm.response.json();
  pm.expect(result.status).to.eql("APPROVED");
  pm.environment.set("courseId", result.id);
  pm.environment.set("courseRagDocumentId", result.rag_document_id);
  pm.environment.set("courseResourceId", result.learning_resource_id);
}
```

Refresh token được xoay sau mỗi lần dùng thành công; luôn lưu cặp token mới.
Không tiếp tục luồng nếu bước trước lỗi hoặc biến `{{...}}` còn chưa được resolve.


<a id="section-3"></a>

## 3. Xác thực và tài khoản

Các URL dưới đây dùng `{{baseUrl}} = http://localhost:8080/api/v1`. Request có JSON chọn **Body → raw → JSON**. Những API không nhận body chọn **Body → none**. Các thao tác thành công trong phần này trả HTTP `200`.

### 3.1. Đăng ký Candidate

`POST {{baseUrl}}/auth/register` — **No Auth**.

```json
{
  "email": "{{candidateEmail}}",
  "password": "{{candidatePassword}}",
  "fullName": "Nguyễn Văn An",
  "preferredLanguage": "vi"
}
```

- `email` bắt buộc, đúng định dạng email, tối đa 255 ký tự; backend trim và chuyển chữ thường.
- `password` bắt buộc, 10–72 ký tự và không vượt 72 byte UTF-8.
- `fullName` bắt buộc, tối đa 255 ký tự. `preferredLanguage` tùy chọn, chỉ `vi` hoặc `en`; bỏ qua mặc định `vi`.
- Tài khoản mới luôn có role `CANDIDATE`, trạng thái `PENDING`; không truyền role để tự cấp quyền.

Response có `message`, `expiresIn: 600`. Khi bật cấu hình lộ OTP cho môi trường phát triển, có thêm `developmentCode`; lưu chuỗi này vào `otpCode`. Ở môi trường gửi email, lấy mã từ hộp thư. Request này chưa cấp access token.

Email đã đăng ký trả `409`. Khi không bật OTP phát triển và chưa cấu hình gửi mail hợp lệ, API trả `503`.

### 3.2. Xác minh email và gửi lại OTP

`POST {{baseUrl}}/auth/verify-email` — **No Auth**.

```json
{
  "email": "{{candidateEmail}}",
  "code": "{{otpCode}}"
}
```

`code` bắt buộc là chuỗi đúng 6 chữ số, ví dụ `"012345"`; giữ số 0 đầu. Mã có hạn 10 phút và giới hạn 5 lần kiểm tra. Thành công trả `message: "Email verified; you can log in"`; tiếp tục gọi login.

`POST {{baseUrl}}/auth/resend-code` — **No Auth**.

```json
{
  "email": "{{candidateEmail}}"
}
```

`email` bắt buộc, đúng định dạng, tối đa 255 ký tự. Chỉ yêu cầu lại sau ít nhất 60 giây. Backend dùng thông báo chung để tránh lộ email có tài khoản; không suy ra tài khoản tồn tại từ `message`.

### 3.3. Đăng nhập bằng email

`POST {{baseUrl}}/auth/login` — **No Auth**.

```json
{
  "email": "{{candidateEmail}}",
  "password": "{{candidatePassword}}"
}
```

`email` bắt buộc, tối đa 255 ký tự. `password` bắt buộc, tối đa 72 ký tự và 72 byte UTF-8. Tài khoản phải có trạng thái `ACTIVE`; email chưa xác minh hoặc tài khoản bị khóa trả `403`. Sai thông tin trả `401`.

Response token dùng **camelCase**:

```json
{
  "accessToken": "<JWT>",
  "refreshToken": "r_<opaque-token>",
  "tokenType": "Bearer",
  "expiresIn": 900
}
```

Lưu `accessToken` vào `candidateAccessToken`, `refreshToken` vào `candidateRefreshToken`. Có thể gán `activeToken = candidateAccessToken` để test các API dùng chung.

Admin và Consultant cũng đăng nhập bằng endpoint này với email/mật khẩu tương ứng; lưu kết quả vào `adminAccessToken`/`adminRefreshToken` hoặc `consultantAccessToken`/`consultantRefreshToken`. Role thực tế lấy từ `GET /me`, không tự chọn trong body login.

### 3.4. Làm mới token

`POST {{baseUrl}}/auth/refresh` — **No Auth**.

```json
{
  "refreshToken": "{{candidateRefreshToken}}"
}
```

`refreshToken` bắt buộc, tối đa 120 ký tự; phải là refresh token do backend cấp, có tiền tố `r_`. Access token không dùng được ở đây.

Response có cấu trúc token như login. Refresh token chỉ dùng một lần: luôn ghi đè **cả hai** biến token sau khi refresh thành công. Refresh token có hạn 30 ngày; token đã dùng, bị thu hồi hoặc hết hạn trả `401`.

### 3.5. Đăng nhập Google

`POST {{baseUrl}}/auth/google` — **No Auth**.

```json
{
  "idToken": "{{googleIdToken}}"
}
```

`idToken` bắt buộc, tối đa 10.000 ký tự. Dùng **Google ID token thật** được cấp cho Google client ID đã cấu hình ở backend. Đây không phải access token của ứng dụng hay chuỗi JWT tự tạo.

Tài khoản hợp lệ/đã xác minh nhận bộ token như login. Với tài khoản Google còn cần xác minh email, response có thể là thông báo gửi OTP (`expiresIn`, và `developmentCode` nếu bật) hoặc `emailVerificationRequired: true`; chỉ lưu token khi response thực sự có `accessToken`.

Backend không tự gộp Google account với account đã có chỉ vì cùng email; trường hợp trùng email trả `409` và yêu cầu dùng cách đăng nhập hiện tại.

### 3.6. Các API tài khoản không có body

Các route sau yêu cầu **Bearer `{{activeToken}}`** của người đang thao tác. Có thể dùng Candidate, Admin hoặc Consultant; dữ liệu `/me` luôn thuộc người mang token.

| Method | URL | Kết quả và điều kiện |
| --- | --- | --- |
| GET | `{{baseUrl}}/me` | Hồ sơ cá nhân; lưu `id` làm ID người dùng nếu cần tham chiếu. |
| GET | `{{baseUrl}}/me/career-profile` | Hồ sơ nghề nghiệp kèm `skills`; hồ sơ chưa có có thể chỉ có `user_id` và danh sách skill. |
| GET | `{{baseUrl}}/me/sessions` | Tối đa 100 phiên/token đang hoạt động, mới nhất trước; mỗi dòng có `id`, `created_at`, `expires_at`. |
| DELETE | `{{baseUrl}}/me/sessions/{{sessionId}}` | Thu hồi đúng dòng phiên thuộc tài khoản; trả `message: "Session revoked"`. |
| POST | `{{baseUrl}}/auth/logout` | Thu hồi toàn bộ token của tài khoản; trả `message: "All sessions logged out"`. |
| DELETE | `{{baseUrl}}/me` | Đóng tài khoản theo kiểu soft delete, thu hồi toàn bộ token; trả `message: "Account closed"`. |

`sessionId` là UUID lấy từ `/me/sessions`, không phải chuỗi access/refresh token. Danh sách hiện lưu cả access token và refresh token dưới dạng các dòng token; thu hồi một dòng không đồng nghĩa thu hồi toàn bộ các dòng khác. Muốn đăng xuất toàn bộ dùng `/auth/logout`.

Xóa tài khoản Admin cuối cùng đang hoạt động bị chặn với `409`. Khi test logout hoặc đóng tài khoản, thực hiện cuối luồng vì token đang dùng sẽ mất hiệu lực.

Response `/me` có `id`, `email`, `auth_provider`, `full_name`, `avatar_url`, `role`, `status`, `preferred_language`, `github_url`, `email_verified_at`, `last_login_at`, `created_at`. Password và khóa Google không được trả về. Lưu ý request cập nhật dùng camelCase, response profile dùng các tên snake_case nêu trên.

### 3.7. Cập nhật thông tin cá nhân

`PUT {{baseUrl}}/me` — **Bearer `{{activeToken}}`**.

```json
{
  "fullName": "Nguyễn Văn An",
  "avatarUrl": "https://example.com/avatar.png",
  "preferredLanguage": "vi",
  "githubUrl": "https://github.com/example"
}
```

`fullName` bắt buộc, tối đa 255 ký tự. `avatarUrl` tối đa 2.000 ký tự; `githubUrl` tối đa 255 ký tự; `preferredLanguage` chỉ `vi`/`en` khi cung cấp.

Response là profile sau cập nhật. Bỏ `avatarUrl`/`githubUrl` sẽ lưu `null` cho các trường đó; bỏ `preferredLanguage` giữ ngôn ngữ hiện tại. Đây không phải API đổi email hoặc role.

### 3.8. Cập nhật hồ sơ nghề nghiệp và kỹ năng

`PUT {{baseUrl}}/me/career-profile` — **Bearer `{{candidateAccessToken}}`**; hợp đồng hiện hành cho phép user đã đăng nhập cập nhật hồ sơ của chính mình.

Lấy `skillId` thật, đang active từ `GET {{baseUrl}}/skills` trước khi gửi:

```json
{
  "currentJobTitle": "Java Intern",
  "currentCompany": "Example Company",
  "targetJobTitle": "Java Backend Developer",
  "careerGoal": "Phát triển kỹ năng Java và Spring Boot",
  "yearsExperience": 0.5,
  "expectedSalary": 7500000,
  "salaryCurrency": "VND",
  "preferredLocation": "Ho Chi Minh City",
  "workplacePreference": "HYBRID",
  "employmentType": "FULL_TIME",
  "profileSummary": "Có kiến thức Java, SQL và phát triển REST API.",
  "preferences": {
    "interestedIndustries": ["Software"]
  },
  "skills": [
    {
      "skillId": "{{skillId}}",
      "proficiencyLevel": "BEGINNER",
      "yearsExperience": 0.5
    }
  ]
}
```

Tất cả trường cấp hồ sơ đều tùy chọn, nhưng endpoint ghi lại toàn bộ các trường hồ sơ trong DTO: trường bỏ qua có thể bị xóa về `null`; `salaryCurrency` bỏ qua trở về `VND`. Gửi lại các giá trị cần giữ.

Chỉ gửi một `expectedSalary` theo tháng. Ví dụ kỳ vọng 7,5 triệu VND gửi `7500000` cùng `salaryCurrency: "VND"`;
không gửi chuỗi `"7tr5"` vào API. Response dùng `expected_salary`. Các trường `expectedSalaryMin`/`expectedSalaryMax`
cũ bị từ chối với `400`, không còn là tiêu chí nhập liệu.

| Trường | Ràng buộc |
| --- | --- |
| `currentJobTitle`, `currentCompany`, `targetJobTitle`, `preferredLocation` | Tối đa 255 ký tự mỗi trường. |
| `careerGoal`, `profileSummary` | Tối đa 10.000 ký tự mỗi trường. |
| `yearsExperience` ở hồ sơ và từng skill | Từ 0 đến 99,9; tối đa 1 chữ số thập phân. |
| `expectedSalary` | Một mức lương kỳ vọng mỗi tháng, lớn hơn 0; tối đa 12 chữ số phần nguyên và 2 chữ số thập phân. `null`/bỏ qua xóa kỳ vọng lương. Ví dụ 7,5 triệu VND gửi `7500000`. |
| `salaryCurrency` | `VND`, `USD` hoặc `EUR`. |
| `workplacePreference`, `employmentType` | Chuỗi tối đa 50 ký tự; ví dụ trên là giá trị mô tả, DTO chưa giới hạn enum. |
| `preferences` | JSON object tùy chọn; response lưu dưới `preferences_json`. |
| `skills` | Tối đa 100 phần tử; `skillId` bắt buộc là UUID skill active và không được trùng trong danh sách. |
| `proficiencyLevel` | Tùy chọn, tối đa 50 ký tự; DTO chưa giới hạn enum. |

Nếu bỏ `skills` hoặc đặt `null`, giữ danh sách skill cũ. Gửi `skills: []` xóa toàn bộ skill; gửi một mảng mới thay thế toàn bộ danh sách. Thành công trả hồ sơ dùng snake_case và `skills` mới. Thay đổi hồ sơ đánh dấu embedding hồ sơ hết hiện hành và các roadmap của user thành `is_outdated: true`.

### 3.9. Đổi mật khẩu khi đã đăng nhập

`PUT {{baseUrl}}/me/password` — **Bearer `{{activeToken}}`**.

```json
{
  "currentPassword": "{{candidatePassword}}",
  "newPassword": "{{candidateNewPassword}}"
}
```

`currentPassword` bắt buộc, tối đa 72 ký tự. `newPassword` bắt buộc, 10–72 ký tự. Cả hai phải nằm trong 72 byte UTF-8. Chỉ tài khoản `auth_provider: "EMAIL"` đổi bằng API này; tài khoản Google quản lý mật khẩu qua nhà cung cấp.

Sai mật khẩu hiện tại trả `401`. Thành công trả `message: "Password changed; log in again"` và thu hồi toàn bộ token. Cập nhật biến mật khẩu, đăng nhập lại để tiếp tục test.

Hiện chưa có endpoint **forgot-password/reset-password** cho người chưa đăng nhập. Không dùng `/me/password` thay cho luồng khôi phục mật khẩu.

### 3.10. Giới hạn gọi xác thực

| Luồng | Giới hạn hiện tại |
| --- | --- |
| Register | 10 lần/IP/giờ. |
| Verify email | 30 lần/IP/10 phút; thêm giới hạn thử mã nêu trên. |
| Resend code | 10 lần/IP/giờ; cách nhau ít nhất 60 giây cho cùng account. |
| Login | 100 lần/IP/15 phút và 15 lần/email/15 phút. |
| Refresh | 100 lần/IP/15 phút. |
| Google | 30 lần/IP/15 phút. |

Chạm giới hạn trả `429`; Postman Runner không nên lặp liên tục các route này.

<a id="section-4"></a>

## 4. Guest và dùng thử

Guest không dùng bearer token. Chọn **No Auth** cho toàn bộ nhóm; sau khi tạo session, thêm `X-Guest-Session: {{guestSession}}` vào các request còn lại. Các thao tác thành công trả HTTP `200`.

### 4.1. Tạo guest session

`POST {{baseUrl}}/guest/sessions` — **No Auth**, **Body → none**, chưa cần `X-Guest-Session`.

Response:

```json
{
  "id": "<guest-session-uuid>",
  "sessionKey": "g_<opaque-key>",
  "tokenLimit": 5000,
  "tokenUsed": 0,
  "expiresAt": "<expiry-time>"
}
```

Lưu **`sessionKey`** vào `guestSession`; lưu `id` vào `guestSessionId` nếu cần theo dõi. Header cần raw key `g_...`, **không nhận UUID `id`**. Key chỉ được trả lúc tạo, không có trong response xem lại session.

Session hết hạn sau 24 giờ; quota mặc định 5.000 token, có thể thay bằng cấu hình `app.guest.token-limit`. Tạo session bị giới hạn 3 lần/IP/24 giờ. Hết quota trả `429`, key sai hoặc hết hạn trả `401`.

### 4.2. Lấy session và dữ liệu demo

Các route sau **No Auth + `X-Guest-Session: {{guestSession}}`**, không có body:

| Method | URL | Dữ liệu cần lấy |
| --- | --- | --- |
| GET | `{{baseUrl}}/guest/sessions/me` | Session hiện tại với các trường DB như `id`, `token_limit`, `token_used`, `expires_at`; không trả session key. |
| GET | `{{baseUrl}}/guest/demo-resumes` | Tối đa 20 CV demo ở trạng thái `READY`; mỗi mục có `id`, `title`, `language`, `structured_data`. Lưu CV muốn thử vào `demoResumeId`. |
| GET | `{{baseUrl}}/guest/templates` | Template đang active và cho dùng thử. Lưu `id` phù hợp vào `trialTemplateId`. |

Chỉ dùng ID thực trong hai danh sách demo/template; không lấy CV riêng của Candidate để gọi guest trial.

### 4.3. Dùng thử tạo CV

`POST {{baseUrl}}/guest/trials/build` — **No Auth + guest header**.

```json
{
  "template_id": "{{trialTemplateId}}",
  "demo_resume_id": "{{demoResumeId}}"
}
```

Cả hai trường bắt buộc là UUID. Tên field là **snake_case**. Backend lấy nội dung từ CV demo và template đã chọn; không nhận nội dung CV cá nhân tùy ý để thay dữ kiện demo.

Response có `trial: true`, `template_id`, `structured_data`, `registration_prompt`. Đây là bản xem thử; muốn tạo và lưu CV cá nhân dùng luồng Candidate sau khi đăng nhập. API cần AI generation được cấu hình và quota guest đủ để xử lý.

### 4.4. Dùng thử gợi ý việc làm

`POST {{baseUrl}}/guest/trials/recommendations` — **No Auth + guest header**.

```json
{
  "demo_resume_id": "{{demoResumeId}}"
}
```

`demo_resume_id` bắt buộc là UUID CV demo. Backend so khớp kỹ năng theo luật, trả tối đa 3 việc làm; không thực hiện semantic matching cho trial này. Mỗi lượt thành công tính 100 token trial cố định.

Response có `matching_run_id`, `mode: "SKILL_OVERLAP"`, `trial: true`, `results`, `registration_prompt`. Lưu `matching_run_id` vào `guestMatchingRunId` nếu cần; không dùng nhầm với run matching của Candidate. `results` có thể rỗng nếu chưa có dữ liệu job.

### 4.5. Chat AI cho guest

Tất cả request dưới đây **No Auth + `X-Guest-Session: {{guestSession}}`**.

| Method | URL | Body và kết quả |
| --- | --- | --- |
| POST | `{{baseUrl}}/guest/assistant/conversations` | Body none. Tạo conversation, trả bản ghi có `id`; lưu vào `guestConversationId`. |
| GET | `{{baseUrl}}/guest/assistant/conversations/{{guestConversationId}}` | Body none. Trả conversation kèm `messages` và `sources`. |

`POST {{baseUrl}}/guest/assistant/conversations/{{guestConversationId}}/messages`:

```json
{
  "message": "Em mới học Java, nên bắt đầu tìm việc thực tập như thế nào?",
  "retrieve": false
}
```

`message` bắt buộc, không trắng, tối đa 8.000 ký tự. `retrieve` tùy chọn, phải là boolean khi có; không gửi `null` hoặc chuỗi `"true"`.

Guest chat hiện không truy xuất RAG, kể cả truyền `retrieve: true`; context của guest chỉ là trial. Muốn kiểm thử gợi ý khóa học đã duyệt qua RAG, dùng assistant của Candidate với `retrieve: true`.

Response gửi tin là bản ghi **tin ASSISTANT** với `id`, `conversation_id`, `sender_type`, `message_text`, thông tin model/token và `sources`. Không đọc câu trả lời từ field `answer`. GET conversation để xem cả câu hỏi và câu trả lời đã lưu.

Conversation phải thuộc đúng guest session và còn `ACTIVE`; dùng key guest khác trả `404`, conversation đã đóng trả `409`. Không có endpoint riêng cho guest để liệt kê mọi conversation, đánh giá tin nhắn hoặc archive conversation.

<a id="section-5"></a>

## 5. Gói cước và thanh toán

`GET /subscriptions/tiers` là public. `POST /payments/webhook` và `POST /payments/sepay/ipn` không dùng Bearer,
nhưng phải có thông tin xác thực riêng của từng gateway. Các API còn lại cần **Bearer `{{candidateAccessToken}}`**
hoặc token user đang test; backend ràng buộc dữ liệu theo người mang token. Thành công trả HTTP `200`.

**SePay Payment Gateway:** dùng `POST /payments/sepay/checkout` với `tierId`, `durationMonths` để nhận đơn và
form đã ký; FE gửi `formFields` theo thứ tự sang `checkoutUrl`. IPN nhận tại `/payments/sepay/ipn`, xác thực
`X-Secret-Key`; đơn SePay không được xác nhận bằng custom webhook cũ. Xem
[cấu hình sandbox, Postman và form FE đầy đủ](sepay-payment-gateway.md).

### 5.1. Xem gói, quota và lịch sử

Không có body:

| Method | URL | Auth | Kết quả |
| --- | --- | --- | --- |
| GET | `{{baseUrl}}/subscriptions/tiers` | No Auth | Các gói active, giá tăng dần; mỗi gói có `id`, `tier_code`, `display_name`, `price_monthly`, `currency`, `quotas`. |
| GET | `{{baseUrl}}/subscriptions/me` | Bearer | Gói có hiệu lực trong `tier`, danh sách `quotas` và `tokensUsedThisMonth`. Nếu không có gói trả phí còn hạn, dùng Free. |
| GET | `{{baseUrl}}/subscriptions/history` | Bearer | Tối đa 100 đăng ký của mình, mới nhất trước; lấy `id` làm `subscriptionId` khi cần cancel. |

Trong danh sách gói public, mỗi quota có `featureCode`, `quotaValue`, `quotaPeriod`. Dữ liệu quota của `/subscriptions/me` lấy từ bảng quota nên có thể dùng snake_case. Không giả định mọi response trong backend dùng cùng một quy tắc đặt tên.

Hạn mức hiện tính theo **token**, bao gồm lượng đang được giữ chỗ cho request AI; đây không phải quota độc lập về số lần gọi API. `quota_value` không âm; quota riêng bằng `0` khóa tính năng tương ứng. Không tự tính quota còn lại chỉ bằng số request đã gửi.

### 5.2. Tạo đơn thanh toán

Chọn gói active có `price_monthly > 0` từ danh sách tiers, lưu `id` vào `paidTierId`. Không tạo order cho Free.

`POST {{baseUrl}}/payments/orders` — **Bearer `{{candidateAccessToken}}`**.

```json
{
  "tierId": "{{paidTierId}}",
  "durationMonths": 1
}
```

`tierId` bắt buộc là UUID. `durationMonths` bắt buộc về mặt giá trị, số nguyên 1–12; bỏ trường sẽ thành `0` và bị validation từ chối. Số tiền backend tự tính bằng `price_monthly × durationMonths`; tổng tối đa `99999999.99`.

Response là order với `id`, `user_id`, `tier_id`, `duration_months`, `amount`, `currency`, `payment_provider`, `provider_order_id`, `status: "PENDING"`, `created_at` và các cột trạng thái liên quan.

Lưu `id` vào `paymentOrderId`, `provider_order_id` vào `providerOrderId`, cùng `amount` vào `paymentAmount`, `currency` vào `paymentCurrency`. Các giá trị này cần khớp khi test webhook.

API hiện tạo đơn và xử lý webhook; không trả URL checkout hoặc QR thanh toán từ một cổng thanh toán thật. Provider mặc định là `MANUAL_GATEWAY` nếu chưa đổi cấu hình.

### 5.3. Đọc đơn, phản ánh và hóa đơn

Tất cả route cần **Bearer `{{candidateAccessToken}}`**, không có body:

| Method | URL | Kết quả |
| --- | --- | --- |
| GET | `{{baseUrl}}/payments/orders` | Tối đa 100 đơn của mình, mới nhất trước. |
| GET | `{{baseUrl}}/payments/orders/{{paymentOrderId}}` | Chi tiết một đơn thuộc mình; kiểm tra `status` sau webhook. |
| GET | `{{baseUrl}}/payments/reports` | Tối đa 100 phản ánh thanh toán của mình. |
| GET | `{{baseUrl}}/invoices` | Tối đa 100 hóa đơn của mình, mới nhất trước; lấy `id` làm `invoiceId`. |
| GET | `{{baseUrl}}/invoices/{{invoiceId}}` | Chi tiết hóa đơn thuộc mình, response JSON. |

Chưa có API tải PDF hóa đơn trong controller hiện tại. Dùng Postman lưu response nếu cần tệp JSON. Không dùng `paymentOrderId` thay cho `invoiceId`: đây là hai UUID khác nhau.

### 5.4. Gửi phản ánh thanh toán

`POST {{baseUrl}}/payments/orders/{{paymentOrderId}}/reports` — **Bearer `{{candidateAccessToken}}`**.

```json
{
  "subject": "Đã thanh toán nhưng gói chưa được kích hoạt",
  "description": "Nhờ kiểm tra giao dịch của đơn này và trạng thái gói tài khoản."
}
```

`subject` bắt buộc, tối đa 255 ký tự; `description` bắt buộc, tối đa 10.000 ký tự. Chỉ gửi cho order thuộc mình. Response có `id`, `payment_order_id`, `user_id`, `subject`, `description`, `status: "OPEN"`, `created_at`; lưu `id` vào `paymentReportId` để Admin xử lý.

### 5.5. Hủy đăng ký

`POST {{baseUrl}}/subscriptions/{{subscriptionId}}/cancel` — **Bearer `{{candidateAccessToken}}`**, **Body → none**.

`subscriptionId` lấy từ lịch sử đăng ký, phải thuộc người đang gọi. Thành công trả `message: "Subscription cancelled"` và cập nhật trạng thái thành `CANCELLED`, `auto_renew: false`.

Hành vi hiện tại hủy hiệu lực đăng ký ngay; không phải lên lịch hủy vào cuối kỳ và không có hoàn tiền tự động. Nếu không còn đăng ký trả phí active/còn hạn, `/subscriptions/me` trả gói Free.

### 5.6. Nhận webhook thanh toán

`POST {{baseUrl}}/payments/webhook` — **No Auth**; xác thực bằng chữ ký webhook, không dùng bearer token.

Headers bắt buộc:

```text
Content-Type: application/json
X-Payment-Timestamp: {{paymentTimestamp}}
X-Payment-Signature: {{paymentSignature}}
```

Hợp đồng JSON cần ký và gửi:

```json
{
  "orderId": "{{paymentOrderId}}",
  "providerOrderId": "{{providerOrderId}}",
  "eventId": "{{paymentEventId}}",
  "status": "COMPLETED",
  "amount": {{paymentAmount}},
  "currency": "{{paymentCurrency}}"
}
```

`paymentAmount` phải được Postman thay bằng số tiền thực trước khi gửi; không bọc biến này trong dấu nháy.
Khi dùng script ở mục 15, raw body chỉ là `{{paymentWebhookBody}}`; không dán lại payload minh họa trên.

- `orderId` là UUID order; `providerOrderId`, `amount`, `currency` khớp chính xác dữ liệu order.
- `eventId` bắt buộc, không trắng, tối đa 255 ký tự; dùng ID mới cho event mới. `providerOrderId` tối đa 255 ký tự.
- `status` chỉ nhận `COMPLETED`, `FAILED`, `CANCELLED` với đúng chữ hoa.
- Timestamp là Unix seconds, lệch thời gian máy chủ không quá 300 giây. Signature là 64 ký tự hex HMAC-SHA256 của `timestamp + "." + rawBody`, khóa là payment webhook secret của backend.
- Body bị giới hạn 65.536 ký tự; raw body gửi đi phải giữ nguyên nội dung đã ký, kể cả khoảng trắng/xuống dòng.

Xem [cách ký webhook trong Postman](#webhook-thanh-toan) để tạo timestamp, signature và raw body đồng bộ; không điền chữ ký minh họa rồi Send.

Response có `accepted`, `duplicate`, `orderId`. Event trùng được xử lý idempotent; tái sử dụng `eventId` cho order khác hoặc đổi trạng thái order đã chốt trả `409`. Sai chữ ký/timestamp trả `401`; chưa cấu hình secret đủ dài trả `503`.

Khi `COMPLETED` thành công, backend kích hoạt đăng ký trả phí mới, hủy đăng ký active cũ, tạo hóa đơn `PAID` và thông báo thanh toán. Sau đó gọi lại `/payments/orders/{{paymentOrderId}}`, `/subscriptions/me`, `/subscriptions/history`, `/invoices` để kiểm tra kết quả; chỉ tạo order chưa làm gói được kích hoạt.


<a id="section-6"></a>

## 6. CV và phân tích CV

Các URL dùng `{{baseUrl}} = http://localhost:8080/api/v1`. Ngoại trừ danh sách template PUBLIC, nhóm này cần đăng nhập và chỉ thao tác CV của người gọi (USER). Khi test Candidate, dùng Bearer `{{candidateAccessToken}}`.

### 6.1. Đọc dữ liệu, đặt CV chính, phân tích và tải tệp

Các request sau để **Body → none**. Các UUID trong URL phải lấy từ response thực.

| Method | URL | Quyền | Thành công và điều kiện |
| --- | --- | --- | --- |
| GET | `{{baseUrl}}/resumes/templates` | PUBLIC | `200`; danh sách template đang active; lấy `id` làm `resumeTemplateId`. |
| GET | `{{baseUrl}}/resumes` | USER | `200`; CV của token, loại bản `ARCHIVED`, sắp CV chính trước; không có query phân trang. |
| GET | `{{baseUrl}}/resumes/{{resumeId}}` | USER | `200`; bản CV, các section đã xác nhận và `parse_results`. |
| POST | `{{baseUrl}}/resumes/{{resumeId}}/primary` | USER | `200`; CV phải `READY`; bỏ cờ chính của CV trước đó. |
| DELETE | `{{baseUrl}}/resumes/{{resumeId}}` | USER | `204`, body rỗng; archive CV, vô hiệu embedding và đánh dấu lộ trình liên quan cần cập nhật. |
| POST | `{{baseUrl}}/resumes/{{resumeId}}/analyses` | USER | `200`; CV phải `READY`; tạo báo cáo phân tích, lưu `id` thành `analysisId`. |
| GET | `{{baseUrl}}/resumes/{{resumeId}}/analyses` | USER | `200`; danh sách báo cáo của CV. |
| GET | `{{baseUrl}}/resumes/{{resumeId}}/analyses/{{analysisId}}` | USER | `200`; báo cáo đúng CV và mảng `suggestions`. |
| POST | `{{baseUrl}}/resumes/{{resumeId}}/embedding` | USER | `200`; CV phải `READY`, embedding provider phải bật; tái sử dụng vector cùng nội dung/provider/model khi có. |
| GET | `{{baseUrl}}/resumes/{{resumeId}}/download?text=false` | USER | `200` bytes/attachment; `text` là boolean, mặc định `false`. |

Với download, dùng chức năng lưu response ra file trong Postman. `text=false` trả file upload gốc nếu CV có file; CV nhập tay/AI không có file gốc sẽ trả TXT. `text=true` luôn trả canonical text UTF-8; khi đã có nội dung cấu trúc, nội dung này được ưu tiên. Endpoint hiện không render CV nhập tay/AI thành PDF.

CV chưa xác nhận trả `409` khi yêu cầu primary, analysis hoặc embedding. Embedding bị tắt trả `503`; CV bị sửa trong lúc tạo vector có thể trả `409`, cần xác nhận lại rồi thử lại.

Phân tích CV có hai chế độ trong `analysis_type`:

| Cấu hình | Kết quả |
| --- | --- |
| AI generation bật | `AI_QUALITY`; đánh giá bằng AI và lưu model. |
| AI generation tắt | `RULE_BASED`; kiểm tra đủ các mục, `ats_score` là `null`; không thay thế ATS theo job. |

### 6.2. Tạo CV thủ công

`POST {{baseUrl}}/resumes` — USER, JSON, thành công `200`.

Payload tối thiểu có nội dung để xác nhận ở bước tiếp theo:

```json
{
  "title": "CV Backend Java",
  "language": "vi",
  "structured_data": {
    "summary": "Sinh viên CNTT có dự án Java và Spring Boot.",
    "skills": ["Java", "SQL"]
  }
}
```

Response là CV mới, `source: "MANUAL"`, `status: "DRAFT"`. Lưu `id` vào `resumeId`, sau đó review/confirm. Tạo thành công chưa đồng nghĩa CV dùng được cho matching.

| Field | Bắt buộc/default | Ràng buộc |
| --- | --- | --- |
| `title` | Bắt buộc khi tạo | String không trắng, tối đa 255 ký tự. |
| `language` | Tùy chọn, mặc định `vi` | `vi` hoặc `en`; service cũng chấp nhận `VI`/`EN` và chuẩn hóa chữ thường. |
| `template_id` | Tùy chọn | UUID template đang active; bỏ field nếu không chọn template. |
| `raw_content` | Tùy chọn, mặc định rỗng | String tối đa 200.000 ký tự. |
| `structured_data` | Bắt buộc với tạo thủ công | JSON object; schema các section bên dưới. |

Có thể thêm template bằng `"template_id": "{{resumeTemplateId}}"` khi biến đã có UUID hợp lệ. Field `is_primary` chỉ được xử lý trong confirm hoặc endpoint primary, không làm CV mới tự thành CV chính.

Schema đầy đủ của `structured_data` để FE dựng form và Postman kiểm tra section:

```json
{
  "full_name": "Nguyen Van A",
  "email": "candidate@example.com",
  "phone": "0901234567",
  "location": "Ho Chi Minh",
  "summary": "Ứng viên Backend Java, có kinh nghiệm dự án Spring Boot.",
  "experience": [
    {
      "company_name": "Cong ty A",
      "job_title": "Backend Intern",
      "start_date": "2026-06-01",
      "is_current": true,
      "description": "Xây dựng REST API bằng Spring Boot."
    }
  ],
  "education": [
    {
      "school_name": "Dai hoc A",
      "degree": "Bachelor",
      "major": "Computer Science",
      "start_date": "2022-09-01",
      "description": "Học phần cơ sở dữ liệu và phát triển phần mềm."
    }
  ],
  "projects": [
    {
      "project_name": "CV Matching",
      "role": "Backend Developer",
      "description": "Thiết kế API và xử lý dữ liệu CV.",
      "technologies": ["Java", "Spring Boot", "PostgreSQL"],
      "project_url": "https://github.com/example/cv-matching",
      "start_date": "2026-01-01",
      "end_date": "2026-05-31"
    }
  ],
  "certifications": [
    {
      "name": "Java Course Certificate",
      "issuer": "Training Provider",
      "issue_date": "2026-05-01"
    }
  ],
  "skills": [
    {
      "name": "Java",
      "proficiency_level": "intermediate",
      "years_experience": 1
    }
  ]
}
```

Đây là giá trị của field `structured_data`, không phải body độc lập để POST tạo CV. Các section bỏ qua sẽ trở thành mảng rỗng; tên mục là `experience` và `education` dạng số ít.

Ràng buộc section:

- `full_name`, `email`, `phone`, `location`: string tối đa 500; `summary` tối đa 20.000 ký tự.
- `experience`, `education`, `projects`, `certifications`, `skills`: mảng tối đa 100 phần tử mỗi mục.
- Các ngày là `YYYY-MM-DD`; ngày kết thúc/hết hạn không được trước ngày bắt đầu/cấp.
- `is_current` là boolean; kinh nghiệm đang làm (`true`) không có `end_date`.
- `technologies` là mảng tối đa 100 string, mỗi string tối đa 150 ký tự.
- `description` tối đa 20.000 ký tự; các field text trong section tối đa 255, riêng `proficiency_level` tối đa 50.
- `certifications` và `skills` cần `name`; `skills` cũng chấp nhận string như `"Java"`.
- Skill có thể thêm `skill_id` UUID thực; `years_experience` là number từ 0 đến 99. Khi confirm, service kiểm tra skill ID hoặc tìm/tạo skill từ tên.
- Certification có thể thêm `expiry_date`, `credential_url`; education có thể thêm `end_date` theo cùng quy tắc.

### 6.3. Tạo CV bằng AI

`POST {{baseUrl}}/resumes/build` — USER, JSON, `200`; cần AI generation hoạt động và quota còn đủ.

```json
{
  "title": "CV Java Fresher",
  "language": "vi",
  "structured_data": {
    "summary": "Sinh viên CNTT định hướng Backend Java.",
    "projects": [
      {
        "project_name": "Quan ly thu vien",
        "description": "REST API bằng Spring Boot và PostgreSQL.",
        "technologies": ["Java", "Spring Boot", "PostgreSQL"]
      }
    ],
    "skills": ["Java", "SQL"]
  }
}
```

`title` bắt buộc; `language`, `template_id`, `raw_content` dùng cùng quy tắc tạo CV. Payload có thể bổ sung dữ kiện ứng viên vì DTO cho phép JSON mở rộng; service cũng đưa career profile hiện có vào yêu cầu AI. Không có field `prompt` bắt buộc trong contract.

Response `source: "AI_BUILDER"`, `status: "REVIEW_REQUIRED"`; lưu `id` vào `resumeId`, xem lại dữ kiện và confirm. AI builder không tự xác nhận nội dung.

Luồng này gọi `ResumeService.create(..., true)` → `AiService.generate("CV_BUILD", ...)` → Ollama chat.
Để gửi một bộ dữ liệu nhất quán từ đầu đến cuối, dùng [luồng test CV bằng Ollama](postman-test-order.md#tao-cv-ollama).
Không dùng điểm ATS/nội dung cố định từ frontend mock làm kết quả model; màn hình AI Resume Builder hiện chưa nối API này.

### 6.4. Upload và bóc tách CV

`POST {{baseUrl}}/resumes/upload` — USER, **Body → form-data**, `200`.

| Key | Loại trong Postman | Bắt buộc/default |
| --- | --- | --- |
| `file` | File | Bắt buộc, chọn file thật. |
| `title` | Text | Tùy chọn; bỏ trống dùng tên file; tối đa 255 ký tự. |
| `language` | Text | Tùy chọn, mặc định `vi`; `vi`/`en`. |

Để Postman tự sinh `Content-Type: multipart/form-data` kèm boundary. Không dùng raw JSON hoặc tự cố định header `application/json`.

- File tối đa 10 MiB; định dạng PDF, DOCX, TXT, PNG, JPG/JPEG; phần mở rộng phải khớp MIME/signature.
- PDF không mã hóa, tối đa 50 trang; ảnh cần OCR và Tesseract language data được cấu hình.
- TXT phải UTF-8; văn bản trích xuất tối đa 200.000 ký tự.
- Không có OCR cho ảnh trả `503`; quá dung lượng file trả `413`; file sai định dạng trả `400`.

Response lưu `source: "UPLOAD"`, `status: "REVIEW_REQUIRED"`, file gốc, `raw_content` và `parse_results`. Nếu parse AI lỗi sau khi đọc file, CV có thể vẫn được tạo với parse result `FAILED` và `error_message`; cần kiểm tra nội dung trước confirm. AI tắt vẫn bóc text bằng parser, rồi người dùng bổ sung cấu trúc thủ công.

### 6.5. Sửa dữ liệu đã review

`PATCH {{baseUrl}}/resumes/{{resumeId}}` — USER, JSON, `200`.

```json
{
  "title": "CV Backend Java - Reviewed",
  "structured_data": {
    "summary": "Sinh viên CNTT có dự án Spring Boot và PostgreSQL.",
    "skills": ["Java", "Spring Boot", "PostgreSQL"]
  }
}
```

Chỉ gửi field cần đổi trong `title`, `language`, `raw_content`, `structured_data`. Không gửi `null` để thay cho field bỏ qua. Nếu gửi `structured_data`, service thay toàn bộ document cấu trúc bằng object đã chuẩn hóa; các section không gửi sẽ thành rỗng. Muốn giữ section cũ, đưa cả section đó trong object mới.

Thay đổi nội dung làm CV trở về `REVIEW_REQUIRED`, bỏ cờ chính và làm embedding/lộ trình liên quan cần cập nhật. CV `ARCHIVED` không sửa được (`409`). `template_id` không được cập nhật qua PATCH hiện tại.

### 6.6. Xác nhận CV đã kiểm tra

`POST {{baseUrl}}/resumes/{{resumeId}}/confirm` — USER, `200`; body là tùy chọn.

Nếu đã sửa xong bằng PATCH, chọn Body none. Hoặc gửi JSON để đồng thời cập nhật nội dung review và đặt CV chính:

```json
{
  "structured_data": {
    "summary": "Đã kiểm tra thông tin Backend Java.",
    "skills": ["Java", "SQL"]
  },
  "is_primary": true
}
```

Confirm xử lý `structured_data`, `raw_content`, `is_primary`; muốn đổi `title`/`language`, dùng PATCH trước. Nội dung phải có raw text, summary hoặc ít nhất một section có phần tử; CV trống trả `400`.

Thành công chuyển sang `READY`, lưu các section/skill đã review để dùng cho matching, ATS và roadmap. `is_primary: false` không gỡ cờ chính nếu CV vốn đang là CV chính; dùng endpoint primary để chọn CV khác. CV archive trả `409`.

### 6.7. Đối chiếu CV với hồ sơ nghề nghiệp cá nhân

Dành cho **CANDIDATE**, Bearer `{{candidateAccessToken}}`, đúng CV của người gọi. Chức năng độc lập với
`/resumes/{id}/analyses` chấm CV và `/jobs/{jobId}/ats` đối chiếu JD. Hồ sơ nghề nghiệp phải có dữ kiện nghề nghiệp để đối chiếu;
chỉ đặt lương chưa đủ. CV phải `READY`, Ollama bật và quota đủ. Không cần embedding.

| Method | URL | Body và kết quả |
| --- | --- | --- |
| POST | `{{baseUrl}}/resumes/{{resumeId}}/profile-alignments` | **none**; đọc hồ sơ/CV trên server, trả báo cáo mới và lưu `id` thành `alignmentId`. |
| GET | `{{baseUrl}}/resumes/{{resumeId}}/profile-alignments?limit=20&offset=0` | **none**; mảng báo cáo, mới nhất trước. `limit` mặc định 20, từ 1–100; `offset` mặc định 0, từ 0–100000. |
| GET | `{{baseUrl}}/resumes/{{resumeId}}/profile-alignments/{{alignmentId}}` | **none**; đọc báo cáo thuộc đúng CV và tài khoản. |
| GET | `{{baseUrl}}/resumes/{{resumeId}}/profile-alignments/refresh-status` | **none**; trạng thái `NONE`, `QUEUED`, `RUNNING`, `SUCCEEDED`, `FAILED` hoặc `CANCELLED`; không gọi AI. |

Lần đầu vẫn cần POST chủ động. PUT hồ sơ thay đổi dữ kiện chuẩn hóa sẽ ghi yêu cầu đánh giá lại cho các CV
`READY` đã có báo cáo, trong cùng transaction lưu hồ sơ. Worker chỉ gọi AI sau commit; PUT vẫn trả `200`
khi hồ sơ được lưu, không chờ model và không rollback khi AI/quota lỗi. Gửi lại cùng nội dung hoặc chỉ đổi
thông tin liên hệ không yêu cầu AI. Báo cáo ATS/chất lượng và matching JD không được chạy lại hoặc ghi đè.

Status có thêm `requested_at`, `started_at`, `completed_at`, `alignment_id`, `error_code`, `message` khi có yêu cầu;
`NONE` có thể chỉ có field `status`. Worker riêng kiểm tra hàng đợi mỗi 5 giây, xử lý một CV/lượt; nhiều lần sửa
được gộp, kết quả lỗi thời bị loại. `SUCCEEDED` thì đọc báo cáo mới qua `alignment_id`; báo cáo cũ vẫn nguyên vẹn.
`FAILED` do quota/AI không tự retry; sau khi khắc phục, POST thủ công để thử lại. POST khi `QUEUED`/`RUNNING`
trả `409`. Lease gián đoạn mặc định 900 giây kết thúc `FAILED`/`INTERRUPTED`, không âm thầm gọi model lại.
Lượt nền dùng `CV_PROFILE_ALIGNMENT` và `AI_TOTAL_TOKENS`; GET lịch sử/trạng thái không tiêu quota model.

`result_json` có bảy tiêu chí nghề nghiệp, điểm trung bình các tiêu chí `ASSESSED`, `coverage_percent`
và tiêu chí lương do server bổ sung. Model có thể chấm vị trí, mục tiêu, kỹ năng và kinh nghiệm khi đủ bằng chứng.
CV hiện chưa có trường riêng cho địa điểm/hình thức/loại công việc mong muốn, nên ba tiêu chí này luôn giữ
`MISSING_CV_EVIDENCE`/`null` nếu hồ sơ đã đặt, `NOT_APPLICABLE`/`null` nếu chưa đặt. Backend không suy chúng
từ địa chỉ, công việc cũ hoặc summary; cần đối chiếu với job/JD. Nếu cả bảy tiêu chí đã đặt và bốn tiêu chí
được chấm, coverage là 57,14%, độc lập với điểm phù hợp trung bình.

Khi có vị trí mong muốn trong hồ sơ và job title ở experience của CV đã review, `TARGET_ROLE` được chấm
với trích dẫn hai phía, kể cả hai nghề khác nhau. Điểm thiếu bằng chứng là `null`; lương luôn có `score: null`,
cần mức trả job để kết luận phù hợp. Phần trăm là suy luận theo rubric, chưa phải xác suất tuyển dụng hoặc
độ chính xác AI. Schema native giới hạn cấu trúc/trích đoạn nguồn, không bảo đảm mọi nhận xét ngữ nghĩa đúng.

Hai snapshot và điểm lịch sử được giữ nguyên. `resume_changed`, `profile_changed`, `is_stale` cho biết dữ liệu
hiện tại khác đầu vào đã dùng; GET không gọi lại model. Nguồn thay đổi khi inference trả `409`; evidence
không đúng trích dẫn hoặc output sai trả `502`, không tạo báo cáo. Đầu vào vượt budget bảo thủ trả `400`
trước khi giữ chỗ quota; không tự cắt dữ kiện. Xem [payload, quota V17, hàng đợi V18 và ca lỗi](cv-profile-alignment.md).

<a id="section-7"></a>

## 7. Việc làm, matching và hỗ trợ ứng tuyển

### 7.1. Danh mục kỹ năng và tìm việc công khai

Nhóm này PUBLIC, chọn **No Auth**, Body none. Không cần guest session.

| Method | URL | Query/default | Thành công |
| --- | --- | --- | --- |
| GET | `{{baseUrl}}/skills` | Không có | `200` mảng skill active; lấy `id` làm `skillId`. |
| GET | `{{baseUrl}}/jobs?q=Java&location=Ho%20Chi%20Minh&limit=20&offset=0` | `q=""`, `location=""`, `limit=20`, `offset=0` | `200` mảng job; lấy `id` làm `jobId`. |
| GET | `{{baseUrl}}/jobs/{{jobId}}` | Không có | `200` chi tiết job kèm `skills`, `sources`. |

`q`, `location` tối đa 255 ký tự; `limit` từ 1 đến 100; `offset` từ 0 đến 100.000. Nhập query qua tab Params để Postman encode dấu tiếng Việt và khoảng trắng. Danh sách trả mảng trực tiếp; không có wrapper `content`/`totalPages` trong contract.

Nếu danh sách trống, cần Admin import/crawl job đang active trước khi test matching hoặc hỗ trợ ứng tuyển. Đừng tạo UUID ngẫu nhiên thay `jobId`.

### 7.2. Tìm việc phù hợp

`POST {{baseUrl}}/matching` — CANDIDATE, JSON, `200`.

Theo một CV đã confirm:

```json
{
  "basis": "SELECTED_CV",
  "resumeIds": ["{{resumeId}}"],
  "limit": 10
}
```

Theo hồ sơ nghề nghiệp:

```json
{
  "basis": "CAREER_PROFILE",
  "limit": 10
}
```

Theo toàn bộ CV đã confirm:

```json
{
  "basis": "ALL_CVS",
  "limit": 10
}
```

| Field | Yêu cầu |
| --- | --- |
| `basis` | Bắt buộc, đúng một trong `CAREER_PROFILE`, `ALL_CVS`, `SELECTED_CV`. |
| `resumeIds` | `SELECTED_CV` cần đúng một UUID CV thuộc user và `READY`; hai basis còn lại không dùng danh sách này. |
| `limit` | Bắt buộc nhập số nguyên từ 1 đến 100; bỏ field dẫn đến giá trị 0 và lỗi validation. |

`CAREER_PROFILE` cần hồ sơ nghề nghiệp; `ALL_CVS` cần ít nhất một CV `READY`, tối đa 100 CV xác nhận mỗi lượt. Cần có job active trong catalog.

Matching theo hồ sơ cần một `expectedSalary` dương. Khi có lương kỳ vọng, ngưỡng job là
`expectedSalary × (1 − tolerancePercent / 100)`; tỷ lệ do Admin cấu hình, mặc định 10%.
Job lương cao hơn vẫn được xét. Candidate đọc chính sách hiện tại bằng `GET {{baseUrl}}/matching/salary-policy`.
Response matching có `salary_filter` lưu biên độ, ngưỡng và `settings_version` đã áp dụng cho chính lượt đó.
Xem [Admin chỉnh biên độ lương và thứ tự kiểm tra đổi cache](admin-salary-policy.md).

Response gồm matching run và `results`; lưu `id` ngoài cùng vào `matchingRunId`, từng `results[].id` vào `matchResultId`. `matchResultId` khác `jobId`. Có thể có `cached: true` và `cache_source: "REDIS"`/`"DATABASE"` khi tái dùng kết quả cùng đầu vào.

| Chế độ | Hành vi |
| --- | --- |
| AI generation bật | `SEMANTIC_LLM`: embed dữ liệu ứng viên, truy xuất job theo vector, AI xếp hạng lại; job phải được index theo provider/model embedding hiện tại. |
| AI generation tắt | `SKILL_OVERLAP`: so khớp kỹ năng bằng quy tắc; không cần vector job. |

AI bật nhưng chưa có job vector phù hợp trả `409` yêu cầu index; không tự chuyển sang keyword matching. `final_score` của matching nằm trong thang 0 đến 1.

### 7.3. Lịch sử matching, AI usage và bản nháp

Các request sau Body none. Các route USER chỉ yêu cầu đăng nhập và dữ liệu thuộc người gọi; dùng candidate token trong luồng này.

| Method | URL | Quyền | Query/default và kết quả |
| --- | --- | --- | --- |
| GET | `{{baseUrl}}/matching?limit=20` | USER | `limit=20`, 1..100; `200` lịch sử các run. |
| GET | `{{baseUrl}}/matching/{{matchingRunId}}` | USER | `200` một run của user và `results`. |
| GET | `{{baseUrl}}/ai/usage?limit=20&offset=0` | CANDIDATE | `limit=20`, 1..100; `offset=0`, 0..100.000; `200` nhật ký AI của user. |
| GET | `{{baseUrl}}/application-drafts` | USER | `200` tối đa 100 bản nháp mới nhất; `jobId` UUID là query tùy chọn. |
| POST | `{{baseUrl}}/jobs/{{jobId}}/salary-estimation` | CANDIDATE | `200` ước lượng lương theo job và career profile; không gửi JSON. |

Ví dụ lọc nháp theo job: `GET {{baseUrl}}/application-drafts?jobId={{jobId}}`.

### 7.4. Phản hồi một kết quả matching

`PUT {{baseUrl}}/matching/results/{{matchResultId}}/feedback` — USER, JSON, `200`; kết quả phải thuộc matching run của user.

```json
{
  "feedbackType": "LIKE",
  "rating": 5,
  "comment": "Công việc phù hợp định hướng Java Backend."
}
```

`feedbackType` bắt buộc, `LIKE`/`DISLIKE`; `rating` tùy chọn, số nguyên 1..5; `comment` tùy chọn, tối đa 2.000 ký tự. PUT cập nhật phản hồi của user cho cùng kết quả; không dùng body `is_helpful` của assistant cho API này.

### 7.5. Phân tích ATS theo một job

`POST {{baseUrl}}/jobs/{{jobId}}/ats` — CANDIDATE, JSON, `200`.

```json
{
  "resumeId": "{{resumeId}}"
}
```

`resumeId` bắt buộc, CV phải thuộc user và `READY`; job cần active với nguồn còn hiệu lực. Cần AI generation. Response có `ats_score` thang 0..100, `strengths_json`, `missing_skills_json`, `improvement_suggestions_json` và `cached`.

Kết quả ATS có ID riêng của `job_resume_analyses`; không lưu đè `analysisId` dùng cho `/resumes/{id}/analyses/{analysisId}`. Nội dung CV/job/provider/model không đổi có thể dùng lại kết quả cache.

### 7.6. Tạo CV theo yêu cầu job

`POST {{baseUrl}}/jobs/{{jobId}}/resume-draft` — CANDIDATE, JSON, `200`.

```json
{
  "title": "CV ung tuyen Java Backend",
  "language": "vi",
  "structured_data": {
    "summary": "Sinh viên CNTT, có dự án backend Spring Boot.",
    "skills": ["Java", "SQL"]
  }
}
```

API nhận object mở rộng cho dữ kiện ứng viên, bổ sung job/required skills và gọi AI builder. `title` tùy chọn, mặc định `CV - <job_title>`; `language` tùy chọn, mặc định `vi`. Có thể gửi `{}` nếu career profile đã có dữ kiện, nhưng cần review kết quả kỹ.

Không có yêu cầu bắt buộc `resumeId` tại route này; field đó không tự nạp nội dung CV cũ. Muốn dùng dữ kiện CV cũ, gửi các thông tin đã review trong payload. Response là CV mới `AI_BUILDER`, `REVIEW_REQUIRED`; confirm trước khi dùng.

### 7.7. Sinh email ứng tuyển và cover letter

`POST {{baseUrl}}/jobs/{{jobId}}/application-drafts` — CANDIDATE, JSON, `200`.

```json
{
  "resumeId": "{{resumeId}}",
  "language": "vi"
}
```

`resumeId` bắt buộc, CV của user và `READY`; `language` tùy chọn, `vi`/`en`, mặc định `vi`. Job cần active với nguồn còn hạn, AI generation hoạt động.

Response là **mảng hai bản ghi đã lưu**: `draft_type: "APPLICATION_EMAIL"` và `draft_type: "COVER_LETTER"`. Email `content` gồm subject và nội dung; cover letter nằm ở bản ghi riêng. Chọn ID dựa vào `draft_type`, không giả định thứ tự mảng; lưu ID muốn sửa vào `applicationDraftId`.

### 7.8. Sửa bản nháp

`PATCH {{baseUrl}}/application-drafts/{{applicationDraftId}}` — USER, JSON, `200`.

```json
{
  "content": "Kính gửi bộ phận tuyển dụng, tôi muốn ứng tuyển vị trí Java Backend..."
}
```

`content` bắt buộc, không trắng, tối đa 30.000 ký tự; nháp phải thuộc user. Endpoint chỉ sửa nội dung, không gửi email ra ngoài.

### 7.9. Lấy link ứng tuyển ngoài hệ thống

`POST {{baseUrl}}/jobs/{{jobId}}/external-apply` — CANDIDATE, `200`; Body none hoặc JSON sau để ghi nhận CV đi kèm:

```json
{
  "resumeId": "{{resumeId}}"
}
```

Khi có body, `resumeId` bắt buộc, CV phải của user và `READY`; nếu không gắn CV thì bỏ toàn bộ body, không gửi `{}`. Job cần nguồn active/còn hạn và URL hợp lệ.

Response `{"event_id":"...","target_url":"https://..."}`. FE mở `target_url`; backend ghi nhận sự kiện redirect, không tự nộp hồ sơ hay gửi email.

### 7.10. Ước lượng lương theo nghề

`POST {{baseUrl}}/salary-estimations` — CANDIDATE, JSON, `200`.

```json
{
  "targetJobTitle": "Java Backend Developer",
  "location": "Ho Chi Minh",
  "currency": "VND",
  "language": "vi"
}
```

`targetJobTitle` bắt buộc, không trắng, tối đa 160 ký tự. `location` tùy chọn, tối đa 120; `currency` tùy chọn, tối đa 80; `language` tùy chọn, `vi`/`en`, mặc định `vi`. Cần career profile đã lưu và AI generation.

Response lưu `estimated_min`, `estimated_max`, `currency`, `reasoning`, `model_name`. Kết quả AI phải có khoảng hợp lệ và currency `VND`/`USD`/`EUR`, nếu không trả `502`. Endpoint theo job ở bảng 7.3 có cùng nhóm kết quả nhưng lấy vị trí từ `jobId`.

<a id="section-8"></a>

## 8. Assessment, lộ trình và nguồn học

Dùng Bearer `{{candidateAccessToken}}`; tạo assessment, tạo roadmap và xem recommendations yêu cầu CANDIDATE. Đọc/lưu đáp án/submit/cập nhật node kiểm tra tài nguyên thuộc người đăng nhập. JSON request của nhóm này dùng **camelCase**, còn nhiều field response dùng **snake_case**.

Luồng hiện tại là **chọn JD → đọc kỹ năng/tài liệu chuẩn bị → làm bài theo JD → học từng skill cần bổ sung**.
Xem [ví dụ và thứ tự kiểm thử đầy đủ](jd-assessment-missing-skills.md). Kết quả mới có
`learning_scope: "MISSING_SKILLS_ONLY"`; bản lịch sử chương trình đầy đủ giữ nhãn `LEGACY_FULL_ROADMAP`.

Trước khi tạo bài: `GET {{baseUrl}}/assessments/preparation?jobId={{jobId}}` — CANDIDATE, Body none.
Trả `job_id`, `job_title`, `skills[]` gồm `skill_name`, `is_required`, `resources` từ catalog.
Endpoint này chưa đánh giá người dùng và không gọi AI; resource có thể rỗng khi chưa có tài liệu phù hợp.

### 8.1. Tạo bài đánh giá

**Luồng dùng trên FE:** `POST {{baseUrl}}/assessments/generations` — CANDIDATE, JSON body như dưới,
trả `202` với `id` tác vụ và trạng thái `QUEUED`/`RUNNING`. Đọc
`GET {{baseUrl}}/assessments/generations/{{generationId}}` mỗi 2–3 giây; khi `SUCCEEDED`, lấy
`assessment_id` rồi đọc bài. `GET {{baseUrl}}/assessments/generations?jobId={{jobId}}` trả mảng tác vụ
gần nhất của đúng chủ sở hữu để tiếp tục sau khi tải lại trang. Cùng user/JD đang chạy chỉ có một tác vụ.
`FAILED` trả `error_code`/`message`, không tự gọi lại model. Xem [đầy đủ trạng thái và giới hạn hàng đợi](jd-assessment-missing-skills.md#3-sinh-bài-đánh-giá-từ-jd-đã-chọn).

**Endpoint đồng bộ tương thích cũ:**

`POST {{baseUrl}}/assessments` — CANDIDATE, JSON, `200`.

```json
{
  "jobId": "{{jobId}}"
}
```

`jobId` bắt buộc, UUID job active/chưa hết hạn, có nội dung JD và kỹ năng đã trích xuất.
`targetJobTitle` tùy chọn, tối đa 255 ký tự; nếu gửi thì phải khớp tên JD đã chọn.
Không còn tạo bài theo `targetJobTitle` hoặc nghề trong hồ sơ khi chưa chọn JD.

Cần AI generation. Backend yêu cầu đúng **5 câu nền tảng + 5 câu kỹ thuật theo JD**; mỗi câu gắn với
skill trong JD và có trích dẫn JD được kiểm tra nội bộ trước khi lưu. Kết quả sai số lượng, phần hoặc
skill/trích dẫn bị từ chối `502`; không lưu bài không hợp lệ. Trích dẫn kiểm tra không phải field được
đảm bảo trả về trong câu hỏi.

Response gồm session, `questions`, `answers`; lưu `id` vào `assessmentId`, một `questions[].id` vào `questionId`. Session mới là `in_progress`; đáp án đúng và giải thích bị ẩn cho đến khi hoàn tất.

### 8.2. Xem bài đánh giá và nộp bài

Các route Body none, quyền USER với session thuộc token.

| Method | URL | Thành công và điều kiện |
| --- | --- | --- |
| GET | `{{baseUrl}}/assessments` | `200` tối đa 100 session mới nhất; không có query. |
| GET | `{{baseUrl}}/assessments/{{assessmentId}}` | `200` session, câu hỏi, đáp án đã chọn; session completed có `learning_recommendations`. |
| POST | `{{baseUrl}}/assessments/{{assessmentId}}/submit` | `200`; phải trả lời mọi câu; tính `skill_scores`, chuyển `completed`, bổ sung nguồn học. |

Submit thiếu câu trả lời trả `400`. Submit lại session `completed` trả kết quả hiện có. Hoàn tất assessment mới đánh dấu các lộ trình cũ cùng JD là `is_outdated: true`; không tự tạo roadmap mới.

### 8.3. Lưu một câu trả lời

`PUT {{baseUrl}}/assessments/{{assessmentId}}/answers` — USER, JSON, `200`.

```json
{
  "questionId": "{{questionId}}",
  "selectedOption": "A"
}
```

`questionId` bắt buộc và thuộc session; `selectedOption` bắt buộc, đúng `A`/`B`/`C`/`D`. Gọi lần lượt cho **mọi câu**, không chỉ câu đang lưu trong biến `questionId`. Gọi lại cùng câu trước submit sẽ cập nhật lựa chọn.

Response có `question_id`, `selected_option`; không lộ đáp án đúng. Chỉ lưu được khi session `in_progress`, nếu đã submit trả `409`.

### 8.4. Tạo hướng dẫn học từng skill cần bổ sung

`POST {{baseUrl}}/roadmaps` — CANDIDATE, JSON, `200`.

Payload tối thiểu theo JD:

```json
{
  "jobId": "{{jobId}}"
}
```

Payload kết hợp job, CV và bài đánh giá đã hoàn tất:

```json
{
  "jobId": "{{jobId}}",
  "resumeId": "{{resumeId}}",
  "assessmentId": "{{assessmentId}}"
}
```

`jobId` bắt buộc. Bỏ field tùy chọn chưa có UUID, không để placeholder chưa resolve.

| Field | Điều kiện |
| --- | --- |
| `targetJobTitle` | Tối đa 255 ký tự nếu gửi; không thay thế JD. Nên bỏ field này; tên mục tiêu lấy từ job đã chọn. |
| `jobId` | Bắt buộc, UUID job active/chưa hết hạn có kỹ năng bắt buộc đã trích xuất. |
| `resumeId` | UUID CV của user, phải `READY` nếu gửi. |
| `assessmentId` | UUID assessment của user đã `completed`, cùng `jobId`; nếu bỏ, tìm bản completed gần nhất cho đúng JD đó, không ghép theo tên nghề. |

Backend so sánh kỹ năng bắt buộc của JD với skill hồ sơ/CV và điểm bài đánh giá cùng JD.
Điểm dưới 70 là `NEEDS_PRACTICE`; không có điểm và chưa khai báo là `MISSING_EVIDENCE`,
không đồng nghĩa xác nhận người dùng không biết. Điểm từ 70 hoặc đã khai báo khi chưa có điểm thì
không bắt học bù skill đó. AI chỉ được viết hướng dẫn cho danh sách skill backend xác định;
không được thêm chương trình học nghề đầy đủ hay tự bịa URL resource.

Khi có skill cần bổ sung, cần AI generation; kế hoạch hợp lệ có 1..50 node, mỗi node 1..1.000 giờ.
Khi không có skill cần bổ sung, không gọi AI; trả `missing_skills: []`, `nodes: []`,
`status: "completed"`, `progress_percent: 100`.

Response chứa `learning_scope: "MISSING_SKILLS_ONLY"`, `missing_skills` với hướng dẫn riêng từng skill,
`nodes`, `skill_gaps`, `learning_recommendations`, `gap_analysis_id`. Lưu `id` vào `roadmapId`,
`gap_analysis_id` vào `gapAnalysisId`; chọn node không có prerequisite làm `roadmapNodeId` đầu tiên nếu có node.

### 8.5. Xem lộ trình và gợi ý nguồn học

Các route sau không gửi body.

| Method | URL | Quyền | Query và kết quả |
| --- | --- | --- | --- |
| GET | `{{baseUrl}}/roadmaps` | USER | `200` tối đa 100 roadmap của user; không kèm toàn bộ node/snapshot. |
| GET | `{{baseUrl}}/roadmaps/{{roadmapId}}` | USER | `200` roadmap, `learning_scope`, `missing_skills` cho bản mới, `nodes[].prerequisites`, `nodes[].resources`, `skill_gaps`, `learning_recommendations`. |
| GET | `{{baseUrl}}/roadmaps/recommendations?targetJobTitle=Java%20Backend%20Developer&skills=Java&skills=SQL` | CANDIDATE | `200` gợi ý theo mục tiêu/kỹ năng; không tạo roadmap, không gọi AI. |

Query `targetJobTitle` bắt buộc, không trắng, tối đa 255 ký tự. `skills` tùy chọn, truyền nhiều key cùng tên;
tối đa 50 tên không trắng, mỗi tên tối đa 150 ký tự. API recommendations trực tiếp chỉ tra tài liệu
cho skill được truyền, không tự suy ra kỹ năng người dùng thiếu và không tạo kế hoạch.

Nguồn học mới có `learning_scope`, `focus_skills`, `roadmaps: []` và `resources`, được giới hạn theo skill.
Kế hoạch mới còn có `missing_skills` và `skills` nhóm resource theo từng tên skill.
Từng resource có provider riêng, gồm tài liệu chính thức hoặc YouTube/Coursera đã được Admin duyệt.

Để kiểm thử khóa học mới: Admin tạo link → approve → Candidate gọi recommendations với skill tương ứng. Việc hiển thị tài nguyên trong roadmap/recommendations dựa vào khóa đã duyệt/active; không cần đợi index RAG. Để assistant RAG tìm được khóa, cần index thành công riêng.

Resource trong recommendations có các dữ liệu như `id`, `title`, `url`, `provider`, `resource_type`, `is_free`, `metadata_json`;
không bảo đảm có field `language` trong response gợi ý trực tiếp. Resource snapshot dưới node lưu đầy đủ dữ liệu catalog,
có thể gồm `language`. Admin xem ngôn ngữ ở GET course. FE mở URL trả về, không suy URL từ tên khóa.

Lộ trình mới lưu snapshot của `learning_recommendations` và resource từng node. Mở lại giữ link/tên/provider đã gợi ý, kể cả sau này khóa bị archive hoặc catalog thay đổi. Lộ trình cũ chưa có snapshot có thể dùng fallback từ catalog hiện tại. API recommendations trực tiếp luôn đọc catalog hiện tại.

### 8.6. Cập nhật tiến độ node

`PATCH {{baseUrl}}/roadmaps/{{roadmapId}}/nodes/{{roadmapNodeId}}` — USER, JSON, `200`.

```json
{
  "status": "in_progress"
}
```

`status` bắt buộc, đúng một trong `not_started`, `in_progress`, `completed` (chữ thường). `roadmapNodeId` phải là node của `roadmapId`, roadmap thuộc user.

- Chuyển sang `in_progress` hoặc `completed`: mọi prerequisite phải `completed`.
- Chuyển một node khỏi `completed`, hoặc reset về `not_started`: các node phụ thuộc trực tiếp phải trở về `not_started` trước.
- Vi phạm dependency trả `409` (`Complete prerequisite nodes first` hoặc `Reset dependent nodes first`).
- Response trả lại toàn bộ roadmap, cập nhật `completed_at`, `progress_percent`; tất cả node xong thì roadmap `completed`, còn lại `ready`.

Trong response, `prerequisites` là mảng object chứa `prerequisite_node_id`, không phải mảng số thứ tự. Cần dùng UUID node thật để xác định thứ tự học.

<a id="section-9"></a>

## 9. Trợ lý AI cho Candidate

Nhóm này cần đăng nhập (USER), kiểm tra conversation/context thuộc user; dùng Bearer `{{candidateAccessToken}}` trong luồng Candidate. Đây là AI assistant; chat với Career Consultant dùng các route consultation ở mục riêng.

### 9.1. Danh sách, lịch sử và archive

Các route sau Body none.

| Method | URL | Thành công |
| --- | --- | --- |
| GET | `{{baseUrl}}/assistant/conversations` | `200` danh sách conversation của user, gồm status để phân biệt `ACTIVE`/`ARCHIVED`; không có query phân trang. |
| GET | `{{baseUrl}}/assistant/conversations/{{assistantConversationId}}` | `200` conversation, `messages` và `sources` đã lưu. |
| DELETE | `{{baseUrl}}/assistant/conversations/{{assistantConversationId}}` | `204`, body rỗng; đặt `ARCHIVED`, vẫn giữ lịch sử và nguồn. |

Conversation archive không nhận tin mới (`409`), vẫn có thể đọc lại bằng GET. Backend chưa có endpoint khôi phục/chỉnh sửa conversation trong controller này.

### 9.2. Tạo conversation

`POST {{baseUrl}}/assistant/conversations` — USER, JSON, `200`.

Payload tối thiểu:

```json
{}
```

Hoặc kèm ngữ cảnh lộ trình:

```json
{
  "title": "Tu van hoc Java Backend",
  "context_type": "roadmap",
  "related_roadmap_id": "{{roadmapId}}",
  "related_resume_id": "{{resumeId}}"
}
```

| Field | Bắt buộc/default và ràng buộc |
| --- | --- |
| `title` | Tùy chọn, tối đa 255 ký tự; mặc định tiêu đề tư vấn nghề nghiệp. Nếu gửi phải là string, không `null`. |
| `context_type` | Tùy chọn, mặc định `general`, tối đa 50 ký tự; hiện là string, không có enum cố định ở service. |
| `related_job_id` | Tùy chọn, UUID job active. |
| `related_resume_id` | Tùy chọn, UUID CV của user. |
| `related_gap_analysis_id` | Tùy chọn, UUID gap analysis của user. |
| `related_roadmap_id` | Tùy chọn, UUID roadmap của user. |

Bỏ các field context chưa dùng; có thể liên kết nhiều context hợp lệ cùng lúc. Response là conversation `ACTIVE`; lưu `id` vào `assistantConversationId`. Các ID context quyết định nội dung được nạp cho AI; chỉ đổi `context_type` không tự nạp tài nguyên.

### 9.3. Gửi tin nhắn, dùng RAG để gợi ý khóa học

`POST {{baseUrl}}/assistant/conversations/{{assistantConversationId}}/messages` — USER, JSON, `200`.

```json
{
  "message": "Gợi ý khóa học Java và SQL đã được duyệt, kèm link YouTube hoặc Coursera.",
  "retrieve": true
}
```

`message` bắt buộc, không trắng, tối đa 8.000 ký tự. `retrieve` tùy chọn, boolean, mặc định không truy xuất; nếu gửi thì không được `null` hoặc string `"true"`.

Nếu chỉ cần chat theo history/context, gửi:

```json
{
  "message": "Giải thích bước đầu trong lộ trình của tôi."
}
```

Gửi tin cần AI generation và quota hợp lệ; `retrieve: true` còn cần embedding hoạt động, tài liệu RAG index `COMPLETED` với đúng provider/model. Khóa học phải được Admin approve, resource active và tài liệu không disabled. Query RAG dùng ngưỡng similarity 0,55, tối đa 5 chunk; có thể không tìm được nguồn phù hợp.

Response là **tin trả lời ASSISTANT**, không phải toàn bộ conversation; lưu `id` vào `assistantMessageId`. Các field đáng dùng: `message_text`, `sender_type`, `retrieval_used`, `model_used`, token usage và `sources`.

Nguồn khóa học trong `sources` lưu `source_label`, `source_url`, `source_provider`, `rag_document_id`, `rag_chunk_id`, `similarity_score`. Link/provider được snapshot theo tin trả lời để đọc lịch sử sau này; nguồn RAG text thường không có URL khóa học có thể trả `null` ở các field link/provider.

`retrieve: true` là yêu cầu truy xuất, còn `retrieval_used: true` chỉ khi tìm được nguồn. FE nên render nguồn từ `sources` và kiểm tra `source_url` có giá trị, không giả định mọi câu trả lời đều có link.

Khi lượt AI thành công, backend lưu cả USER và ASSISTANT cùng citations, GET conversation đọc lại được. Service hiện chỉ ghi hai tin sau khi AI trả lời thành công; lượt gọi AI lỗi không được đảm bảo lưu tin USER. Lịch sử đưa vào model là 20 tin gần nhất, còn GET trả lịch sử đã lưu đầy đủ.

### 9.4. Đánh giá câu trả lời AI

`POST {{baseUrl}}/assistant/messages/{{assistantMessageId}}/feedback` — USER, JSON, `200`.

```json
{
  "rating": 5,
  "is_helpful": true,
  "feedback_text": "Nguồn học phù hợp và có link rõ ràng."
}
```

`rating` tùy chọn, số nguyên 1..5; `is_helpful` tùy chọn, boolean; `feedback_text` tùy chọn, tối đa 4.000 ký tự. DTO không yêu cầu phải có ít nhất một field, nhưng nên gửi đánh giá có ý nghĩa.

ID phải là tin `ASSISTANT` của conversation thuộc user, không phải `assistantConversationId` hoặc tin USER. POST này tạo bản ghi feedback; không phải PUT/upsert phản hồi matching.


<a id="section-10"></a>

## 10. Tư vấn Candidate–Consultant

Các API tư vấn dùng prefix `/api/v1/consultations`; API phân công của Admin cũng nằm dưới prefix này.
Tất cả request cần Bearer token. Candidate chỉ tạo yêu cầu cho chính mình.
Admin xem toàn bộ; người dùng khác chỉ xem thread khi là Candidate của thread hoặc Consultant đang được phân công.
Quyền này được kiểm tra ở service/repository, không suy ra từ tên controller hay URL.

### 10.1. Tạo yêu cầu tư vấn

`POST {{baseUrl}}/consultations` — **CANDIDATE**.

```json
{
  "subject": "Tư vấn chuyển sang Java Backend",
  "requestType": "CAREER_ORIENTATION",
  "description": "Mình cần góp ý CV và hướng học để ứng tuyển Junior Java."
}
```

| Field | Quy tắc |
|---|---|
| `subject` | Bắt buộc, không trắng, tối đa 255 ký tự |
| `requestType` | Không bắt buộc, tối đa 50 ký tự; hiện chưa có enum cố định |
| `description` | Bắt buộc, không trắng, tối đa 10.000 ký tự |

Response là thread, các field lưu trữ dùng snake_case như `id`, `candidate_user_id`, `consultant_user_id`,
`subject`, `request_type`, `request_description`, `status`, `priority`, `created_at`, `closed_at`.
Lưu `id` vào `threadId`. Thread mới có trạng thái `OPEN`, priority `NORMAL`.

### 10.2. Danh sách, chi tiết, hồ sơ và lịch sử phân tích

| Method | URL | Query / kết quả |
|---|---|---|
| GET | `{{baseUrl}}/consultations` | Tối đa 100 thread mới nhất; Admin xem toàn bộ, người khác xem thread liên quan |
| GET | `{{baseUrl}}/consultations/{{threadId}}` | Chi tiết thread có quyền truy cập |
| GET | `{{baseUrl}}/consultations/{{threadId}}/candidate-profile` | `resumeId` UUID tùy chọn; bỏ qua để lấy CV chính ở trạng thái `READY` |
| GET | `{{baseUrl}}/consultations/{{threadId}}/candidate-analyses` | `resumeId`, `jobId` UUID tùy chọn; `limit=20` (1–100), `offset=0` (≥0) |
| GET | `{{baseUrl}}/consultations/{{threadId}}/messages` | `limit=50` (1–100), `offset=0` (0–100.000); tin nhắn theo `sent_at,id` tăng dần |
| GET | `{{baseUrl}}/consultations/{{threadId}}/events` | SSE; xem [SSE tư vấn](#sse-tu-van) |

`candidate-profile` trả `{ "candidate": {...}, "career_profile": {...}, "resume": {...} }`.
`candidate-analyses` đọc các phân tích đã lưu của Candidate, không gọi AI để tạo phân tích mới.
Candidate chủ thread, Consultant được phân công và Admin đều có thể đọc.
Nếu gửi `resumeId`, CV phải thuộc Candidate và đang `READY`.

Các khóa response của `candidate-analyses`:

```json
{
  "candidate_id": "uuid",
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

`limit`/`offset` áp dụng riêng cho từng nhóm kết quả; đây không phải response phân trang có `total`.

### 10.3. Admin phân công hoặc đổi Consultant

`PUT {{baseUrl}}/consultations/{{threadId}}/consultant` — **ADMIN**.

```json
{
  "consultantId": "{{consultantId}}"
}
```

`consultantId` bắt buộc là UUID của tài khoản `CAREER_CONSULTANT` đang `ACTIVE`.
Thread phải còn `OPEN`. Response là thread sau khi phân công.
Khi đổi người phụ trách, các kết nối SSE hiện tại của thread được đóng để kết nối lại theo quyền mới.

### 10.4. Gửi tin nhắn và đánh dấu đã đọc

`POST {{baseUrl}}/consultations/{{threadId}}/messages`.

```json
{
  "content": "Mình gửi thêm thông tin về mục tiêu công việc."
}
```

`content` bắt buộc, không trắng, tối đa 10.000 ký tự.
Candidate chủ thread, Consultant được phân công và Admin có thể gửi khi thread `OPEN`.
Response là tin đã lưu, gồm `id`, `thread_id`, `sender_user_id`, `content`, `sent_at`, `read_at`.
Lưu `id` vào `consultationMessageId`.

| Method | URL | Body / kết quả |
|---|---|---|
| PATCH | `{{baseUrl}}/consultations/{{threadId}}/messages/{{consultationMessageId}}/read` | Không body; `{ "id": "uuid", "read": true }` |
| POST | `{{baseUrl}}/consultations/{{threadId}}/close` | Không body; trả thread sau khi đóng |

Đánh dấu đọc chỉ cập nhật tin do người khác gửi; tin phải thuộc đúng thread.
Đóng thread chuyển sang `CLOSED`, giữ lịch sử và đóng SSE.
Gửi tin vào thread đã đóng trả `409`. Hiện không có endpoint mở lại thread.

### 10.5. Luồng test hai tài khoản

1. Candidate tạo thread và lưu `threadId`.
2. Admin dùng token Admin để phân công `consultantId`.
3. Consultant đọc danh sách, hồ sơ và `candidate-analyses` của thread.
4. Candidate và Consultant mở SSE hoặc đọc lịch sử rồi gửi tin bằng token tương ứng.
5. Người nhận đánh dấu tin đã đọc; kiểm tra lại GET lịch sử.
6. Đóng thread, mở lại trang lịch sử và xác nhận tin đã lưu vẫn còn.

<a id="section-11"></a>

## 11. Thông báo và hỗ trợ

### 11.1. Thông cáo và thông báo của người dùng

Các request dưới đây cần Bearer token của người đang xem.

| Method | URL | Query / kết quả |
|---|---|---|
| GET | `{{baseUrl}}/announcements` | Thông cáo đã publish, còn hiệu lực và đúng đối tượng |
| GET | `{{baseUrl}}/notifications` | `unreadOnly=false`; đổi thành `true` để chỉ lấy chưa đọc |
| PATCH | `{{baseUrl}}/notifications/{{notificationId}}/read` | Không body; chỉ tác động thông báo của chính mình |
| POST | `{{baseUrl}}/notifications/read-all` | Không body; đánh dấu toàn bộ thông báo của chính mình |

Thông cáo (`announcements`) là nội dung Admin xuất bản.
Thông báo (`notifications`) là bản ghi gửi đến từng người dùng, có trạng thái đọc.

### 11.2. Admin quản lý thông cáo

**ADMIN**, prefix `/api/v1/admin/announcements`.

| Method | URL | Body / kết quả |
|---|---|---|
| GET | `{{baseUrl}}/admin/announcements` | Danh sách cả bản nháp, đã publish và archive |
| POST | `{{baseUrl}}/admin/announcements` | JSON bên dưới; tạo bản `DRAFT` |
| PUT | `{{baseUrl}}/admin/announcements/{{announcementId}}` | Cùng schema tạo; chỉ sửa được `DRAFT` |
| POST | `{{baseUrl}}/admin/announcements/{{announcementId}}/publish` | Không body; xuất bản bản nháp |
| POST | `{{baseUrl}}/admin/announcements/{{announcementId}}/archive` | Không body; ngừng hiển thị |

Body tạo hoặc sửa:

```json
{
  "title": "Thông báo cập nhật dịch vụ",
  "content": "Nội dung thông báo cho người dùng.",
  "expiresAt": null,
  "targets": [
    {
      "type": "ROLE",
      "role": "CANDIDATE"
    }
  ]
}
```

| Field | Quy tắc |
|---|---|
| `title` | Bắt buộc, tối đa 255 ký tự |
| `content` | Bắt buộc, tối đa 50.000 ký tự |
| `expiresAt` | `null` hoặc thời điểm tương lai dạng ISO-8601 có offset |
| `targets` | Bắt buộc 1–100 phần tử |
| `targets[].type` | `ALL`, `ROLE` hoặc `USER` |
| `targets[].role` | Với `ROLE`: `CANDIDATE`, `CAREER_CONSULTANT` hoặc `ADMIN` |
| `targets[].userId` | Với `USER`: UUID tài khoản nhận |

Ví dụ đối tượng khác: `{ "type": "ALL" }` hoặc `{ "type": "USER", "userId": "{{candidateId}}" }`.
Không gửi field `status` trong body; dùng endpoint publish/archive để chuyển trạng thái.
Bản đã publish/archive không sửa bằng PUT được. Publish bản hết hạn bị từ chối.

### 11.3. Tạo ticket hỗ trợ

`POST {{baseUrl}}/support/tickets` — người dùng đã đăng nhập.

```json
{
  "subject": "Không xem được kết quả phân tích",
  "category": "TECHNICAL",
  "description": "Mình gặp lỗi khi mở lại kết quả CV đã phân tích."
}
```

`subject`: bắt buộc, tối đa 255; `category`: bắt buộc, tối đa 50, hiện chưa có enum cố định;
`description`: bắt buộc, tối đa 10.000 ký tự.
Ticket mới có `status=OPEN`, `priority=NORMAL`. Lưu `id` vào `ticketId`.

| Method | URL | Kết quả |
|---|---|---|
| GET | `{{baseUrl}}/support/tickets` | Admin xem toàn bộ; người khác chỉ xem ticket của mình |
| GET | `{{baseUrl}}/support/tickets/{{ticketId}}` | Chi tiết và mảng `messages` |

### 11.4. Trao đổi trong ticket

`POST {{baseUrl}}/support/tickets/{{ticketId}}/messages` — chủ ticket hoặc Admin.

```json
{
  "content": "Mình bổ sung thời điểm và các bước gặp lỗi."
}
```

`content` bắt buộc, tối đa 10.000 ký tự. Response là message đã lưu.
Admin trả lời ticket của người khác sẽ tạo notification `SUPPORT_REPLY` cho chủ ticket.
Ticket `CLOSED` không nhận thêm tin (`409`); `RESOLVED` vẫn có thể trao đổi.

### 11.5. Admin cập nhật ticket

`PATCH {{baseUrl}}/support/tickets/{{ticketId}}` — **ADMIN**.
Endpoint này không nằm dưới `/admin`, nhưng service vẫn yêu cầu quyền Admin.

```json
{
  "status": "IN_PROGRESS",
  "priority": "HIGH",
  "assignedAdminId": "{{adminId}}"
}
```

| Field | Quy tắc |
|---|---|
| `status` | Bắt buộc: `OPEN`, `IN_PROGRESS`, `RESOLVED`, `CLOSED` |
| `priority` | Bắt buộc: `LOW`, `NORMAL`, `HIGH`, `URGENT` |
| `assignedAdminId` | UUID tài khoản Admin được phân công, hoặc `null` để bỏ phân công |

Dù method là PATCH, phải gửi cả `status` và `priority`.
Response là chi tiết ticket kèm messages.

<a id="section-12"></a>

## 12. Admin: tài khoản, gói và cấu hình AI

Toàn bộ mục này yêu cầu token **ADMIN**, prefix `/api/v1/admin`.
Các response dạng Map thường giữ tên cột snake_case; request dùng đúng camelCase trong ví dụ.

### 12.1. Dashboard và tài khoản

| Method | URL | Query / kết quả |
|---|---|---|
| GET | `{{baseUrl}}/admin/dashboard` | `users`, `activeSubscriptions`, `revenue`, `aiUsage`, `openSupportTickets` |
| GET | `{{baseUrl}}/admin/users` | `search=""` (≤255 ký tự), `page=0` (≥0), `size=20` (1–100); trả array |
| GET | `{{baseUrl}}/admin/users/{{adminUserId}}` | Chi tiết tài khoản |
| DELETE | `{{baseUrl}}/admin/users/{{adminUserId}}` | Không body; xóa mềm tài khoản |

Tạo tài khoản: `POST {{baseUrl}}/admin/users`.

```json
{
  "email": "{{consultantEmail}}",
  "fullName": "Nguyễn Tư Vấn",
  "role": "CAREER_CONSULTANT",
  "temporaryPassword": "{{temporaryPassword}}"
}
```

| Field | Quy tắc |
|---|---|
| `email` | Bắt buộc, email hợp lệ, ≤255 ký tự; được trim và chuyển chữ thường |
| `fullName` | Bắt buộc, ≤255 ký tự |
| `role` | Bắt buộc: `CANDIDATE`, `CAREER_CONSULTANT`, `ADMIN` |
| `temporaryPassword` | Bắt buộc, 10–72 ký tự và không quá 72 byte UTF-8 |

Email trùng trả `409`. Tài khoản tạo bởi Admin được kích hoạt và xác minh email ngay.
Không dùng mật khẩu mẫu cho tài khoản thật.
Lưu `id` response vào `adminUserId`; với Consultant, lưu thêm vào `consultantId`.
Đặt `consultantPassword` bằng `temporaryPassword` vừa gửi trước khi đăng nhập tài khoản này.

Sửa tài khoản: `PUT {{baseUrl}}/admin/users/{{adminUserId}}`.

```json
{
  "fullName": "Nguyễn Tư Vấn",
  "role": "CAREER_CONSULTANT",
  "status": "ACTIVE"
}
```

Ba field đều bắt buộc; `fullName` ≤255, `role` như lúc tạo, `status` là `ACTIVE` hoặc `SUSPENDED`.
Không gửi `email` hay mật khẩu vào schema cập nhật.
Đổi role hoặc đình chỉ sẽ thu hồi phiên đăng nhập của tài khoản bị cập nhật.
Backend bảo vệ Admin hoạt động cuối cùng và không khôi phục tài khoản `DELETED` bằng PUT.

### 12.2. Gói đăng ký

| Method | URL | Body / kết quả |
|---|---|---|
| GET | `{{baseUrl}}/admin/subscription-tiers` | Danh sách gói; response này chưa gộp quota |
| POST | `{{baseUrl}}/admin/subscription-tiers` | Tạo gói mới bằng schema bên dưới |
| PUT | `{{baseUrl}}/admin/subscription-tiers/{{managedTierId}}` | Sửa thông tin gói, cùng schema |

```json
{
  "tierCode": "PLUS_2026",
  "displayName": "Plus 2026",
  "priceMonthly": 99000,
  "currency": "VND",
  "active": true
}
```

| Field | Quy tắc |
|---|---|
| `tierCode` | Bắt buộc, `[A-Z0-9_]{2,30}` |
| `displayName` | Bắt buộc, ≤100 ký tự |
| `priceMonthly` | Bắt buộc, 0–99.999.999,99; tối đa 8 chữ số nguyên và 2 thập phân |
| `currency` | Bắt buộc, đúng 3 chữ cái in hoa |
| `active` | Boolean, nên gửi rõ |

Quy ước nghiệp vụ mong muốn là giữ gói Free và tạo gói mới khi đổi chính sách.
Backend hiện chỉ ép gói `FREE` giữ code `FREE`, giá 0 và `active=true`;
vẫn cho sửa tên hiển thị, tiền tệ và quota Free. Không coi Free đã bị khóa hoàn toàn.

### 12.3. Cập nhật quota của gói

`PUT {{baseUrl}}/admin/subscription-tiers/{{managedTierId}}/quotas`.
Body là **array JSON**, không bọc trong object `quotas`:

```json
[
  {
    "featureCode": "AI_TOTAL_TOKENS",
    "quotaValue": 2000000,
    "quotaPeriod": "MONTHLY"
  },
  {
    "featureCode": "CAREER_CHAT",
    "quotaValue": 200000,
    "quotaPeriod": "MONTHLY"
  },
  {
    "featureCode": "CV_ANALYZE",
    "quotaValue": 100000,
    "quotaPeriod": "MONTHLY"
  }
]
```

Tối đa 100 phần tử; không trùng `featureCode` trong cùng request.
`featureCode`: `[A-Z0-9_]{2,100}`; `quotaValue` ≥0;
`quotaPeriod`: `DAILY`, `MONTHLY`, `YEARLY`, `LIFETIME`.
Đây là upsert các feature được gửi, không xóa các quota khác khi bỏ khỏi array.
Hạn mức AI được tính theo token; không diễn giải `quotaValue` thành số lần gọi API.
Dùng feature code có trong catalog hiện hành của hệ thống.
`AI_TOTAL_TOKENS` là hạn mức tổng bắt buộc để gói dùng được AI; thiếu quota này làm bước reserve trả `503`.
Các quota theo feature bổ sung giới hạn riêng; quota feature bằng 0 chặn feature đó.
Sau khi tạo gói mới, lưu `id` vào `managedTierId` rồi cấu hình quota trước khi thử sử dụng gói.

### 12.4. Cấu hình AI theo phiên bản

| Method | URL | Body / kết quả |
|---|---|---|
| GET | `{{baseUrl}}/admin/ai/configurations` | Danh sách các phiên bản cấu hình |
| POST | `{{baseUrl}}/admin/ai/configurations` | Tạo và kích hoạt phiên bản mới |
| POST | `{{baseUrl}}/admin/ai/configurations/{{aiConfigurationId}}/restore` | Không body; sao chép phiên bản cũ thành phiên bản mới |

Body tạo cấu hình:

```json
{
  "configType": "POSTMAN_TEST",
  "provider": "{{aiProvider}}",
  "modelName": "{{aiChatModel}}",
  "configuration": {
    "temperature": 0.2
  }
}
```

`configType`: bắt buộc, `[A-Z_]{2,50}`; `provider`: bắt buộc ≤100;
`modelName`: bắt buộc ≤150; `configuration`: object bắt buộc.
Ví dụ dùng nhóm `POSTMAN_TEST` để thử tạo/restore phiên bản mà không thay nhóm cấu hình CHAT đang chạy.
Điền provider/model từ runtime vào hai biến trước khi gửi. Khi chủ động cập nhật cấu hình chat thực,
đổi `configType` thành `CHAT` và chọn provider/model phù hợp với triển khai.
Runtime hiện chọn model từ bản active `configType=CHAT` có provider khớp provider triển khai.
Credentials phải cấu hình bằng biến môi trường, không gửi API key, secret hay password trong `configuration`.
Backend từ chối nội dung có các chuỗi `api_key`, `apikey`, `secret`, `password`.

POST tạo bản `version` tiếp theo cho cùng `configType`, đánh dấu bản cũ `is_active=false`.
Restore nhận **UUID `id` của bản cũ**, không nhận số `version`.
Restore tạo một bản mới đang active, giữ nội dung bản cũ trong lịch sử.
Việc lưu cấu hình không đồng nghĩa mọi field có hiệu lực runtime ngay; đặc biệt embedding runtime vẫn phụ thuộc cấu hình triển khai.

### 12.5. Prompt theo phiên bản

| Method | URL | Body / kết quả |
|---|---|---|
| GET | `{{baseUrl}}/admin/ai/prompts` | Danh sách prompt và phiên bản |
| POST | `{{baseUrl}}/admin/ai/prompts` | Tạo phiên bản mới, kích hoạt cho `featureCode` |
| POST | `{{baseUrl}}/admin/ai/prompts/{{aiPromptId}}/restore` | Không body; tạo phiên bản mới từ bản cũ |

```json
{
  "featureCode": "POSTMAN_GUIDE_TEST",
  "name": "Postman Guide Test Prompt",
  "systemPrompt": "Bạn là trợ lý tư vấn nghề nghiệp. Trả kết quả theo schema của hệ thống.",
  "userPromptTemplate": "Đọc dữ liệu được cung cấp và trả câu trả lời phù hợp."
}
```

| Field | Quy tắc |
|---|---|
| `featureCode` | Bắt buộc, `[A-Z0-9_]{2,100}` |
| `name` | Bắt buộc, ≤150 ký tự |
| `systemPrompt` | Bắt buộc, ≤50.000 ký tự |
| `userPromptTemplate` | Bắt buộc, ≤50.000 ký tự |

Body dùng feature thử riêng `POSTMAN_GUIDE_TEST` để kiểm tra tạo/restore phiên bản mà không đổi prompt đang dùng.
Giữ output schema và dữ liệu template theo feature đang chạy khi tạo phiên bản thực tế.
API không có PUT để ghi đè nội dung bản prompt ổn định.
Mỗi POST tăng version cho feature, kích hoạt bản mới và giữ nội dung bản cũ.
`aiPromptId` khi restore là UUID `id`, không phải số version; restore cũng tạo bản mới.

### 12.6. Monitoring và phản ánh thanh toán

| Method | URL | Body / kết quả |
|---|---|---|
| GET | `{{baseUrl}}/admin/monitoring/{{monitoringCategory}}` | Array dữ liệu theo category bên dưới |
| GET | `{{baseUrl}}/admin/payment-reports` | Các phản ánh thanh toán do người dùng gửi |
| POST | `{{baseUrl}}/admin/payment-reports/{{paymentReportId}}/resolve` | JSON `resolution` bên dưới |

| `category` | Dữ liệu |
|---|---|
| `ai-usage` | Log sử dụng AI |
| `vector-stores` | Chỉ số vector store |
| `indexing-jobs` | Tác vụ index |
| `logs` | Log hệ thống |
| `audit` | Lịch sử thao tác quản trị |
| `billing` | Đơn thanh toán |
| `crawl-runs` | Các lượt thu thập việc làm |

Category khác trả `400`. Các endpoint này chưa có query phân trang ở controller.
`payment-reports` là **phản ánh/khiếu nại giao dịch**, không phải báo cáo thống kê doanh thu.

```json
{
  "resolution": "Đã đối chiếu giao dịch và phản hồi kết quả cho người dùng."
}
```

`resolution` bắt buộc, không trắng, tối đa 10.000 ký tự.
Response là phản ánh sau xử lý; endpoint này không tự phát lệnh hoàn tiền qua cổng thanh toán.

<a id="admin-resume-templates"></a>

### 12.7. Quản trị template CV

**Bearer Admin**, prefix `{{baseUrl}}/admin/resume-templates`: GET danh sách (mặc định
`limit=50&offset=0`, tối đa 100 mục), GET `/{id}`, POST tạo, PUT `/{id}` thay thế đầy đủ và
PATCH `/{id}/status` với `{ "active": false }` hoặc `true`. Không có hard DELETE template.

Request dùng `name`, `careerPath`, `language`, `description`, `active`, `trialEnabled` và **`htmlContent` bắt buộc**;
response metadata snake_case với `html_content` là HTML/CSS đã kiểm tra. Từ V19 không còn `layoutConfig`/`layout_config`.
HTML tối đa 200.000 ký tự, phải có slot `fullName` và ít nhất một section hợp lệ; không nhận script hoặc tài nguyên ngoài.
Admin có thể **POST `{{baseUrl}}/admin/resume-templates/import`**, Body `form-data` với `file` (File PDF/DOC/DOCX)
và `language` (Text vi/en, mặc định vi). Để Postman tự đặt Content-Type multipart. Giới hạn 10 MB, PDF tối đa
10 trang; tài liệu phải có text và không mã hóa/hỏng. Response gồm HTML bản nháp, các khối, cảnh báo và
`requires_review=true`; chưa ghi template hoặc gọi AI. Admin sửa/gắn slot, xem lại rồi POST/PUT `htmlContent`
theo hợp đồng lưu ở trên. Không hỗ trợ PPTX/OCR trong luồng này; không cam kết bố cục giống từng pixel.
PDF giữ chữ dưới dạng HTML theo tọa độ và phần đồ họa/ảnh tĩnh dưới dạng PNG nhúng; dùng font hệ thống,
CSS tự chứa, không tải Bootstrap/CDN. Ảnh Word chưa được nhập. PNG tối đa 200.000 byte, 2.048 px mỗi chiều,
4.000.000 pixel; cả HTML gồm base64 vẫn tối đa 200.000 ký tự. Bố cục PDF cố định không tự phân trang khi
nội dung Candidate dài hơn; cần xem thử dữ liệu, sửa vùng nội dung và xóa/thay ảnh/chữ mẫu trước khi lưu.
Endpoint download CV vẫn không tự xuất PDF.
Xem [thứ tự import → biên tập → lưu, quy ước slot và JSON Postman](html-resume-templates.md).

Tắt mẫu ẩn nó khỏi `GET /resumes/templates` và ngăn chọn cho CV mới; CV cũ giữ `template_id`.
Xem [contract, JSON tạo/sửa, validation và thứ tự test public/FK](admin-resume-templates.md).

### 12.8. Biên độ lương gợi ý việc làm

**Bearer Admin**:

```http
GET {{baseUrl}}/admin/matching/salary-policy
PUT {{baseUrl}}/admin/matching/salary-policy
```

Body PUT raw JSON:

```json
{
  "tolerancePercent": 20
}
```

`tolerancePercent` là JSON number từ 0 đến 100, tối đa hai chữ số thập phân. Expected `200` với
`tolerancePercent`, `minimumRatio`, `version`, `policyVersion`, `updatedAt` và `updatedBy` nếu đã có người chỉnh.
Mặc định 10%, version 1. Ví dụ 20% cho phép job trả từ 80% lương kỳ vọng trở lên, không có trần phía trên.
Lưu thực sự thay đổi giá trị sẽ tăng version và ghi audit; cấu hình tồn tại qua restart.

Candidate chỉ đọc bằng **GET `{{baseUrl}}/matching/salary-policy`** với token Candidate; không được gọi PUT Admin.
Thay đổi biên độ áp dụng cho lượt matching mới và đổi cache key; lịch sử giữ snapshot cũ.
Xem [hướng dẫn và ví dụ 7,5 triệu](admin-salary-policy.md).

<a id="section-13"></a>

## 13. Admin: thu thập và index việc làm

Tất cả request trong mục này dùng token **ADMIN**.
Import, crawl, đổi trạng thái và index là các thao tác riêng; lưu bản ghi chưa bảo đảm vector đã sẵn sàng.

### 13.1. Import nội dung việc làm

`POST {{baseUrl}}/admin/jobs`.

```json
{
  "sourceSite": "ITVIEC",
  "sourceUrl": "https://itviec.com/it-jobs/example-java-backend-123456",
  "jobTitle": "Java Backend Developer",
  "companyName": "Example Company",
  "location": "Ho Chi Minh",
  "salaryText": "Negotiable",
  "roleCategory": "BACKEND",
  "content": "Develop Java backend services with Spring Boot and PostgreSQL.",
  "skills": [
    {
      "name": "Java",
      "required": true
    },
    {
      "name": "Spring Boot",
      "required": true
    }
  ],
  "employmentType": "FULL_TIME",
  "requirements": "Knowledge of Java, REST APIs and SQL.",
  "responsibilities": "Implement and maintain backend services.",
  "benefits": "Training opportunities.",
  "postedAt": null,
  "validThrough": null
}
```

URL trên minh họa định dạng; thay bằng URL nguồn thật đang quản lý khi import dữ liệu thật.
API nhận nội dung đã có, không tự lấy nội dung từ `sourceUrl` như endpoint crawl.

| Field | Quy tắc |
|---|---|
| `sourceSite` | DTO bắt buộc ≤50; service hỗ trợ `ITVIEC` hoặc `TOPCV` |
| `sourceUrl` | Bắt buộc ≤2.000; HTTPS, tên miền phải khớp nguồn |
| `jobTitle` | Bắt buộc ≤255 |
| `companyName`, `location`, `salaryText` | Tùy chọn, mỗi field ≤255 |
| `roleCategory` | Bắt buộc ≤100 |
| `content` | Bắt buộc ≤100.000 |
| `skills` | Tùy chọn, tối đa 100 phần tử |
| `skills[].skillId` | UUID skill có sẵn, tùy chọn |
| `skills[].name` | Bắt buộc ngay cả khi có `skillId`, ≤100 |
| `skills[].required` | Boolean |
| `employmentType` | Tùy chọn ≤50 |
| `requirements`, `responsibilities`, `benefits` | Tùy chọn, mỗi field ≤100.000 |
| `postedAt`, `validThrough` | Tùy chọn, ISO-8601 có offset |

Response có định danh job dùng để lưu `jobId` và kiểm tra index tiếp theo.
Dữ liệu được chuẩn hóa/khử trùng lặp theo nội dung và nguồn; không giả định mỗi lần POST đều tạo job mới.
`content` sau khi bỏ HTML phải có ít nhất 30 ký tự; `validThrough` nếu có phải nằm trong tương lai.
Response có `deduplicated=true/false` và trả job kèm skills/sources.

### 13.2. Trạng thái và index job

| Method | URL | Body / kết quả |
|---|---|---|
| POST | `{{baseUrl}}/admin/jobs/{{jobId}}/index` | Không body; trả tác vụ index; `id` là `indexingJobId`, không phải `jobId` |
| GET | `{{baseUrl}}/admin/jobs/{{jobId}}/index` | `{ "job_id": "uuid", "indexing_jobs": [...] }` |
| PATCH | `{{baseUrl}}/admin/jobs/{{jobId}}/status` | JSON trạng thái bên dưới |

```json
{
  "status": "EXPIRED"
}
```

`status` bắt buộc: `ACTIVE`, `EXPIRED`, `REMOVED`, `INVALID`.
Chuyển khỏi `ACTIVE` ngừng sử dụng embedding của job.
Đưa job về `ACTIVE` sẽ xếp tác vụ index, nhưng bị từ chối nếu `valid_through` đã qua;
khi nguồn có hạn mới cần import phiên bản nguồn mới.
Theo dõi cả trạng thái job và `indexing_jobs`, không dùng một trạng thái thay cho cả hai.
POST index chỉ nhận job đang active, chưa hết hạn; embedding tắt trả `503`.
Đọc `status` của tác vụ trả về để xác nhận kết quả, không mặc định POST thành công nghĩa là đã `COMPLETED`.

### 13.3. Crawl thủ công

`POST {{baseUrl}}/admin/jobs/crawl`.

```json
{
  "sourceSite": "ITVIEC",
  "keyword": "Java Backend",
  "roleCategory": "BACKEND",
  "location": "Ho Chi Minh",
  "urls": [],
  "limit": 10,
  "maxPages": 2
}
```

| Field | Quy tắc |
|---|---|
| `sourceSite` | Bắt buộc, `ITVIEC` hoặc `TOPCV` |
| `keyword` | Bắt buộc, ≤255 |
| `roleCategory` | Bắt buộc, ≤100 |
| `location` | Tùy chọn, ≤120 |
| `urls` | Tùy chọn, tối đa 20 URL chi tiết; mỗi URL không trắng, ≤2.000 |
| `limit` | 1–20; bỏ qua hoặc `null` mặc định 20 |
| `maxPages` | 1–5; bỏ qua hoặc `null` mặc định 3 |

Không có `urls` hoặc array rỗng: tìm URL theo keyword/location, tối đa `maxPages`, lấy tối đa `limit` job.
Có `urls`: crawl trực tiếp danh sách URL chi tiết đã chỉ định, không cần khám phá trang tìm kiếm.
URL phải là HTTPS của `itviec.com`/`www.itviec.com` hoặc `topcv.vn`/`www.topcv.vn` tương ứng nguồn.
Backend kiểm tra nguồn, URL và redirect; không phải API tải URL bất kỳ.
Lượt crawl thực sự gọi mạng đến nguồn ngoài, có thể gặp lỗi nguồn/chặn truy cập.

| Method | URL | Query / kết quả |
|---|---|---|
| GET | `{{baseUrl}}/admin/jobs/crawl` | `limit=20` (1–100), `offset=0` (0–100.000) |
| GET | `{{baseUrl}}/admin/jobs/crawl/{{crawlRunId}}` | Chi tiết một lượt crawl và các item |
| GET | `{{baseUrl}}/admin/crawl-history` | Cùng lịch sử, cùng `limit`/`offset` |

Kiểm tra kết quả từng item và các số liệu của run; HTTP thành công không có nghĩa mọi nguồn đều import thành công.
Theo dõi index job riêng sau khi thu thập.

### 13.4. Cấu hình crawl tự động

| Method | URL | Body / kết quả |
|---|---|---|
| GET | `{{baseUrl}}/admin/crawl-settings` | Cấu hình hiệu lực và metadata phiên bản |
| PUT | `{{baseUrl}}/admin/crawl-settings` | Thay cấu hình bằng JSON bên dưới |

Ba endpoint `/crawl-settings` (GET/PUT), `/crawl-history` (GET) còn có alias prefix `/api/admin`.
Hướng dẫn dùng prefix chuẩn `/api/v1/admin` để đồng nhất collection.

```json
{
  "enabled": false,
  "fixedDelay": "PT6H",
  "actorId": "{{adminId}}",
  "sources": [
    {
      "sourceSite": "ITVIEC",
      "keyword": "Java Backend",
      "roleCategory": "BACKEND",
      "location": "Ho Chi Minh",
      "limit": 20,
      "maxPages": 3
    },
    {
      "sourceSite": "TOPCV",
      "keyword": "Java",
      "roleCategory": "BACKEND",
      "location": null,
      "limit": 10,
      "maxPages": 2
    }
  ]
}
```

| Field | Quy tắc |
|---|---|
| `enabled` | Boolean bắt buộc |
| `fixedDelay` | Bắt buộc, duration ISO-8601 từ `PT1M` đến `P30D` |
| `actorId` | UUID Admin đang active; bỏ qua/`null` dùng Admin đang gọi API |
| `sources` | Array bắt buộc, tối đa 20; khi enabled phải có ít nhất 1 nguồn |
| `sources[].sourceSite` | Bắt buộc: `ITVIEC`, `TOPCV` |
| `sources[].keyword` | Bắt buộc ≤255 |
| `sources[].roleCategory` | Bắt buộc ≤100 |
| `sources[].location` | Tùy chọn ≤120; nếu có phải là tên địa điểm hợp lệ |
| `sources[].limit` | 1–20, mặc định 20 |
| `sources[].maxPages` | 1–5, mặc định 3 |

PUT phải gửi đủ `enabled`, `fixedDelay`, `sources`, kể cả chỉ muốn đổi một field.
Không gửi `version` trong request; server tự tăng phiên bản mỗi lần lưu.
Không được có nguồn trùng bộ source/keyword/role/location sau chuẩn hóa.
`actorId` là người đại diện cho tác vụ tự động; `updatedBy` vẫn là Admin thực sự cập nhật.

Response dùng camelCase:

```json
{
  "enabled": false,
  "fixedDelay": "PT6H",
  "actorId": "uuid",
  "sources": [],
  "configurationSource": "DATABASE",
  "version": 1,
  "updatedBy": "uuid",
  "updatedAt": "2026-10-15T03:00:00Z"
}
```

Nếu chưa lưu trong database, `configurationSource=ENVIRONMENT`, `version=0` và metadata cập nhật có thể `null`.
Sau PUT, nguồn là `DATABASE` và có version do server quản lý.
Bật `enabled=true` cho phép scheduler thực hiện các đợt crawl tiếp theo.

<a id="section-14"></a>

## 14. Admin: RAG và khóa học đã duyệt

Tất cả endpoint mục này yêu cầu **ADMIN**.
Tài liệu RAG là nội dung tri thức; khóa học có thêm bước duyệt để đưa link vào catalog gợi ý.

### 14.1. Tài liệu RAG

| Method | URL | Body / kết quả |
|---|---|---|
| GET | `{{baseUrl}}/admin/rag/documents` | Danh sách tài liệu |
| POST | `{{baseUrl}}/admin/rag/documents` | Tạo tài liệu từ nội dung text |
| GET | `{{baseUrl}}/admin/rag/documents/{{ragDocumentId}}` | Chi tiết và `chunks` |
| POST | `{{baseUrl}}/admin/rag/documents/{{ragDocumentId}}/index` | Không body; tạo embedding/index |
| POST | `{{baseUrl}}/admin/rag/documents/{{ragDocumentId}}/archive` | Không body; vô hiệu hóa retrieval |

Body tạo:

```json
{
  "title": "Hướng dẫn học Java Backend",
  "content": "Nội dung kỹ năng, điều kiện tiên quyết và kiến thức cần học.",
  "sourceType": "ADMIN",
  "metadata": {
    "topic": "java-backend",
    "language": "vi"
  }
}
```

`title` bắt buộc ≤255; `content` bắt buộc ≤100.000 ký tự.
`sourceType` có thể bỏ qua; nếu gửi phải đúng `ADMIN`, không được `null`.
`metadata` có thể bỏ qua; nếu gửi phải là object, không được `null`.
Không gửi file upload hay URL và mong hệ thống tự đọc trang/transcript: API này nhập text đã chuẩn bị.
Server chia nội dung thành chunks và khởi tạo `embedding_status=PENDING`.

Index cần embedding provider đang bật; nếu bị tắt trả `503`.
Theo dõi `embedding_status`: `PENDING`, `PROCESSING`, `COMPLETED`, `FAILED`.
Có thể gọi lại index khi `FAILED` hoặc `COMPLETED`; khi đang `PROCESSING` bị từ chối `409`.
Archive đặt `metadata.disabled=true` và trạng thái embedding `FAILED`;
vì vậy cần đọc `metadata.disabled` để phân biệt bị archive với index lỗi.
Tài liệu archive không được index lại.

Sau index thành công, chat cần bật retrieval và truy xuất được chunk liên quan mới dùng được tài liệu.
Nếu nhập link khóa học thủ công vào tài liệu, hãy đưa link vào `content`;
metadata chung không phải phần nội dung luôn được đưa cho AI.
Đối với khóa YouTube/Coursera cần duyệt và gắn vào lộ trình, dùng nhóm course bên dưới.

### 14.2. Thêm link YouTube hoặc Coursera

`POST {{baseUrl}}/admin/rag/courses`.

```json
{
  "title": "Khóa Java nền tảng đã kiểm duyệt",
  "url": "{{courseUrl}}",
  "description": "Nội dung, trình độ phù hợp và lý do Admin lựa chọn khóa học này.",
  "skills": ["Java", "Object Oriented Programming"],
  "language": "en",
  "isFree": false
}
```

Link minh họa cho cấu trúc API; Admin cần tự xác minh nội dung, độ uy tín và chính sách học phí trước khi duyệt.
Backend kiểm tra tên miền/định dạng link, không tự đánh giá độ uy tín hoặc tự lấy học phí.

| Field | Quy tắc |
|---|---|
| `title` | Bắt buộc, ≤255 ký tự |
| `url` | Bắt buộc, ≤2.048, HTTPS YouTube/Coursera được hỗ trợ |
| `description` | Bắt buộc, ≤12.000 ký tự; nhập nội dung giúp matching/retrieval |
| `skills` | Bắt buộc 1–30 tên kỹ năng; mỗi tên không trắng, ≤150 ký tự |
| `language` | Bắt buộc, ≤10; ví dụ `vi`, `en`, `en-US` |
| `isFree` | Boolean bắt buộc; tên request là `isFree` |

Các dạng URL chấp nhận:

| Nguồn | Dạng URL |
|---|---|
| YouTube video | `https://www.youtube.com/watch?v=VIDEO_ID` với ID 11 ký tự |
| YouTube link ngắn | `https://youtu.be/VIDEO_ID` |
| YouTube playlist | `https://www.youtube.com/playlist?list=PLAYLIST_ID` |
| Coursera | `https://www.coursera.org/learn/slug` |
| Coursera | `/specializations/slug`, `/professional-certificates/slug` |
| Coursera | `/projects/slug`, `/guided-projects/slug` |

YouTube còn chấp nhận host `youtube.com`, `m.youtube.com`; Coursera chấp nhận `coursera.org`.
Không nhận link kênh, link YouTube Shorts hoặc URL nền tảng khác.
Server chuẩn hóa URL, bỏ tracking và chặn URL đã tồn tại (`409`).
Một URL đã archive vẫn tồn tại trong lịch sử nên không mặc định có thể tạo lại.
`provider` và loại tài nguyên được suy từ URL, không nhập qua body.

### 14.3. Duyệt, index và archive khóa học

| Method | URL | Body / kết quả |
|---|---|---|
| GET | `{{baseUrl}}/admin/rag/courses` | Tối đa 200 course mới nhất; không có query phân trang |
| GET | `{{baseUrl}}/admin/rag/courses/{{courseId}}` | Chi tiết course |
| POST | `{{baseUrl}}/admin/rag/courses/{{courseId}}/approve` | Không body; Admin duyệt course |
| POST | `{{baseUrl}}/admin/rag/courses/{{courseId}}/index` | Không body; index tài liệu của course đã duyệt |
| POST | `{{baseUrl}}/admin/rag/courses/{{courseId}}/archive` | Không body; ngừng đề xuất mới |

Tạo mới trả trạng thái `PENDING_REVIEW`; lưu `id` vào `courseId`.
Approve tạo `learning_resource` đang active, tạo tài liệu RAG chứa cả URL và mô tả,
chuyển course thành `APPROVED` và lưu `learning_resource_id`, `rag_document_id`.
Approve **chưa chạy embedding**; muốn AI chat dùng qua retrieval cần gọi index sau đó.
Chỉ course `APPROVED` có tài liệu liên kết mới được index.

Hai trạng thái cần hiển thị riêng:

| Trạng thái | Field / cách đọc |
|---|---|
| Duyệt khóa học | `status` của course: `PENDING_REVIEW`, `APPROVED`, `ARCHIVED` |
| Index tri thức | `embedding_status` của RAG document: `PENDING`, `PROCESSING`, `COMPLETED`, `FAILED` |

GET course trả row snake_case, ví dụ `is_free`, `learning_resource_id`, `rag_document_id`,
`created_by_user_id`, `reviewed_by_user_id`, `reviewed_at`.
GET course không tự gộp `embedding_status`; lấy document bằng `rag_document_id` để đọc trạng thái index.
Response của POST course index có thêm object `rag_document` chứa kết quả index.

Trong Postman, đọc course rồi lưu ID tài liệu liên kết ở **Scripts → Post-response**:

```javascript
if (pm.response.code === 200) {
  const course = pm.response.json();
  pm.environment.set("courseId", course.id);
  if (course.rag_document_id) {
    pm.environment.set("courseRagDocumentId", course.rag_document_id);
  } else {
    pm.environment.unset("courseRagDocumentId");
  }
}
```

Sau đó tạo GET `{{baseUrl}}/admin/rag/documents/{{courseRagDocumentId}}`
để kiểm tra `embedding_status` cùng `metadata.disabled`.

Archive chấp nhận course đang chờ hoặc đã duyệt.
Nếu đã có resource/document, archive tắt resource và archive tài liệu RAG để ngừng đề xuất mới.
Các link đã được lưu dưới dạng snapshot trong lộ trình hoặc nguồn chat vẫn được giữ làm lịch sử.
Không có endpoint sửa, reject hoặc restore course ở backend hiện tại.

### 14.4. Luồng test khóa học và lộ trình

1. Admin tạo course, xác nhận `PENDING_REVIEW`.
2. Trước duyệt, course chưa được đưa vào catalog tài nguyên gợi ý.
3. Admin approve; xác nhận `APPROVED` và có hai ID liên kết.
4. Đọc document; nếu muốn retrieval cho chat, chạy index và xác nhận `COMPLETED`.
5. Candidate tạo lộ trình hoặc hỏi AI có retrieval về kỹ năng liên quan.
6. Kiểm tra tài nguyên được chọn phù hợp kỹ năng; duyệt/index không bảo đảm mọi câu hỏi đều chọn course đó.
7. Mở lại lộ trình/chat để kiểm tra link, tên và nguồn đã đề xuất được lưu.
8. Admin archive course, xác nhận không còn đề xuất mới từ resource đó và lịch sử cũ vẫn giữ snapshot.



<a id="section-15"></a>

## 15. Thứ tự chạy thử, SSE và ký webhook

### 15.1. Luồng kiểm thử theo vai trò

Chạy từng luồng trên dữ liệu test. Khi có bước không thành công, xử lý lỗi trước khi dùng ID ở bước tiếp theo.
Các bước approve, archive, đổi mật khẩu, hủy gói hoặc thu hồi phiên làm thay đổi trạng thái dữ liệu.

| Luồng | Thứ tự thực hiện và điều cần kiểm tra |
| --- | --- |
| Guest | Tạo session → lưu `sessionKey` → lấy demo/template → build/matching/chat thử bằng cùng header Guest. |
| Tài khoản | Register → lấy OTP → verify → login → lưu token → `/me` → cập nhật career profile. |
| Admin/Consultant | Login Admin đã có → tạo account Consultant → login Consultant → lưu token vào biến riêng. |
| CV/Ollama | Login → kiểm tra quota → build AI hoặc tạo thủ công/upload → xem/sửa dữ liệu → confirm `READY` → đặt primary → embedding 1024 → phân tích/tải CV → đối chiếu AI usage. |
| Tìm việc | Admin import job active → index bằng embedding Ollama → xem skills/jobs → chọn `jobId` → matching theo CV `READY` → mở run/result → feedback → ATS hoặc draft ứng tuyển. |
| Đánh giá kỹ năng | Tạo assessment → lấy các câu hỏi → trả lời đầy đủ → submit → xem kết quả → tạo roadmap liên quan. |
| Lộ trình | Tạo roadmap → lưu ID → mở chi tiết → cập nhật node sau khi đủ prerequisite → kiểm tra tiến độ. |
| Khóa học đã duyệt | Admin tạo course → xác nhận chưa xuất hiện trong gợi ý → approve → xem recommendations theo skill. |
| Khóa học trong RAG chat | Course đã approve → index → Candidate tạo conversation → gửi `retrieve: true` → kiểm tra `sources`. |
| Lịch sử link | Lưu roadmap có course → ghi nhận URL → archive course → gợi ý mới bỏ course nhưng roadmap cũ giữ URL. |
| Tư vấn người thật | Candidate tạo thread → Admin assign Consultant → hai phía gửi/đọc tin → đóng → đọc lại lịch sử. |
| Thanh toán thử | Xem tier trả phí → tạo order → lưu amount/currency/provider reference → ký webhook → xem subscription/invoice. |
| Hỗ trợ | Candidate tạo ticket → Admin xem/đổi trạng thái/phân công → hai phía gửi message và xem notification. |
| Phiên bản AI | Dùng nhóm cấu hình hoặc feature thử riêng → tạo v1/v2 → restore UUID v1 → nhận bản mới v3. |

`assessmentId`, `resumeId`, `threadId` và các ID liên quan phải thuộc đúng người/luồng đang thử.
Không dùng UUID ngẫu nhiên để thay đối tượng chưa được tạo. Lấy job/CV phù hợp trước khi gọi API phụ thuộc.

Thứ tự folder, dữ liệu mẫu xuyên suốt và các điều kiện dừng nằm trong [checklist chạy Postman](postman-test-order.md#thu-tu-toan-bo).
Để test CV trước, không cần tạo thanh toán, gói trả phí, job, RAG hoặc Consultant. Tài khoản mới dùng quota Free
nếu catalog/seed đã cài đúng; backend vẫn kiểm tra token reserve trước mỗi lời gọi model.

### 15.2. Upload và download CV

Upload dùng **Body → form-data**:

| Key | Loại | Giá trị |
| --- | --- | --- |
| `file` | File | Chọn file PDF/DOCX/TXT được endpoint hỗ trợ; xem giới hạn ở mục 6. |
| `title` | Text, tùy chọn | Ví dụ `CV Java Backend`. |
| `language` | Text, tùy chọn | `vi` hoặc `en`; mặc định `vi`. |

Để Postman tự sinh multipart boundary; bỏ header JSON tự đặt cho request upload.
Download trả attachment/bytes. Dùng thao tác lưu response thành file trong Postman.
`text=true` tải nội dung TXT; mặc định có thể trả file upload gốc. CV tạo bằng AI/thủ công chưa có trình xuất PDF riêng.

<a id="sse-tu-van"></a>

### 15.3. Nhận tin tư vấn qua SSE

Tạo một HTTP request riêng:

```http
GET {{baseUrl}}/consultations/{{threadId}}/events
Authorization: Bearer {{consultantAccessToken}}
Accept: text/event-stream
```

Body chọn **none**. Giữ request này mở, dùng tab/request khác gửi tin bằng token Candidate vào cùng thread.
Stream gửi `connected` khi kết nối và `message` khi có tin. Postman hỗ trợ xem SSE qua HTTP request;
xem [hướng dẫn SSE của Postman](https://blog.postman.com/support-for-server-sent-events/).

SSE có thời hạn khoảng 5 phút. Khi reconnect, tải lại `/consultations/{id}/messages` để bù lịch sử
và khử trùng theo message ID; backend chưa replay theo `Last-Event-ID`.
Kết nối là luồng nhận sự kiện; gửi tin vẫn dùng POST messages.

<a id="webhook-thanh-toan"></a>

### 15.4. Mô phỏng webhook thanh toán có chữ ký

Đây là mô phỏng hợp đồng webhook trên môi trường test; request hợp lệ có thể kích hoạt gói và phát hành hóa đơn
trong database test. Nó không tạo checkout hoặc thu tiền qua cổng thanh toán.

Sau `POST /payments/orders`, đặt **Post-response**:

```javascript
if (pm.response.code === 200) {
  const order = pm.response.json();
  pm.expect(order.id).to.be.a("string").and.not.empty;
  pm.expect(order.provider_order_id).to.be.a("string").and.not.empty;
  pm.environment.set("paymentOrderId", order.id);
  pm.environment.set("providerOrderId", order.provider_order_id);
  pm.environment.set("paymentAmount", String(order.amount));
  pm.environment.set("paymentCurrency", order.currency);
}
```

Chuẩn bị `paymentWebhookSecret`, `paymentEventId`, `paymentStatus` trong Environment.
Tạo request **POST `{{baseUrl}}/payments/webhook`**, chọn **No Auth**, **Body → raw → JSON**.
Toàn bộ raw body phải là đúng biến dưới, không bọc dấu nháy:

```text
{{paymentWebhookBody}}
```

Đặt script sau trong **Scripts → Pre-request**. Script tạo JSON một lần rồi ký đúng chuỗi đó:

```javascript
const encoder = new TextEncoder();
const requiredVariable = (name) => {
  const value = pm.environment.get(name);
  if (value === undefined || value === null || String(value).trim() === "") {
    throw new Error("Thiếu biến Environment: " + name);
  }
  return String(value);
};

const secret = requiredVariable("paymentWebhookSecret");
if (encoder.encode(secret).byteLength < 32) {
  throw new Error("paymentWebhookSecret cần ít nhất 32 byte UTF-8 và phải khớp backend test");
}
if (!pm.request.body || pm.request.body.mode !== "raw" ||
    pm.request.body.raw !== "{{paymentWebhookBody}}") {
  throw new Error("Đặt toàn bộ raw body thành {{paymentWebhookBody}}, không có dấu nháy");
}

const amount = Number(requiredVariable("paymentAmount"));
if (!Number.isFinite(amount) || amount <= 0) {
  throw new Error("paymentAmount phải là số tiền của order đã tạo");
}
const status = requiredVariable("paymentStatus");
if (!["COMPLETED", "FAILED", "CANCELLED"].includes(status)) {
  throw new Error("paymentStatus phải là COMPLETED, FAILED hoặc CANCELLED");
}

const payload = {
  orderId: requiredVariable("paymentOrderId"),
  providerOrderId: requiredVariable("providerOrderId"),
  eventId: requiredVariable("paymentEventId"),
  status,
  amount,
  currency: requiredVariable("paymentCurrency")
};
const rawBody = JSON.stringify(payload);
const timestamp = String(Math.floor(Date.now() / 1000));
const key = await crypto.subtle.importKey(
  "raw", encoder.encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]
);
const digest = await crypto.subtle.sign("HMAC", key, encoder.encode(timestamp + "." + rawBody));
const signature = Array.from(new Uint8Array(digest))
  .map((byte) => byte.toString(16).padStart(2, "0")).join("");

pm.variables.set("paymentWebhookBody", rawBody);
pm.environment.set("paymentTimestamp", timestamp);
pm.environment.set("paymentSignature", signature);
pm.request.headers.upsert({ key: "Content-Type", value: "application/json" });
pm.request.headers.upsert({ key: "X-Payment-Timestamp", value: timestamp });
pm.request.headers.upsert({ key: "X-Payment-Signature", value: signature });
```

Script dùng `TextEncoder` và Web Crypto có trong Postman sandbox hiện tại;
xem [các global object được hỗ trợ](https://learning.postman.com/docs/tests-and-scripts/write-scripts/postman-sandbox-reference/pm-require/).
Công thức backend là HMAC-SHA256 trên UTF-8 `timestamp + "." + rawBody`, trả hex 64 ký tự.
Timestamp phải nằm trong khoảng ±300 giây so với server. Không sửa body hoặc chèn thêm newline sau khi ký.
Secret là cấu hình của server test, không phải access token và không phải field gửi trong body.

| Tình huống | Kết quả cần kiểm tra |
| --- | --- |
| Order PENDING, event COMPLETED hợp lệ | Order hoàn tất; subscription và invoice được tạo theo nghiệp vụ. |
| Event FAILED/CANCELLED | Không kích hoạt gói trả phí. |
| Gửi lại cùng event/order | Không tạo trùng subscription/invoice. |
| Tái sử dụng event ID cho order khác | `409`. |
| Sửa amount/currency/provider reference rồi ký lại | `400` vì không khớp order. |
| Sửa body nhưng giữ chữ ký cũ | `401`. |
| Timestamp quá hạn hoặc chữ ký sai | `401`. |
| Backend chưa có secret đủ dài | `503`. |

<a id="section-16"></a>

## 16. API kỹ thuật, lỗi thường gặp và giới hạn hiện tại

### 16.1. Kiểm tra ứng dụng và OpenAPI

Các đường dẫn dưới không dùng tiền tố `/api/v1`:

| Method | URL mặc định | Mục đích |
| --- | --- | --- |
| GET | `http://localhost:8080/actuator/health` | Health của ứng dụng; có thể trả DOWN khi dependency chưa sẵn sàng. |
| GET | `http://localhost:8080/actuator/health/liveness` | Liveness khi health probes được bật. |
| GET | `http://localhost:8080/actuator/health/readiness` | Readiness khi health probes được bật. |
| GET | `http://localhost:8080/v3/api-docs` | OpenAPI JSON thực tế của backend đang chạy. |
| GET | `http://localhost:8080/swagger-ui.html` | Swagger UI, có thể chuyển hướng tới trang index. |

Đổi port theo môi trường. Các endpoint Actuator khác phụ thuộc cấu hình exposure và không mặc định public
theo quy tắc security hiện tại.

### 16.2. Đọc lỗi

Lỗi nghiệp vụ/validation thường trả Problem Detail, ví dụ:

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "Request validation failed",
  "errors": ["title: must not be blank"]
}
```

Lỗi từ security filter có thể dùng JSON `status`/`message` thay vì `detail`.
Kiểm tra HTTP status trước khi đọc field; response download/SSE không phải JSON.

| HTTP | Nguyên nhân thường gặp | Cách kiểm tra |
| --- | --- | --- |
| `400` | Thiếu/sai field, UUID chưa resolve, enum sai, query ngoài giới hạn, prerequisite chưa đúng theo thao tác | Kiểm tra body/Params và DTO ở mục tương ứng. Một số xung đột prerequisite trả `409`. |
| `401` | Access token hết hạn/thu hồi; thiếu Guest key; chữ ký webhook sai | Refresh/login lại; dùng đúng `sessionKey`; kiểm tra timestamp và raw body đã ký. |
| `403` | Sai role, email chưa xác thực hoặc tính năng không được gói cho phép | Đổi đúng token/tài khoản, xác minh email, kiểm tra quota/feature. |
| `404` | ID không tồn tại hoặc không thuộc người gọi; Consultant chưa được phân công | Lấy ID từ đúng response, kiểm tra chủ sở hữu và assignment. |
| `409` | Trùng dữ liệu, course trùng sau chuẩn hóa, trạng thái đã đóng/archived, dữ liệu chưa READY | Kiểm tra trạng thái hiện tại; không lặp thao tác tạo bằng cùng dữ liệu ngoài ý muốn. |
| `413` | Upload vượt giới hạn | Kiểm tra `MULTIPART_MAX_FILE_SIZE`/`MULTIPART_MAX_REQUEST_SIZE` và giới hạn file của service. |
| `429` | Hết quota token/trial hoặc giới hạn tần suất OTP/login | Xem usage/quota, chờ kỳ giới hạn hoặc dùng tài khoản test/gói phù hợp. |
| `502` | Ollama thất bại hoặc JSON/field đầu ra không đúng hợp đồng | Kiểm tra model đã tải, container, bộ nhớ, log; phản hồi JSON lỗi vẫn có thể bị tính token đã dùng. |
| `503` | Database/SMTP/AI embedding/webhook chưa cấu hình hoặc chưa sẵn sàng | Kiểm tra các dependency và biến môi trường liên quan. |

### 16.3. Phạm vi hiện tại để tránh thử nhầm chức năng

- Chưa có API Forgot/Reset Password qua email; đổi mật khẩu hiện yêu cầu đăng nhập và mật khẩu hiện tại.
- Quota đang tính theo token/tính năng, chưa có hạn mức số lần gọi API độc lập.
- Billing đã có SePay Payment Gateway: form checkout được ký ở BE, IPN xác thực secret và xử lý trùng trước khi
  kích hoạt gói. Cần cấu hình merchant và HTTPS công khai để thử với SePay; xem [hướng dẫn](sepay-payment-gateway.md).
- Invoice API hiện phục vụ hóa đơn của chính người gọi. `payment-reports` là phản ánh thanh toán, không phải
  báo cáo analytics hoặc file xuất quản trị. Chưa có endpoint xuất báo cáo CSV/XLSX/PDF riêng.
- Cấu hình AI/prompt tạo phiên bản mới. Lưu cấu hình embedding chưa thay bean/model embedding của process đang chạy.
- Gói Free chưa bị khóa sửa hoàn toàn ở backend. Nếu thử CRUD gói/quota, tạo một gói test riêng để giữ chính sách Free.
- Khóa học có create/list/detail/approve/index/archive; chưa có edit, reject riêng hay khôi phục sau archive.
  Admin nhập mô tả/kỹ năng và kiểm duyệt nội dung; backend không tự tải transcript YouTube/nội dung Coursera.
- Roadmap mới lưu snapshot nguồn học; node links cũ được chụp khi migration V9 chạy. Roadmap cũ chưa có snapshot
  `learning_recommendations` vẫn dùng nhánh tính lại cho trường đó.
- Chat AI lưu lượt gửi thành công. Nếu provider lỗi trước khi ghi message, tin người dùng ở lượt lỗi chưa được lưu.
- SSE consultation là cơ chế hiện có; client WebSocket/STOMP không dùng trực tiếp endpoint SSE này.

Nguồn đối chiếu hợp đồng: [controller](../src/main/java/com/example/intelligent_cv_matching_ai_agent/controller),
[request DTO](../src/main/java/com/example/intelligent_cv_matching_ai_agent/dto/request),
[service](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service) và
[cấu hình ứng dụng](../src/main/resources/application.yaml).
