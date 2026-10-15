# Toàn bộ API EduNexus và input để test bằng Postman

Đối chiếu controller, request DTO và service ngày **13/10/2026**. Tài liệu chứa **138 cặp method/route nghiệp vụ thuộc 23 controller**, kèm input mẫu, query, path variable, header, quyền truy cập và điều kiện sử dụng.

Base URL API: `http://localhost:8080/api/v1`. Mỗi endpoint ở phần danh mục có URL dùng biến Postman `{{baseUrl}}`. Không có endpoint tạo dữ liệu nào đã được gọi trong quá trình viết tài liệu.

## Mục lục

- [1. Chuẩn bị backend và Postman](#1-chuẩn-bị-backend-và-postman)
- [2. Biến môi trường và header](#2-biến-môi-trường-và-header)
- [3. Lưu token, OTP và UUID từ response](#3-lưu-token-otp-và-uuid-từ-response)
- [4. Thứ tự test từng luồng](#4-thứ-tự-test-từng-luồng)
- [5. Upload, download, SSE và webhook](#5-upload-download-sse-và-webhook)
- [6. Danh mục API và input mẫu](#6-danh-mục-api-và-input-mẫu)
- [7. API kỹ thuật và lỗi thường gặp](#7-api-kỹ-thuật-và-lỗi-thường-gặp)

## 1. Chuẩn bị backend và Postman

Từ thư mục chứa `pom.xml`, dùng `scripts/run-local.ps1` để nạp `.env` rồi chạy backend:

~~~powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/run-local.ps1 -WithInfrastructure
~~~

Cần Java25/Maven/PostgreSQL có pgvector. Nếu DB riêng đã chạy, bỏ switch WithInfrastructure. Chi tiết setup nằm ở [project-guide.md](project-guide.md).

Để test đăng ký trước khi có SMTP, đặt `AUTH_EXPOSE_DEV_OTP=true`. Nếu test gửi mail, đặt flag này false, bật MAIL_ENABLED và credentials SMTP/Mailtrap. Cả hai chế độ tắt làm luồng gửi OTP503.

Các API AI generation cần AI_ENABLED và provider/key đúng; semantic matching/index/RAG cần embedding1536 chiều. Chưa bật AI vẫn có thể test CV manual, extraction/review, rule matching/analysis, tư vấn người thật, billing contract và quản trị.

Trong Postman:

1. Tạo Environment **EduNexus Local** và chọn nó trước khi Send.
2. Thêm các biến ở mục2; giá trị mẫu email/password dùng cho tài khoản test.
3. Tạo request với method/URL của endpoint.
4. Nhập query trong tab **Params**.
5. Nếu có JSON body: **Body → raw → JSON**, dán mẫu.
6. Nếu bodyless: **Body → none**, không tự thêm `{}` cho GET/DELETE.
7. Chọn **Authorization → Bearer Token** hoặc **No Auth** theo từng API.
8. Dán script lưu dữ liệu vào **Scripts → Post-response** nếu cần nối các request.

Postman dùng biến environment trong URL/body/header và hỗ trợ `pm.environment.set(...)` để cập nhật sau response. [Tài liệu biến Postman](https://learning.postman.com/docs/use/send-requests/variables/environment-variables).

Dùng đúng field case. Ví dụ `resumeId` của ATS khác `related_resume_id` của assistant và `structured_data` của CV. Placeholder `{{...}}` phải có giá trị; không gửi nguyên một placeholder chưa resolve tới backend.

## 2. Biến môi trường và header

### 2.1. Biến cần khai báo

| Biến | Giá trị mẫu/cách điền | Ý nghĩa |
| --- | --- | --- |
| `baseUrl` | http://localhost:8080/api/v1 | Tiền tố API, không thêm /api/v1 lần hai. |
| `candidateEmail` | candidate.postman.20261013@example.com | Email mới cho register; live SMTP dùng hộp thư thật. |
| `candidatePassword` | PostmanDemo2026! | Mật khẩu candidate test, ít nhất10 ký tự/tối đa72 byte UTF-8. |
| `candidateNewPassword` | PostmanNewPassword2026! | Mật khẩu mới khi thử change password; sau đổi cập nhật candidatePassword/login lại. |
| `adminEmail` | admin.postman@example.com | Account đã register/verified/bootstrap admin; đổi body login. |
| `adminPassword` | PostmanAdmin2026! | Phải đúng password account admin đã tạo; không là mật khẩu mặc định. |
| `consultantEmail` | consultant.postman@example.com | Account CAREER_CONSULTANT do admin tạo. |
| `consultantPassword` | PostmanConsultant2026! | Đúng temporaryPassword khi tạo consultant. |
| `adminAccountEmail` | consultant.postman@example.com | Email account mới trong POST /admin/users; đổi nếu đã tồn tại. |
| `temporaryPassword` | PostmanConsultant2026! | Dùng cho account admin tạo. |
| `activeToken` | Để trống, sau đó dán access token | Token của role đang test USER/THREAD. |
| `candidateAccessToken` | Để trống | Lưu từ candidate login/refresh. |
| `candidateRefreshToken` | Để trống | Lưu refresh mới sau mỗi lần dùng. |
| `adminAccessToken` | Để trống | Lưu từ login admin; dùng Bearer cho ADMIN. |
| `adminRefreshToken` | Để trống | Refresh admin, nếu cần. |
| `consultantAccessToken` | Để trống | Lưu từ login consultant; dán vào activeToken khi thử thread. |
| `consultantRefreshToken` | Để trống | Refresh consultant, nếu cần. |
| `googleIdToken` | Để trống | ID token Google thật cho GOOGLE_CLIENT_ID cấu hình. |
| `otpCode` | Để trống | Code6 số từ developmentCode hoặc email; giữ string. |
| `guestSession` | Để trống | sessionKey response guest/sessions; không dùng UUID session. |
| `aiProvider` | openai | Provider hiện có trong cấu hình process. |
| `aiChatModel` | gpt-4.1-mini | Giá trị mẫu repository; dùng model của môi trường chạy. |
| `sourceJobUrl` | Để trống rồi nhập URL nguồn | HTTPS trang chi tiết ITviec/TopCV, phải khớp sourceSite; không URL giả khi crawl. |
| `monitoringCategory` | audit | ai-usage/vector-stores/indexing-jobs/logs/audit/billing/crawl-runs. |
| `paymentWebhookSecret` | Điền secret backend test | Secret test đúng backend, &gt;=32 byte UTF-8; không phải JWT/token user. |
| `paymentAmount` | Để trống | Number từ amount order; script đọc Number(...) và kiểm &gt;0. |
| `paymentCurrency` | VND | Lấy currency thật từ order, có thể khác giá trị mẫu. |
| `paymentEventId` | postman-event-20261013-001 | Đổi ID mỗi event mới; giữ ID khi cố ý test duplicate. |
| `paymentStatus` | COMPLETED | COMPLETED/FAILED/CANCELLED; script mặc định COMPLETED. |
| `paymentTimestamp` | Được script sinh | Unix seconds, lệch tối đa300 giây. |
| `paymentSignature` | Được script sinh | Hex HMAC-SHA256, không nhập giá trị mẫu giả. |
| `paymentWebhookBody` | Được Pre-request script sinh cục bộ | Đặt raw body thành {{paymentWebhookBody}}; không điền chuỗi ký thủ công. |
| `adminUserId` | Để trống | Lưu từ POST /api/v1/admin/users; ID tài khoản đích để GET/PUT/DELETE admin/users. |
| `aiConfigurationId` | Để trống | Lưu từ POST /api/v1/admin/ai/configurations; UUID record dùng để restore. |
| `aiPromptId` | Để trống | Lưu từ POST /api/v1/admin/ai/prompts; UUID record dùng để restore prompt. |
| `analysisId` | Để trống | Lưu từ POST /api/v1/resumes/{id}/analyses; Đây là ID resume_analyses dùng route /resumes/{id}/analyses/{analysisId}; không thay bằng ID ATS job. |
| `announcementId` | Để trống | Lưu từ POST /api/v1/admin/announcements; UUID draft để edit/publish/archive. |
| `applicationDraftId` | Để trống | Lưu từ POST /api/v1/jobs/{jobId}/application-drafts; Chọn theo draft_type; không giả định array[0] luôn là email. |
| `assessmentId` | Để trống | Lưu từ POST /api/v1/assessments; Chỉ lưu sau response thành công; chọn đúng bản ghi cần kiểm thử. |
| `assistantConversationId` | Để trống | Lưu từ POST /api/v1/assistant/conversations; Chỉ lưu sau response thành công; chọn đúng bản ghi cần kiểm thử. |
| `assistantMessageId` | Để trống | Lưu từ POST /api/v1/assistant/conversations/{id}/messages; ID ASSISTANT reply để feedback; không thay assistantConversationId. |
| `candidateExpiresIn` | Để trống | Lưu từ POST /api/v1/auth/login; Số giây access token có hiệu lực; hiện 900. |
| `candidateId` | Để trống | Lưu từ GET /api/v1/me; UUID người gọi; chỉ dùng như ID tham chiếu, quyền quyết định bởi token. |
| `consultantId` | Để trống | Lưu từ POST /api/v1/admin/users; Ví dụ role CAREER_CONSULTANT nên dùng ID này để assign consultation. |
| `consultationMessageId` | Để trống | Lưu từ POST /api/v1/consultations/{id}/messages; ID tin; dùng token của người nhận để mark read. |
| `crawlRunId` | Để trống | Lưu từ POST /api/v1/admin/jobs/crawl; UUID run để GET lịch sử chi tiết. |
| `demoResumeId` | Để trống | Lưu từ GET /api/v1/guest/demo-resumes; Chọn demo thực, chỉ lưu nếu array không rỗng. |
| `gapAnalysisId` | Để trống | Lưu từ POST /api/v1/roadmaps; Chỉ lưu sau response thành công; chọn đúng bản ghi cần kiểm thử. |
| `guestAssistantMessageId` | Để trống | Lưu từ POST /api/v1/guest/assistant/conversations/{id}/messages; UUID message phản hồi. Guest không có feedback API riêng. |
| `guestConversationId` | Để trống | Lưu từ POST /api/v1/guest/assistant/conversations; UUID để GET conversation và POST messages bằng cùng guest session. |
| `guestMatchingRunId` | Để trống | Lưu từ POST /api/v1/guest/trials/recommendations; ID run guest để theo dõi; không phải candidate matchingRunId. |
| `guestSessionId` | Để trống | Lưu từ POST /api/v1/guest/sessions; UUID guest session; không thay header raw key. |
| `invoiceId` | Để trống | Lưu từ GET /api/v1/invoices; Chọn invoice thực thuộc user; chỉ lưu nếu array không rỗng. |
| `jobId` | Để trống | Lưu từ GET /api/v1/jobs; Chọn đúng job muốn kiểm thử; nếu [] phải import trước. |
| `indexingJobId` | Để trống | ID vector_indexing_jobs từ POST job index hoặc GET trạng thái index; khác canonical jobId. |
| `matchResultId` | Để trống | Lưu từ POST /api/v1/matching; Chỉ khi results có phần tử; đây là ID job_match_results, không phải jobId. |
| `matchingRunId` | Để trống | Lưu từ POST /api/v1/matching; Chỉ lưu sau response thành công; chọn đúng bản ghi cần kiểm thử. |
| `notificationId` | Để trống | UUID thật theo path/body của endpoint; lấy từ API liên quan. |
| `paidTierId` | Để trống | Lưu từ GET /api/v1/subscriptions/tiers; Chọn gói active có giá &gt;0; thường BASIC do sort giá tăng. Kiểm tra giá/currency trước tạo order. |
| `paymentOrderId` | Để trống | Lưu từ POST /api/v1/payments/orders; UUID order dùng GET/report/webhook. |
| `paymentReportId` | Để trống | Lưu từ POST /api/v1/payments/orders/{id}/reports; UUID report để admin resolve. |
| `providerOrderId` | Để trống | Lưu từ POST /api/v1/payments/orders; Provider reference phải khớp khi ký webhook. |
| `questionId` | Để trống | Lưu từ POST /api/v1/assessments; Lưu một question để trả lời; phải tiếp tục trả lời mọi question trước submit. |
| `ragDocumentId` | Để trống | Lưu từ POST /api/v1/admin/rag/documents; UUID document dùng index/get/archive. |
| `resumeId` | Để trống | Lưu từ GET /api/v1/resumes; Chỉ chọn bản ghi mong muốn khi danh sách không rỗng; thao tác tạo bên dưới lưu ID chính xác hơn. |
| `resumeTemplateId` | Để trống | Lưu từ GET /api/v1/resumes/templates; Danh sách không rỗng; chọn template phù hợp, không suy UUID từ tên/seed. |
| `roadmapId` | Để trống | Lưu từ POST /api/v1/roadmaps; Chỉ lưu sau response thành công; chọn đúng bản ghi cần kiểm thử. |
| `roadmapNodeId` | Để trống | Lưu từ POST /api/v1/roadmaps; Chọn node không có prerequisites hoặc đã hoàn thành prerequisites. |
| `sessionId` | Để trống | Lưu từ GET /api/v1/me/sessions; Chọn row muốn revoke; chỉ lưu khi array không rỗng, có thể chính là access hiện dùng. |
| `skillId` | Để trống | Lưu từ GET /api/v1/skills; Chọn skill phù hợp tên Java/SQL theo response; không lưu nếu danh sách rỗng. |
| `subscriptionId` | Để trống | Lưu từ GET /api/v1/subscriptions/history; Chọn subscription thật thuộc account; kiểm expiry và side effect trước cancel. |
| `threadId` | Để trống | Lưu từ POST /api/v1/consultations; UUID consultation dùng xuyên các endpoints tư vấn. |
| `ticketId` | Để trống | Lưu từ POST /api/v1/support/tickets; UUID ticket để xem, gửi message, admin PATCH. |
| `trialTemplateId` | Để trống | Lưu từ GET /api/v1/guest/templates; Chọn template active/trial thực; chỉ lưu khi array không rỗng. |

UUID lấy từ response hoặc GET danh mục, không tự dùng UUID ngẫu nhiên để liên kết tài nguyên. `skillId` là skill active; `resumeId` là CV của account đang test; `paidTierId` phải là tier trả phí.

Email đăng ký mỗi lượt nên khác nhau. Có thể đặt biến candidateEmail thủ công, ví dụ `candidate.postman.20261013@example.com`; sandbox nhận thư dù địa chỉ test không có hộp thư thật. Khi gửi SMTP live, dùng email nhận thư được.

### 2.2. Quy ước quyền trong tài liệu

| Mã quyền | Postman Authorization/header |
| --- | --- |
| PUBLIC | **No Auth**, bỏ header bearer cũ |
| GUEST | **No Auth** + `X-Guest-Session: {{guestSession}}` |
| USER | `Authorization: Bearer {{activeToken}}`; dữ liệu thường phải thuộc user |
| CANDIDATE | `Authorization: Bearer {{candidateAccessToken}}` |
| ADMIN | `Authorization: Bearer {{adminAccessToken}}` |
| THREAD | `Authorization: Bearer {{activeToken}}`; candidate owner, assigned consultant hoặc admin tùy thread |

Với USER/THREAD, dán access token của vai trò đang test vào `activeToken`. Có thể đổi token sang candidate, consultant hoặc admin. Trong Bearer Token input chỉ nhập biến token, không thêm từ Bearer lần nữa.

JSON request dùng `Content-Type: application/json`. GET JSON có thể dùng `Accept: application/json`. Multipart và SSE dùng header riêng ở mục5.

Public route vẫn có thể401 nếu gửi bearer sai/hết hạn vì BearerFilter kiểm header trước. Thư mục controller không thay thế quy tắc quyền service; ví dụ PATCH support ticket và phân công consultant cần admin dù URL không bắt đầu /admin.

## 3. Lưu token, OTP và UUID từ response

### 3.1. Token và OTP

Sau login candidate hoặc refresh candidate, dán vào **Post-response**:

~~~javascript
if (pm.response.code === 200) {
  const json = pm.response.json();
  if (json.accessToken) {
    pm.environment.set("candidateAccessToken", json.accessToken);
    pm.environment.set("activeToken", json.accessToken);
  }
  if (json.refreshToken) {
    pm.environment.set("candidateRefreshToken", json.refreshToken);
  }
}
~~~

Đăng nhập admin/consultant qua cùng API login nhưng thay email/password đúng account. Đổi tên biến script tương ứng thành `adminAccessToken/adminRefreshToken` hoặc `consultantAccessToken/consultantRefreshToken`. Không ghi đè candidate tokens khi đổi role.

Sau register/resend trong dev mode:

~~~javascript
if (pm.response.code === 200) {
  const json = pm.response.json();
  if (json.developmentCode) {
    pm.environment.set("otpCode", json.developmentCode);
  }
}
~~~

Nếu SMTP bật, đọc code trong Mailtrap/hộp thư và nhập otpCode thủ công. Code giữ dạng string6 ký tự, kể cả đầu0.

Access hạn15 phút, refresh30 ngày; refresh xoay sau dùng và không dùng làm bearer. Logout/password change/delete/suspend có thể thu hồi token ngay. GET sessions là token rows, không phải một session thiết bị có cặp access/refresh.

### 3.2. Lưu UUID

Sau API tạo CV trả object có id:

~~~javascript
if (pm.response.code === 200) {
  const json = pm.response.json();
  if (json.id) pm.environment.set("resumeId", json.id);
}
~~~

Mỗi endpoint có bảng **Lưu biến** chỉ rõ expression theo response của nó. `json` trong bảng nghĩa là `pm.response.json()`. Dùng UUID đúng loại; không nhầm id của CV, analysis, matching run, match result và conversation.

Response list rỗng là hợp lệ. Chỉ chọn phần tử có thật và phù hợp owner/trạng thái; không lưu `undefined` từ array rỗng. Field database thường snake_case.

## 4. Thứ tự test từng luồng

| Luồng | Thứ tự |
| --- | --- |
| Candidate | register → verify-email → login → GET me → career-profile → tạo/upload CV → review/confirm |
| Job cơ bản | Admin import/crawl ACTIVE job → candidate GET jobs → matching CV READY/profile |
| Job AI | Bật AI/embedding → import/crawl enqueue tự động → GET job index status → retry POST index nếu cần → candidate matching semantic/ATS/draft/salary |
| Assessment/roadmap | Tạo assessment → trả lời đủ question → submit → tạo roadmap → update node theo prerequisites |
| Assistant/RAG | Tạo conversation → gửi message; retrieve cần admin document indexed và chat/embedding |
| Guest | POST session → giữ sessionKey → demo/template → trial build/recommendations/chat |
| Tư vấn | Candidate tạo thread → admin assign consultant ACTIVE → các role đọc/gửi/SSE → close |
| Thanh toán contract | GET tiers → tạo order trả phí → lưu order/amount/currency/provider ID → ký webhook → xem gói/invoice |
| Support/thông báo | User tạo ticket → admin reply/update; admin create announcement → publish → user xem/read |
| Các thao tác kết thúc | Thử archive/delete/cancel/logout/password change sau khi đã dùng dữ liệu liên quan |

Đăng ký/xác minh một account rồi bootstrap admin đầu tiên bằng `scripts/bootstrap-admin.sql`; không có admin/password mặc định. Sau đó admin tạo consultant bằng API. Lệnh bootstrap và cách giữ token theo vai trò xem [project-guide.md](project-guide.md).

Tài liệu là danh mục request để chạy có chủ đích; không sắp mọi API thành một run tuần tự tự động. DELETE account, cancel gói và close thread làm các bước tiếp theo trên tài nguyên đó thay đổi kết quả.

## 5. Upload, download, SSE và webhook

### 5.1. Upload CV

`POST {{baseUrl}}/resumes/upload`:

- Authorization: Bearer candidate/active token hợp lệ.
- Body **form-data**: file (type **File**, chọn tệp), title (Text, tùy chọn), language (Text, vi/en).
- Để Postman tạo Content-Type multipart kèm boundary; không dán application/json.
- File tối đa10MiB, request11MB. PDF/DOCX/TXT; PNG/JPEG cần OCR. PDF scan có thể cần nhập/review thủ công.

### 5.2. Download CV

GET download trả bytes/attachment, không phải JSON. Lưu response thành file trong Postman. `text=true` tải canonical text đã review; default có thể trả original upload.

### 5.3. SSE tư vấn

GET events gửi bearer + `Accept: text/event-stream`, body none. Mở request SSE và gửi message từ request/role khác để xem event connected/message; stream của backend timeout5 phút. Reconnect thì GET messages để bù lịch sử.

Postman hỗ trợ mở HTTP request và hiển thị SSE events. [Tài liệu SSE Postman](https://learning.postman.com/latest-v-12/docs/use/send-requests/response-data/responses).

### 5.4. Webhook thanh toán có chữ ký

Chỉ mô phỏng trên môi trường test của bạn. Đây là giả lập notification contract, không thực hiện thu tiền hay checkout thật.

1. Tạo order từ candidate.
2. Lưu paymentOrderId/providerOrderId/paymentAmount/paymentCurrency từ response.
3. Đặt paymentWebhookSecret **đúng secret backend test** và ít nhất32 byte UTF-8.
4. POST payments/webhook: No Auth, raw JSON, body ở endpoint tương ứng.
5. Nếu dùng script bên dưới, đặt toàn bộ raw body thành `{{paymentWebhookBody}}` (không có dấu nháy); script tạo payload từ biến đã lưu, timestamp và signature.
6. Header X-Payment-Timestamp/X-Payment-Signature được script upsert; không thêm bản trùng.

Đặt paymentEventId là ID mới cho mỗi event. Khi test duplicate, giữ cùng event ID và body/đơn. Mẫu JSON trong danh mục minh họa payload. Với script bên dưới, raw body thực của request chỉ là `{{paymentWebhookBody}}`; amount/currency và các ID được lấy từ Environment.

~~~javascript
const { Buffer } = require("buffer");
const secret = pm.environment.get("paymentWebhookSecret");
if (!secret || Buffer.byteLength(secret, "utf8") < 32) {
  throw new Error("paymentWebhookSecret must match backend and contain at least 32 UTF-8 bytes");
}
if (!pm.request.body || pm.request.body.mode !== "raw" ||
    pm.request.body.raw.trim() !== "{{paymentWebhookBody}}") {
  throw new Error("Set the entire raw body to {{paymentWebhookBody}} without quotes");
}

const amount = Number(pm.environment.get("paymentAmount"));
const currency = pm.environment.get("paymentCurrency");
const payload = {
  orderId: pm.environment.get("paymentOrderId"),
  providerOrderId: pm.environment.get("providerOrderId"),
  eventId: pm.environment.get("paymentEventId"),
  status: pm.environment.get("paymentStatus") || "COMPLETED",
  amount,
  currency,
};
if (!payload.orderId || !payload.providerOrderId || !payload.eventId ||
    !Number.isFinite(amount) || amount <= 0 || !currency) {
  throw new Error("Save order IDs, event ID, amount and currency before signing");
}
if (!["COMPLETED", "FAILED", "CANCELLED"].includes(payload.status)) {
  throw new Error("paymentStatus must be COMPLETED, FAILED or CANCELLED");
}

const rawBody = JSON.stringify(payload);
pm.variables.set("paymentWebhookBody", rawBody);
const timestamp = String(Math.floor(Date.now() / 1000));
const key = await crypto.subtle.importKey(
  "raw",
  Buffer.from(secret, "utf8"),
  { name: "HMAC", hash: "SHA-256" },
  false,
  ["sign"]
);
const digest = await crypto.subtle.sign(
  "HMAC",
  key,
  Buffer.from(timestamp + "." + rawBody, "utf8")
);
const signature = Buffer.from(digest).toString("hex");
pm.environment.set("paymentTimestamp", timestamp);
pm.environment.set("paymentSignature", signature);
pm.request.headers.upsert({ key: "X-Payment-Timestamp", value: timestamp });
pm.request.headers.upsert({ key: "X-Payment-Signature", value: signature });
pm.request.headers.upsert({ key: "Content-Type", value: "application/json" });
~~~

Raw body dùng biến thay thế, không sửa `pm.request.body` trong script. [Postman request properties](https://learning.postman.com/latest-v-12/docs/tests-and-scripts/write-scripts/postman-sandbox-reference/pm-request).

HMAC tính trên UTF-8 `timestamp + "." + rawBody`, timestamp lệch tối đa300 giây. Không sửa body sau ký. Script dùng Web Crypto và module buffer được sandbox hiện tại hỗ trợ; built-in crypto-js cũ đã deprecated/không còn được hỗ trợ. [Postman Sandbox libraries](https://learning.postman.com/latest-v-12/docs/tests-and-scripts/write-scripts/postman-sandbox-reference/pm-require).

COMPLETED mới trên order PENDING hợp lệ kích hoạt gói và tạo invoice; FAILED/CANCELLED không kích hoạt. Lặp webhook không tạo lại gói/invoice. Secret là cấu hình test server, không phải access token candidate và không đưa vào frontend.

## 6. Danh mục API và input mẫu

Mỗi mục có method/path gốc, URL Postman, header, body mode và các input. Không có query nghĩa là controller không khai báo query; không tự thêm limit/offset cho endpoint không hỗ trợ.

Trường **bắt buộc** phải có giá trị hợp lệ trong luồng đó; **tùy chọn** nên bỏ khỏi JSON khi chưa có dữ liệu. Bỏ field UUID tùy chọn nếu biến còn trống, không gửi `""` hoặc chuỗi placeholder. Các record/map extensible còn được service kiểm tra ngoài DTO.

**Tra nhanh 138 request:** bấm method/path để tới input của endpoint.

| # | Method / path | Quyền | Mục đích |
| --- | --- | --- | --- |
| API-001 | [POST /api/v1/auth/register](#api-001) | PUBLIC | Đăng ký tài khoản ứng viên |
| API-002 | [POST /api/v1/auth/verify-email](#api-002) | PUBLIC | Xác minh email bằng OTP |
| API-003 | [POST /api/v1/auth/resend-code](#api-003) | PUBLIC | Gửi lại OTP xác minh |
| API-004 | [POST /api/v1/auth/login](#api-004) | PUBLIC | Đăng nhập bằng email và mật khẩu |
| API-005 | [POST /api/v1/auth/refresh](#api-005) | PUBLIC | Đổi refresh token lấy cặp token mới |
| API-006 | [POST /api/v1/auth/logout](#api-006) | USER | Đăng xuất toàn bộ token của tài khoản |
| API-007 | [POST /api/v1/auth/google](#api-007) | PUBLIC | Đăng nhập hoặc đăng ký bằng Google ID token |
| API-008 | [GET /api/v1/me](#api-008) | USER | Xem thông tin cá nhân hiện tại |
| API-009 | [PUT /api/v1/me](#api-009) | USER | Cập nhật thông tin cá nhân |
| API-010 | [GET /api/v1/me/career-profile](#api-010) | USER | Xem hồ sơ nghề nghiệp và kỹ năng |
| API-011 | [PUT /api/v1/me/career-profile](#api-011) | USER | Cập nhật đầy đủ hồ sơ nghề nghiệp |
| API-012 | [DELETE /api/v1/me](#api-012) | USER | Đóng tài khoản hiện tại |
| API-013 | [PUT /api/v1/me/password](#api-013) | USER | Đổi mật khẩu và thu hồi toàn bộ token |
| API-014 | [GET /api/v1/me/sessions](#api-014) | USER | Liệt kê token còn hiệu lực của tài khoản |
| API-015 | [DELETE /api/v1/me/sessions/{id}](#api-015) | USER | Thu hồi một token row thuộc tài khoản |
| API-016 | [POST /api/v1/guest/sessions](#api-016) | PUBLIC | Tạo phiên guest dùng thử |
| API-017 | [GET /api/v1/guest/sessions/me](#api-017) | GUEST | Xem phiên guest và hạn mức còn lại |
| API-018 | [GET /api/v1/guest/demo-resumes](#api-018) | GUEST | Liệt kê CV demo hệ thống cho guest |
| API-019 | [GET /api/v1/guest/templates](#api-019) | GUEST | Liệt kê template cho phép dùng thử |
| API-020 | [POST /api/v1/guest/trials/build](#api-020) | GUEST | Sinh bản CV thử nghiệm từ demo và template |
| API-021 | [POST /api/v1/guest/trials/recommendations](#api-021) | GUEST | Gợi ý việc làm thử nghiệm từ CV demo |
| API-022 | [POST /api/v1/guest/assistant/conversations](#api-022) | GUEST | Tạo cuộc trò chuyện trợ lý cho guest |
| API-023 | [GET /api/v1/guest/assistant/conversations/{id}](#api-023) | GUEST | Xem conversation và lịch sử của guest |
| API-024 | [POST /api/v1/guest/assistant/conversations/{id}/messages](#api-024) | GUEST | Gửi câu hỏi nghề nghiệp cho trợ lý guest |
| API-025 | [GET /api/v1/resumes/templates](#api-025) | PUBLIC | Danh sách template CV đang hoạt động |
| API-026 | [GET /api/v1/resumes](#api-026) | USER | Danh sách CV cá nhân |
| API-027 | [GET /api/v1/resumes/{id}](#api-027) | USER | Chi tiết CV và lịch sử parsing |
| API-028 | [POST /api/v1/resumes](#api-028) | USER | Tạo CV thủ công đầy đủ |
| API-029 | [POST /api/v1/resumes/build](#api-029) | USER | Sinh CV bằng AI từ dữ kiện đầy đủ |
| API-030 | [POST /api/v1/resumes/upload](#api-030) | USER | Upload và trích xuất CV |
| API-031 | [PATCH /api/v1/resumes/{id}](#api-031) | USER | Sửa dữ liệu CV trước khi xác nhận lại |
| API-032 | [POST /api/v1/resumes/{id}/confirm](#api-032) | USER | Xác nhận dữ liệu CV và chọn primary |
| API-033 | [POST /api/v1/resumes/{id}/primary](#api-033) | USER | Chọn CV READY làm CV chính |
| API-034 | [DELETE /api/v1/resumes/{id}](#api-034) | USER | Lưu trữ CV cá nhân |
| API-035 | [POST /api/v1/resumes/{id}/analyses](#api-035) | USER | Phân tích chất lượng CV |
| API-036 | [GET /api/v1/resumes/{id}/analyses](#api-036) | USER | Lịch sử phân tích CV |
| API-037 | [GET /api/v1/resumes/{id}/analyses/{analysisId}](#api-037) | USER | Chi tiết một lần phân tích CV |
| API-038 | [POST /api/v1/resumes/{id}/embedding](#api-038) | USER | Tạo hoặc tái dùng vector CV |
| API-039 | [GET /api/v1/resumes/{id}/download](#api-039) | USER | Tải CV gốc hoặc nội dung đã review |
| API-040 | [GET /api/v1/skills](#api-040) | PUBLIC | Danh mục kỹ năng công khai |
| API-041 | [GET /api/v1/jobs](#api-041) | PUBLIC | Tìm việc làm đang hoạt động |
| API-042 | [GET /api/v1/jobs/{id}](#api-042) | PUBLIC | Chi tiết việc làm và kỹ năng yêu cầu |
| API-043 | [POST /api/v1/matching](#api-043) | CANDIDATE | So khớp ứng viên với việc làm |
| API-044 | [GET /api/v1/matching/{id}](#api-044) | USER | Chi tiết lần matching của mình |
| API-045 | [GET /api/v1/matching](#api-045) | USER | Lịch sử matching cá nhân |
| API-046 | [GET /api/v1/ai/usage](#api-046) | CANDIDATE | Lịch sử AI và token của ứng viên |
| API-047 | [PUT /api/v1/matching/results/{id}/feedback](#api-047) | USER | Đánh giá kết quả matching |
| API-048 | [POST /api/v1/jobs/{jobId}/ats](#api-048) | CANDIDATE | Phân tích ATS của CV theo job |
| API-049 | [POST /api/v1/jobs/{jobId}/resume-draft](#api-049) | CANDIDATE | Sinh CV hướng tới một job |
| API-050 | [POST /api/v1/jobs/{jobId}/application-drafts](#api-050) | CANDIDATE | Sinh email ứng tuyển và cover letter |
| API-051 | [GET /api/v1/application-drafts](#api-051) | USER | Lịch sử nội dung ứng tuyển |
| API-052 | [PATCH /api/v1/application-drafts/{id}](#api-052) | USER | Sửa nội dung email hoặc cover letter |
| API-053 | [POST /api/v1/jobs/{id}/external-apply](#api-053) | CANDIDATE | Lấy URL ứng tuyển ngoài và lưu event |
| API-054 | [POST /api/v1/jobs/{id}/salary-estimation](#api-054) | CANDIDATE | Ước lượng lương theo job và profile |
| API-055 | [POST /api/v1/salary-estimations](#api-055) | CANDIDATE | Ước lượng lương theo nghề không cần job |
| API-056 | [POST /api/v1/assessments](#api-056) | CANDIDATE | Sinh bài đánh giá kỹ năng |
| API-057 | [GET /api/v1/assessments](#api-057) | USER | Lịch sử assessment cá nhân |
| API-058 | [GET /api/v1/assessments/{id}](#api-058) | USER | Chi tiết assessment và lựa chọn đã lưu |
| API-059 | [PUT /api/v1/assessments/{id}/answers](#api-059) | USER | Lưu lựa chọn cho một câu hỏi |
| API-060 | [POST /api/v1/assessments/{id}/submit](#api-060) | USER | Nộp và chấm bài assessment |
| API-061 | [POST /api/v1/roadmaps](#api-061) | CANDIDATE | Tạo skill gap và lộ trình học |
| API-062 | [GET /api/v1/roadmaps](#api-062) | USER | Lịch sử roadmap cá nhân |
| API-063 | [GET /api/v1/roadmaps/{id}](#api-063) | USER | Chi tiết roadmap, gaps và resources |
| API-064 | [PATCH /api/v1/roadmaps/{id}/nodes/{nodeId}](#api-064) | USER | Cập nhật tiến độ học một node |
| API-065 | [GET /api/v1/assistant/conversations](#api-065) | USER | Danh sách hội thoại assistant |
| API-066 | [POST /api/v1/assistant/conversations](#api-066) | USER | Tạo hội thoại có ngữ cảnh nghề nghiệp |
| API-067 | [GET /api/v1/assistant/conversations/{id}](#api-067) | USER | Đọc hội thoại, messages và sources |
| API-068 | [POST /api/v1/assistant/conversations/{id}/messages](#api-068) | USER | Gửi tin nhắn assistant, tùy chọn RAG |
| API-069 | [DELETE /api/v1/assistant/conversations/{id}](#api-069) | USER | Lưu trữ hội thoại assistant |
| API-070 | [POST /api/v1/assistant/messages/{id}/feedback](#api-070) | USER | Đánh giá câu trả lời assistant |
| API-071 | [POST /api/v1/consultations](#api-071) | CANDIDATE | Candidate tạo yêu cầu tư vấn |
| API-072 | [GET /api/v1/consultations](#api-072) | USER | Xem các consultation được phép truy cập |
| API-073 | [GET /api/v1/consultations/{id}](#api-073) | THREAD | Xem một consultation |
| API-074 | [GET /api/v1/consultations/{id}/messages](#api-074) | THREAD | Đọc lịch sử tin nhắn tư vấn |
| API-075 | [POST /api/v1/consultations/{id}/messages](#api-075) | THREAD | Gửi tin nhắn trong thread tư vấn |
| API-076 | [PATCH /api/v1/consultations/{id}/messages/{messageId}/read](#api-076) | THREAD | Đánh dấu tin nhắn tư vấn đã đọc |
| API-077 | [POST /api/v1/consultations/{id}/close](#api-077) | THREAD | Đóng consultation |
| API-078 | [GET /api/v1/consultations/{id}/events](#api-078) | THREAD | Subscribe SSE tin nhắn tư vấn |
| API-079 | [PUT /api/v1/consultations/{id}/consultant](#api-079) | ADMIN | Admin phân công tư vấn viên |
| API-080 | [GET /api/v1/consultations/{id}/candidate-profile](#api-080) | THREAD | Đọc career profile và CV của candidate trong thread |
| API-081 | [GET /api/v1/consultations/{id}/candidate-analyses](#api-081) | THREAD | Đọc các báo cáo đã lưu của candidate |
| API-082 | [GET /api/v1/subscriptions/tiers](#api-082) | PUBLIC | Xem các gói và quota đang hoạt động |
| API-083 | [GET /api/v1/subscriptions/me](#api-083) | USER | Xem gói hiệu lực và hạn mức tài khoản |
| API-084 | [GET /api/v1/subscriptions/history](#api-084) | USER | Xem lịch sử subscription của tài khoản |
| API-085 | [POST /api/v1/subscriptions/{id}/cancel](#api-085) | USER | Hủy subscription thuộc tài khoản |
| API-086 | [POST /api/v1/payments/orders](#api-086) | USER | Tạo đơn mua gói có trả phí |
| API-087 | [GET /api/v1/payments/orders](#api-087) | USER | Liệt kê đơn thanh toán của tài khoản |
| API-088 | [GET /api/v1/payments/orders/{id}](#api-088) | USER | Xem một đơn thanh toán thuộc tài khoản |
| API-089 | [POST /api/v1/payments/orders/{id}/reports](#api-089) | USER | Tạo báo cáo lỗi cho đơn thanh toán |
| API-090 | [GET /api/v1/payments/reports](#api-090) | USER | Liệt kê báo cáo lỗi thanh toán của tài khoản |
| API-091 | [GET /api/v1/invoices](#api-091) | USER | Liệt kê hóa đơn thuộc tài khoản |
| API-092 | [GET /api/v1/invoices/{id}](#api-092) | USER | Xem hóa đơn thuộc tài khoản |
| API-093 | [POST /api/v1/payments/webhook](#api-093) | PUBLIC | Xử lý webhook thanh toán có chữ ký HMAC |
| API-094 | [GET /api/v1/admin/dashboard](#api-094) | ADMIN | Thống kê quản trị |
| API-095 | [GET /api/v1/admin/users](#api-095) | ADMIN | Tìm danh sách tài khoản |
| API-096 | [GET /api/v1/admin/users/{id}](#api-096) | ADMIN | Xem tài khoản theo ID |
| API-097 | [POST /api/v1/admin/users](#api-097) | ADMIN | Admin tạo tài khoản consultant |
| API-098 | [PUT /api/v1/admin/users/{id}](#api-098) | ADMIN | Admin cập nhật tài khoản |
| API-099 | [DELETE /api/v1/admin/users/{id}](#api-099) | ADMIN | Admin soft-delete tài khoản |
| API-100 | [GET /api/v1/admin/subscription-tiers](#api-100) | ADMIN | Xem gói và cấu hình quota |
| API-101 | [POST /api/v1/admin/subscription-tiers](#api-101) | ADMIN | Tạo subscription tier |
| API-102 | [PUT /api/v1/admin/subscription-tiers/{id}](#api-102) | ADMIN | Sửa subscription tier |
| API-103 | [PUT /api/v1/admin/subscription-tiers/{id}/quotas](#api-103) | ADMIN | Upsert hạn mức theo feature |
| API-104 | [GET /api/v1/admin/ai/configurations](#api-104) | ADMIN | Xem lịch sử AI configuration |
| API-105 | [POST /api/v1/admin/ai/configurations](#api-105) | ADMIN | Lưu phiên bản AI configuration |
| API-106 | [POST /api/v1/admin/ai/configurations/{oldVersionId}/restore](#api-106) | ADMIN | Khôi phục cấu hình thành phiên bản mới |
| API-107 | [GET /api/v1/admin/ai/prompts](#api-107) | ADMIN | Xem lịch sử prompt |
| API-108 | [POST /api/v1/admin/ai/prompts](#api-108) | ADMIN | Lưu phiên bản prompt cho feature |
| API-109 | [POST /api/v1/admin/ai/prompts/{oldVersionId}/restore](#api-109) | ADMIN | Khôi phục prompt thành phiên bản mới |
| API-110 | [GET /api/v1/admin/monitoring/{category}](#api-110) | ADMIN | Xem logs và trạng thái vận hành |
| API-111 | [GET /api/v1/admin/payment-reports](#api-111) | ADMIN | Xem các báo cáo thanh toán |
| API-112 | [POST /api/v1/admin/payment-reports/{id}/resolve](#api-112) | ADMIN | Đánh dấu báo cáo thanh toán đã giải quyết |
| API-113 | [POST /api/v1/admin/jobs](#api-113) | ADMIN | Import job bằng dữ liệu admin cung cấp |
| API-114 | [POST /api/v1/admin/jobs/{id}/index](#api-114) | ADMIN | Sinh embedding cho job |
| API-138 | [GET /api/v1/admin/jobs/{id}/index](#api-138) | ADMIN | Xem trạng thái hàng đợi embedding job |
| API-115 | [PATCH /api/v1/admin/jobs/{id}/status](#api-115) | ADMIN | Đổi trạng thái job |
| API-116 | [POST /api/v1/admin/jobs/crawl](#api-116) | ADMIN | Tìm và crawl job từ keyword hoặc URL có sẵn |
| API-117 | [GET /api/v1/admin/jobs/crawl](#api-117) | ADMIN | Xem lịch sử crawl runs |
| API-118 | [GET /api/v1/admin/jobs/crawl/{id}](#api-118) | ADMIN | Xem crawl run và từng item |
| API-119 | [GET /api/v1/admin/rag/documents](#api-119) | ADMIN | Xem danh sách tài liệu RAG |
| API-120 | [POST /api/v1/admin/rag/documents](#api-120) | ADMIN | Nhập tài liệu RAG dạng text |
| API-121 | [GET /api/v1/admin/rag/documents/{id}](#api-121) | ADMIN | Xem tài liệu RAG và chunks |
| API-122 | [POST /api/v1/admin/rag/documents/{id}/index](#api-122) | ADMIN | Index embedding tài liệu RAG |
| API-123 | [POST /api/v1/admin/rag/documents/{id}/archive](#api-123) | ADMIN | Archive tài liệu khỏi retrieval |
| API-124 | [GET /api/v1/admin/announcements](#api-124) | ADMIN | Admin xem mọi announcement |
| API-125 | [POST /api/v1/admin/announcements](#api-125) | ADMIN | Tạo announcement nháp |
| API-126 | [PUT /api/v1/admin/announcements/{id}](#api-126) | ADMIN | Sửa nội dung và audience announcement |
| API-127 | [POST /api/v1/admin/announcements/{id}/publish](#api-127) | ADMIN | Publish announcement và tạo notifications |
| API-128 | [POST /api/v1/admin/announcements/{id}/archive](#api-128) | ADMIN | Archive announcement |
| API-129 | [GET /api/v1/announcements](#api-129) | USER | Xem thông báo đúng audience |
| API-130 | [GET /api/v1/notifications](#api-130) | USER | Xem thông báo riêng của tài khoản |
| API-131 | [PATCH /api/v1/notifications/{id}/read](#api-131) | USER | Đánh dấu một notification đã đọc |
| API-132 | [POST /api/v1/notifications/read-all](#api-132) | USER | Đánh dấu tất cả notification đã đọc |
| API-133 | [GET /api/v1/support/tickets](#api-133) | USER | Xem support tickets |
| API-134 | [POST /api/v1/support/tickets](#api-134) | USER | Tạo yêu cầu hỗ trợ |
| API-135 | [GET /api/v1/support/tickets/{id}](#api-135) | USER | Xem ticket và lịch sử hỗ trợ |
| API-136 | [POST /api/v1/support/tickets/{id}/messages](#api-136) | USER | Gửi tin nhắn hỗ trợ |
| API-137 | [PATCH /api/v1/support/tickets/{id}](#api-137) | ADMIN | Admin cập nhật trạng thái và phân công ticket |

### 6.1. Đăng ký, OTP, đăng nhập

[AuthController](../src/main/java/com/example/intelligent_cv_matching_ai_agent/controller/shared/identity/AuthController.java).

<a id="api-001"></a>

#### API-001 — Đăng ký tài khoản ứng viên

**Method/path:** `POST /api/v1/auth/register`  
**URL Postman:** `{{baseUrl}}/auth/register`  
**Quyền:** `PUBLIC`  
**Body mode:** `json`

Authorization tab: **No Auth**.

**Headers:**

| Key | Value |
| --- | --- |
| `Content-Type` | `application/json` |

**Query:** Không có query parameter được khai báo.

**Input fields:**

| Field | Type | Bắt buộc | Validation / cách dùng |
| --- | --- | --- | --- |
| `email` | string | Có | Email hợp lệ, không trống, tối đa 255 ký tự; service trim/lowercase. |
| `password` | string | Có | Không trống, 10–72 ký tự khi đăng ký; tối đa 72 byte UTF-8. |
| `fullName` | string | Có | Không trống, tối đa 255 ký tự; service trim. |
| `preferredLanguage` | string | Không | Chỉ vi hoặc en; thiếu/null mặc định vi. |

**Body → raw → JSON (input mẫu):**

~~~json
{
  "email": "{{candidateEmail}}",
  "password": "{{candidatePassword}}",
  "fullName": "Ứng viên thử nghiệm",
  "preferredLanguage": "vi"
}
~~~

**Kết quả mong đợi:** 200 — message và expiresIn=600; dev mode có developmentCode.

**Lưu biến** (`json` = `pm.response.json()`; chỉ lưu khi response thành công và có dữ liệu):

| Biến | Expression | Lưu ý |
| --- | --- | --- |
| `otpCode` | `json.developmentCode` | Chỉ lưu nếu field tồn tại khi AUTH_EXPOSE_DEV_OTP=true. SMTP mode đọc OTP trong Mailtrap rồi tự đặt otpCode; dev mode bỏ gửi SMTP. |

**Điều kiện và lưu ý:**

- Dùng email mới; email đã tồn tại trả 409. Tạo EMAIL/CANDIDATE/PENDING; chưa cấp token.
- Giới hạn 10 request/IP/giờ.
- AUTH_EXPOSE_DEV_OTP=false yêu cầu MAIL_ENABLED=true và SMTP; lỗi gửi trả 503, rollback user/OTP.

<a id="api-002"></a>

#### API-002 — Xác minh email bằng OTP

**Method/path:** `POST /api/v1/auth/verify-email`  
**URL Postman:** `{{baseUrl}}/auth/verify-email`  
**Quyền:** `PUBLIC`  
**Body mode:** `json`

Authorization tab: **No Auth**.

**Headers:**

| Key | Value |
| --- | --- |
| `Content-Type` | `application/json` |

**Query:** Không có query parameter được khai báo.

**Input fields:**

| Field | Type | Bắt buộc | Validation / cách dùng |
| --- | --- | --- | --- |
| `email` | string | Có | Email hợp lệ, không trống; DTO này không khai báo Size(max=255). |
| `code` | string | Có | Đúng 6 chữ số ASCII [0-9]{6}, giữ dưới dạng chuỗi để không mất số 0 đầu. |

**Body → raw → JSON (input mẫu):**

~~~json
{
  "email": "{{candidateEmail}}",
  "code": "{{otpCode}}"
}
~~~

**Kết quả mong đợi:** 200 — {message:'Email verified; you can log in'}.

**Điều kiện và lưu ý:**

- User phải PENDING; OTP chưa dùng và còn hạn 10 phút.
- 5 lần sai đầu trả 400; request tiếp theo trả 429, kể cả mã đúng. DTO sai định dạng không tăng attempt DB.
- Giới hạn 30 request/IP/10 phút. Verify đúng chuyển ACTIVE; gọi login riêng.

<a id="api-003"></a>

#### API-003 — Gửi lại OTP xác minh

**Method/path:** `POST /api/v1/auth/resend-code`  
**URL Postman:** `{{baseUrl}}/auth/resend-code`  
**Quyền:** `PUBLIC`  
**Body mode:** `json`

Authorization tab: **No Auth**.

**Headers:**

| Key | Value |
| --- | --- |
| `Content-Type` | `application/json` |

**Query:** Không có query parameter được khai báo.

**Input fields:**

| Field | Type | Bắt buộc | Validation / cách dùng |
| --- | --- | --- | --- |
| `email` | string | Có | Email hợp lệ, không trống, tối đa 255 ký tự; service trim/lowercase. |

**Body → raw → JSON (input mẫu):**

~~~json
{
  "email": "{{candidateEmail}}"
}
~~~

**Kết quả mong đợi:** 200 — message; nhánh PENDING có expiresIn=600, dev mode có developmentCode.

**Lưu biến** (`json` = `pm.response.json()`; chỉ lưu khi response thành công và có dữ liệu):

| Biến | Expression | Lưu ý |
| --- | --- | --- |
| `otpCode` | `json.developmentCode` | Chỉ lưu nếu field tồn tại khi AUTH_EXPOSE_DEV_OTP=true. SMTP mode đọc OTP trong Mailtrap rồi tự đặt otpCode; dev mode bỏ gửi SMTP. |

**Điều kiện và lưu ý:**

- Chỉ gửi cho user PENDING; không tồn tại/không PENDING trả message chung, không expiresIn và không gửi.
- Chờ ít nhất 60 giây từ mã trước; sớm hơn trả 429. Gửi mã mới thành công làm mã cũ hết hạn.
- Giới hạn 10 request/IP/giờ. Gửi SMTP lỗi rollback thay đổi OTP.

<a id="api-004"></a>

#### API-004 — Đăng nhập bằng email và mật khẩu

**Method/path:** `POST /api/v1/auth/login`  
**URL Postman:** `{{baseUrl}}/auth/login`  
**Quyền:** `PUBLIC`  
**Body mode:** `json`

Authorization tab: **No Auth**.

**Headers:**

| Key | Value |
| --- | --- |
| `Content-Type` | `application/json` |

**Query:** Không có query parameter được khai báo.

**Input fields:**

| Field | Type | Bắt buộc | Validation / cách dùng |
| --- | --- | --- | --- |
| `email` | string | Có | Email hợp lệ, không trống, tối đa 255 ký tự; service trim/lowercase. |
| `password` | string | Có | Không trống, tối đa 72 ký tự và 72 byte UTF-8; DTO login không yêu cầu tối thiểu 10 ký tự. |

**Body → raw → JSON (input mẫu):**

~~~json
{
  "email": "{{candidateEmail}}",
  "password": "{{candidatePassword}}"
}
~~~

**Kết quả mong đợi:** 200 — accessToken, refreshToken, tokenType='Bearer', expiresIn=900.

**Lưu biến** (`json` = `pm.response.json()`; chỉ lưu khi response thành công và có dữ liệu):

| Biến | Expression | Lưu ý |
| --- | --- | --- |
| `candidateAccessToken` | `json.accessToken` | JWT access 15 phút; dùng Bearer cho request riêng tư. |
| `candidateRefreshToken` | `json.refreshToken` | Refresh random r_ sống 30 ngày; cập nhật token sau login/rotate. |
| `candidateExpiresIn` | `json.accessToken ? json.expiresIn : undefined` | Số giây access token có hiệu lực; hiện 900. |

**Điều kiện và lưu ý:**

- Chỉ EMAIL provider, tài khoản ACTIVE/chưa deleted. Sai thông tin trả 401; password đúng nhưng PENDING/inactive trả 403.
- Giới hạn 100/IP/15 phút và 15/email/15 phút.
- Có thể dùng cùng route cho account ADMIN/CAREER_CONSULTANT; lưu token vào biến role tương ứng khi đổi tài khoản.

<a id="api-005"></a>

#### API-005 — Đổi refresh token lấy cặp token mới

**Method/path:** `POST /api/v1/auth/refresh`  
**URL Postman:** `{{baseUrl}}/auth/refresh`  
**Quyền:** `PUBLIC`  
**Body mode:** `json`

Authorization tab: **No Auth**.

**Headers:**

| Key | Value |
| --- | --- |
| `Content-Type` | `application/json` |

**Query:** Không có query parameter được khai báo.

**Input fields:**

| Field | Type | Bắt buộc | Validation / cách dùng |
| --- | --- | --- | --- |
| `refreshToken` | string | Có | DTO không trống/max120; service yêu cầu prefix r_ và dài đúng 45 ký tự. |

**Body → raw → JSON (input mẫu):**

~~~json
{
  "refreshToken": "{{candidateRefreshToken}}"
}
~~~

**Kết quả mong đợi:** 200 — accessToken, refreshToken, tokenType='Bearer', expiresIn=900.

**Lưu biến** (`json` = `pm.response.json()`; chỉ lưu khi response thành công và có dữ liệu):

| Biến | Expression | Lưu ý |
| --- | --- | --- |
| `candidateAccessToken` | `json.accessToken` | JWT access 15 phút; dùng Bearer cho request riêng tư. |
| `candidateRefreshToken` | `json.refreshToken` | Refresh random r_ sống 30 ngày; cập nhật token sau login/rotate. |
| `candidateExpiresIn` | `json.accessToken ? json.expiresIn : undefined` | Số giây access token có hiệu lực; hiện 900. |

**Điều kiện và lưu ý:**

- Giới hạn 100/IP/15 phút; token refresh chỉ dùng một lần. Token hết hạn/đã dùng hoặc account không ACTIVE trả 401.
- Cập nhật cả access và refresh sau response; không chạy đồng thời hai request với cùng refresh.
- Rotate không tự revoke access cũ; logout/đổi mật khẩu/đình chỉ có thể revoke.

<a id="api-006"></a>

#### API-006 — Đăng xuất toàn bộ token của tài khoản

**Method/path:** `POST /api/v1/auth/logout`  
**URL Postman:** `{{baseUrl}}/auth/logout`  
**Quyền:** `USER`  
**Body mode:** `none`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{activeToken}}` |

**Query:** Không có query parameter được khai báo.

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 200 — {message:'All sessions logged out'}.

**Điều kiện và lưu ý:**

- Không body. Cần access token hợp lệ.
- Thu hồi mọi token của account, gồm JWT access và refresh; dọn biến token trong Postman sau response.

<a id="api-007"></a>

#### API-007 — Đăng nhập hoặc đăng ký bằng Google ID token

**Method/path:** `POST /api/v1/auth/google`  
**URL Postman:** `{{baseUrl}}/auth/google`  
**Quyền:** `PUBLIC`  
**Body mode:** `json`

Authorization tab: **No Auth**.

**Headers:**

| Key | Value |
| --- | --- |
| `Content-Type` | `application/json` |

**Query:** Không có query parameter được khai báo.

**Input fields:**

| Field | Type | Bắt buộc | Validation / cách dùng |
| --- | --- | --- | --- |
| `idToken` | string | Có | Không trống/max10000; phải Google ID token hợp lệ dành GOOGLE_CLIENT_ID của backend. |

**Body → raw → JSON (input mẫu):**

~~~json
{
  "idToken": "{{googleIdToken}}"
}
~~~

**Kết quả mong đợi:** 200 — cặp token nếu ACTIVE; hoặc kết quả gửi OTP; hoặc message+emailVerificationRequired=true.

**Lưu biến** (`json` = `pm.response.json()`; chỉ lưu khi response thành công và có dữ liệu):

| Biến | Expression | Lưu ý |
| --- | --- | --- |
| `candidateAccessToken` | `json.accessToken` | JWT access 15 phút; dùng Bearer cho request riêng tư. |
| `candidateRefreshToken` | `json.refreshToken` | Refresh random r_ sống 30 ngày; cập nhật token sau login/rotate. |
| `candidateExpiresIn` | `json.accessToken ? json.expiresIn : undefined` | Số giây access token có hiệu lực; hiện 900. |
| `otpCode` | `json.developmentCode` | Chỉ lưu nếu field tồn tại khi AUTH_EXPOSE_DEV_OTP=true. SMTP mode đọc OTP trong Mailtrap rồi tự đặt otpCode; dev mode bỏ gửi SMTP. |

**Điều kiện và lưu ý:**

- Backend thiếu GOOGLE_CLIENT_ID trả 503; token claims/signature không hợp lệ trả 401; giới hạn 30/IP/15 phút.
- Không tự link account theo email; email đã có account khác trả 409.
- User mới Gmail hoặc token có hd != null được ACTIVE; email bên thứ ba cần OTP hệ thống. User Google PENDING đã tồn tại trả emailVerificationRequired=true; dùng resend-code rồi đăng nhập Google lại.
- Chỉ lưu token nếu response có accessToken/refreshToken; nhánh gửi OTP trả message/expiresIn hoặc developmentCode.

### 6.2. Tài khoản cá nhân và career profile

[ProfileController](../src/main/java/com/example/intelligent_cv_matching_ai_agent/controller/shared/identity/ProfileController.java).

<a id="api-008"></a>

#### API-008 — Xem thông tin cá nhân hiện tại

**Method/path:** `GET /api/v1/me`  
**URL Postman:** `{{baseUrl}}/me`  
**Quyền:** `USER`  
**Body mode:** `none`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{activeToken}}` |

**Query:** Không có query parameter được khai báo.

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 200 — object profile công khai.

**Lưu biến** (`json` = `pm.response.json()`; chỉ lưu khi response thành công và có dữ liệu):

| Biến | Expression | Lưu ý |
| --- | --- | --- |
| `candidateId` | `json.id` | UUID người gọi; chỉ dùng như ID tham chiếu, quyền quyết định bởi token. |

**Điều kiện và lưu ý:**

- Không body/query; user lấy từ Bearer, không gửi userId.
- Response có snake_case như full_name, auth_provider, email_verified_at; không password hash/Google subject.

<a id="api-009"></a>

#### API-009 — Cập nhật thông tin cá nhân

**Method/path:** `PUT /api/v1/me`  
**URL Postman:** `{{baseUrl}}/me`  
**Quyền:** `USER`  
**Body mode:** `json`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{activeToken}}` |
| `Content-Type` | `application/json` |

**Query:** Không có query parameter được khai báo.

**Input fields:**

| Field | Type | Bắt buộc | Validation / cách dùng |
| --- | --- | --- | --- |
| `fullName` | string | Có | Không trống/max255; trim. |
| `avatarUrl` | string\|null | Không | Tối đa 2000; không có validation URL riêng. |
| `preferredLanguage` | string\|null | Không | vi hoặc en; null giữ language hiện tại nhờ COALESCE. |
| `githubUrl` | string\|null | Không | Tối đa 255; không có validation URL riêng. |

**Body → raw → JSON (input mẫu):**

~~~json
{
  "fullName": "Ứng viên thử nghiệm",
  "avatarUrl": "https://example.com/avatar.png",
  "preferredLanguage": "vi",
  "githubUrl": "https://github.com/example"
}
~~~

**Kết quả mong đợi:** 200 — profile mới.

**Lưu biến** (`json` = `pm.response.json()`; chỉ lưu khi response thành công và có dữ liệu):

| Biến | Expression | Lưu ý |
| --- | --- | --- |
| `candidateId` | `json.id` | UUID account hiện tại. |

**Điều kiện và lưu ý:**

- PUT; fullName bắt buộc. avatarUrl/githubUrl bỏ qua hoặc null sẽ được ghi null.
- Response profile dùng snake_case; request dùng camelCase.

<a id="api-010"></a>

#### API-010 — Xem hồ sơ nghề nghiệp và kỹ năng

**Method/path:** `GET /api/v1/me/career-profile`  
**URL Postman:** `{{baseUrl}}/me/career-profile`  
**Quyền:** `USER`  
**Body mode:** `none`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{activeToken}}` |

**Query:** Không có query parameter được khai báo.

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 200 — career profile kèm skills.

**Lưu biến** (`json` = `pm.response.json()`; chỉ lưu khi response thành công và có dữ liệu):

| Biến | Expression | Lưu ý |
| --- | --- | --- |
| `candidateId` | `json.user_id` | UUID owner của career profile. |

**Điều kiện và lưu ý:**

- Không body/query. Nếu chưa có candidate_profiles, vẫn trả user_id và skills.
- Response field SQL snake_case; skills gồm skill_id/name/slug/proficiency_level/years_experience.

<a id="api-011"></a>

#### API-011 — Cập nhật đầy đủ hồ sơ nghề nghiệp

**Method/path:** `PUT /api/v1/me/career-profile`  
**URL Postman:** `{{baseUrl}}/me/career-profile`  
**Quyền:** `USER`  
**Body mode:** `json`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{activeToken}}` |
| `Content-Type` | `application/json` |

**Query:** Không có query parameter được khai báo.

**Input fields:**

| Field | Type | Bắt buộc | Validation / cách dùng |
| --- | --- | --- | --- |
| `currentJobTitle` | string\|null | Không | Tối đa 255. |
| `currentCompany` | string\|null | Không | Tối đa 255. |
| `targetJobTitle` | string\|null | Không | Tối đa 255. |
| `careerGoal` | string\|null | Không | Tối đa 10000. |
| `yearsExperience` | number\|null | Không | 0–99.9; tối đa 1 chữ số thập phân. |
| `expectedSalaryMin` | number\|null | Không | Không âm; tối đa 12 chữ số nguyên, 2 thập phân. |
| `expectedSalaryMax` | number\|null | Không | Không âm; tối đa 12 chữ số nguyên, 2 thập phân; &gt;= min nếu cả hai có giá trị. |
| `salaryCurrency` | string\|null | Không | VND\|USD\|EUR. |
| `preferredLocation` | string\|null | Không | Tối đa 255. |
| `workplacePreference` | string\|null | Không | Tối đa 50; DTO không giới hạn enum. |
| `employmentType` | string\|null | Không | Tối đa 50; DTO không giới hạn enum. |
| `profileSummary` | string\|null | Không | Tối đa 10000. |
| `preferences` | object\|null | Không | JSON tùy chọn; không có schema key cố định trong DTO. |
| `skills` | array\|null | Không | Tối đa 100; null/bỏ qua giữ links cũ; [] xóa links. |
| `skills[].skillId` | UUID string | Có | Bắt buộc trong mỗi phần tử, skill phải tồn tại/active; ID không được trùng. |
| `skills[].proficiencyLevel` | string\|null | Không | Tối đa 50; không validation enum. |
| `skills[].yearsExperience` | number\|null | Không | 0–99.9; tối đa 1 chữ số thập phân. |

**Body → raw → JSON (input mẫu):**

~~~json
{
  "currentJobTitle": "Junior Java Developer",
  "currentCompany": "Công ty mẫu",
  "targetJobTitle": "Java Backend Developer",
  "careerGoal": "Phát triển backend Spring Boot",
  "yearsExperience": 1.5,
  "expectedSalaryMin": 10000000,
  "expectedSalaryMax": 20000000,
  "salaryCurrency": "VND",
  "preferredLocation": "Ho Chi Minh",
  "workplacePreference": "HYBRID",
  "employmentType": "FULL_TIME",
  "profileSummary": "Có kinh nghiệm Java, REST API và PostgreSQL.",
  "preferences": {
    "willingToRelocate": false,
    "preferredIndustries": [
      "Software"
    ]
  },
  "skills": [
    {
      "skillId": "{{skillId}}",
      "proficiencyLevel": "intermediate",
      "yearsExperience": 1.5
    }
  ]
}
~~~

**Kết quả mong đợi:** 200 — profile mới kèm skills.

**Lưu biến** (`json` = `pm.response.json()`; chỉ lưu khi response thành công và có dữ liệu):

| Biến | Expression | Lưu ý |
| --- | --- | --- |
| `candidateId` | `json.user_id` | UUID owner của career profile. |

**Điều kiện và lưu ý:**

- Cần skillId thực từ GET /skills; UUID luôn nằm trong chuỗi JSON.
- PUT cập nhật scalar; field bỏ qua thường trở thành null. Gửi đủ giá trị muốn giữ.
- Tạo profile nếu chưa có, vô hiệu current profile embeddings và đánh dấu roadmap user outdated.
- Mẫu preferences chỉ minh họa dữ liệu JSON; không cam kết có xử lý nghiệp vụ các key này.

<a id="api-012"></a>

#### API-012 — Đóng tài khoản hiện tại

**Method/path:** `DELETE /api/v1/me`  
**URL Postman:** `{{baseUrl}}/me`  
**Quyền:** `USER`  
**Body mode:** `none`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{activeToken}}` |

**Query:** Không có query parameter được khai báo.

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 200 — {message:'Account closed'}.

**Điều kiện và lưu ý:**

- Không body. Thực hiện cuối quy trình test trên account thử nghiệm.
- Soft delete status DELETED/deleted_at và revoke mọi token; giữ lịch sử/FK.
- Không cho xóa admin ACTIVE cuối cùng, trả 409.

<a id="api-013"></a>

#### API-013 — Đổi mật khẩu và thu hồi toàn bộ token

**Method/path:** `PUT /api/v1/me/password`  
**URL Postman:** `{{baseUrl}}/me/password`  
**Quyền:** `USER`  
**Body mode:** `json`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{activeToken}}` |
| `Content-Type` | `application/json` |

**Query:** Không có query parameter được khai báo.

**Input fields:**

| Field | Type | Bắt buộc | Validation / cách dùng |
| --- | --- | --- | --- |
| `currentPassword` | string | Có | Không trống/max72 ký tự và &lt;=72 byte UTF-8. |
| `newPassword` | string | Có | Không trống, 10–72 ký tự và &lt;=72 byte UTF-8. |

**Body → raw → JSON (input mẫu):**

~~~json
{
  "currentPassword": "{{candidatePassword}}",
  "newPassword": "{{candidateNewPassword}}"
}
~~~

**Kết quả mong đợi:** 200 — {message:'Password changed; log in again'}.

**Điều kiện và lưu ý:**

- Chỉ EMAIL provider; Google user trả 400 và đổi password qua provider.
- Mật khẩu hiện tại sai trả 401. Thành công revoke tất cả access/refresh; cập nhật candidatePassword bằng mật khẩu mới và login lại.
- Không có workflow forgot-password/reset qua email tại đây.

<a id="api-014"></a>

#### API-014 — Liệt kê token còn hiệu lực của tài khoản

**Method/path:** `GET /api/v1/me/sessions`  
**URL Postman:** `{{baseUrl}}/me/sessions`  
**Quyền:** `USER`  
**Body mode:** `none`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{activeToken}}` |

**Query:** Không có query parameter được khai báo.

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 200 — array token rows.

**Lưu biến** (`json` = `pm.response.json()`; chỉ lưu khi response thành công và có dữ liệu):

| Biến | Expression | Lưu ý |
| --- | --- | --- |
| `sessionId` | `json[0]?.id` | Chọn row muốn revoke; chỉ lưu khi array không rỗng, có thể chính là access hiện dùng. |

**Điều kiện và lưu ý:**

- Không body/query; tối đa 100 rows.
- Cả access và refresh lưu cùng refresh_tokens; đây không phải session thiết bị hoặc token pair. Response chỉ id/created_at/expires_at.

<a id="api-015"></a>

#### API-015 — Thu hồi một token row thuộc tài khoản

**Method/path:** `DELETE /api/v1/me/sessions/{id}`  
**URL Postman:** `{{baseUrl}}/me/sessions/{{sessionId}}`  
**Quyền:** `USER`  
**Body mode:** `none`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{activeToken}}` |

**Path variables:**

| Param | Giá trị Postman | Điều kiện |
| --- | --- | --- |
| `id` | `{{sessionId}}` | UUID row từ GET /me/sessions, không raw token. |

**Query:** Không có query parameter được khai báo.

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 200 — {message:'Session revoked'}.

**Điều kiện và lưu ý:**

- Không body. ID phải thuộc user, sai ownership trả 404.
- Nếu revoke row của access hiện dùng, request riêng tư tiếp theo trả 401; đổi refresh nếu còn hợp lệ hoặc login.

### 6.3. Guest và dùng thử

[GuestController](../src/main/java/com/example/intelligent_cv_matching_ai_agent/controller/guest/identity/GuestController.java), [GuestTrialController](../src/main/java/com/example/intelligent_cv_matching_ai_agent/controller/guest/ai/GuestTrialController.java), [GuestAssistantController](../src/main/java/com/example/intelligent_cv_matching_ai_agent/controller/guest/ai/GuestAssistantController.java).

<a id="api-016"></a>

#### API-016 — Tạo phiên guest dùng thử

**Method/path:** `POST /api/v1/guest/sessions`  
**URL Postman:** `{{baseUrl}}/guest/sessions`  
**Quyền:** `PUBLIC`  
**Body mode:** `none`

Authorization tab: **No Auth**.

**Headers:** Không có header bắt buộc riêng.

**Query:** Không có query parameter được khai báo.

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 200 — id, sessionKey, tokenLimit, tokenUsed=0, expiresAt.

**Lưu biến** (`json` = `pm.response.json()`; chỉ lưu khi response thành công và có dữ liệu):

| Biến | Expression | Lưu ý |
| --- | --- | --- |
| `guestSession` | `json.sessionKey` | Gửi X-Guest-Session: {{guestSession}} cho các API guest tiếp theo. |
| `guestSessionId` | `json.id` | UUID guest session; không thay header raw key. |

**Điều kiện và lưu ý:**

- Không body/query/bearer. Giới hạn 3 session/IP/24 giờ trên mỗi process.
- Phiên hạn 24 giờ; GUEST_TOKEN_LIMIT mặc định 5000. Key raw chỉ trả khi tạo, DB chỉ giữ SHA-256 hash.
- Không có API đổi history guest thành user; registration độc lập.

<a id="api-017"></a>

#### API-017 — Xem phiên guest và hạn mức còn lại

**Method/path:** `GET /api/v1/guest/sessions/me`  
**URL Postman:** `{{baseUrl}}/guest/sessions/me`  
**Quyền:** `GUEST`  
**Body mode:** `none`

Authorization tab: **No Auth**.

**Headers:**

| Key | Value |
| --- | --- |
| `X-Guest-Session` | `{{guestSession}}` |

**Query:** Không có query parameter được khai báo.

**Input fields:**

| Field | Type | Bắt buộc | Validation / cách dùng |
| --- | --- | --- | --- |
| `header.X-Guest-Session` | string | Có | Header bắt buộc: {{guestSession}}; raw key g_ dài 45 ký tự, còn hạn/chưa converted. |

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 200 — guest session công khai gồm token_limit/token_used/expires_at.

**Điều kiện và lưu ý:**

- Không body/query. Header thiếu/invalid/hết hạn trả 401.
- Response dùng SQL snake_case token_limit/token_used/expires_at; không có session_key hash.

<a id="api-018"></a>

#### API-018 — Liệt kê CV demo hệ thống cho guest

**Method/path:** `GET /api/v1/guest/demo-resumes`  
**URL Postman:** `{{baseUrl}}/guest/demo-resumes`  
**Quyền:** `GUEST`  
**Body mode:** `none`

Authorization tab: **No Auth**.

**Headers:**

| Key | Value |
| --- | --- |
| `X-Guest-Session` | `{{guestSession}}` |

**Query:** Không có query parameter được khai báo.

**Input fields:**

| Field | Type | Bắt buộc | Validation / cách dùng |
| --- | --- | --- | --- |
| `header.X-Guest-Session` | string | Có | Header bắt buộc: {{guestSession}}; raw key g_ dài 45 ký tự, còn hạn/chưa converted. |

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 200 — array id/title/language/structured_data.

**Lưu biến** (`json` = `pm.response.json()`; chỉ lưu khi response thành công và có dữ liệu):

| Biến | Expression | Lưu ý |
| --- | --- | --- |
| `demoResumeId` | `json[0]?.id` | Chọn demo thực, chỉ lưu nếu array không rỗng. |

**Điều kiện và lưu ý:**

- Không body/query; tối đa 20 CV is_demo và READY.
- Lấy demoResumeId ở đây; CV private không dùng được ở trial.

<a id="api-019"></a>

#### API-019 — Liệt kê template cho phép dùng thử

**Method/path:** `GET /api/v1/guest/templates`  
**URL Postman:** `{{baseUrl}}/guest/templates`  
**Quyền:** `GUEST`  
**Body mode:** `none`

Authorization tab: **No Auth**.

**Headers:**

| Key | Value |
| --- | --- |
| `X-Guest-Session` | `{{guestSession}}` |

**Query:** Không có query parameter được khai báo.

**Input fields:**

| Field | Type | Bắt buộc | Validation / cách dùng |
| --- | --- | --- | --- |
| `header.X-Guest-Session` | string | Có | Header bắt buộc: {{guestSession}}; raw key g_ dài 45 ký tự, còn hạn/chưa converted. |

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 200 — array template rows.

**Lưu biến** (`json` = `pm.response.json()`; chỉ lưu khi response thành công và có dữ liệu):

| Biến | Expression | Lưu ý |
| --- | --- | --- |
| `trialTemplateId` | `json[0]?.id` | Chọn template active/trial thực; chỉ lưu khi array không rỗng. |

**Điều kiện và lưu ý:**

- Không body/query. Chỉ template is_active và is_trial_enabled.
- Không có query chọn ngôn ngữ hoặc page/limit trong controller này.

<a id="api-020"></a>

#### API-020 — Sinh bản CV thử nghiệm từ demo và template

**Method/path:** `POST /api/v1/guest/trials/build`  
**URL Postman:** `{{baseUrl}}/guest/trials/build`  
**Quyền:** `GUEST`  
**Body mode:** `json`

Authorization tab: **No Auth**.

**Headers:**

| Key | Value |
| --- | --- |
| `X-Guest-Session` | `{{guestSession}}` |
| `Content-Type` | `application/json` |

**Query:** Không có query parameter được khai báo.

**Input fields:**

| Field | Type | Bắt buộc | Validation / cách dùng |
| --- | --- | --- | --- |
| `header.X-Guest-Session` | string | Có | Header bắt buộc: {{guestSession}}; raw key g_ dài 45 ký tự, còn hạn/chưa converted. |
| `template_id` | UUID string | Có | snake_case; template phải active/trial. |
| `demo_resume_id` | UUID string | Có | snake_case; CV hệ thống is_demo=true, user_id null, READY. |

**Body → raw → JSON (input mẫu):**

~~~json
{
  "template_id": "{{trialTemplateId}}",
  "demo_resume_id": "{{demoResumeId}}"
}
~~~

**Kết quả mong đợi:** 200 — {trial:true,template_id,structured_data,registration_prompt}.

**Điều kiện và lưu ý:**

- AI_ENABLED và ChatModel/provider/key/quota guest phải sẵn sàng; AI tắt trả 503.
- Chỉ lấy facts demo; field lạ được bỏ qua bởi DTO. Không truyền private CV/structured_data để tạo CV cá nhân.
- Trả preview structured_data, không tạo resumes row; không có resumeId mới để lưu.

<a id="api-021"></a>

#### API-021 — Gợi ý việc làm thử nghiệm từ CV demo

**Method/path:** `POST /api/v1/guest/trials/recommendations`  
**URL Postman:** `{{baseUrl}}/guest/trials/recommendations`  
**Quyền:** `GUEST`  
**Body mode:** `json`

Authorization tab: **No Auth**.

**Headers:**

| Key | Value |
| --- | --- |
| `X-Guest-Session` | `{{guestSession}}` |
| `Content-Type` | `application/json` |

**Query:** Không có query parameter được khai báo.

**Input fields:**

| Field | Type | Bắt buộc | Validation / cách dùng |
| --- | --- | --- | --- |
| `header.X-Guest-Session` | string | Có | Header bắt buộc: {{guestSession}}; raw key g_ dài 45 ký tự, còn hạn/chưa converted. |
| `demo_resume_id` | UUID string | Có | CV hệ thống demo, user_id null, READY. |

**Body → raw → JSON (input mẫu):**

~~~json
{
  "demo_resume_id": "{{demoResumeId}}"
}
~~~

**Kết quả mong đợi:** 200 — matching_run_id, mode='SKILL_OVERLAP', trial=true, results, registration_prompt.

**Lưu biến** (`json` = `pm.response.json()`; chỉ lưu khi response thành công và có dữ liệu):

| Biến | Expression | Lưu ý |
| --- | --- | --- |
| `guestMatchingRunId` | `json.matching_run_id` | ID run guest để theo dõi; không phải candidate matchingRunId. |

**Điều kiện và lưu ý:**

- Rule-based không cần AI provider; lấy tối đa 500 ACTIVE jobs, trả top tối đa 3.
- Chi phí 100 trial credit cố định khi thành công, kể cả không có job. Không đủ budget trả 429.
- Không có request limit/offset/mode; field lạ bị DTO bỏ qua.
- Run thuộc guest; không lấy run này bằng candidate GET /matching/{id}.

<a id="api-022"></a>

#### API-022 — Tạo cuộc trò chuyện trợ lý cho guest

**Method/path:** `POST /api/v1/guest/assistant/conversations`  
**URL Postman:** `{{baseUrl}}/guest/assistant/conversations`  
**Quyền:** `GUEST`  
**Body mode:** `none`

Authorization tab: **No Auth**.

**Headers:**

| Key | Value |
| --- | --- |
| `X-Guest-Session` | `{{guestSession}}` |

**Query:** Không có query parameter được khai báo.

**Input fields:**

| Field | Type | Bắt buộc | Validation / cách dùng |
| --- | --- | --- | --- |
| `header.X-Guest-Session` | string | Có | Header bắt buộc: {{guestSession}}; raw key g_ dài 45 ký tự, còn hạn/chưa converted. |

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 200 — conversation row với id.

**Lưu biến** (`json` = `pm.response.json()`; chỉ lưu khi response thành công và có dữ liệu):

| Biến | Expression | Lưu ý |
| --- | --- | --- |
| `guestConversationId` | `json.id` | UUID để GET conversation và POST messages bằng cùng guest session. |

**Điều kiện và lưu ý:**

- Không body/query. Title/context server mặc định; guest không gửi context CV/job cá nhân.
- Tạo conversation ACTIVE; chưa gọi AI/chưa tiêu quota chat.

<a id="api-023"></a>

#### API-023 — Xem conversation và lịch sử của guest

**Method/path:** `GET /api/v1/guest/assistant/conversations/{id}`  
**URL Postman:** `{{baseUrl}}/guest/assistant/conversations/{{guestConversationId}}`  
**Quyền:** `GUEST`  
**Body mode:** `none`

Authorization tab: **No Auth**.

**Headers:**

| Key | Value |
| --- | --- |
| `X-Guest-Session` | `{{guestSession}}` |

**Path variables:**

| Param | Giá trị Postman | Điều kiện |
| --- | --- | --- |
| `id` | `{{guestConversationId}}` | UUID conversation guest, không candidate conversation. |

**Query:** Không có query parameter được khai báo.

**Input fields:**

| Field | Type | Bắt buộc | Validation / cách dùng |
| --- | --- | --- | --- |
| `header.X-Guest-Session` | string | Có | Header bắt buộc: {{guestSession}}; raw key g_ dài 45 ký tự, còn hạn/chưa converted. |

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 200 — conversation object kèm messages/sources.

**Điều kiện và lưu ý:**

- Không body/query. Conversation phải thuộc guest của header; sai ownership trả 404.
- Response includes messages và sources; không gọi AI.

<a id="api-024"></a>

#### API-024 — Gửi câu hỏi nghề nghiệp cho trợ lý guest

**Method/path:** `POST /api/v1/guest/assistant/conversations/{id}/messages`  
**URL Postman:** `{{baseUrl}}/guest/assistant/conversations/{{guestConversationId}}/messages`  
**Quyền:** `GUEST`  
**Body mode:** `json`

Authorization tab: **No Auth**.

**Headers:**

| Key | Value |
| --- | --- |
| `X-Guest-Session` | `{{guestSession}}` |
| `Content-Type` | `application/json` |

**Path variables:**

| Param | Giá trị Postman | Điều kiện |
| --- | --- | --- |
| `id` | `{{guestConversationId}}` | UUID conversation tạo bằng cùng guest session. |

**Query:** Không có query parameter được khai báo.

**Input fields:**

| Field | Type | Bắt buộc | Validation / cách dùng |
| --- | --- | --- | --- |
| `header.X-Guest-Session` | string | Có | Header bắt buộc: {{guestSession}}; raw key g_ dài 45 ký tự, còn hạn/chưa converted. |
| `message` | string | Có | Không trống/max8000 ký tự. |
| `retrieve` | boolean | Không | Có gửi phải boolean, không null. Guest không retrieve RAG dù true; dùng false rõ ràng. |

**Body → raw → JSON (input mẫu):**

~~~json
{
  "message": "Tôi nên học gì để trở thành Java Backend Developer?",
  "retrieve": false
}
~~~

**Kết quả mong đợi:** 200 — assistant message row (id,message_text,...) kèm sources=[].

**Lưu biến** (`json` = `pm.response.json()`; chỉ lưu khi response thành công và có dữ liệu):

| Biến | Expression | Lưu ý |
| --- | --- | --- |
| `guestAssistantMessageId` | `json.id` | UUID message phản hồi. Guest không có feedback API riêng. |

**Điều kiện và lưu ý:**

- Conversation phải thuộc guest và ACTIVE. Cần chat AI sẵn sàng và quota trial; lỗi budget trả 429.
- DTO bỏ unknown fields. Không gắn CV/job/roadmap cá nhân trong guest message.
- Response là row tin ASSISTANT với message_text, không phải field answer của provider.

### 6.4. CV / resume

[ResumeController](../src/main/java/com/example/intelligent_cv_matching_ai_agent/controller/candidate/resume/ResumeController.java).

<a id="api-025"></a>

#### API-025 — Danh sách template CV đang hoạt động

**Method/path:** `GET /api/v1/resumes/templates`  
**URL Postman:** `{{baseUrl}}/resumes/templates`  
**Quyền:** `PUBLIC`  
**Body mode:** `none`

Authorization tab: **No Auth**.

**Headers:** Không có header bắt buộc riêng.

**Query:** Không có query parameter được khai báo.

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 200 mảng resume_templates active

**Lưu biến** (`json` = `pm.response.json()`; chỉ lưu khi response thành công và có dữ liệu):

| Biến | Expression | Lưu ý |
| --- | --- | --- |
| `resumeTemplateId` | `json[0].id` | Danh sách không rỗng; chọn template phù hợp, không suy UUID từ tên/seed. |

<a id="api-026"></a>

#### API-026 — Danh sách CV cá nhân

**Method/path:** `GET /api/v1/resumes`  
**URL Postman:** `{{baseUrl}}/resumes`  
**Quyền:** `USER`  
**Body mode:** `none`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{activeToken}}` |

**Query:** Không có query parameter được khai báo.

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 200 mảng CV của người dùng

**Lưu biến** (`json` = `pm.response.json()`; chỉ lưu khi response thành công và có dữ liệu):

| Biến | Expression | Lưu ý |
| --- | --- | --- |
| `resumeId` | `json[0].id` | Chỉ chọn bản ghi mong muốn khi danh sách không rỗng; thao tác tạo bên dưới lưu ID chính xác hơn. |

**Điều kiện và lưu ý:**

- Không trả CV ARCHIVED; primary trước rồi updated_at mới nhất. Không có query limit/offset.

<a id="api-027"></a>

#### API-027 — Chi tiết CV và lịch sử parsing

**Method/path:** `GET /api/v1/resumes/{id}`  
**URL Postman:** `{{baseUrl}}/resumes/{{resumeId}}`  
**Quyền:** `USER`  
**Body mode:** `none`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{activeToken}}` |

**Path variables:**

| Param | Giá trị Postman | Điều kiện |
| --- | --- | --- |
| `id` | `{{resumeId}}` | UUID thực từ response/API liên quan; phải thuộc người dùng khi endpoint kiểm tra ownership. |

**Query:** Không có query parameter được khai báo.

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 200 object CV chi tiết

**Điều kiện và lưu ý:**

- Owner vẫn GET CV ARCHIVED bằng ID; demo hệ thống không thuộc user.
- Response gồm structured_data, experiences, educations, projects, certifications, skills, parse_results.

<a id="api-028"></a>

#### API-028 — Tạo CV thủ công đầy đủ

**Method/path:** `POST /api/v1/resumes`  
**URL Postman:** `{{baseUrl}}/resumes`  
**Quyền:** `USER`  
**Body mode:** `json`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{activeToken}}` |
| `Content-Type` | `application/json` |

**Query:** Không có query parameter được khai báo.

**Input fields:**

| Field | Type | Bắt buộc | Validation / cách dùng |
| --- | --- | --- | --- |
| `title` | string | Có | Không rỗng; tối đa 255; explicit null bị từ chối. |
| `language` | string | Không | vi/en (VI/EN cũng normalize); bỏ/null dùng vi. |
| `template_id` | UUID | Không | {{resumeTemplateId}} phải active; bỏ/null nếu không dùng template. |
| `raw_content` | string | Không | Tối đa 200000 ký tự; tạo mới bỏ/null dùng chuỗi rỗng. |
| `structured_data` | object | Có | Manual bắt buộc; normalize chỉ giữ các field được liệt kê. PATCH/confirm nếu gửi object thì thay toàn bộ dữ liệu cấu trúc, không merge sâu. |
| `structured_data.full_name` | string | Không | Tối đa 500 ký tự. |
| `structured_data.email` | string | Không | Tối đa 500; schema CV không áp dụng @Email cho field này. |
| `structured_data.phone` | string | Không | Tối đa 500. |
| `structured_data.location` | string | Không | Tối đa 500. |
| `structured_data.summary` | string | Không | Tối đa 20000 ký tự; nên có thông tin thực tế. |
| `structured_data.experience` | array | Không | Tối đa 100 entries; bỏ field dùng []; explicit null bị từ chối. |
| `structured_data.education` | array | Không | Tối đa 100 entries; bỏ field dùng []; explicit null bị từ chối. |
| `structured_data.projects` | array | Không | Tối đa 100 entries; bỏ field dùng []; explicit null bị từ chối. |
| `structured_data.certifications` | array | Không | Tối đa 100 entries; bỏ field dùng []; explicit null bị từ chối. |
| `structured_data.skills` | array | Không | Tối đa 100 entries; bỏ field dùng []; explicit null bị từ chối. |
| `structured_data.experience[].company_name` | string | Không | Tối đa 255. |
| `structured_data.experience[].job_title` | string | Không | Tối đa 255. |
| `structured_data.experience[].start_date` | string YYYY-MM-DD | Không | ISO date hoặc bỏ/null. |
| `structured_data.experience[].end_date` | string YYYY-MM-DD | Không | Không trước start_date; bỏ/null khi is_current=true. |
| `structured_data.experience[].is_current` | boolean | Không | Phải boolean nếu gửi; true không được có end_date. |
| `structured_data.experience[].description` | string | Không | Tối đa 20000. |
| `structured_data.education[].school_name` | string | Không | Tối đa 255. |
| `structured_data.education[].degree` | string | Không | Tối đa 255. |
| `structured_data.education[].major` | string | Không | Tối đa 255. |
| `structured_data.education[].start_date` | string YYYY-MM-DD | Không | ISO date hoặc bỏ/null. |
| `structured_data.education[].end_date` | string YYYY-MM-DD | Không | Không trước start_date. |
| `structured_data.education[].description` | string | Không | Tối đa 20000. |
| `structured_data.projects[].project_name` | string | Không | Tối đa 255. |
| `structured_data.projects[].role` | string | Không | Tối đa 255. |
| `structured_data.projects[].description` | string | Không | Tối đa 20000. |
| `structured_data.projects[].technologies` | array&lt;string&gt; | Không | Tối đa 100 strings, mỗi string tối đa 150. |
| `structured_data.projects[].project_url` | string | Không | Tối đa 255. |
| `structured_data.projects[].start_date` | string YYYY-MM-DD | Không | ISO date hoặc bỏ/null. |
| `structured_data.projects[].end_date` | string YYYY-MM-DD | Không | Không trước start_date. |
| `structured_data.certifications[].name` | string | Có | Bắt buộc không rỗng cho từng certification; tối đa 255. |
| `structured_data.certifications[].issuer` | string | Không | Tối đa 255. |
| `structured_data.certifications[].issue_date` | string YYYY-MM-DD | Không | ISO date hoặc bỏ/null. |
| `structured_data.certifications[].expiry_date` | string YYYY-MM-DD | Không | Không trước issue_date. |
| `structured_data.certifications[].credential_url` | string | Không | Tối đa 255. |
| `structured_data.skills[]` | string hoặc object | Không | String như Java được normalize thành {name:Java}. |
| `structured_data.skills[].name` | string | Có | Bắt buộc cho từng skill object, kể cả khi có skill_id; tối đa 255. |
| `structured_data.skills[].skill_id` | UUID | Không | Nếu chọn catalog dùng {{skillId}}, phải active; nếu bỏ thì confirm resolve/upsert theo slug của name. |
| `structured_data.skills[].proficiency_level` | string | Không | Tối đa 50; không enum validation ở ResumeData. |
| `structured_data.skills[].years_experience` | number | Không | Hữu hạn, 0..99. |

**Body → raw → JSON (input mẫu):**

~~~json
{
  "title": "CV Java Backend",
  "language": "vi",
  "template_id": "{{resumeTemplateId}}",
  "raw_content": "",
  "structured_data": {
    "full_name": "Nguyễn An",
    "email": "candidate@example.com",
    "phone": "0900000000",
    "location": "Ho Chi Minh",
    "summary": "Đã xây dựng REST API quản lý học tập với Java, Spring Boot và PostgreSQL.",
    "experience": [
      {
        "company_name": "Example Software",
        "job_title": "Backend Intern",
        "start_date": "2025-01-01",
        "end_date": "2025-06-30",
        "is_current": false,
        "description": "Triển khai REST API và viết truy vấn SQL trong dự án thực tập."
      }
    ],
    "education": [
      {
        "school_name": "Đại học Example",
        "degree": "Bachelor",
        "major": "Computer Science",
        "start_date": "2022-09-01",
        "end_date": "2026-06-30",
        "description": "Đã học lập trình, cơ sở dữ liệu và công nghệ phần mềm."
      }
    ],
    "projects": [
      {
        "project_name": "Learning Management API",
        "role": "Backend Developer",
        "description": "Thiết kế API khóa học, người dùng và bài tập; tích hợp PostgreSQL.",
        "technologies": [
          "Java",
          "Spring Boot",
          "PostgreSQL"
        ],
        "project_url": "https://example.com/projects/learning-api",
        "start_date": "2025-07-01",
        "end_date": "2025-12-31"
      }
    ],
    "certifications": [
      {
        "name": "Example Java Course Completion",
        "issuer": "Example Training",
        "issue_date": "2025-12-01",
        "expiry_date": "2027-12-01",
        "credential_url": "https://example.com/credentials/java-course"
      }
    ],
    "skills": [
      {
        "name": "Java",
        "proficiency_level": "BEGINNER",
        "years_experience": 1
      },
      {
        "name": "SQL",
        "proficiency_level": "BEGINNER",
        "years_experience": 1
      }
    ]
  }
}
~~~

**Kết quả mong đợi:** 200 object CV DRAFT

**Lưu biến** (`json` = `pm.response.json()`; chỉ lưu khi response thành công và có dữ liệu):

| Biến | Expression | Lưu ý |
| --- | --- | --- |
| `resumeId` | `json.id` | Chỉ lưu sau response thành công; chọn đúng bản ghi cần kiểm thử. |

**Điều kiện và lưu ý:**

- Dữ liệu mẫu là dữ kiện minh họa: thay bằng kinh nghiệm/chứng chỉ thực tế của người kiểm thử.
- Tên request CV là snake_case; section experience/education dùng số ít.
- Ownership lấy từ access token. Không cần/không dùng user_id client để thay chủ sở hữu.
- Template chỉ được kiểm tra ID active; backend chưa render PDF/DOCX theo template.
- Body bắt buộc. Tạo source MANUAL/status DRAFT; chưa ghi các bảng section đến khi confirm.
- is_primary/status/source nếu gửi thêm không thay trạng thái hoặc chủ sở hữu khi create.

<a id="api-029"></a>

#### API-029 — Sinh CV bằng AI từ dữ kiện đầy đủ

**Method/path:** `POST /api/v1/resumes/build`  
**URL Postman:** `{{baseUrl}}/resumes/build`  
**Quyền:** `USER`  
**Body mode:** `json`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{activeToken}}` |
| `Content-Type` | `application/json` |

**Query:** Không có query parameter được khai báo.

**Input fields:**

| Field | Type | Bắt buộc | Validation / cách dùng |
| --- | --- | --- | --- |
| `title` | string | Có | Không rỗng; tối đa 255; explicit null bị từ chối. |
| `language` | string | Không | vi/en (VI/EN cũng normalize); bỏ/null dùng vi. |
| `template_id` | UUID | Không | {{resumeTemplateId}} phải active; bỏ/null nếu không dùng template. |
| `raw_content` | string | Không | Tối đa 200000 ký tự; tạo mới bỏ/null dùng chuỗi rỗng. |
| `structured_data` | object | Không | Facts tùy chọn để AI sử dụng; builder không bắt buộc structured_data request. |
| `structured_data.full_name` | string | Không | Tối đa 500 ký tự. |
| `structured_data.email` | string | Không | Tối đa 500; schema CV không áp dụng @Email cho field này. |
| `structured_data.phone` | string | Không | Tối đa 500. |
| `structured_data.location` | string | Không | Tối đa 500. |
| `structured_data.summary` | string | Không | Tối đa 20000 ký tự; nên có thông tin thực tế. |
| `structured_data.experience` | array | Không | Tối đa 100 entries; bỏ field dùng []; explicit null bị từ chối. |
| `structured_data.education` | array | Không | Tối đa 100 entries; bỏ field dùng []; explicit null bị từ chối. |
| `structured_data.projects` | array | Không | Tối đa 100 entries; bỏ field dùng []; explicit null bị từ chối. |
| `structured_data.certifications` | array | Không | Tối đa 100 entries; bỏ field dùng []; explicit null bị từ chối. |
| `structured_data.skills` | array | Không | Tối đa 100 entries; bỏ field dùng []; explicit null bị từ chối. |
| `structured_data.experience[].company_name` | string | Không | Tối đa 255. |
| `structured_data.experience[].job_title` | string | Không | Tối đa 255. |
| `structured_data.experience[].start_date` | string YYYY-MM-DD | Không | ISO date hoặc bỏ/null. |
| `structured_data.experience[].end_date` | string YYYY-MM-DD | Không | Không trước start_date; bỏ/null khi is_current=true. |
| `structured_data.experience[].is_current` | boolean | Không | Phải boolean nếu gửi; true không được có end_date. |
| `structured_data.experience[].description` | string | Không | Tối đa 20000. |
| `structured_data.education[].school_name` | string | Không | Tối đa 255. |
| `structured_data.education[].degree` | string | Không | Tối đa 255. |
| `structured_data.education[].major` | string | Không | Tối đa 255. |
| `structured_data.education[].start_date` | string YYYY-MM-DD | Không | ISO date hoặc bỏ/null. |
| `structured_data.education[].end_date` | string YYYY-MM-DD | Không | Không trước start_date. |
| `structured_data.education[].description` | string | Không | Tối đa 20000. |
| `structured_data.projects[].project_name` | string | Không | Tối đa 255. |
| `structured_data.projects[].role` | string | Không | Tối đa 255. |
| `structured_data.projects[].description` | string | Không | Tối đa 20000. |
| `structured_data.projects[].technologies` | array&lt;string&gt; | Không | Tối đa 100 strings, mỗi string tối đa 150. |
| `structured_data.projects[].project_url` | string | Không | Tối đa 255. |
| `structured_data.projects[].start_date` | string YYYY-MM-DD | Không | ISO date hoặc bỏ/null. |
| `structured_data.projects[].end_date` | string YYYY-MM-DD | Không | Không trước start_date. |
| `structured_data.certifications[].name` | string | Có | Bắt buộc không rỗng cho từng certification; tối đa 255. |
| `structured_data.certifications[].issuer` | string | Không | Tối đa 255. |
| `structured_data.certifications[].issue_date` | string YYYY-MM-DD | Không | ISO date hoặc bỏ/null. |
| `structured_data.certifications[].expiry_date` | string YYYY-MM-DD | Không | Không trước issue_date. |
| `structured_data.certifications[].credential_url` | string | Không | Tối đa 255. |
| `structured_data.skills[]` | string hoặc object | Không | String như Java được normalize thành {name:Java}. |
| `structured_data.skills[].name` | string | Có | Bắt buộc cho từng skill object, kể cả khi có skill_id; tối đa 255. |
| `structured_data.skills[].skill_id` | UUID | Không | Nếu chọn catalog dùng {{skillId}}, phải active; nếu bỏ thì confirm resolve/upsert theo slug của name. |
| `structured_data.skills[].proficiency_level` | string | Không | Tối đa 50; không enum validation ở ResumeData. |
| `structured_data.skills[].years_experience` | number | Không | Hữu hạn, 0..99. |
| `target_job_title` | string (facts mở rộng) | Không | Ví dụ guidance nghề mục tiêu; DTO builder nhận JSON mở rộng, không phải field có enum/validator riêng. |
| `career_goal` | string (facts mở rộng) | Không | Dữ kiện định hướng gửi cho model. |

**Body → raw → JSON (input mẫu):**

~~~json
{
  "title": "CV Java Backend bằng AI",
  "language": "vi",
  "template_id": "{{resumeTemplateId}}",
  "target_job_title": "Java Backend Developer",
  "career_goal": "Tìm vị trí backend phù hợp các kỹ năng đã có",
  "raw_content": "Đã học Java và SQL; xây dựng API quản lý học tập. Dùng các dữ kiện trong structured_data; không tự tạo kinh nghiệm.",
  "structured_data": {
    "full_name": "Nguyễn An",
    "email": "candidate@example.com",
    "phone": "0900000000",
    "location": "Ho Chi Minh",
    "summary": "Đã xây dựng REST API quản lý học tập với Java, Spring Boot và PostgreSQL.",
    "experience": [
      {
        "company_name": "Example Software",
        "job_title": "Backend Intern",
        "start_date": "2025-01-01",
        "end_date": "2025-06-30",
        "is_current": false,
        "description": "Triển khai REST API và viết truy vấn SQL trong dự án thực tập."
      }
    ],
    "education": [
      {
        "school_name": "Đại học Example",
        "degree": "Bachelor",
        "major": "Computer Science",
        "start_date": "2022-09-01",
        "end_date": "2026-06-30",
        "description": "Đã học lập trình, cơ sở dữ liệu và công nghệ phần mềm."
      }
    ],
    "projects": [
      {
        "project_name": "Learning Management API",
        "role": "Backend Developer",
        "description": "Thiết kế API khóa học, người dùng và bài tập; tích hợp PostgreSQL.",
        "technologies": [
          "Java",
          "Spring Boot",
          "PostgreSQL"
        ],
        "project_url": "https://example.com/projects/learning-api",
        "start_date": "2025-07-01",
        "end_date": "2025-12-31"
      }
    ],
    "certifications": [
      {
        "name": "Example Java Course Completion",
        "issuer": "Example Training",
        "issue_date": "2025-12-01",
        "expiry_date": "2027-12-01",
        "credential_url": "https://example.com/credentials/java-course"
      }
    ],
    "skills": [
      {
        "name": "Java",
        "proficiency_level": "BEGINNER",
        "years_experience": 1
      },
      {
        "name": "SQL",
        "proficiency_level": "BEGINNER",
        "years_experience": 1
      }
    ]
  }
}
~~~

**Lưu ý validation AI:** Các giới hạn section/ngày trong bảng mô tả cấu trúc CV **đầu ra** được normalize. Nested facts đầu vào AI không được kiểm tra như CV manual trước khi gọi provider; dùng cấu trúc mẫu để kết quả nhất quán.

**Kết quả mong đợi:** 200 object CV REVIEW_REQUIRED

**Lưu biến** (`json` = `pm.response.json()`; chỉ lưu khi response thành công và có dữ liệu):

| Biến | Expression | Lưu ý |
| --- | --- | --- |
| `resumeId` | `json.id` | Chỉ lưu sau response thành công; chọn đúng bản ghi cần kiểm thử. |

**Điều kiện và lưu ý:**

- Dữ liệu mẫu là dữ kiện minh họa: thay bằng kinh nghiệm/chứng chỉ thực tế của người kiểm thử.
- Tên request CV là snake_case; section experience/education dùng số ít.
- Ownership lấy từ access token. Không cần/không dùng user_id client để thay chủ sở hữu.
- Template chỉ được kiểm tra ID active; backend chưa render PDF/DOCX theo template.
- Body bắt buộc; title vẫn bắt buộc. Chat AI và quota phải sẵn sàng; 503 nếu chat provider tắt.
- Service tự thêm career_profile lấy từ database, bỏ giá trị client nếu client gửi career_profile.
- CV_BUILD → normalize output → source AI_BUILDER/status REVIEW_REQUIRED; user phải review/confirm.
- Để bỏ template, xóa template_id; không giữ placeholder chưa có UUID.

<a id="api-030"></a>

#### API-030 — Upload và trích xuất CV

**Method/path:** `POST /api/v1/resumes/upload`  
**URL Postman:** `{{baseUrl}}/resumes/upload`  
**Quyền:** `USER`  
**Body mode:** `multipart`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{activeToken}}` |

**Query:** Không có query parameter được khai báo.

**Body → form-data / input:**

| Field | Type | Bắt buộc | Validation / cách dùng |
| --- | --- | --- | --- |
| `file` | File | Có | Postman Body → form-data; key file phải Type File, chọn file local. |
| `title` | Text | Không | Bỏ/null/trống dùng tên file; không trống tối đa 255. |
| `language` | Text | Không | Mặc định vi; vi/en. |

Body tab chọn **form-data**, file dùng type **File**, title/language dùng **Text**. Không set Content-Type thủ công.

**Kết quả mong đợi:** 200 object CV UPLOAD/REVIEW_REQUIRED

**Lưu biến** (`json` = `pm.response.json()`; chỉ lưu khi response thành công và có dữ liệu):

| Biến | Expression | Lưu ý |
| --- | --- | --- |
| `resumeId` | `json.id` | Chỉ lưu sau response thành công; chọn đúng bản ghi cần kiểm thử. |

**Điều kiện và lưu ý:**

- Không tự đặt Content-Type: Postman tạo multipart boundary.
- PDF/DOCX/TXT/PNG/JPEG ≤10 MiB, tổng request ≤11MB; MIME/extension/signature phải phù hợp.
- PDF không encrypted, tối đa 50 trang. TXT UTF-8 không NUL. DOCX đúng structure word/document.xml, ≤2000 entries/50MiB expanded.
- PNG/JPEG cần OCR; OCR tắt trả 503. PDF scan có thể upload text rỗng để nhập thủ công.
- Nếu AI enabled và text có dữ liệu sẽ gọi CV_PARSE; lỗi AI lưu parse FAILED nhưng vẫn giữ CV REVIEW_REQUIRED.
- Lưu ID CV response; review structured_data/parse_results trước confirm.

<a id="api-031"></a>

#### API-031 — Sửa dữ liệu CV trước khi xác nhận lại

**Method/path:** `PATCH /api/v1/resumes/{id}`  
**URL Postman:** `{{baseUrl}}/resumes/{{resumeId}}`  
**Quyền:** `USER`  
**Body mode:** `json`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{activeToken}}` |
| `Content-Type` | `application/json` |

**Path variables:**

| Param | Giá trị Postman | Điều kiện |
| --- | --- | --- |
| `id` | `{{resumeId}}` | UUID thực từ response/API liên quan; phải thuộc người dùng khi endpoint kiểm tra ownership. |

**Query:** Không có query parameter được khai báo.

**Input fields:**

| Field | Type | Bắt buộc | Validation / cách dùng |
| --- | --- | --- | --- |
| `title` | string | Không | Bỏ giữ cũ; nếu gửi phải không rỗng/max255; null bị từ chối. |
| `language` | string | Không | Bỏ giữ cũ; vi/en; explicit null chuyển vi. |
| `raw_content` | string | Không | Bỏ giữ cũ; gửi chuỗi ≤200000; explicit null bị service từ chối. |
| `structured_data` | object | Không | Manual bắt buộc; normalize chỉ giữ các field được liệt kê. PATCH/confirm nếu gửi object thì thay toàn bộ dữ liệu cấu trúc, không merge sâu. |
| `structured_data.full_name` | string | Không | Tối đa 500 ký tự. |
| `structured_data.email` | string | Không | Tối đa 500; schema CV không áp dụng @Email cho field này. |
| `structured_data.phone` | string | Không | Tối đa 500. |
| `structured_data.location` | string | Không | Tối đa 500. |
| `structured_data.summary` | string | Không | Tối đa 20000 ký tự; nên có thông tin thực tế. |
| `structured_data.experience` | array | Không | Tối đa 100 entries; bỏ field dùng []; explicit null bị từ chối. |
| `structured_data.education` | array | Không | Tối đa 100 entries; bỏ field dùng []; explicit null bị từ chối. |
| `structured_data.projects` | array | Không | Tối đa 100 entries; bỏ field dùng []; explicit null bị từ chối. |
| `structured_data.certifications` | array | Không | Tối đa 100 entries; bỏ field dùng []; explicit null bị từ chối. |
| `structured_data.skills` | array | Không | Tối đa 100 entries; bỏ field dùng []; explicit null bị từ chối. |
| `structured_data.experience[].company_name` | string | Không | Tối đa 255. |
| `structured_data.experience[].job_title` | string | Không | Tối đa 255. |
| `structured_data.experience[].start_date` | string YYYY-MM-DD | Không | ISO date hoặc bỏ/null. |
| `structured_data.experience[].end_date` | string YYYY-MM-DD | Không | Không trước start_date; bỏ/null khi is_current=true. |
| `structured_data.experience[].is_current` | boolean | Không | Phải boolean nếu gửi; true không được có end_date. |
| `structured_data.experience[].description` | string | Không | Tối đa 20000. |
| `structured_data.education[].school_name` | string | Không | Tối đa 255. |
| `structured_data.education[].degree` | string | Không | Tối đa 255. |
| `structured_data.education[].major` | string | Không | Tối đa 255. |
| `structured_data.education[].start_date` | string YYYY-MM-DD | Không | ISO date hoặc bỏ/null. |
| `structured_data.education[].end_date` | string YYYY-MM-DD | Không | Không trước start_date. |
| `structured_data.education[].description` | string | Không | Tối đa 20000. |
| `structured_data.projects[].project_name` | string | Không | Tối đa 255. |
| `structured_data.projects[].role` | string | Không | Tối đa 255. |
| `structured_data.projects[].description` | string | Không | Tối đa 20000. |
| `structured_data.projects[].technologies` | array&lt;string&gt; | Không | Tối đa 100 strings, mỗi string tối đa 150. |
| `structured_data.projects[].project_url` | string | Không | Tối đa 255. |
| `structured_data.projects[].start_date` | string YYYY-MM-DD | Không | ISO date hoặc bỏ/null. |
| `structured_data.projects[].end_date` | string YYYY-MM-DD | Không | Không trước start_date. |
| `structured_data.certifications[].name` | string | Có | Bắt buộc không rỗng cho từng certification; tối đa 255. |
| `structured_data.certifications[].issuer` | string | Không | Tối đa 255. |
| `structured_data.certifications[].issue_date` | string YYYY-MM-DD | Không | ISO date hoặc bỏ/null. |
| `structured_data.certifications[].expiry_date` | string YYYY-MM-DD | Không | Không trước issue_date. |
| `structured_data.certifications[].credential_url` | string | Không | Tối đa 255. |
| `structured_data.skills[]` | string hoặc object | Không | String như Java được normalize thành {name:Java}. |
| `structured_data.skills[].name` | string | Có | Bắt buộc cho từng skill object, kể cả khi có skill_id; tối đa 255. |
| `structured_data.skills[].skill_id` | UUID | Không | Nếu chọn catalog dùng {{skillId}}, phải active; nếu bỏ thì confirm resolve/upsert theo slug của name. |
| `structured_data.skills[].proficiency_level` | string | Không | Tối đa 50; không enum validation ở ResumeData. |
| `structured_data.skills[].years_experience` | number | Không | Hữu hạn, 0..99. |

**Body → raw → JSON (input mẫu):**

~~~json
{
  "title": "CV Java Backend đã cập nhật",
  "language": "vi",
  "raw_content": "",
  "structured_data": {
    "full_name": "Nguyễn An",
    "email": "candidate@example.com",
    "phone": "0900000000",
    "location": "Ho Chi Minh",
    "summary": "Đã xây dựng REST API quản lý học tập với Java, Spring Boot và PostgreSQL.",
    "experience": [
      {
        "company_name": "Example Software",
        "job_title": "Backend Intern",
        "start_date": "2025-01-01",
        "end_date": "2025-06-30",
        "is_current": false,
        "description": "Triển khai REST API và viết truy vấn SQL trong dự án thực tập."
      }
    ],
    "education": [
      {
        "school_name": "Đại học Example",
        "degree": "Bachelor",
        "major": "Computer Science",
        "start_date": "2022-09-01",
        "end_date": "2026-06-30",
        "description": "Đã học lập trình, cơ sở dữ liệu và công nghệ phần mềm."
      }
    ],
    "projects": [
      {
        "project_name": "Learning Management API",
        "role": "Backend Developer",
        "description": "Thiết kế API khóa học, người dùng và bài tập; tích hợp PostgreSQL.",
        "technologies": [
          "Java",
          "Spring Boot",
          "PostgreSQL"
        ],
        "project_url": "https://example.com/projects/learning-api",
        "start_date": "2025-07-01",
        "end_date": "2025-12-31"
      }
    ],
    "certifications": [
      {
        "name": "Example Java Course Completion",
        "issuer": "Example Training",
        "issue_date": "2025-12-01",
        "expiry_date": "2027-12-01",
        "credential_url": "https://example.com/credentials/java-course"
      }
    ],
    "skills": [
      {
        "name": "Java",
        "proficiency_level": "BEGINNER",
        "years_experience": 1
      },
      {
        "name": "SQL",
        "proficiency_level": "BEGINNER",
        "years_experience": 1
      }
    ]
  }
}
~~~

**Kết quả mong đợi:** 200 object CV cập nhật

**Điều kiện và lưu ý:**

- Dữ liệu mẫu là dữ kiện minh họa: thay bằng kinh nghiệm/chứng chỉ thực tế của người kiểm thử.
- Tên request CV là snake_case; section experience/education dùng số ít.
- Ownership lấy từ access token. Không cần/không dùng user_id client để thay chủ sở hữu.
- Template chỉ được kiểm tra ID active; backend chưa render PDF/DOCX theo template.
- Body bắt buộc, {} hợp lệ và giữ nội dung. Không merge sâu structured_data; section bỏ trong object thay mới thành [].
- ARCHIVED không được edit. Nội dung/title/language/hash đổi → REVIEW_REQUIRED, bỏ primary, invalidate vector/roadmap liên quan.
- PATCH không thay template_id hoặc is_primary; dùng /primary hoặc confirm cho primary.

<a id="api-032"></a>

#### API-032 — Xác nhận dữ liệu CV và chọn primary

**Method/path:** `POST /api/v1/resumes/{id}/confirm`  
**URL Postman:** `{{baseUrl}}/resumes/{{resumeId}}/confirm`  
**Quyền:** `USER`  
**Body mode:** `json`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{activeToken}}` |
| `Content-Type` | `application/json` |

**Path variables:**

| Param | Giá trị Postman | Điều kiện |
| --- | --- | --- |
| `id` | `{{resumeId}}` | UUID thực từ response/API liên quan; phải thuộc người dùng khi endpoint kiểm tra ownership. |

**Query:** Không có query parameter được khai báo.

**Input fields:**

| Field | Type | Bắt buộc | Validation / cách dùng |
| --- | --- | --- | --- |
| `structured_data` | object | Không | Bỏ dùng structured_data hiện có; nếu gửi object thay cấu trúc hoàn chỉnh; null không hợp lệ. |
| `structured_data.full_name` | string | Không | Tối đa 500 ký tự. |
| `structured_data.email` | string | Không | Tối đa 500; schema CV không áp dụng @Email cho field này. |
| `structured_data.phone` | string | Không | Tối đa 500. |
| `structured_data.location` | string | Không | Tối đa 500. |
| `structured_data.summary` | string | Không | Tối đa 20000 ký tự; nên có thông tin thực tế. |
| `structured_data.experience` | array | Không | Tối đa 100 entries; bỏ field dùng []; explicit null bị từ chối. |
| `structured_data.education` | array | Không | Tối đa 100 entries; bỏ field dùng []; explicit null bị từ chối. |
| `structured_data.projects` | array | Không | Tối đa 100 entries; bỏ field dùng []; explicit null bị từ chối. |
| `structured_data.certifications` | array | Không | Tối đa 100 entries; bỏ field dùng []; explicit null bị từ chối. |
| `structured_data.skills` | array | Không | Tối đa 100 entries; bỏ field dùng []; explicit null bị từ chối. |
| `structured_data.experience[].company_name` | string | Không | Tối đa 255. |
| `structured_data.experience[].job_title` | string | Không | Tối đa 255. |
| `structured_data.experience[].start_date` | string YYYY-MM-DD | Không | ISO date hoặc bỏ/null. |
| `structured_data.experience[].end_date` | string YYYY-MM-DD | Không | Không trước start_date; bỏ/null khi is_current=true. |
| `structured_data.experience[].is_current` | boolean | Không | Phải boolean nếu gửi; true không được có end_date. |
| `structured_data.experience[].description` | string | Không | Tối đa 20000. |
| `structured_data.education[].school_name` | string | Không | Tối đa 255. |
| `structured_data.education[].degree` | string | Không | Tối đa 255. |
| `structured_data.education[].major` | string | Không | Tối đa 255. |
| `structured_data.education[].start_date` | string YYYY-MM-DD | Không | ISO date hoặc bỏ/null. |
| `structured_data.education[].end_date` | string YYYY-MM-DD | Không | Không trước start_date. |
| `structured_data.education[].description` | string | Không | Tối đa 20000. |
| `structured_data.projects[].project_name` | string | Không | Tối đa 255. |
| `structured_data.projects[].role` | string | Không | Tối đa 255. |
| `structured_data.projects[].description` | string | Không | Tối đa 20000. |
| `structured_data.projects[].technologies` | array&lt;string&gt; | Không | Tối đa 100 strings, mỗi string tối đa 150. |
| `structured_data.projects[].project_url` | string | Không | Tối đa 255. |
| `structured_data.projects[].start_date` | string YYYY-MM-DD | Không | ISO date hoặc bỏ/null. |
| `structured_data.projects[].end_date` | string YYYY-MM-DD | Không | Không trước start_date. |
| `structured_data.certifications[].name` | string | Có | Bắt buộc không rỗng cho từng certification; tối đa 255. |
| `structured_data.certifications[].issuer` | string | Không | Tối đa 255. |
| `structured_data.certifications[].issue_date` | string YYYY-MM-DD | Không | ISO date hoặc bỏ/null. |
| `structured_data.certifications[].expiry_date` | string YYYY-MM-DD | Không | Không trước issue_date. |
| `structured_data.certifications[].credential_url` | string | Không | Tối đa 255. |
| `structured_data.skills[]` | string hoặc object | Không | String như Java được normalize thành {name:Java}. |
| `structured_data.skills[].name` | string | Có | Bắt buộc cho từng skill object, kể cả khi có skill_id; tối đa 255. |
| `structured_data.skills[].skill_id` | UUID | Không | Nếu chọn catalog dùng {{skillId}}, phải active; nếu bỏ thì confirm resolve/upsert theo slug của name. |
| `structured_data.skills[].proficiency_level` | string | Không | Tối đa 50; không enum validation ở ResumeData. |
| `structured_data.skills[].years_experience` | number | Không | Hữu hạn, 0..99. |
| `raw_content` | string | Không | Bỏ giữ cũ; tối đa 200000; explicit null bị service từ chối. |
| `is_primary` | boolean | Không | true chọn primary; bỏ/null/false không tự bỏ primary đang có. |

**Body → raw → JSON (input mẫu):**

~~~json
{
  "structured_data": {
    "full_name": "Nguyễn An",
    "email": "candidate@example.com",
    "phone": "0900000000",
    "location": "Ho Chi Minh",
    "summary": "Đã xây dựng REST API quản lý học tập với Java, Spring Boot và PostgreSQL.",
    "experience": [
      {
        "company_name": "Example Software",
        "job_title": "Backend Intern",
        "start_date": "2025-01-01",
        "end_date": "2025-06-30",
        "is_current": false,
        "description": "Triển khai REST API và viết truy vấn SQL trong dự án thực tập."
      }
    ],
    "education": [
      {
        "school_name": "Đại học Example",
        "degree": "Bachelor",
        "major": "Computer Science",
        "start_date": "2022-09-01",
        "end_date": "2026-06-30",
        "description": "Đã học lập trình, cơ sở dữ liệu và công nghệ phần mềm."
      }
    ],
    "projects": [
      {
        "project_name": "Learning Management API",
        "role": "Backend Developer",
        "description": "Thiết kế API khóa học, người dùng và bài tập; tích hợp PostgreSQL.",
        "technologies": [
          "Java",
          "Spring Boot",
          "PostgreSQL"
        ],
        "project_url": "https://example.com/projects/learning-api",
        "start_date": "2025-07-01",
        "end_date": "2025-12-31"
      }
    ],
    "certifications": [
      {
        "name": "Example Java Course Completion",
        "issuer": "Example Training",
        "issue_date": "2025-12-01",
        "expiry_date": "2027-12-01",
        "credential_url": "https://example.com/credentials/java-course"
      }
    ],
    "skills": [
      {
        "name": "Java",
        "proficiency_level": "BEGINNER",
        "years_experience": 1
      },
      {
        "name": "SQL",
        "proficiency_level": "BEGINNER",
        "years_experience": 1
      }
    ]
  },
  "is_primary": true
}
~~~

**Kết quả mong đợi:** 200 object CV READY

**Lưu biến** (`json` = `pm.response.json()`; chỉ lưu khi response thành công và có dữ liệu):

| Biến | Expression | Lưu ý |
| --- | --- | --- |
| `resumeId` | `json.id` | Chỉ lưu sau response thành công; chọn đúng bản ghi cần kiểm thử. |

**Điều kiện và lưu ý:**

- Body hoàn toàn optional: Body none, JSON null hoặc {} dùng dữ liệu hiện có. Sample gửi toàn bộ dữ liệu review.
- CV phải owned và chưa ARCHIVED. Cần raw/summary hoặc ít nhất một entry section/skill; chỉ full_name/email chưa đủ.
- Normalize và thay các bảng section/skills → READY; dữ liệu hash đổi làm vector/roadmap stale.
- Title/language/template_id nếu gửi tại confirm không được cập nhật; PATCH trước nếu cần.
- Chỉ một CV primary per user; primary an toàn bằng lock user.

<a id="api-033"></a>

#### API-033 — Chọn CV READY làm CV chính

**Method/path:** `POST /api/v1/resumes/{id}/primary`  
**URL Postman:** `{{baseUrl}}/resumes/{{resumeId}}/primary`  
**Quyền:** `USER`  
**Body mode:** `none`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{activeToken}}` |

**Path variables:**

| Param | Giá trị Postman | Điều kiện |
| --- | --- | --- |
| `id` | `{{resumeId}}` | UUID thực từ response/API liên quan; phải thuộc người dùng khi endpoint kiểm tra ownership. |

**Query:** Không có query parameter được khai báo.

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 200 object CV is_primary=true

**Điều kiện và lưu ý:**

- Không cần body. CV phải owned/READY, nếu chưa confirm trả 409.
- Bỏ primary cũ của user rồi chọn CV này.

<a id="api-034"></a>

#### API-034 — Lưu trữ CV cá nhân

**Method/path:** `DELETE /api/v1/resumes/{id}`  
**URL Postman:** `{{baseUrl}}/resumes/{{resumeId}}`  
**Quyền:** `USER`  
**Body mode:** `none`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{activeToken}}` |

**Path variables:**

| Param | Giá trị Postman | Điều kiện |
| --- | --- | --- |
| `id` | `{{resumeId}}` | UUID thực từ response/API liên quan; phải thuộc người dùng khi endpoint kiểm tra ownership. |

**Query:** Không có query parameter được khai báo.

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 204 No Content

**Điều kiện và lưu ý:**

- Không cần body; soft archive, bỏ primary, invalidate vector/roadmap; không xóa file hoặc row vật lý.
- Chạy sau các ca CV/AI khác vì ARCHIVED không confirm/edit/matching được.

<a id="api-035"></a>

#### API-035 — Phân tích chất lượng CV

**Method/path:** `POST /api/v1/resumes/{id}/analyses`  
**URL Postman:** `{{baseUrl}}/resumes/{{resumeId}}/analyses`  
**Quyền:** `USER`  
**Body mode:** `none`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{activeToken}}` |

**Path variables:**

| Param | Giá trị Postman | Điều kiện |
| --- | --- | --- |
| `id` | `{{resumeId}}` | UUID thực từ response/API liên quan; phải thuộc người dùng khi endpoint kiểm tra ownership. |

**Query:** Không có query parameter được khai báo.

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 200 object resume_analysis và suggestions

**Lưu biến** (`json` = `pm.response.json()`; chỉ lưu khi response thành công và có dữ liệu):

| Biến | Expression | Lưu ý |
| --- | --- | --- |
| `analysisId` | `json.id` | Đây là ID resume_analyses dùng route /resumes/{id}/analyses/{analysisId}; không thay bằng ID ATS job. |

**Điều kiện và lưu ý:**

- Không cần body. CV phải owned/READY.
- Chat bật → CV_ANALYZE và quota; tắt → RULE_BASED completeness, ats_score=null.
- RULE_BASED 20 điểm mỗi mục summary/experience/education/skills/projects có nội dung; không phải phân tích CV-job.

<a id="api-036"></a>

#### API-036 — Lịch sử phân tích CV

**Method/path:** `GET /api/v1/resumes/{id}/analyses`  
**URL Postman:** `{{baseUrl}}/resumes/{{resumeId}}/analyses`  
**Quyền:** `USER`  
**Body mode:** `none`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{activeToken}}` |

**Path variables:**

| Param | Giá trị Postman | Điều kiện |
| --- | --- | --- |
| `id` | `{{resumeId}}` | UUID thực từ response/API liên quan; phải thuộc người dùng khi endpoint kiểm tra ownership. |

**Query:** Không có query parameter được khai báo.

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 200 mảng resume_analyses

**Lưu biến** (`json` = `pm.response.json()`; chỉ lưu khi response thành công và có dữ liệu):

| Biến | Expression | Lưu ý |
| --- | --- | --- |
| `analysisId` | `json[0].id` | Nếu có record; chọn analysis thuộc chính resumeId này. |

<a id="api-037"></a>

#### API-037 — Chi tiết một lần phân tích CV

**Method/path:** `GET /api/v1/resumes/{id}/analyses/{analysisId}`  
**URL Postman:** `{{baseUrl}}/resumes/{{resumeId}}/analyses/{{analysisId}}`  
**Quyền:** `USER`  
**Body mode:** `none`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{activeToken}}` |

**Path variables:**

| Param | Giá trị Postman | Điều kiện |
| --- | --- | --- |
| `id` | `{{resumeId}}` | UUID thực từ response/API liên quan; phải thuộc người dùng khi endpoint kiểm tra ownership. |
| `analysisId` | `{{analysisId}}` | ID resume_analyses phải thuộc resumeId; lấy từ POST/GET analyses. |

**Query:** Không có query parameter được khai báo.

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 200 analysis object với suggestions

<a id="api-038"></a>

#### API-038 — Tạo hoặc tái dùng vector CV

**Method/path:** `POST /api/v1/resumes/{id}/embedding`  
**URL Postman:** `{{baseUrl}}/resumes/{{resumeId}}/embedding`  
**Quyền:** `USER`  
**Body mode:** `none`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{activeToken}}` |

**Path variables:**

| Param | Giá trị Postman | Điều kiện |
| --- | --- | --- |
| `id` | `{{resumeId}}` | UUID thực từ response/API liên quan; phải thuộc người dùng khi endpoint kiểm tra ownership. |

**Query:** Không có query parameter được khai báo.

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 200 resume_embedding metadata

**Điều kiện và lưu ý:**

- Không cần body. CV owned/READY, embedding provider enabled và quota đầy đủ.
- Cache theo provider/model/content_hash; vector phải 1536 chiều hữu hạn.
- CV đổi trong lúc chờ provider trả 409, indexing job FAILED; confirm phiên bản mới rồi retry.
- Response metadata không chứa raw vector.

<a id="api-039"></a>

#### API-039 — Tải CV gốc hoặc nội dung đã review

**Method/path:** `GET /api/v1/resumes/{id}/download`  
**URL Postman:** `{{baseUrl}}/resumes/{{resumeId}}/download?text=true`  
**Quyền:** `USER`  
**Body mode:** `download`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{activeToken}}` |

**Path variables:**

| Param | Giá trị Postman | Điều kiện |
| --- | --- | --- |
| `id` | `{{resumeId}}` | UUID thực từ response/API liên quan; phải thuộc người dùng khi endpoint kiểm tra ownership. |

**Params (query):**

| Key | Type | Bắt buộc | Default khi bỏ | Input mẫu | Quy tắc |
| --- | --- | --- | --- | --- | --- |
| `text` | boolean | Không | `false` | `true` | false(default): original uploaded file nếu có; true: canonical reviewed content TXT. |

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 200 bytes, Content-Disposition attachment

**Điều kiện và lưu ý:**

- Postman dùng Send and Download; response bytes/attachment, không pm.response.json().
- CV generated/manual chưa có PDF/DOCX rendering: trả UTF-8 TXT. Owner vẫn có thể tải ARCHIVED.
- File storage/path/ownership được kiểm tra; header nosniff.

### 6.5. Danh mục kỹ năng và việc làm công khai

[JobController](../src/main/java/com/example/intelligent_cv_matching_ai_agent/controller/shared/jobs/JobController.java).

<a id="api-040"></a>

#### API-040 — Danh mục kỹ năng công khai

**Method/path:** `GET /api/v1/skills`  
**URL Postman:** `{{baseUrl}}/skills`  
**Quyền:** `PUBLIC`  
**Body mode:** `none`

Authorization tab: **No Auth**.

**Headers:** Không có header bắt buộc riêng.

**Query:** Không có query parameter được khai báo.

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 200 mảng skill active

**Lưu biến** (`json` = `pm.response.json()`; chỉ lưu khi response thành công và có dữ liệu):

| Biến | Expression | Lưu ý |
| --- | --- | --- |
| `skillId` | `json[0].id` | Chọn skill phù hợp tên Java/SQL theo response; không lưu nếu danh sách rỗng. |

<a id="api-041"></a>

#### API-041 — Tìm việc làm đang hoạt động

**Method/path:** `GET /api/v1/jobs`  
**URL Postman:** `{{baseUrl}}/jobs?q=Java&location=Ho%20Chi%20Minh&limit=20&offset=0`  
**Quyền:** `PUBLIC`  
**Body mode:** `none`

Authorization tab: **No Auth**.

**Headers:** Không có header bắt buộc riêng.

**Params (query):**

| Key | Type | Bắt buộc | Default khi bỏ | Input mẫu | Quy tắc |
| --- | --- | --- | --- | --- | --- |
| `q` | string | Không | `""` | `Java` | Tối đa 255 ký tự; tìm substring ILIKE trong job_title (không search company_name/raw_content). |
| `location` | string | Không | `""` | `Ho Chi Minh` | Tối đa 255; lọc substring ILIKE. |
| `limit` | integer | Không | `20` | `20` | 1..100. |
| `offset` | integer | Không | `0` | `0` | 0..100000. |

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 200 mảng market_job_posts

**Lưu biến** (`json` = `pm.response.json()`; chỉ lưu khi response thành công và có dữ liệu):

| Biến | Expression | Lưu ý |
| --- | --- | --- |
| `jobId` | `json[0].id` | Chọn đúng job muốn kiểm thử; nếu [] phải import trước. |

**Điều kiện và lưu ý:**

- Admin phải import/crawl ACTIVE jobs; chưa có dữ liệu trả [].
- Lọc chỉ ACTIVE; đây không phải semantic matching.

<a id="api-042"></a>

#### API-042 — Chi tiết việc làm và kỹ năng yêu cầu

**Method/path:** `GET /api/v1/jobs/{id}`  
**URL Postman:** `{{baseUrl}}/jobs/{{jobId}}`  
**Quyền:** `PUBLIC`  
**Body mode:** `none`

Authorization tab: **No Auth**.

**Headers:** Không có header bắt buộc riêng.

**Path variables:**

| Param | Giá trị Postman | Điều kiện |
| --- | --- | --- |
| `id` | `{{jobId}}` | UUID job ACTIVE; lấy từ GET jobs hoặc admin import. |

**Query:** Không có query parameter được khai báo.

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 200 job object + skills

**Điều kiện và lưu ý:**

- Job không ACTIVE trả 404.

### 6.6. Matching, ATS và công cụ nghề nghiệp

[CareerToolsController](../src/main/java/com/example/intelligent_cv_matching_ai_agent/controller/candidate/jobs/CareerToolsController.java).

<a id="api-043"></a>

#### API-043 — So khớp ứng viên với việc làm

**Method/path:** `POST /api/v1/matching`  
**URL Postman:** `{{baseUrl}}/matching`  
**Quyền:** `CANDIDATE`  
**Body mode:** `json`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{candidateAccessToken}}` |
| `Content-Type` | `application/json` |

**Query:** Không có query parameter được khai báo.

**Input fields:**

| Field | Type | Bắt buộc | Validation / cách dùng |
| --- | --- | --- | --- |
| `basis` | string | Có | CAREER_PROFILE\|ALL_CVS\|SELECTED_CV. |
| `resumeIds` | array&lt;UUID&gt; | Không | SELECTED_CV bắt buộc đúng 1 ID owned READY. ALL_CVS/CAREER_PROFILE không dùng list này. |
| `limit` | integer | Có | 1..100; DTO int không có default. Bỏ field =0 gây validation 400, explicit null cũng không tạo default20. |

**Body → raw → JSON (input mẫu):**

~~~json
{
  "basis": "SELECTED_CV",
  "resumeIds": [
    "{{resumeId}}"
  ],
  "limit": 20
}
~~~

**Kết quả mong đợi:** 200 job_matching_run object, results[], cached

**Lưu biến** (`json` = `pm.response.json()`; chỉ lưu khi response thành công và có dữ liệu):

| Biến | Expression | Lưu ý |
| --- | --- | --- |
| `matchingRunId` | `json.id` | Chỉ lưu sau response thành công; chọn đúng bản ghi cần kiểm thử. |
| `matchResultId` | `json.results[0].id` | Chỉ khi results có phần tử; đây là ID job_match_results, không phải jobId. |
| `jobId` | `json.results[0].market_job_id` | Chọn job của result cần test; chỉ khi results không rỗng. |

**Điều kiện và lưu ý:**

- Body bắt buộc; SELECTED_CV sample dùng CV đã confirm.
- CAREER_PROFILE cần profile, nên điền profile skills; ALL_CVS dùng tất cả READY không demo tối đa100 và cần ít nhất1.
- Cần ACTIVE jobs; không có jobs trả409. AI tắt → SKILL_OVERLAP; AI bật cần chat+embedding và ACTIVE jobs indexed đúng provider/model.
- SEMANTIC_LLM tiêu quota embedding và rerank; cache Redis/database 1 giờ có thể trả cached=true.

<a id="api-044"></a>

#### API-044 — Chi tiết lần matching của mình

**Method/path:** `GET /api/v1/matching/{id}`  
**URL Postman:** `{{baseUrl}}/matching/{{matchingRunId}}`  
**Quyền:** `USER`  
**Body mode:** `none`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{activeToken}}` |

**Path variables:**

| Param | Giá trị Postman | Điều kiện |
| --- | --- | --- |
| `id` | `{{matchingRunId}}` | UUID job_matching_runs owned. |

**Query:** Không có query parameter được khai báo.

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 200 run object + results

<a id="api-045"></a>

#### API-045 — Lịch sử matching cá nhân

**Method/path:** `GET /api/v1/matching`  
**URL Postman:** `{{baseUrl}}/matching?limit=20`  
**Quyền:** `USER`  
**Body mode:** `none`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{activeToken}}` |

**Params (query):**

| Key | Type | Bắt buộc | Default khi bỏ | Input mẫu | Quy tắc |
| --- | --- | --- | --- | --- | --- |
| `limit` | integer | Không | `20` | `20` | 1..100. |

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 200 mảng matching runs

**Điều kiện và lưu ý:**

- Không có query offset cho endpoint này.

<a id="api-046"></a>

#### API-046 — Lịch sử AI và token của ứng viên

**Method/path:** `GET /api/v1/ai/usage`  
**URL Postman:** `{{baseUrl}}/ai/usage?limit=20&offset=0`  
**Quyền:** `CANDIDATE`  
**Body mode:** `none`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{candidateAccessToken}}` |

**Params (query):**

| Key | Type | Bắt buộc | Default khi bỏ | Input mẫu | Quy tắc |
| --- | --- | --- | --- | --- | --- |
| `limit` | integer | Không | `20` | `20` | 1..100. |
| `offset` | integer | Không | `0` | `0` | 0..100000. |

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 200 mảng usage logs với tokens/latency/success/error

<a id="api-047"></a>

#### API-047 — Đánh giá kết quả matching

**Method/path:** `PUT /api/v1/matching/results/{id}/feedback`  
**URL Postman:** `{{baseUrl}}/matching/results/{{matchResultId}}/feedback`  
**Quyền:** `USER`  
**Body mode:** `json`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{activeToken}}` |
| `Content-Type` | `application/json` |

**Path variables:**

| Param | Giá trị Postman | Điều kiện |
| --- | --- | --- |
| `id` | `{{matchResultId}}` | ID một job_match_results thuộc matching run owned; không dùng jobId. |

**Query:** Không có query parameter được khai báo.

**Input fields:**

| Field | Type | Bắt buộc | Validation / cách dùng |
| --- | --- | --- | --- |
| `feedbackType` | string | Có | LIKE\|DISLIKE, không trống. |
| `rating` | integer | Không | 1..5; bỏ/null hợp lệ. |
| `comment` | string | Không | Tối đa2000; bỏ/null hợp lệ. |

**Body → raw → JSON (input mẫu):**

~~~json
{
  "feedbackType": "LIKE",
  "rating": 5,
  "comment": "Vị trí phù hợp kỹ năng hiện có"
}
~~~

**Kết quả mong đợi:** 200 feedback object

**Điều kiện và lưu ý:**

- Body bắt buộc. Upsert feedback user/result; gọi lại sửa feedback.

<a id="api-048"></a>

#### API-048 — Phân tích ATS của CV theo job

**Method/path:** `POST /api/v1/jobs/{jobId}/ats`  
**URL Postman:** `{{baseUrl}}/jobs/{{jobId}}/ats`  
**Quyền:** `CANDIDATE`  
**Body mode:** `json`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{candidateAccessToken}}` |
| `Content-Type` | `application/json` |

**Path variables:**

| Param | Giá trị Postman | Điều kiện |
| --- | --- | --- |
| `jobId` | `{{jobId}}` | Job ACTIVE. |

**Query:** Không có query parameter được khai báo.

**Input fields:**

| Field | Type | Bắt buộc | Validation / cách dùng |
| --- | --- | --- | --- |
| `resumeId` | UUID | Có | CV owned/READY; camelCase. {} hoặc null thiếu ID bị validation từ chối. |

**Body → raw → JSON (input mẫu):**

~~~json
{
  "resumeId": "{{resumeId}}"
}
~~~

**Kết quả mong đợi:** 200 job_resume_analysis object + cached

**Điều kiện và lưu ý:**

- Body bắt buộc. Chat AI+quota cần cho lần phân tích mới; Redis TTL24h; database tái dùng bản ghi cùng input_hash, không có TTL riêng trong query.
- Response ats_score0..100, strengths_json/missing_skills_json/improvement_suggestions_json và cached.
- Không dùng response.id này làm analysisId của /resumes/.../analyses; ATS lưu bảng riêng.

<a id="api-049"></a>

#### API-049 — Sinh CV hướng tới một job

**Method/path:** `POST /api/v1/jobs/{jobId}/resume-draft`  
**URL Postman:** `{{baseUrl}}/jobs/{{jobId}}/resume-draft`  
**Quyền:** `CANDIDATE`  
**Body mode:** `json`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{candidateAccessToken}}` |
| `Content-Type` | `application/json` |

**Path variables:**

| Param | Giá trị Postman | Điều kiện |
| --- | --- | --- |
| `jobId` | `{{jobId}}` | Job ACTIVE. |

**Query:** Không có query parameter được khai báo.

**Input fields:**

| Field | Type | Bắt buộc | Validation / cách dùng |
| --- | --- | --- | --- |
| `title` | string | Không | Bỏ/null service mặc định CV - &lt;job_title&gt;; nếu gửi chuỗi phải không rỗng/max255. |
| `language` | string | Không | vi/en (VI/EN cũng normalize); bỏ/null dùng vi. |
| `template_id` | UUID | Không | {{resumeTemplateId}} phải active; bỏ/null nếu không dùng template. |
| `raw_content` | string | Không | Tối đa 200000 ký tự; tạo mới bỏ/null dùng chuỗi rỗng. |
| `structured_data` | object | Không | Facts CV gửi cho AI; có thể dùng đầy đủ object mẫu. |
| `structured_data.full_name` | string | Không | Tối đa 500 ký tự. |
| `structured_data.email` | string | Không | Tối đa 500; schema CV không áp dụng @Email cho field này. |
| `structured_data.phone` | string | Không | Tối đa 500. |
| `structured_data.location` | string | Không | Tối đa 500. |
| `structured_data.summary` | string | Không | Tối đa 20000 ký tự; nên có thông tin thực tế. |
| `structured_data.experience` | array | Không | Tối đa 100 entries; bỏ field dùng []; explicit null bị từ chối. |
| `structured_data.education` | array | Không | Tối đa 100 entries; bỏ field dùng []; explicit null bị từ chối. |
| `structured_data.projects` | array | Không | Tối đa 100 entries; bỏ field dùng []; explicit null bị từ chối. |
| `structured_data.certifications` | array | Không | Tối đa 100 entries; bỏ field dùng []; explicit null bị từ chối. |
| `structured_data.skills` | array | Không | Tối đa 100 entries; bỏ field dùng []; explicit null bị từ chối. |
| `structured_data.experience[].company_name` | string | Không | Tối đa 255. |
| `structured_data.experience[].job_title` | string | Không | Tối đa 255. |
| `structured_data.experience[].start_date` | string YYYY-MM-DD | Không | ISO date hoặc bỏ/null. |
| `structured_data.experience[].end_date` | string YYYY-MM-DD | Không | Không trước start_date; bỏ/null khi is_current=true. |
| `structured_data.experience[].is_current` | boolean | Không | Phải boolean nếu gửi; true không được có end_date. |
| `structured_data.experience[].description` | string | Không | Tối đa 20000. |
| `structured_data.education[].school_name` | string | Không | Tối đa 255. |
| `structured_data.education[].degree` | string | Không | Tối đa 255. |
| `structured_data.education[].major` | string | Không | Tối đa 255. |
| `structured_data.education[].start_date` | string YYYY-MM-DD | Không | ISO date hoặc bỏ/null. |
| `structured_data.education[].end_date` | string YYYY-MM-DD | Không | Không trước start_date. |
| `structured_data.education[].description` | string | Không | Tối đa 20000. |
| `structured_data.projects[].project_name` | string | Không | Tối đa 255. |
| `structured_data.projects[].role` | string | Không | Tối đa 255. |
| `structured_data.projects[].description` | string | Không | Tối đa 20000. |
| `structured_data.projects[].technologies` | array&lt;string&gt; | Không | Tối đa 100 strings, mỗi string tối đa 150. |
| `structured_data.projects[].project_url` | string | Không | Tối đa 255. |
| `structured_data.projects[].start_date` | string YYYY-MM-DD | Không | ISO date hoặc bỏ/null. |
| `structured_data.projects[].end_date` | string YYYY-MM-DD | Không | Không trước start_date. |
| `structured_data.certifications[].name` | string | Có | Bắt buộc không rỗng cho từng certification; tối đa 255. |
| `structured_data.certifications[].issuer` | string | Không | Tối đa 255. |
| `structured_data.certifications[].issue_date` | string YYYY-MM-DD | Không | ISO date hoặc bỏ/null. |
| `structured_data.certifications[].expiry_date` | string YYYY-MM-DD | Không | Không trước issue_date. |
| `structured_data.certifications[].credential_url` | string | Không | Tối đa 255. |
| `structured_data.skills[]` | string hoặc object | Không | String như Java được normalize thành {name:Java}. |
| `structured_data.skills[].name` | string | Có | Bắt buộc cho từng skill object, kể cả khi có skill_id; tối đa 255. |
| `structured_data.skills[].skill_id` | UUID | Không | Nếu chọn catalog dùng {{skillId}}, phải active; nếu bỏ thì confirm resolve/upsert theo slug của name. |
| `structured_data.skills[].proficiency_level` | string | Không | Tối đa 50; không enum validation ở ResumeData. |
| `structured_data.skills[].years_experience` | number | Không | Hữu hạn, 0..99. |

**Body → raw → JSON (input mẫu):**

~~~json
{
  "title": "CV Java cho job được chọn",
  "language": "vi",
  "template_id": "{{resumeTemplateId}}",
  "raw_content": "Sử dụng dữ kiện thật trong structured_data. Không thêm kinh nghiệm hoặc chứng chỉ chưa có.",
  "structured_data": {
    "full_name": "Nguyễn An",
    "email": "candidate@example.com",
    "phone": "0900000000",
    "location": "Ho Chi Minh",
    "summary": "Đã xây dựng REST API quản lý học tập với Java, Spring Boot và PostgreSQL.",
    "experience": [
      {
        "company_name": "Example Software",
        "job_title": "Backend Intern",
        "start_date": "2025-01-01",
        "end_date": "2025-06-30",
        "is_current": false,
        "description": "Triển khai REST API và viết truy vấn SQL trong dự án thực tập."
      }
    ],
    "education": [
      {
        "school_name": "Đại học Example",
        "degree": "Bachelor",
        "major": "Computer Science",
        "start_date": "2022-09-01",
        "end_date": "2026-06-30",
        "description": "Đã học lập trình, cơ sở dữ liệu và công nghệ phần mềm."
      }
    ],
    "projects": [
      {
        "project_name": "Learning Management API",
        "role": "Backend Developer",
        "description": "Thiết kế API khóa học, người dùng và bài tập; tích hợp PostgreSQL.",
        "technologies": [
          "Java",
          "Spring Boot",
          "PostgreSQL"
        ],
        "project_url": "https://example.com/projects/learning-api",
        "start_date": "2025-07-01",
        "end_date": "2025-12-31"
      }
    ],
    "certifications": [
      {
        "name": "Example Java Course Completion",
        "issuer": "Example Training",
        "issue_date": "2025-12-01",
        "expiry_date": "2027-12-01",
        "credential_url": "https://example.com/credentials/java-course"
      }
    ],
    "skills": [
      {
        "name": "Java",
        "proficiency_level": "BEGINNER",
        "years_experience": 1
      },
      {
        "name": "SQL",
        "proficiency_level": "BEGINNER",
        "years_experience": 1
      }
    ]
  }
}
~~~

**Lưu ý validation AI:** Các giới hạn section/ngày trong bảng mô tả cấu trúc CV **đầu ra** được normalize. Nested facts đầu vào AI không được kiểm tra như CV manual trước khi gọi provider; dùng cấu trúc mẫu để kết quả nhất quán.

**Kết quả mong đợi:** 200 CV AI_BUILDER/REVIEW_REQUIRED

**Lưu biến** (`json` = `pm.response.json()`; chỉ lưu khi response thành công và có dữ liệu):

| Biến | Expression | Lưu ý |
| --- | --- | --- |
| `resumeId` | `json.id` | Lưu ID CV mới, khác CV nguồn. |

**Điều kiện và lưu ý:**

- Dữ liệu mẫu là dữ kiện minh họa: thay bằng kinh nghiệm/chứng chỉ thực tế của người kiểm thử.
- Tên request CV là snake_case; section experience/education dùng số ít.
- Ownership lấy từ access token. Không cần/không dùng user_id client để thay chủ sở hữu.
- Template chỉ được kiểm tra ID active; backend chưa render PDF/DOCX theo template.
- Body JSON object bắt buộc; {} vẫn được service xây từ career profile/job, nhưng thiếu facts có thể tạo CV chưa đủ nội dung.
- Service tự thêm target_job/job_requirements/accuracy_requirement; không nhận resumeId để tự lấy CV cũ.
- Chat AI+quota; CV_BUILD tạo REVIEW_REQUIRED, cần review/confirm trước dùng matching.

<a id="api-050"></a>

#### API-050 — Sinh email ứng tuyển và cover letter

**Method/path:** `POST /api/v1/jobs/{jobId}/application-drafts`  
**URL Postman:** `{{baseUrl}}/jobs/{{jobId}}/application-drafts`  
**Quyền:** `CANDIDATE`  
**Body mode:** `json`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{candidateAccessToken}}` |
| `Content-Type` | `application/json` |

**Path variables:**

| Param | Giá trị Postman | Điều kiện |
| --- | --- | --- |
| `jobId` | `{{jobId}}` | Job ACTIVE. |

**Query:** Không có query parameter được khai báo.

**Input fields:**

| Field | Type | Bắt buộc | Validation / cách dùng |
| --- | --- | --- | --- |
| `resumeId` | UUID | Có | CV owned READY. |
| `language` | string | Không | vi\|en; bỏ/null dùng vi. |

**Body → raw → JSON (input mẫu):**

~~~json
{
  "resumeId": "{{resumeId}}",
  "language": "vi"
}
~~~

**Kết quả mong đợi:** 200 mảng 2 application_drafts (APPLICATION_EMAIL và COVER_LETTER)

**Lưu biến** (`json` = `pm.response.json()`; chỉ lưu khi response thành công và có dữ liệu):

| Biến | Expression | Lưu ý |
| --- | --- | --- |
| `applicationDraftId` | `json.find(item => item.draft_type === "APPLICATION_EMAIL").id` | Chọn theo draft_type; không giả định array[0] luôn là email. |

**Điều kiện và lưu ý:**

- Body bắt buộc. Chat AI+quota; APPLICATION_EMAIL lưu hai draft.
- Backend chỉ sinh nội dung, không gửi SMTP hay nộp đơn.

<a id="api-051"></a>

#### API-051 — Lịch sử nội dung ứng tuyển

**Method/path:** `GET /api/v1/application-drafts`  
**URL Postman:** `{{baseUrl}}/application-drafts?jobId={{jobId}}`  
**Quyền:** `USER`  
**Body mode:** `none`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{activeToken}}` |

**Params (query):**

| Key | Type | Bắt buộc | Default khi bỏ | Input mẫu | Quy tắc |
| --- | --- | --- | --- | --- | --- |
| `jobId` | UUID | Không | Không gửi | `{{jobId}}` | Bỏ query để lấy mọi draft owned; gửi để lọc market_job_id. Không gửi literal null hay placeholder trống. |

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 200 mảng application_drafts

**Điều kiện và lưu ý:**

- Tối đa100 records mới nhất; không có query limit/offset.

<a id="api-052"></a>

#### API-052 — Sửa nội dung email hoặc cover letter

**Method/path:** `PATCH /api/v1/application-drafts/{id}`  
**URL Postman:** `{{baseUrl}}/application-drafts/{{applicationDraftId}}`  
**Quyền:** `USER`  
**Body mode:** `json`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{activeToken}}` |
| `Content-Type` | `application/json` |

**Path variables:**

| Param | Giá trị Postman | Điều kiện |
| --- | --- | --- |
| `id` | `{{applicationDraftId}}` | ID application_drafts owned lấy từ response tạo/list. |

**Query:** Không có query parameter được khai báo.

**Input fields:**

| Field | Type | Bắt buộc | Validation / cách dùng |
| --- | --- | --- | --- |
| `content` | string | Có | Không trống, tối đa30000 ký tự; null/bỏ field không hợp lệ. |

**Body → raw → JSON (input mẫu):**

~~~json
{
  "content": "Kính gửi bộ phận tuyển dụng,\nTôi muốn ứng tuyển vị trí Java Backend Developer. Tôi đã thực hiện dự án REST API bằng Spring Boot và PostgreSQL.\nTrân trọng,\nNguyễn An"
}
~~~

**Kết quả mong đợi:** 200 draft object đã cập nhật

<a id="api-053"></a>

#### API-053 — Lấy URL ứng tuyển ngoài và lưu event

**Method/path:** `POST /api/v1/jobs/{id}/external-apply`  
**URL Postman:** `{{baseUrl}}/jobs/{{jobId}}/external-apply`  
**Quyền:** `CANDIDATE`  
**Body mode:** `json`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{candidateAccessToken}}` |
| `Content-Type` | `application/json` |

**Path variables:**

| Param | Giá trị Postman | Điều kiện |
| --- | --- | --- |
| `id` | `{{jobId}}` | Job ACTIVE; source_url phải hợp lệ theo JobSourceValidator. |

**Query:** Không có query parameter được khai báo.

**Input fields:**

| Field | Type | Bắt buộc | Validation / cách dùng |
| --- | --- | --- | --- |
| `resumeId` | UUID | Không | Optional ở endpoint này: controller không @Valid CvRequest; nếu gửi non-null phải owned/READY. |

**Body → raw → JSON (input mẫu):**

~~~json
{
  "resumeId": "{{resumeId}}"
}
~~~

**Kết quả mong đợi:** 200 {event_id,target_url}

**Điều kiện và lưu ý:**

- Body optional: Body none/JSON null/{} hoặc {resumeId:null} đều bỏ gắn CV.
- Sample gắn CV đã confirm. Trả target_url để frontend/người dùng mở.
- Không tự submit tại trang nguồn; event_type REDIRECT không phải ứng tuyển thành công.

<a id="api-054"></a>

#### API-054 — Ước lượng lương theo job và profile

**Method/path:** `POST /api/v1/jobs/{id}/salary-estimation`  
**URL Postman:** `{{baseUrl}}/jobs/{{jobId}}/salary-estimation`  
**Quyền:** `CANDIDATE`  
**Body mode:** `none`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{candidateAccessToken}}` |

**Path variables:**

| Param | Giá trị Postman | Điều kiện |
| --- | --- | --- |
| `id` | `{{jobId}}` | Job ACTIVE. |

**Query:** Không có query parameter được khai báo.

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 200 salary_estimation object với estimated_min/estimated_max/currency/reasoning

**Điều kiện và lưu ý:**

- Không cần body; cần career profile DB và chat AI/quota.
- Language mặc định vi; không có field body language tại route này.
- AI output validate min≤max, currency VND/USD/EUR; không phải giá thị trường được xác minh.

<a id="api-055"></a>

#### API-055 — Ước lượng lương theo nghề không cần job

**Method/path:** `POST /api/v1/salary-estimations`  
**URL Postman:** `{{baseUrl}}/salary-estimations`  
**Quyền:** `CANDIDATE`  
**Body mode:** `json`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{candidateAccessToken}}` |
| `Content-Type` | `application/json` |

**Query:** Không có query parameter được khai báo.

**Input fields:**

| Field | Type | Bắt buộc | Validation / cách dùng |
| --- | --- | --- | --- |
| `targetJobTitle` | string | Có | Không trống, tối đa160. |
| `location` | string | Không | Tối đa120; bỏ/null hợp lệ. |
| `currency` | string | Không | DTO giới hạn80 nhưng không enum đầu vào; nên dùng VND/USD/EUR vì output service chỉ chấp nhận ba currency này. |
| `language` | string | Không | vi\|en; bỏ/null dùng vi. |

**Body → raw → JSON (input mẫu):**

~~~json
{
  "targetJobTitle": "Java Backend Developer",
  "location": "Ho Chi Minh",
  "currency": "VND",
  "language": "vi"
}
~~~

**Kết quả mong đợi:** 200 salary_estimation object

**Điều kiện và lưu ý:**

- Body bắt buộc; không nhận jobId. Cần career profile DB, chat AI/quota.
- Lưu estimate không liên kết job; min≤max, currency output VND/USD/EUR.

### 6.7. Assessment và roadmap

[LearningController](../src/main/java/com/example/intelligent_cv_matching_ai_agent/controller/candidate/learning/LearningController.java).

<a id="api-056"></a>

#### API-056 — Sinh bài đánh giá kỹ năng

**Method/path:** `POST /api/v1/assessments`  
**URL Postman:** `{{baseUrl}}/assessments`  
**Quyền:** `CANDIDATE`  
**Body mode:** `json`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{candidateAccessToken}}` |
| `Content-Type` | `application/json` |

**Query:** Không có query parameter được khai báo.

**Input fields:**

| Field | Type | Bắt buộc | Validation / cách dùng |
| --- | --- | --- | --- |
| `targetJobTitle` | string | Không | Tối đa255; bỏ/null/trống lấy job_title, rồi profile.target_job_title; cuối cùng cần target không trống. |
| `jobId` | UUID | Không | Nếu gửi phải ACTIVE; bỏ/null để assessment từ target/profile. |

**Body → raw → JSON (input mẫu):**

~~~json
{
  "targetJobTitle": "Java Developer",
  "jobId": "{{jobId}}"
}
~~~

**Kết quả mong đợi:** 200 assessment session object + questions[] + answers[]

**Lưu biến** (`json` = `pm.response.json()`; chỉ lưu khi response thành công và có dữ liệu):

| Biến | Expression | Lưu ý |
| --- | --- | --- |
| `assessmentId` | `json.id` | Chỉ lưu sau response thành công; chọn đúng bản ghi cần kiểm thử. |
| `questionId` | `json.questions[0].id` | Lưu một question để trả lời; phải tiếp tục trả lời mọi question trước submit. |

**Điều kiện và lưu ý:**

- Body bắt buộc; {} được chấp nhận nếu profile đã có target.
- Chat AI/quota; prompt yêu cầu5 nền tảng+5 chuyên môn nhưng code chấp nhận2..30 câu, phải có cả part1/part2.
- Response status in_progress; questions chưa chứa correct_option/explanation.
- Sau tạo, các assessment cùng user/target trước đó thành is_current=false.

<a id="api-057"></a>

#### API-057 — Lịch sử assessment cá nhân

**Method/path:** `GET /api/v1/assessments`  
**URL Postman:** `{{baseUrl}}/assessments`  
**Quyền:** `USER`  
**Body mode:** `none`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{activeToken}}` |

**Query:** Không có query parameter được khai báo.

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 200 mảng assessment_sessions

**Điều kiện và lưu ý:**

- Tối đa100 phiên mới nhất; không có query limit/offset.

<a id="api-058"></a>

#### API-058 — Chi tiết assessment và lựa chọn đã lưu

**Method/path:** `GET /api/v1/assessments/{id}`  
**URL Postman:** `{{baseUrl}}/assessments/{{assessmentId}}`  
**Quyền:** `USER`  
**Body mode:** `none`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{activeToken}}` |

**Path variables:**

| Param | Giá trị Postman | Điều kiện |
| --- | --- | --- |
| `id` | `{{assessmentId}}` | UUID thực từ response/API liên quan; phải thuộc người dùng khi endpoint kiểm tra ownership. |

**Query:** Không có query parameter được khai báo.

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 200 session object + questions[] + answers[]

**Lưu biến** (`json` = `pm.response.json()`; chỉ lưu khi response thành công và có dữ liệu):

| Biến | Expression | Lưu ý |
| --- | --- | --- |
| `questionId` | `json.questions[0].id` | Chọn câu tiếp theo theo response, không lặp chỉ câu đầu nếu muốn submit. |

**Điều kiện và lưu ý:**

- in_progress không lộ correct_option/explanation; completed được xem đáp án/lời giải.

<a id="api-059"></a>

#### API-059 — Lưu lựa chọn cho một câu hỏi

**Method/path:** `PUT /api/v1/assessments/{id}/answers`  
**URL Postman:** `{{baseUrl}}/assessments/{{assessmentId}}/answers`  
**Quyền:** `USER`  
**Body mode:** `json`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{activeToken}}` |
| `Content-Type` | `application/json` |

**Path variables:**

| Param | Giá trị Postman | Điều kiện |
| --- | --- | --- |
| `id` | `{{assessmentId}}` | UUID thực từ response/API liên quan; phải thuộc người dùng khi endpoint kiểm tra ownership. |

**Query:** Không có query parameter được khai báo.

**Input fields:**

| Field | Type | Bắt buộc | Validation / cách dùng |
| --- | --- | --- | --- |
| `questionId` | UUID | Có | Question phải thuộc assessmentId này. |
| `selectedOption` | string | Có | Một trong A/B/C/D, uppercase, không trống. |

**Body → raw → JSON (input mẫu):**

~~~json
{
  "questionId": "{{questionId}}",
  "selectedOption": "B"
}
~~~

**Kết quả mong đợi:** 200 {question_id,selected_option}

**Điều kiện và lưu ý:**

- Body bắt buộc. Session owned/in_progress; upsert cho phép đổi đáp án trước submit.
- Đáp án đúng do server giữ; response không chấm correctness tại bước này.

<a id="api-060"></a>

#### API-060 — Nộp và chấm bài assessment

**Method/path:** `POST /api/v1/assessments/{id}/submit`  
**URL Postman:** `{{baseUrl}}/assessments/{{assessmentId}}/submit`  
**Quyền:** `USER`  
**Body mode:** `none`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{activeToken}}` |

**Path variables:**

| Param | Giá trị Postman | Điều kiện |
| --- | --- | --- |
| `id` | `{{assessmentId}}` | UUID thực từ response/API liên quan; phải thuộc người dùng khi endpoint kiểm tra ownership. |

**Query:** Không có query parameter được khai báo.

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 200 completed session object + questions/answers/skill_scores

**Điều kiện và lưu ý:**

- Không cần body. Trả lời đủ tất cả questions; thiếu trả400.
- Server chấm theo correct_option, lưu skill_scores, status completed; không gọi AI mới.
- Submit completed lần nữa trả cùng result; không sửa answer sau nộp.
- Roadmaps user cùng target đánh dấu outdated; chưa tự tạo roadmap.

<a id="api-061"></a>

#### API-061 — Tạo skill gap và lộ trình học

**Method/path:** `POST /api/v1/roadmaps`  
**URL Postman:** `{{baseUrl}}/roadmaps`  
**Quyền:** `CANDIDATE`  
**Body mode:** `json`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{candidateAccessToken}}` |
| `Content-Type` | `application/json` |

**Query:** Không có query parameter được khai báo.

**Input fields:**

| Field | Type | Bắt buộc | Validation / cách dùng |
| --- | --- | --- | --- |
| `targetJobTitle` | string | Không | Max255; bỏ/null/trống lấy job title rồi profile target; cần target cuối cùng không trống. |
| `jobId` | UUID | Không | ACTIVE job; bỏ/null nếu không dùng job. |
| `resumeId` | UUID | Không | Owned READY; bỏ/null nếu không dùng CV. |
| `assessmentId` | UUID | Không | Owned completed và cùng target (case-insensitive); bỏ/null tự tìm completed current cùng target nếu có. |

**Body → raw → JSON (input mẫu):**

~~~json
{
  "targetJobTitle": "Java Developer",
  "jobId": "{{jobId}}",
  "resumeId": "{{resumeId}}",
  "assessmentId": "{{assessmentId}}"
}
~~~

**Kết quả mong đợi:** 200 roadmap object + nodes[]/skill_gaps[]

**Lưu biến** (`json` = `pm.response.json()`; chỉ lưu khi response thành công và có dữ liệu):

| Biến | Expression | Lưu ý |
| --- | --- | --- |
| `roadmapId` | `json.id` | Chỉ lưu sau response thành công; chọn đúng bản ghi cần kiểm thử. |
| `roadmapNodeId` | `json.nodes[0].id` | Chọn node không có prerequisites hoặc đã hoàn thành prerequisites. |
| `gapAnalysisId` | `json.gap_analysis_id` | Chỉ lưu sau response thành công; chọn đúng bản ghi cần kiểm thử. |

**Điều kiện và lưu ý:**

- Body bắt buộc; sample cần mọi ID đã có. Xóa optional field chưa dùng; {} dùng career profile target.
- Chat AI/quota; assessment không bắt buộc. Nộp assessment và tạo roadmap là hai API riêng.
- ROADMAP_GENERATE → gap_analyses/version và nodes/prerequisites/resources. Nodes1..50, hours integer1..1000; prerequisite trỏ số thứ tự trước.
- Resources từ DB active title ILIKE skill name, tối đa3/node, có thể rỗng; không dùng URL AI tự tạo.
- Tổng giờ tính tổng node hours, trạng thái roadmap ready.

<a id="api-062"></a>

#### API-062 — Lịch sử roadmap cá nhân

**Method/path:** `GET /api/v1/roadmaps`  
**URL Postman:** `{{baseUrl}}/roadmaps`  
**Quyền:** `USER`  
**Body mode:** `none`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{activeToken}}` |

**Query:** Không có query parameter được khai báo.

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 200 mảng roadmaps

**Điều kiện và lưu ý:**

- Tối đa100 mới nhất; không query limit/offset.

<a id="api-063"></a>

#### API-063 — Chi tiết roadmap, gaps và resources

**Method/path:** `GET /api/v1/roadmaps/{id}`  
**URL Postman:** `{{baseUrl}}/roadmaps/{{roadmapId}}`  
**Quyền:** `USER`  
**Body mode:** `none`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{activeToken}}` |

**Path variables:**

| Param | Giá trị Postman | Điều kiện |
| --- | --- | --- |
| `id` | `{{roadmapId}}` | UUID thực từ response/API liên quan; phải thuộc người dùng khi endpoint kiểm tra ownership. |

**Query:** Không có query parameter được khai báo.

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 200 roadmap object + nodes (prerequisites/resources) + skill_gaps

<a id="api-064"></a>

#### API-064 — Cập nhật tiến độ học một node

**Method/path:** `PATCH /api/v1/roadmaps/{id}/nodes/{nodeId}`  
**URL Postman:** `{{baseUrl}}/roadmaps/{{roadmapId}}/nodes/{{roadmapNodeId}}`  
**Quyền:** `USER`  
**Body mode:** `json`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{activeToken}}` |
| `Content-Type` | `application/json` |

**Path variables:**

| Param | Giá trị Postman | Điều kiện |
| --- | --- | --- |
| `id` | `{{roadmapId}}` | UUID thực từ response/API liên quan; phải thuộc người dùng khi endpoint kiểm tra ownership. |
| `nodeId` | `{{roadmapNodeId}}` | Node phải thuộc roadmapId. |

**Query:** Không có query parameter được khai báo.

**Input fields:**

| Field | Type | Bắt buộc | Validation / cách dùng |
| --- | --- | --- | --- |
| `status` | string | Có | not_started\|in_progress\|completed, lowercase. |

**Body → raw → JSON (input mẫu):**

~~~json
{
  "status": "completed"
}
~~~

**Kết quả mong đợi:** 200 full roadmap đã cập nhật

**Điều kiện và lưu ý:**

- Body bắt buộc. Sang in_progress/completed phải hoàn thành tất cả prerequisite.
- Reset/lùi node yêu cầu dependent nodes về not_started trước, sai thứ tự409.
- Progress floor(100*completed/count); tất cả completed thì roadmap completed, còn lại ready.
- Không gọi AI; roadmap is_outdated không chặn API progress.

### 6.8. Career assistant

[CareerAssistantController](../src/main/java/com/example/intelligent_cv_matching_ai_agent/controller/candidate/ai/CareerAssistantController.java).

<a id="api-065"></a>

#### API-065 — Danh sách hội thoại assistant

**Method/path:** `GET /api/v1/assistant/conversations`  
**URL Postman:** `{{baseUrl}}/assistant/conversations`  
**Quyền:** `USER`  
**Body mode:** `none`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{activeToken}}` |

**Query:** Không có query parameter được khai báo.

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 200 mảng career_assistant_conversations

**Điều kiện và lưu ý:**

- Có cả ARCHIVED; order updated_at DESC; không query pagination.

<a id="api-066"></a>

#### API-066 — Tạo hội thoại có ngữ cảnh nghề nghiệp

**Method/path:** `POST /api/v1/assistant/conversations`  
**URL Postman:** `{{baseUrl}}/assistant/conversations`  
**Quyền:** `USER`  
**Body mode:** `json`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{activeToken}}` |
| `Content-Type` | `application/json` |

**Query:** Không có query parameter được khai báo.

**Input fields:**

| Field | Type | Bắt buộc | Validation / cách dùng |
| --- | --- | --- | --- |
| `title` | string | Không | Max255; bỏ mặc định Tư vấn nghề nghiệp; explicit null bị DTO từ chối. |
| `context_type` | string | Không | Max50, không enum; bỏ default general; explicit null bị từ chối. |
| `related_resume_id` | UUID | Không | Owned CV; service assistant không yêu cầu READY. |
| `related_job_id` | UUID | Không | ACTIVE job tại lúc tạo. |
| `related_gap_analysis_id` | UUID | Không | Owned gap analysis; lấy từ roadmap.gap_analysis_id. |
| `related_roadmap_id` | UUID | Không | Owned roadmap. |

**Body → raw → JSON (input mẫu):**

~~~json
{
  "title": "Luyện phỏng vấn Java",
  "context_type": "resume",
  "related_resume_id": "{{resumeId}}",
  "related_job_id": "{{jobId}}",
  "related_gap_analysis_id": "{{gapAnalysisId}}",
  "related_roadmap_id": "{{roadmapId}}"
}
~~~

**Kết quả mong đợi:** 200 conversation object

**Lưu biến** (`json` = `pm.response.json()`; chỉ lưu khi response thành công và có dữ liệu):

| Biến | Expression | Lưu ý |
| --- | --- | --- |
| `assistantConversationId` | `json.id` | Chỉ lưu sau response thành công; chọn đúng bản ghi cần kiểm thử. |

**Điều kiện và lưu ý:**

- Body bắt buộc; {} valid tạo general conversation không link. Sample đầy đủ chỉ gửi refs đã có, xóa optional field chưa dùng.
- Request assistant create dùng snake_case. UUID refs bỏ/null không link.
- Không gọi AI khi create; trạng thái ACTIVE.

<a id="api-067"></a>

#### API-067 — Đọc hội thoại, messages và sources

**Method/path:** `GET /api/v1/assistant/conversations/{id}`  
**URL Postman:** `{{baseUrl}}/assistant/conversations/{{assistantConversationId}}`  
**Quyền:** `USER`  
**Body mode:** `none`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{activeToken}}` |

**Path variables:**

| Param | Giá trị Postman | Điều kiện |
| --- | --- | --- |
| `id` | `{{assistantConversationId}}` | UUID thực từ response/API liên quan; phải thuộc người dùng khi endpoint kiểm tra ownership. |

**Query:** Không có query parameter được khai báo.

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 200 conversation object + messages[] + sources[]

<a id="api-068"></a>

#### API-068 — Gửi tin nhắn assistant, tùy chọn RAG

**Method/path:** `POST /api/v1/assistant/conversations/{id}/messages`  
**URL Postman:** `{{baseUrl}}/assistant/conversations/{{assistantConversationId}}/messages`  
**Quyền:** `USER`  
**Body mode:** `json`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{activeToken}}` |
| `Content-Type` | `application/json` |

**Path variables:**

| Param | Giá trị Postman | Điều kiện |
| --- | --- | --- |
| `id` | `{{assistantConversationId}}` | UUID thực từ response/API liên quan; phải thuộc người dùng khi endpoint kiểm tra ownership. |

**Query:** Không có query parameter được khai báo.

**Input fields:**

| Field | Type | Bắt buộc | Validation / cách dùng |
| --- | --- | --- | --- |
| `message` | string | Có | Không trống, tối đa8000; dữ liệu nghề nghiệp thật. |
| `retrieve` | boolean | Không | Bỏ dùng false; explicit null bị DTO từ chối. true kích hoạt query embedding+RAG cho user đăng nhập. |

**Body → raw → JSON (input mẫu):**

~~~json
{
  "message": "Dựa trên hồ sơ và các dữ kiện đã có, mình nên cải thiện CV Java ở điểm nào?",
  "retrieve": false
}
~~~

**Kết quả mong đợi:** 200 assistant message object + sources[]

**Lưu biến** (`json` = `pm.response.json()`; chỉ lưu khi response thành công và có dữ liệu):

| Biến | Expression | Lưu ý |
| --- | --- | --- |
| `assistantMessageId` | `json.id` | ID ASSISTANT reply để feedback; không thay assistantConversationId. |

**Điều kiện và lưu ý:**

- Body bắt buộc. Owned conversation ACTIVE; archived hoặc bị archive lúc chờ trả409.
- Chat AI+quota. retrieve=true còn cần embedding provider/quota và RAG documents COMPLETED/không disabled.
- Lấy20 message gần nhất; retrieve tối đa5 chunks cosine≥0.55 cùng provider/model từ owner/admin documents.
- Response là ASSISTANT message object, không phải conversation object; sources gắn với reply.
- Provider lỗi trả502/503; output/nghiệp vụ thất bại sau response vẫn có thể tính usage.

<a id="api-069"></a>

#### API-069 — Lưu trữ hội thoại assistant

**Method/path:** `DELETE /api/v1/assistant/conversations/{id}`  
**URL Postman:** `{{baseUrl}}/assistant/conversations/{{assistantConversationId}}`  
**Quyền:** `USER`  
**Body mode:** `none`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{activeToken}}` |

**Path variables:**

| Param | Giá trị Postman | Điều kiện |
| --- | --- | --- |
| `id` | `{{assistantConversationId}}` | UUID thực từ response/API liên quan; phải thuộc người dùng khi endpoint kiểm tra ownership. |

**Query:** Không có query parameter được khai báo.

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 204 No Content

**Điều kiện và lưu ý:**

- Không cần body; ARCHIVED, không gửi message mới; GET list/detail vẫn đọc được.

<a id="api-070"></a>

#### API-070 — Đánh giá câu trả lời assistant

**Method/path:** `POST /api/v1/assistant/messages/{id}/feedback`  
**URL Postman:** `{{baseUrl}}/assistant/messages/{{assistantMessageId}}/feedback`  
**Quyền:** `USER`  
**Body mode:** `json`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{activeToken}}` |
| `Content-Type` | `application/json` |

**Path variables:**

| Param | Giá trị Postman | Điều kiện |
| --- | --- | --- |
| `id` | `{{assistantMessageId}}` | Phải là ASSISTANT message của conversation owned, không phải USER message. |

**Query:** Không có query parameter được khai báo.

**Input fields:**

| Field | Type | Bắt buộc | Validation / cách dùng |
| --- | --- | --- | --- |
| `rating` | integer | Không | 1..5; bỏ/null hợp lệ; số thập phân bị từ chối. |
| `is_helpful` | boolean | Không | Bỏ/null hợp lệ. |
| `feedback_text` | string | Không | Tối đa4000; bỏ/null hợp lệ. |

**Body → raw → JSON (input mẫu):**

~~~json
{
  "rating": 5,
  "is_helpful": true,
  "feedback_text": "Gợi ý phù hợp dữ kiện trong CV"
}
~~~

**Kết quả mong đợi:** 200 feedback object

**Điều kiện và lưu ý:**

- Body bắt buộc; {} hợp lệ vì mọi field optional.
- Snake_case is_helpful/feedback_text; insert feedback mới mỗi request, không upsert.

### 6.9. Tư vấn candidate / consultant / admin

[CandidateConsultationController](../src/main/java/com/example/intelligent_cv_matching_ai_agent/controller/candidate/consultation/CandidateConsultationController.java), [ConsultationController](../src/main/java/com/example/intelligent_cv_matching_ai_agent/controller/shared/consultation/ConsultationController.java), [AdminConsultationController](../src/main/java/com/example/intelligent_cv_matching_ai_agent/controller/admin/consultation/AdminConsultationController.java), [ConsultantConsultationController](../src/main/java/com/example/intelligent_cv_matching_ai_agent/controller/consultant/consultation/ConsultantConsultationController.java).

<a id="api-071"></a>

#### API-071 — Candidate tạo yêu cầu tư vấn

**Method/path:** `POST /api/v1/consultations`  
**URL Postman:** `{{baseUrl}}/consultations`  
**Quyền:** `CANDIDATE`  
**Body mode:** `json`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{candidateAccessToken}}` |
| `Content-Type` | `application/json` |

**Query:** Không có query parameter được khai báo.

**Input fields:**

| Field | Type | Bắt buộc | Validation / cách dùng |
| --- | --- | --- | --- |
| `subject` | string | Có | Không rỗng, tối đa255. |
| `requestType` | string | Không | Tối đa50; chuỗi mô tả tự do, không enum cố định. |
| `description` | string | Có | Không rỗng, tối đa10000. |

**Body → raw → JSON (input mẫu):**

~~~json
{
  "subject": "Tư vấn hướng phát triển Backend",
  "requestType": "CAREER",
  "description": "Mình có kinh nghiệm Java cơ bản và muốn được tư vấn chuẩn bị CV và kỹ năng backend."
}
~~~

**Kết quả mong đợi:** 200 OK; object thread mới.

**Lưu biến** (`json` = `pm.response.json()`; chỉ lưu khi response thành công và có dữ liệu):

| Biến | Expression | Lưu ý |
| --- | --- | --- |
| `threadId` | `json.id` | UUID consultation dùng xuyên các endpoints tư vấn. |

**Điều kiện và lưu ý:**

- Tạo consultation_threads OPEN/priority NORMAL; consultant chưa được assign.

<a id="api-072"></a>

#### API-072 — Xem các consultation được phép truy cập

**Method/path:** `GET /api/v1/consultations`  
**URL Postman:** `{{baseUrl}}/consultations`  
**Quyền:** `USER`  
**Body mode:** `none`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{activeToken}}` |

**Query:** Không có query parameter được khai báo.

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 200 OK; array consultation_threads theo created_at DESC.

**Điều kiện và lưu ý:**

- Candidate: threads của mình; consultant: assigned threads; admin: tất cả; latest100.

<a id="api-073"></a>

#### API-073 — Xem một consultation

**Method/path:** `GET /api/v1/consultations/{id}`  
**URL Postman:** `{{baseUrl}}/consultations/{{threadId}}`  
**Quyền:** `THREAD`  
**Body mode:** `none`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{activeToken}}` |

**Path variables:**

| Param | Giá trị Postman | Điều kiện |
| --- | --- | --- |
| `id` | `{{threadId}}` | UUID thread; quyền candidate owner/current assigned consultant/admin. |

**Query:** Không có query parameter được khai báo.

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 200 OK; object consultation thread.

**Điều kiện và lưu ý:**

- Bearer của candidate chủ thread, consultant đang được assign hoặc ADMIN; người khác404.

<a id="api-074"></a>

#### API-074 — Đọc lịch sử tin nhắn tư vấn

**Method/path:** `GET /api/v1/consultations/{id}/messages`  
**URL Postman:** `{{baseUrl}}/consultations/{{threadId}}/messages?limit=50&offset=0`  
**Quyền:** `THREAD`  
**Body mode:** `none`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{activeToken}}` |

**Path variables:**

| Param | Giá trị Postman | Điều kiện |
| --- | --- | --- |
| `id` | `{{threadId}}` | UUID thread; quyền candidate owner/current assigned consultant/admin. |

**Params (query):**

| Key | Type | Bắt buộc | Default khi bỏ | Input mẫu | Quy tắc |
| --- | --- | --- | --- | --- | --- |
| `limit` | integer | Không | `50` | `50` | 1–100. |
| `offset` | integer | Không | `0` | `0` | 0–100000. |

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 200 OK; array messages kèm full_name/role người gửi.

**Điều kiện và lưu ý:**

- Bearer của candidate chủ thread, consultant đang được assign hoặc ADMIN; người khác404.
- Messages theo sent_at,id tăng dần; để lấy tiếp tăng offset; reconnect SSE nên đọc lại lịch sử để bù tin.

<a id="api-075"></a>

#### API-075 — Gửi tin nhắn trong thread tư vấn

**Method/path:** `POST /api/v1/consultations/{id}/messages`  
**URL Postman:** `{{baseUrl}}/consultations/{{threadId}}/messages`  
**Quyền:** `THREAD`  
**Body mode:** `json`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{activeToken}}` |
| `Content-Type` | `application/json` |

**Path variables:**

| Param | Giá trị Postman | Điều kiện |
| --- | --- | --- |
| `id` | `{{threadId}}` | UUID thread; quyền candidate owner/current assigned consultant/admin. |

**Query:** Không có query parameter được khai báo.

**Input fields:**

| Field | Type | Bắt buộc | Validation / cách dùng |
| --- | --- | --- | --- |
| `content` | string | Có | Không rỗng, tối đa10000. |

**Body → raw → JSON (input mẫu):**

~~~json
{
  "content": "Nhờ tư vấn viên nhận xét phần kỹ năng Java và kinh nghiệm trong CV của mình."
}
~~~

**Kết quả mong đợi:** 200 OK; object consultation_message mới.

**Lưu biến** (`json` = `pm.response.json()`; chỉ lưu khi response thành công và có dữ liệu):

| Biến | Expression | Lưu ý |
| --- | --- | --- |
| `consultationMessageId` | `json.id` | ID tin; dùng token của người nhận để mark read. |

**Điều kiện và lưu ý:**

- Bearer của candidate chủ thread, consultant đang được assign hoặc ADMIN; người khác404.
- Thread phải OPEN; closed409. Lock thread, insert message, phát SSE sau commit.

<a id="api-076"></a>

#### API-076 — Đánh dấu tin nhắn tư vấn đã đọc

**Method/path:** `PATCH /api/v1/consultations/{id}/messages/{messageId}/read`  
**URL Postman:** `{{baseUrl}}/consultations/{{threadId}}/messages/{{consultationMessageId}}/read`  
**Quyền:** `THREAD`  
**Body mode:** `none`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{activeToken}}` |

**Path variables:**

| Param | Giá trị Postman | Điều kiện |
| --- | --- | --- |
| `id` | `{{threadId}}` | UUID thread; quyền candidate owner/current assigned consultant/admin. |
| `messageId` | `{{consultationMessageId}}` | UUID message thuộc thread trên, thường chọn tin do người khác gửi. |

**Query:** Không có query parameter được khai báo.

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 200 OK; {id: UUID, read: true}; field read=true không chứng minh tin của mình được thay read_at.

**Điều kiện và lưu ý:**

- Bearer của candidate chủ thread, consultant đang được assign hoặc ADMIN; người khác404.
- Không body. Không cập nhật read_at cho tin chính mình gửi.

<a id="api-077"></a>

#### API-077 — Đóng consultation

**Method/path:** `POST /api/v1/consultations/{id}/close`  
**URL Postman:** `{{baseUrl}}/consultations/{{threadId}}/close`  
**Quyền:** `THREAD`  
**Body mode:** `none`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{activeToken}}` |

**Path variables:**

| Param | Giá trị Postman | Điều kiện |
| --- | --- | --- |
| `id` | `{{threadId}}` | UUID thread; quyền candidate owner/current assigned consultant/admin. |

**Query:** Không có query parameter được khai báo.

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 200 OK; object thread CLOSED.

**Điều kiện và lưu ý:**

- Bearer của candidate chủ thread, consultant đang được assign hoặc ADMIN; người khác404.
- Không body. Closed timestamp giữ lần đầu; ngắt SSE sau commit, không gửi thêm message được.

<a id="api-078"></a>

#### API-078 — Subscribe SSE tin nhắn tư vấn

**Method/path:** `GET /api/v1/consultations/{id}/events`  
**URL Postman:** `{{baseUrl}}/consultations/{{threadId}}/events`  
**Quyền:** `THREAD`  
**Body mode:** `sse`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{activeToken}}` |
| `Accept` | `text/event-stream` |

**Path variables:**

| Param | Giá trị Postman | Điều kiện |
| --- | --- | --- |
| `id` | `{{threadId}}` | UUID thread; quyền candidate owner/current assigned consultant/admin. |

**Query:** Không có query parameter được khai báo.

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 200 OK; text/event-stream connected/message cho đến đóng/timeout.

**Điều kiện và lưu ý:**

- Bearer của candidate chủ thread, consultant đang được assign hoặc ADMIN; người khác404.
- Không body. Accept: text/event-stream, bearer vẫn bắt buộc; Postman xem streaming events nếu bản đang dùng hỗ trợ.
- connected và message, timeout5 phút; in-process registry, không replay. Khi reconnect GET messages; assignment/close ngắt stream.
- Endpoint này stream text/event-stream, không parse response bằng pm.response.json().

<a id="api-079"></a>

#### API-079 — Admin phân công tư vấn viên

**Method/path:** `PUT /api/v1/consultations/{id}/consultant`  
**URL Postman:** `{{baseUrl}}/consultations/{{threadId}}/consultant`  
**Quyền:** `ADMIN`  
**Body mode:** `json`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{adminAccessToken}}` |
| `Content-Type` | `application/json` |

**Path variables:**

| Param | Giá trị Postman | Điều kiện |
| --- | --- | --- |
| `id` | `{{threadId}}` | UUID thread; quyền candidate owner/current assigned consultant/admin. |

**Query:** Không có query parameter được khai báo.

**Input fields:**

| Field | Type | Bắt buộc | Validation / cách dùng |
| --- | --- | --- | --- |
| `consultantId` | UUID | Có | User ACTIVE với role CAREER_CONSULTANT; dùng UUID trả từ admin create/list users. |

**Body → raw → JSON (input mẫu):**

~~~json
{
  "consultantId": "{{consultantId}}"
}
~~~

**Kết quả mong đợi:** 200 OK; object thread sau assignment.

**Điều kiện và lưu ý:**

- Thread phải OPEN. Assignment row-lock và audit; assign lại thu hồi quyền consultant cũ, disconnect SSE subscribers.

<a id="api-080"></a>

#### API-080 — Đọc career profile và CV của candidate trong thread

**Method/path:** `GET /api/v1/consultations/{id}/candidate-profile`  
**URL Postman:** `{{baseUrl}}/consultations/{{threadId}}/candidate-profile?resumeId={{resumeId}}`  
**Quyền:** `THREAD`  
**Body mode:** `none`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{activeToken}}` |

**Path variables:**

| Param | Giá trị Postman | Điều kiện |
| --- | --- | --- |
| `id` | `{{threadId}}` | UUID thread; quyền candidate owner/current assigned consultant/admin. |

**Params (query):**

| Key | Type | Bắt buộc | Default khi bỏ | Input mẫu | Quy tắc |
| --- | --- | --- | --- | --- | --- |
| `resumeId` | UUID | Không | Không gửi | `{{resumeId}}` | Optional; CV READY thuộc candidate của thread. Bỏ param lấy primary READY nếu có. |

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 200 OK; object candidate, career_profile (kèm skills), resume.

**Điều kiện và lưu ý:**

- Bearer của candidate chủ thread, consultant đang được assign hoặc ADMIN; người khác404.
- CandidateProfile endpoint không mở quyền tải file/list CV của ứng viên.

<a id="api-081"></a>

#### API-081 — Đọc các báo cáo đã lưu của candidate

**Method/path:** `GET /api/v1/consultations/{id}/candidate-analyses`  
**URL Postman:** `{{baseUrl}}/consultations/{{threadId}}/candidate-analyses?resumeId={{resumeId}}&jobId={{jobId}}&limit=20&offset=0`  
**Quyền:** `THREAD`  
**Body mode:** `none`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{activeToken}}` |

**Path variables:**

| Param | Giá trị Postman | Điều kiện |
| --- | --- | --- |
| `id` | `{{threadId}}` | UUID thread; quyền candidate owner/current assigned consultant/admin. |

**Params (query):**

| Key | Type | Bắt buộc | Default khi bỏ | Input mẫu | Quy tắc |
| --- | --- | --- | --- | --- | --- |
| `resumeId` | UUID | Không | Không gửi | `{{resumeId}}` | Nếu gửi phải READY thuộc candidate của thread. |
| `jobId` | UUID | Không | Không gửi | `{{jobId}}` | Lọc các nhóm gắn với job; không lọc generic resume_analyses. |
| `limit` | integer | Không | `20` | `20` | 1–100; áp dụng riêng mỗi nhóm kết quả. |
| `offset` | integer | Không | `0` | `0` | Không âm; DTO không đặt max; riêng từng nhóm. |

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 200 OK; candidate_id/resume_id/job_id/limit/offset và resume_analyses, ats_analyses, matching_results, salary_estimations, assessments, gap_analyses, roadmaps.

**Điều kiện và lưu ý:**

- Bearer của candidate chủ thread, consultant đang được assign hoặc ADMIN; người khác404.
- Candidate đã DELETED trả404. Repository khóa thread FOR SHARE để assignment không thay trong lượt đọc.
- Chỉ đọc report saved, không gọi AI/không quota mới. resumeId không lọc salary/assessment; jobId không lọc resume analysis.
- 7 nhóm độc lập, mới nhất created_at DESC,id DESC; không dữ liệu thì [] chứ không fail.

### 6.10. Subscription, payment và invoice

[BillingController](../src/main/java/com/example/intelligent_cv_matching_ai_agent/controller/shared/billing/BillingController.java).

<a id="api-082"></a>

#### API-082 — Xem các gói và quota đang hoạt động

**Method/path:** `GET /api/v1/subscriptions/tiers`  
**URL Postman:** `{{baseUrl}}/subscriptions/tiers`  
**Quyền:** `PUBLIC`  
**Body mode:** `none`

Authorization tab: **No Auth**.

**Headers:** Không có header bắt buộc riêng.

**Query:** Không có query parameter được khai báo.

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 200 — array active tiers; quota item featureCode/quotaValue/quotaPeriod.

**Lưu biến** (`json` = `pm.response.json()`; chỉ lưu khi response thành công và có dữ liệu):

| Biến | Expression | Lưu ý |
| --- | --- | --- |
| `paidTierId` | `json.find(tier => Number(tier.price_monthly) > 0)?.id` | Chọn gói active có giá &gt;0; thường BASIC do sort giá tăng. Kiểm tra giá/currency trước tạo order. |

**Điều kiện và lưu ý:**

- Không body/query/bearer. Đọc giá hiện tại qua API, không mặc định seed luôn giữ nguyên.
- Mẫu seed BASIC 99000 VND/tháng; PRO 199000; FREE không tạo payment order.

<a id="api-083"></a>

#### API-083 — Xem gói hiệu lực và hạn mức tài khoản

**Method/path:** `GET /api/v1/subscriptions/me`  
**URL Postman:** `{{baseUrl}}/subscriptions/me`  
**Quyền:** `USER`  
**Body mode:** `none`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{activeToken}}` |

**Query:** Không có query parameter được khai báo.

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 200 — {tier,quotas,tokensUsedThisMonth}.

**Điều kiện và lưu ý:**

- Không body/query. Gói ACTIVE/chưa hết hạn/tier active mới có quyền; fallback FREE ngay khi hết hạn, không đợi scheduler.
- tokensUsedThisMonth tính accounting RESERVED+COMPLETED của tháng, không phải request count.
- Response tier.id là ID tier, không phải ID user_subscription để cancel.

<a id="api-084"></a>

#### API-084 — Xem lịch sử subscription của tài khoản

**Method/path:** `GET /api/v1/subscriptions/history`  
**URL Postman:** `{{baseUrl}}/subscriptions/history`  
**Quyền:** `USER`  
**Body mode:** `none`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{activeToken}}` |

**Query:** Không có query parameter được khai báo.

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 200 — array user_subscriptions kèm display_name.

**Lưu biến** (`json` = `pm.response.json()`; chỉ lưu khi response thành công và có dữ liệu):

| Biến | Expression | Lưu ý |
| --- | --- | --- |
| `subscriptionId` | `json.find(subscription => subscription.status === 'ACTIVE')?.id` | Chọn subscription thật thuộc account; kiểm expiry và side effect trước cancel. |

**Điều kiện và lưu ý:**

- Không body/query; owner-only, tối đa 100 rows.
- Chọn subscription status ACTIVE và chưa hết hạn để thử cancel; id khác tier_id.

<a id="api-085"></a>

#### API-085 — Hủy subscription thuộc tài khoản

**Method/path:** `POST /api/v1/subscriptions/{id}/cancel`  
**URL Postman:** `{{baseUrl}}/subscriptions/{{subscriptionId}}/cancel`  
**Quyền:** `USER`  
**Body mode:** `none`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{activeToken}}` |

**Path variables:**

| Param | Giá trị Postman | Điều kiện |
| --- | --- | --- |
| `id` | `{{subscriptionId}}` | UUID user_subscription từ GET history, không paidTierId. |

**Query:** Không có query parameter được khai báo.

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 200 — {message:'Subscription cancelled'}.

**Điều kiện và lưu ý:**

- Không body. Owner-only; ID không thuộc user trả 404.
- CANCELLED và auto_renew=false; quyền gói dừng ngay; không tự refund. Test sau khi đã kiểm invoice/subscription thành công.

<a id="api-086"></a>

#### API-086 — Tạo đơn mua gói có trả phí

**Method/path:** `POST /api/v1/payments/orders`  
**URL Postman:** `{{baseUrl}}/payments/orders`  
**Quyền:** `USER`  
**Body mode:** `json`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{activeToken}}` |
| `Content-Type` | `application/json` |

**Query:** Không có query parameter được khai báo.

**Input fields:**

| Field | Type | Bắt buộc | Validation / cách dùng |
| --- | --- | --- | --- |
| `tierId` | UUID string | Có | Gói tồn tại, is_active=true, price_monthly&gt;0. |
| `durationMonths` | integer | Có | 1–12; thiếu primitive này thành 0 và validation trả 400. |

**Body → raw → JSON (input mẫu):**

~~~json
{
  "tierId": "{{paidTierId}}",
  "durationMonths": 1
}
~~~

**Kết quả mong đợi:** 200 — payment_orders row (id,provider_order_id,amount,currency,status='PENDING',...).

**Lưu biến** (`json` = `pm.response.json()`; chỉ lưu khi response thành công và có dữ liệu):

| Biến | Expression | Lưu ý |
| --- | --- | --- |
| `paymentOrderId` | `json.id` | UUID order dùng GET/report/webhook. |
| `providerOrderId` | `json.provider_order_id` | Provider reference phải khớp khi ký webhook. |
| `paymentAmount` | `json.amount` | Số tiền thật; body webhook phải nhập đúng dưới dạng JSON number. |
| `paymentCurrency` | `json.currency` | Tiền tệ thật; nếu DB char padding thì trim trước dùng. |

**Điều kiện và lưu ý:**

- Không gửi amount/currency từ browser để quyết định giá; backend lấy giá/currency tier × số tháng, tổng <=99999999.99.
- Trả order PENDING, chưa checkout/chưa kích hoạt subscription. MANUAL_GATEWAY chỉ signed webhook generic, chưa adapter thu tiền/QR.
- provider_order_id hiện bằng UUID order; dùng đúng giá trị response khi mô phỏng webhook.

<a id="api-087"></a>

#### API-087 — Liệt kê đơn thanh toán của tài khoản

**Method/path:** `GET /api/v1/payments/orders`  
**URL Postman:** `{{baseUrl}}/payments/orders`  
**Quyền:** `USER`  
**Body mode:** `none`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{activeToken}}` |

**Query:** Không có query parameter được khai báo.

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 200 — array payment order rows.

**Điều kiện và lưu ý:**

- Không body/query, owner-only; tối đa 100 rows theo created_at giảm dần.
- Không có page/limit/offset trong controller này.

<a id="api-088"></a>

#### API-088 — Xem một đơn thanh toán thuộc tài khoản

**Method/path:** `GET /api/v1/payments/orders/{id}`  
**URL Postman:** `{{baseUrl}}/payments/orders/{{paymentOrderId}}`  
**Quyền:** `USER`  
**Body mode:** `none`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{activeToken}}` |

**Path variables:**

| Param | Giá trị Postman | Điều kiện |
| --- | --- | --- |
| `id` | `{{paymentOrderId}}` | UUID order đã tạo bằng user hiện tại. |

**Query:** Không có query parameter được khai báo.

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 200 — payment order row.

**Lưu biến** (`json` = `pm.response.json()`; chỉ lưu khi response thành công và có dữ liệu):

| Biến | Expression | Lưu ý |
| --- | --- | --- |
| `subscriptionId` | `json.subscription_id` | Chỉ lưu nếu đơn đã COMPLETED và có subscription_id. |

**Điều kiện và lưu ý:**

- Không body/query. Sai ownership hoặc không tồn tại trả 404.
- Sau webhook COMPLETED response có subscription_id để đối chiếu lịch sử.

<a id="api-089"></a>

#### API-089 — Tạo báo cáo lỗi cho đơn thanh toán

**Method/path:** `POST /api/v1/payments/orders/{id}/reports`  
**URL Postman:** `{{baseUrl}}/payments/orders/{{paymentOrderId}}/reports`  
**Quyền:** `USER`  
**Body mode:** `json`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{activeToken}}` |
| `Content-Type` | `application/json` |

**Path variables:**

| Param | Giá trị Postman | Điều kiện |
| --- | --- | --- |
| `id` | `{{paymentOrderId}}` | UUID payment order thuộc account. |

**Query:** Không có query parameter được khai báo.

**Input fields:**

| Field | Type | Bắt buộc | Validation / cách dùng |
| --- | --- | --- | --- |
| `subject` | string | Có | Không trống/max255. |
| `description` | string | Có | Không trống/max10000. |

**Body → raw → JSON (input mẫu):**

~~~json
{
  "subject": "Đơn chưa được cập nhật",
  "description": "Tôi cần hỗ trợ kiểm tra trạng thái thanh toán của đơn này."
}
~~~

**Kết quả mong đợi:** 200 — payment_reports row.

**Lưu biến** (`json` = `pm.response.json()`; chỉ lưu khi response thành công và có dữ liệu):

| Biến | Expression | Lưu ý |
| --- | --- | --- |
| `paymentReportId` | `json.id` | UUID report để admin resolve. |

**Điều kiện và lưu ý:**

- Order phải thuộc user, sai ownership trả 404. Tạo report OPEN; không tự hoàn tiền.
- Admin dùng endpoint resolve riêng; user không gửi status/resolution trong request này.

<a id="api-090"></a>

#### API-090 — Liệt kê báo cáo lỗi thanh toán của tài khoản

**Method/path:** `GET /api/v1/payments/reports`  
**URL Postman:** `{{baseUrl}}/payments/reports`  
**Quyền:** `USER`  
**Body mode:** `none`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{activeToken}}` |

**Query:** Không có query parameter được khai báo.

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 200 — array payment report rows.

**Điều kiện và lưu ý:**

- Không body/query; owner-only, tối đa 100 rows.

<a id="api-091"></a>

#### API-091 — Liệt kê hóa đơn thuộc tài khoản

**Method/path:** `GET /api/v1/invoices`  
**URL Postman:** `{{baseUrl}}/invoices`  
**Quyền:** `USER`  
**Body mode:** `none`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{activeToken}}` |

**Query:** Không có query parameter được khai báo.

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 200 — array invoice rows.

**Lưu biến** (`json` = `pm.response.json()`; chỉ lưu khi response thành công và có dữ liệu):

| Biến | Expression | Lưu ý |
| --- | --- | --- |
| `invoiceId` | `json[0]?.id` | Chọn invoice thực thuộc user; chỉ lưu nếu array không rỗng. |

**Điều kiện và lưu ý:**

- Không body/query; owner-only, tối đa 100 theo issued_at giảm dần.
- Chỉ webhook COMPLETED đã verified tạo hóa đơn PAID; chưa có PDF invoice download.

<a id="api-092"></a>

#### API-092 — Xem hóa đơn thuộc tài khoản

**Method/path:** `GET /api/v1/invoices/{id}`  
**URL Postman:** `{{baseUrl}}/invoices/{{invoiceId}}`  
**Quyền:** `USER`  
**Body mode:** `none`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{activeToken}}` |

**Path variables:**

| Param | Giá trị Postman | Điều kiện |
| --- | --- | --- |
| `id` | `{{invoiceId}}` | UUID invoice từ GET /invoices, không paymentOrderId. |

**Query:** Không có query parameter được khai báo.

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 200 — invoice row.

**Điều kiện và lưu ý:**

- Không body/query; sai ownership hoặc không tồn tại trả 404.
- Subtotal=order amount, tax hiện 0, total=subtotal; không gửi mail/download PDF.

<a id="api-093"></a>

#### API-093 — Xử lý webhook thanh toán có chữ ký HMAC

**Method/path:** `POST /api/v1/payments/webhook`  
**URL Postman:** `{{baseUrl}}/payments/webhook`  
**Quyền:** `PUBLIC`  
**Body mode:** `raw-json`

Authorization tab: **No Auth**.

**Headers:**

| Key | Value |
| --- | --- |
| `X-Payment-Timestamp` | `{{paymentTimestamp}}` |
| `X-Payment-Signature` | `{{paymentSignature}}` |
| `Content-Type` | `application/json` |

**Query:** Không có query parameter được khai báo.

**Input fields:**

| Field | Type | Bắt buộc | Validation / cách dùng |
| --- | --- | --- | --- |
| `header.X-Payment-Timestamp` | string Unix seconds | Có | Header {{paymentTimestamp}}; timestamp lệch thời gian server tối đa ±300 giây. |
| `header.X-Payment-Signature` | string hex | Có | Header {{paymentSignature}}; 64 ký tự hex HMAC-SHA256 UTF-8(timestamp + '.' + rawBody), secret PAYMENT_WEBHOOK_SECRET &gt;=32 byte. |
| `orderId` | UUID string | Có | Order thật cần xử lý; user order ID. |
| `providerOrderId` | string | Có | Phải khớp provider_order_id response; tối đa 255. |
| `eventId` | string | Có | Không trống/max255; unique theo provider. Dùng UUID/event mới khi thử event mới. |
| `status` | string | Có | Chỉ COMPLETED\|FAILED\|CANCELLED. |
| `amount` | number | Có | Mẫu 99000 cho BASIC 1 tháng seed; đổi số này thành paymentAmount thật, không bọc trong dấu nháy. |
| `currency` | string | Có | Phải khớp currency order sau trim. |

**Body → raw → JSON (input mẫu):**

~~~json
{
  "orderId": "{{paymentOrderId}}",
  "providerOrderId": "{{providerOrderId}}",
  "eventId": "{{paymentEventId}}",
  "status": "COMPLETED",
  "amount": 99000,
  "currency": "{{paymentCurrency}}"
}
~~~

Nếu dùng Pre-request script mục5.4, **thay toàn bộ raw body bằng** chuỗi dưới đây; JSON trên chỉ minh họa payload. Script lấy ID/amount/currency/status từ Environment:

~~~text
{{paymentWebhookBody}}
~~~

**Kết quả mong đợi:** 200 — {accepted:true,duplicate:boolean,orderId}.

**Điều kiện và lưu ý:**

- Không Bearer; đây là endpoint PUBLIC được bảo vệ bằng chữ ký bắt buộc, không phải thanh toán không xác thực.
- Ký nguyên body raw sau Postman đã thay các biến Postman, không reformat JSON sau ký; body tối đa 65536 ký tự Java.
- Cấu hình provider backend phải bằng payment_provider của order. Sai chữ ký/timestamp trả 401; secret chưa đủ32 byte trả 503; mismatch order/amount/currency trả400.
- COMPLETED tạo subscription ACTIVE, invoice PAID, notification trong transaction; cancel mọi ACTIVE subscription cũ, không cộng hạn dư. FAILED/CANCELLED chỉ finalize order.
- Event replay cùng order trả duplicate=true, không kích hoạt thêm; eventId dùng cho order khác hoặc đổi terminal status trả409.
- Chỉ mô phỏng trên order/test account cục bộ; adapter checkout/gateway thật chưa có sẵn.

### 6.11. Admin users / tiers / AI / monitoring

[AdminController](../src/main/java/com/example/intelligent_cv_matching_ai_agent/controller/admin/management/AdminController.java).

<a id="api-094"></a>

#### API-094 — Thống kê quản trị

**Method/path:** `GET /api/v1/admin/dashboard`  
**URL Postman:** `{{baseUrl}}/admin/dashboard`  
**Quyền:** `ADMIN`  
**Body mode:** `none`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{adminAccessToken}}` |

**Query:** Không có query parameter được khai báo.

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 200 OK; object users, activeSubscriptions, revenue, aiUsage, openSupportTickets.

**Điều kiện và lưu ý:**

- Users chưa DELETED, subscription ACTIVE còn hạn, doanh thu payment thành công theo currency, AI usage tháng hiện tại và OPEN/IN_PROGRESS tickets.

<a id="api-095"></a>

#### API-095 — Tìm danh sách tài khoản

**Method/path:** `GET /api/v1/admin/users`  
**URL Postman:** `{{baseUrl}}/admin/users?search=Nguyen&page=0&size=20`  
**Quyền:** `ADMIN`  
**Body mode:** `none`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{adminAccessToken}}` |

**Params (query):**

| Key | Type | Bắt buộc | Default khi bỏ | Input mẫu | Quy tắc |
| --- | --- | --- | --- | --- | --- |
| `search` | string | Không | `""` | `Nguyen` | Tối đa 255 ký tự; tìm email hoặc full_name bằng ILIKE. |
| `page` | integer | Không | `0` | `0` | Không âm; offset = page × size. |
| `size` | integer | Không | `20` | `20` | 1–100. |

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 200 OK; array tài khoản, theo created_at DESC.

**Điều kiện và lưu ý:**

- Response array projection tài khoản; không trả password hash/token. Tự chọn id tài khoản đích để lưu adminUserId; consultant dùng consultantId.

<a id="api-096"></a>

#### API-096 — Xem tài khoản theo ID

**Method/path:** `GET /api/v1/admin/users/{id}`  
**URL Postman:** `{{baseUrl}}/admin/users/{{adminUserId}}`  
**Quyền:** `ADMIN`  
**Body mode:** `none`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{adminAccessToken}}` |

**Path variables:**

| Param | Giá trị Postman | Điều kiện |
| --- | --- | --- |
| `id` | `{{adminUserId}}` | UUID của tài khoản đích mà admin muốn quản lý. |

**Query:** Không có query parameter được khai báo.

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 200 OK; object tài khoản.

**Điều kiện và lưu ý:**

- Không tồn tại trả 404; đây là dữ liệu tài khoản, không credential.

<a id="api-097"></a>

#### API-097 — Admin tạo tài khoản consultant

**Method/path:** `POST /api/v1/admin/users`  
**URL Postman:** `{{baseUrl}}/admin/users`  
**Quyền:** `ADMIN`  
**Body mode:** `json`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{adminAccessToken}}` |
| `Content-Type` | `application/json` |

**Query:** Không có query parameter được khai báo.

**Input fields:**

| Field | Type | Bắt buộc | Validation / cách dùng |
| --- | --- | --- | --- |
| `email` | string | Có | Email hợp lệ, tối đa 255 ký tự; được trim/lowercase; không trùng. |
| `fullName` | string | Có | Không rỗng, tối đa 255 ký tự. |
| `role` | string | Có | Chính xác CANDIDATE \| CAREER_CONSULTANT \| ADMIN. |
| `temporaryPassword` | string | Có | 10–72 ký tự và tối đa 72 UTF-8 bytes. Không dùng mật khẩu thật trong tài liệu; không gửi qua SMTP. |

**Body → raw → JSON (input mẫu):**

~~~json
{
  "email": "{{adminAccountEmail}}",
  "fullName": "Tư vấn viên thử nghiệm",
  "role": "CAREER_CONSULTANT",
  "temporaryPassword": "{{temporaryPassword}}"
}
~~~

**Kết quả mong đợi:** 200 OK; object tài khoản mới với id.

**Lưu biến** (`json` = `pm.response.json()`; chỉ lưu khi response thành công và có dữ liệu):

| Biến | Expression | Lưu ý |
| --- | --- | --- |
| `adminUserId` | `json.id` | ID tài khoản đích để GET/PUT/DELETE admin/users. |
| `consultantId` | `json.id` | Ví dụ role CAREER_CONSULTANT nên dùng ID này để assign consultation. |

**Điều kiện và lưu ý:**

- Tài khoản mới EMAIL/ACTIVE và được đánh dấu verified ngay theo trusted admin flow; tạo CANDIDATE thì thêm candidate profile.
- Không gửi password qua SMTP; không có forced-change flag.

<a id="api-098"></a>

#### API-098 — Admin cập nhật tài khoản

**Method/path:** `PUT /api/v1/admin/users/{id}`  
**URL Postman:** `{{baseUrl}}/admin/users/{{adminUserId}}`  
**Quyền:** `ADMIN`  
**Body mode:** `json`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{adminAccessToken}}` |
| `Content-Type` | `application/json` |

**Path variables:**

| Param | Giá trị Postman | Điều kiện |
| --- | --- | --- |
| `id` | `{{adminUserId}}` | Tài khoản đích; không chọn active admin cuối cùng để hạ quyền. |

**Query:** Không có query parameter được khai báo.

**Input fields:**

| Field | Type | Bắt buộc | Validation / cách dùng |
| --- | --- | --- | --- |
| `fullName` | string | Có | Không rỗng, tối đa 255 ký tự. |
| `role` | string | Có | CANDIDATE \| CAREER_CONSULTANT \| ADMIN. |
| `status` | string | Có | ACTIVE \| SUSPENDED; không nhận PENDING/DELETED tại endpoint này. |

**Body → raw → JSON (input mẫu):**

~~~json
{
  "fullName": "Tư vấn viên thử nghiệm",
  "role": "CAREER_CONSULTANT",
  "status": "ACTIVE"
}
~~~

**Kết quả mong đợi:** 200 OK; object tài khoản sau cập nhật.

**Điều kiện và lưu ý:**

- Deleted account không được reactivate. Muốn ACTIVE thì email phải verified.
- Đổi role hoặc status khác ACTIVE thu hồi mọi session; admin ACTIVE cuối cùng được bảo vệ bằng lock, thao tác vi phạm trả 409.

<a id="api-099"></a>

#### API-099 — Admin soft-delete tài khoản

**Method/path:** `DELETE /api/v1/admin/users/{id}`  
**URL Postman:** `{{baseUrl}}/admin/users/{{adminUserId}}`  
**Quyền:** `ADMIN`  
**Body mode:** `none`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{adminAccessToken}}` |

**Path variables:**

| Param | Giá trị Postman | Điều kiện |
| --- | --- | --- |
| `id` | `{{adminUserId}}` | UUID tài khoản đích. |

**Query:** Không có query parameter được khai báo.

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 200 OK; {message: "Account deleted"}.

**Điều kiện và lưu ý:**

- Đánh dấu DELETED và revoked sessions, không hard-delete; không xóa active admin cuối cùng.

<a id="api-100"></a>

#### API-100 — Xem gói và cấu hình quota

**Method/path:** `GET /api/v1/admin/subscription-tiers`  
**URL Postman:** `{{baseUrl}}/admin/subscription-tiers`  
**Quyền:** `ADMIN`  
**Body mode:** `none`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{adminAccessToken}}` |

**Query:** Không có query parameter được khai báo.

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 200 OK; array subscription_tiers.

**Điều kiện và lưu ý:**

- Danh sách tất cả tiers theo price_monthly, gồm active/inactive; khác GET public subscriptions/tiers.

<a id="api-101"></a>

#### API-101 — Tạo subscription tier

**Method/path:** `POST /api/v1/admin/subscription-tiers`  
**URL Postman:** `{{baseUrl}}/admin/subscription-tiers`  
**Quyền:** `ADMIN`  
**Body mode:** `json`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{adminAccessToken}}` |
| `Content-Type` | `application/json` |

**Query:** Không có query parameter được khai báo.

**Input fields:**

| Field | Type | Bắt buộc | Validation / cách dùng |
| --- | --- | --- | --- |
| `tierCode` | string | Có | Pattern [A-Z0-9_]{2,30}; ví dụ BASIC_TEST. |
| `displayName` | string | Có | Không rỗng, tối đa 100 ký tự. |
| `priceMonthly` | number | Có | 0–99999999.99; tối đa 8 chữ số nguyên và 2 thập phân; JSON number. |
| `currency` | string | Có | Đúng 3 chữ cái viết HOA, ví dụ VND. |
| `active` | boolean | Không | Primitive boolean; bỏ field thì false. Nên truyền true rõ ràng để gói có thể mua. |

**Body → raw → JSON (input mẫu):**

~~~json
{
  "tierCode": "BASIC_TEST",
  "displayName": "Gói thử nghiệm",
  "priceMonthly": 99000,
  "currency": "VND",
  "active": true
}
~~~

**Kết quả mong đợi:** 200 OK; object tier mới.

**Lưu biến** (`json` = `pm.response.json()`; chỉ lưu khi response thành công và có dữ liệu):

| Biến | Expression | Lưu ý |
| --- | --- | --- |
| `paidTierId` | `json.id` | Gói active có giá &gt;0 dùng cho payment order. |

**Điều kiện và lưu ý:**

- Ghi audit; tier_code phải đáp ứng unique constraint database.

<a id="api-102"></a>

#### API-102 — Sửa subscription tier

**Method/path:** `PUT /api/v1/admin/subscription-tiers/{id}`  
**URL Postman:** `{{baseUrl}}/admin/subscription-tiers/{{paidTierId}}`  
**Quyền:** `ADMIN`  
**Body mode:** `json`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{adminAccessToken}}` |
| `Content-Type` | `application/json` |

**Path variables:**

| Param | Giá trị Postman | Điều kiện |
| --- | --- | --- |
| `id` | `{{paidTierId}}` | UUID tier cần sửa. |

**Query:** Không có query parameter được khai báo.

**Input fields:**

| Field | Type | Bắt buộc | Validation / cách dùng |
| --- | --- | --- | --- |
| `tierCode` | string | Có | Pattern [A-Z0-9_]{2,30}; ví dụ BASIC_TEST. |
| `displayName` | string | Có | Không rỗng, tối đa 100 ký tự. |
| `priceMonthly` | number | Có | 0–99999999.99; tối đa 8 chữ số nguyên và 2 thập phân; JSON number. |
| `currency` | string | Có | Đúng 3 chữ cái viết HOA, ví dụ VND. |
| `active` | boolean | Không | Primitive boolean; bỏ field thì false. Nên truyền true rõ ràng để gói có thể mua. |

**Body → raw → JSON (input mẫu):**

~~~json
{
  "tierCode": "BASIC_TEST",
  "displayName": "Gói thử nghiệm đã cập nhật",
  "priceMonthly": 129000,
  "currency": "VND",
  "active": true
}
~~~

**Kết quả mong đợi:** 200 OK; object tier sau cập nhật.

**Điều kiện và lưu ý:**

- FREE phải giữ tierCode FREE, active=true và giá 0; không được vô hiệu hóa hoặc biến FREE thành gói trả phí.

<a id="api-103"></a>

#### API-103 — Upsert hạn mức theo feature

**Method/path:** `PUT /api/v1/admin/subscription-tiers/{id}/quotas`  
**URL Postman:** `{{baseUrl}}/admin/subscription-tiers/{{paidTierId}}/quotas`  
**Quyền:** `ADMIN`  
**Body mode:** `json`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{adminAccessToken}}` |
| `Content-Type` | `application/json` |

**Path variables:**

| Param | Giá trị Postman | Điều kiện |
| --- | --- | --- |
| `id` | `{{paidTierId}}` | UUID tier có sẵn. |

**Query:** Không có query parameter được khai báo.

**Input fields:**

| Field | Type | Bắt buộc | Validation / cách dùng |
| --- | --- | --- | --- |
| `body (array)` | array | Có | Body là JSON array, tối đa 100 phần tử; không bọc quotas/object; array rỗng được DTO cho phép. |
| `[].featureCode` | string | Có | Pattern [A-Z0-9_]{2,100}; không trùng featureCode trong cùng batch. |
| `[].quotaValue` | integer | Không | Không âm (long); bỏ field thì 0. Với feature AI đây là ngân sách token, không số request. |
| `[].quotaPeriod` | string | Có | DAILY \| MONTHLY \| YEARLY \| LIFETIME, viết HOA. |

**Body → raw → JSON (input mẫu):**

~~~json
[
  {
    "featureCode": "AI_TOTAL_TOKENS",
    "quotaValue": 200000,
    "quotaPeriod": "MONTHLY"
  },
  {
    "featureCode": "CV_BUILD",
    "quotaValue": 50000,
    "quotaPeriod": "MONTHLY"
  }
]
~~~

**Kết quả mong đợi:** 200 OK; array quotas của tier theo feature_code.

**Điều kiện và lưu ý:**

- Upsert theo tier/feature; những feature không có trong batch vẫn giữ nguyên. Ví dụ AI_TOTAL_TOKENS kiểm soát tổng AI và CV_BUILD kiểm soát build.

<a id="api-104"></a>

#### API-104 — Xem lịch sử AI configuration

**Method/path:** `GET /api/v1/admin/ai/configurations`  
**URL Postman:** `{{baseUrl}}/admin/ai/configurations`  
**Quyền:** `ADMIN`  
**Body mode:** `none`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{adminAccessToken}}` |

**Query:** Không có query parameter được khai báo.

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 200 OK; array ai_configurations.

**Điều kiện và lưu ý:**

- Tối đa 100 records, theo config_type/version DESC. Dùng UUID id (không số version) khi restore.

<a id="api-105"></a>

#### API-105 — Lưu phiên bản AI configuration

**Method/path:** `POST /api/v1/admin/ai/configurations`  
**URL Postman:** `{{baseUrl}}/admin/ai/configurations`  
**Quyền:** `ADMIN`  
**Body mode:** `json`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{adminAccessToken}}` |
| `Content-Type` | `application/json` |

**Query:** Không có query parameter được khai báo.

**Input fields:**

| Field | Type | Bắt buộc | Validation / cách dùng |
| --- | --- | --- | --- |
| `configType` | string | Có | Pattern [A-Z_]{2,50}; CHAT tác động tên model chat khi provider khớp. |
| `provider` | string | Có | Không rỗng, tối đa 100 ký tự; phải khớp provider của process để CHAT model áp dụng. |
| `modelName` | string | Có | Không rỗng, tối đa 150 ký tự; model mà tài khoản provider của bạn có quyền gọi. |
| `configuration` | object | Có | Không null. Stringify toàn bộ object rồi kiểm tra các chuỗi api_key/apikey/secret/password, gồm values; credentials phải ở environment. |

**Body → raw → JSON (input mẫu):**

~~~json
{
  "configType": "CHAT",
  "provider": "{{aiProvider}}",
  "modelName": "{{aiChatModel}}",
  "configuration": {}
}
~~~

**Kết quả mong đợi:** 200 OK; object cấu hình mới.

**Lưu biến** (`json` = `pm.response.json()`; chỉ lưu khi response thành công và có dữ liệu):

| Biến | Expression | Lưu ý |
| --- | --- | --- |
| `aiConfigurationId` | `json.id` | UUID record dùng để restore. |

**Điều kiện và lưu ý:**

- Tạo version max+1 và UUID mới, active mới/deactivate cũ theo configType trong một transaction; audit.
- Không gọi model, không bật AI và không đổi provider bean/API key. configuration {} hợp lệ; không phải mọi key cấu hình tùy ý đều được AI đọc.

<a id="api-106"></a>

#### API-106 — Khôi phục cấu hình thành phiên bản mới

**Method/path:** `POST /api/v1/admin/ai/configurations/{oldVersionId}/restore`  
**URL Postman:** `{{baseUrl}}/admin/ai/configurations/{{aiConfigurationId}}/restore`  
**Quyền:** `ADMIN`  
**Body mode:** `none`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{adminAccessToken}}` |

**Path variables:**

| Param | Giá trị Postman | Điều kiện |
| --- | --- | --- |
| `oldVersionId` | `{{aiConfigurationId}}` | UUID id phiên bản muốn sao chép; không phải version integer. |

**Query:** Không có query parameter được khai báo.

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 200 OK; object cấu hình phiên bản mới.

**Điều kiện và lưu ý:**

- Không body. Copy record được chọn thành UUID mới và version max+1 rồi active; giữ nội dung/lịch sử cũ. Mỗi lần gọi tạo thêm version.
- Không bật AI hoặc đổi env/provider/credentials.

<a id="api-107"></a>

#### API-107 — Xem lịch sử prompt

**Method/path:** `GET /api/v1/admin/ai/prompts`  
**URL Postman:** `{{baseUrl}}/admin/ai/prompts`  
**Quyền:** `ADMIN`  
**Body mode:** `none`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{adminAccessToken}}` |

**Query:** Không có query parameter được khai báo.

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 200 OK; array ai_prompt_templates.

**Điều kiện và lưu ý:**

- Tối đa 100 records, theo feature_code/version DESC. Chọn UUID record cần restore.

<a id="api-108"></a>

#### API-108 — Lưu phiên bản prompt cho feature

**Method/path:** `POST /api/v1/admin/ai/prompts`  
**URL Postman:** `{{baseUrl}}/admin/ai/prompts`  
**Quyền:** `ADMIN`  
**Body mode:** `json`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{adminAccessToken}}` |
| `Content-Type` | `application/json` |

**Query:** Không có query parameter được khai báo.

**Input fields:**

| Field | Type | Bắt buộc | Validation / cách dùng |
| --- | --- | --- | --- |
| `featureCode` | string | Có | Pattern [A-Z0-9_]{2,100}; dùng feature thực tế như CAREER_CHAT/CV_BUILD/JOB_ATS. |
| `name` | string | Có | Không rỗng, tối đa 150 ký tự. |
| `systemPrompt` | string | Có | Không rỗng, tối đa 50000 ký tự; nối vào guidance của system prompt. |
| `userPromptTemplate` | string | Có | Không rỗng, tối đa 50000 ký tự; hiện được nối thành task output guidance text, không nội suy placeholder. |

**Body → raw → JSON (input mẫu):**

~~~json
{
  "featureCode": "CAREER_CHAT",
  "name": "Trợ lý nghề nghiệp",
  "systemPrompt": "Giải thích rõ bằng ngôn ngữ của người dùng, chỉ dựa vào thông tin đã cung cấp.",
  "userPromptTemplate": "Giữ câu trả lời thực tế, nêu rõ điều chưa chắc chắn."
}
~~~

**Kết quả mong đợi:** 200 OK; object prompt mới.

**Lưu biến** (`json` = `pm.response.json()`; chỉ lưu khi response thành công và có dữ liệu):

| Biến | Expression | Lưu ý |
| --- | --- | --- |
| `aiPromptId` | `json.id` | UUID record dùng để restore prompt. |

**Điều kiện và lưu ý:**

- Version max+1, active mới/deactivate trước trong group feature. Không gọi model; áp dụng lần generate kế tiếp của đúng feature.
- Không thay quy tắc JSON schema/factuality/data boundary của AiService.

<a id="api-109"></a>

#### API-109 — Khôi phục prompt thành phiên bản mới

**Method/path:** `POST /api/v1/admin/ai/prompts/{oldVersionId}/restore`  
**URL Postman:** `{{baseUrl}}/admin/ai/prompts/{{aiPromptId}}/restore`  
**Quyền:** `ADMIN`  
**Body mode:** `none`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{adminAccessToken}}` |

**Path variables:**

| Param | Giá trị Postman | Điều kiện |
| --- | --- | --- |
| `oldVersionId` | `{{aiPromptId}}` | UUID id prompt phiên bản muốn sao chép. |

**Query:** Không có query parameter được khai báo.

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 200 OK; object prompt phiên bản mới.

**Điều kiện và lưu ý:**

- Không body; copy sang UUID/version mới active, không ghi đè version cũ; audit RESTORE_AI_PROMPT.

<a id="api-110"></a>

#### API-110 — Xem logs và trạng thái vận hành

**Method/path:** `GET /api/v1/admin/monitoring/{category}`  
**URL Postman:** `{{baseUrl}}/admin/monitoring/{{monitoringCategory}}`  
**Quyền:** `ADMIN`  
**Body mode:** `none`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{adminAccessToken}}` |

**Path variables:**

| Param | Giá trị Postman | Điều kiện |
| --- | --- | --- |
| `category` | `{{monitoringCategory}}` | Một trong ai-usage \| vector-stores \| indexing-jobs \| logs \| audit \| billing \| crawl-runs; khác casing có thể bị 400. |

**Query:** Không có query parameter được khai báo.

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 200 OK; array records đúng category; category khác trả 400.

**Điều kiện và lưu ý:**

- Ví dụ environment monitoringCategory=ai-usage (chuỗi). Mỗi category tối đa 100 records gần nhất; không có query phân trang.

<a id="api-111"></a>

#### API-111 — Xem các báo cáo thanh toán

**Method/path:** `GET /api/v1/admin/payment-reports`  
**URL Postman:** `{{baseUrl}}/admin/payment-reports`  
**Quyền:** `ADMIN`  
**Body mode:** `none`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{adminAccessToken}}` |

**Query:** Không có query parameter được khai báo.

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 200 OK; array payment_reports.

**Điều kiện và lưu ý:**

- Tối đa 100 payment_reports mới nhất; tự chọn paymentReportId từ response.

<a id="api-112"></a>

#### API-112 — Đánh dấu báo cáo thanh toán đã giải quyết

**Method/path:** `POST /api/v1/admin/payment-reports/{id}/resolve`  
**URL Postman:** `{{baseUrl}}/admin/payment-reports/{{paymentReportId}}/resolve`  
**Quyền:** `ADMIN`  
**Body mode:** `json`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{adminAccessToken}}` |
| `Content-Type` | `application/json` |

**Path variables:**

| Param | Giá trị Postman | Điều kiện |
| --- | --- | --- |
| `id` | `{{paymentReportId}}` | UUID report đã tạo bởi người dùng. |

**Query:** Không có query parameter được khai báo.

**Input fields:**

| Field | Type | Bắt buộc | Validation / cách dùng |
| --- | --- | --- | --- |
| `resolution` | string | Có | Không rỗng, tối đa 10000 ký tự. |

**Body → raw → JSON (input mẫu):**

~~~json
{
  "resolution": "Đã đối chiếu thông tin đơn hàng và phản hồi người dùng."
}
~~~

**Kết quả mong đợi:** 200 OK; object report sau cập nhật.

**Điều kiện và lưu ý:**

- Cập nhật report RESOLVED, assigned_admin_id=current admin và resolved_at; audit. Endpoint này không tự xác nhận paid order hay cấp subscription.

### 6.12. Admin import / crawl / index việc làm

[AdminJobController](../src/main/java/com/example/intelligent_cv_matching_ai_agent/controller/admin/jobs/AdminJobController.java), [JobCrawlController](../src/main/java/com/example/intelligent_cv_matching_ai_agent/controller/admin/jobs/JobCrawlController.java).

<a id="api-113"></a>

#### API-113 — Import job bằng dữ liệu admin cung cấp

**Method/path:** `POST /api/v1/admin/jobs`  
**URL Postman:** `{{baseUrl}}/admin/jobs`  
**Quyền:** `ADMIN`  
**Body mode:** `json`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{adminAccessToken}}` |
| `Content-Type` | `application/json` |

**Query:** Không có query parameter được khai báo.

**Input fields:**

| Field | Type | Bắt buộc | Validation / cách dùng |
| --- | --- | --- | --- |
| `sourceSite` | string | Có | ITVIEC hoặc TOPCV; service strip/uppercase; tối đa 50 ký tự. |
| `sourceUrl` | string | Có | Tối đa 2000; {{sourceJobUrl}} phải là URL chi tiết thực tế HTTPS đúng itviec.com/www.itviec.com hoặc topcv.vn/www.topcv.vn theo sourceSite; không userinfo/port. |
| `jobTitle` | string | Có | Không rỗng, tối đa 255 ký tự. |
| `companyName` | string | Không | Tối đa 255 ký tự. |
| `location` | string | Không | Tối đa 255 ký tự. |
| `salaryText` | string | Không | Tối đa 255 ký tự. |
| `roleCategory` | string | Có | Không rỗng, tối đa 100 ký tự. |
| `content` | string | Có | Tối đa 100000; HTML/text normalize rồi phải dài ít nhất 30 ký tự; dùng nội dung nguồn thực được phép sử dụng. |
| `skills` | array&lt;object&gt; | Không | Tối đa 100 kỹ năng. |
| `skills[].skillId` | UUID | Không | Nếu gửi phải là skill active; lấy GET /skills. |
| `skills[].name` | string | Có | Mỗi phần tử: không rỗng, tối đa 100. |
| `skills[].required` | boolean | Không | Primitive boolean; bỏ field thì false; truyền true rõ ràng cho kỹ năng bắt buộc. |
| `employmentType` | string | Không | Tối đa50; normalize FULL_TIME/PART_TIME/CONTRACT/INTERNSHIP... khi biết alias, không invent loại thiếu. |
| `requirements` | string | Không | Tối đa100000; yêu cầu tuyển dụng có trong nguồn. |
| `responsibilities` | string | Không | Tối đa100000; trách nhiệm có trong nguồn. |
| `benefits` | string | Không | Tối đa100000; quyền lợi có trong nguồn. |
| `postedAt` | ISO8601 datetime | Không | Có offset, ví dụ 2026-10-01T08:00:00+07:00; dữ liệu ngày đăng nguồn. |
| `validThrough` | ISO8601 datetime | Không | Có offset, deadline phải tương lai khi import; bỏ field nếu nguồn không có deadline. |

**Body → raw → JSON (input mẫu):**

~~~json
{
  "sourceSite": "ITVIEC",
  "sourceUrl": "{{sourceJobUrl}}",
  "jobTitle": "Java Backend Developer",
  "companyName": "Example Company",
  "location": "Ho Chi Minh",
  "salaryText": "Thỏa thuận",
  "roleCategory": "Backend",
  "content": "Mô tả tuyển dụng được cung cấp hợp lệ, yêu cầu Java, SQL và Spring Boot cho công việc backend.",
  "employmentType": "FULL_TIME",
  "requirements": "Có kinh nghiệm Java, Spring Boot và SQL.",
  "responsibilities": "Xây dựng REST API và bảo trì backend.",
  "benefits": "Quyền lợi do nguồn cung cấp.",
  "postedAt": "2026-10-01T08:00:00+07:00",
  "validThrough": "2030-12-31T23:59:59+07:00",
  "skills": [
    {
      "name": "Java",
      "required": true
    },
    {
      "name": "SQL",
      "required": true
    }
  ]
}
~~~

**Kết quả mong đợi:** 200 OK; object job ACTIVE; job mới kèm skills.

**Lưu biến** (`json` = `pm.response.json()`; chỉ lưu khi response thành công và có dữ liệu):

| Biến | Expression | Lưu ý |
| --- | --- | --- |
| `jobId` | `json.id` | UUID job để xem public/index/ATS/matching. |

**Điều kiện và lưu ý:**

- Không fetch source URL hay gọi AI trong manual import; nội dung do admin cung cấp.
- Unchanged ACTIVE refresh alias và trả deduplicated=true; unchanged inactive trả409. Hash gồm metadata/skills/dates; source đổi nội dung chuyển alias tới version mới, canonical cũ chỉ REMOVED nếu không còn alias ACTIVE.
- Fingerprint bảo thủ liên kết nhiều nguồn nếu có company và cùng title/location/content/requirements chuẩn hóa. Response có sources; không fuzzy merge hoặc invent metadata thiếu.
- Import tự enqueue embedding; chưa có nghĩa vector đã sẵn sàng. Worker cần embedding provider/quota admin, xem GET job index để biết trạng thái. Dùng dates/source URL/content thực, bỏ metadata tùy chọn khi nguồn không có.

<a id="api-114"></a>

#### API-114 — Sinh embedding cho job

**Method/path:** `POST /api/v1/admin/jobs/{id}/index`  
**URL Postman:** `{{baseUrl}}/admin/jobs/{{jobId}}/index`  
**Quyền:** `ADMIN`  
**Body mode:** `none`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{adminAccessToken}}` |

**Path variables:**

| Param | Giá trị Postman | Điều kiện |
| --- | --- | --- |
| `id` | `{{jobId}}` | UUID ACTIVE job đã import/crawl. |

**Query:** Không có query parameter được khai báo.

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 200 OK; vector_indexing_jobs row với id/status/attempt_count/error_message/... theo trạng thái thực.

**Lưu biến:** `indexingJobId = json.id`; đây là task UUID, không ghi đè jobId.

**Điều kiện và lưu ý:**

- Không body. Cần embedding provider sẵn sàng; disabled trả503, job inactive/expired trả409.
- Enqueue/reuse task và thử xử lý nếu claim được lease. Đã có current vector đúng hash/provider/model thì reuse, không gọi provider lại.
- Response có thể COMPLETED, QUEUED, PROCESSING, FAILED hoặc CANCELLED. HTTP200 không bảo đảm đã index thành công; xem error_message/attempt_count và GET index status trước semantic matching.
- Lease/content hash ngăn worker cũ publish sau khi job đổi/hết hạn; retry hữu hạn. Quota/usage attributed admin yêu cầu.

<a id="api-138"></a>

#### API-138 — Xem trạng thái hàng đợi embedding job

**Method/path:** `GET /api/v1/admin/jobs/{id}/index`  
**URL Postman:** `{{baseUrl}}/admin/jobs/{{jobId}}/index`  
**Quyền:** `ADMIN`  
**Body mode:** `none`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{adminAccessToken}}` |

**Path variables:**

| Param | Giá trị Postman | Điều kiện |
| --- | --- | --- |
| `id` | `{{jobId}}` | UUID canonical job có thật, không task UUID. |

**Query:** Không có query parameter được khai báo.

**Body:** none; chỉ đọc, không gọi AI và không tiêu quota.

**Kết quả mong đợi:** 200 OK; `{job_id,indexing_jobs:[...]}`, tối đa20 task mới nhất; job chưa có task trả `[]`, không tồn tại trả404.

Task có `id,provider,model,content_hash,status,attempt_count,error_message,next_attempt_at,started_at,completed_at,created_at`. Kiểm đúng provider/model/content_hash trước kết luận vector dùng được; trạng thái gồm QUEUED/PROCESSING/COMPLETED/FAILED/CANCELLED.

**Lưu biến:** `indexingJobId = json.indexing_jobs[0]?.id`, chỉ khi array không rỗng. Không ghi đè jobId.

**Điều kiện và lưu ý:**

- Dùng sau import/crawl để theo dõi automatic embedding; HTTP200 của endpoint này chỉ nghĩa đọc trạng thái thành công.
- Lỗi FAILED xem error_message và cấu hình provider/quota, rồi dùng POST cùng path để retry/reuse task. Lượt PROCESSING khác đang giữ lease không được tạo vector trùng.
- Collection định kỳ/expiration và giới hạn nguồn xem [job-collection-setup.md](job-collection-setup.md); không có endpoint mới để bật scheduler trong Postman.

<a id="api-115"></a>

#### API-115 — Đổi trạng thái job

**Method/path:** `PATCH /api/v1/admin/jobs/{id}/status`  
**URL Postman:** `{{baseUrl}}/admin/jobs/{{jobId}}/status`  
**Quyền:** `ADMIN`  
**Body mode:** `raw-json`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{adminAccessToken}}` |
| `Content-Type` | `application/json` |

**Path variables:**

| Param | Giá trị Postman | Điều kiện |
| --- | --- | --- |
| `id` | `{{jobId}}` | UUID job cần thay trạng thái. |

**Query:** Không có query parameter được khai báo.

**Input fields:**

| Field | Type | Bắt buộc | Validation / cách dùng |
| --- | --- | --- | --- |
| `status` | string | Có | Chính xác ACTIVE \| EXPIRED \| REMOVED \| INVALID; không rỗng, tối đa 20. |

**Body → raw → JSON (input mẫu):**

~~~json
{
  "status": "ACTIVE"
}
~~~

**Kết quả mong đợi:** 200 OK; object job sau đổi status.

**Điều kiện và lưu ý:**

- Non-ACTIVE làm embeddings noncurrent và cập nhật alias status; chuyển ACTIVE enqueue lại embedding, chưa tự khôi phục vector current. Deadline đã qua không được activate; nhập source version mới. Ghi audit.

<a id="api-116"></a>

#### API-116 — Tìm và crawl job từ keyword hoặc URL có sẵn

**Method/path:** `POST /api/v1/admin/jobs/crawl`  
**URL Postman:** `{{baseUrl}}/admin/jobs/crawl`  
**Quyền:** `ADMIN`  
**Body mode:** `json`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{adminAccessToken}}` |
| `Content-Type` | `application/json` |

**Query:** Không có query parameter được khai báo.

**Input fields:**

| Field | Type | Bắt buộc | Validation / cách dùng |
| --- | --- | --- | --- |
| `sourceSite` | string | Có | ITVIEC \| TOPCV, viết HOA. |
| `keyword` | string | Có | Không rỗng, tối đa255; dùng search nguồn khi urls thiếu/null/rỗng. |
| `roleCategory` | string | Có | Không rỗng, tối đa 100. |
| `location` | string | Không | Tối đa 120; filter substring accent-insensitive của location nguồn; không dịch tên/viết tắt. |
| `urls` | array&lt;string&gt; | Không | Thiếu/null/[] kích hoạt discovery; nếu gửi tối đa20 URL HTTPS đúng nguồn, mỗi URL nonblank/max2000; canonical trùng fetch một lần. |
| `limit` | integer | Không | 1–20, mặc định20; số detail URL tối đa xử lý. |
| `maxPages` | integer | Không | 1–5, mặc định3; chỉ dùng cho discovery, không dùng khi có explicit URLs. |

**Body → raw → JSON (input mẫu):**

~~~json
{
  "sourceSite": "ITVIEC",
  "keyword": "Java",
  "roleCategory": "Backend",
  "location": "Ho Chi Minh",
  "urls": [
    "{{sourceJobUrl}}"
  ],
  "limit": 20,
  "maxPages": 3
}
~~~

**Discovery tự động (bỏ urls):**

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

**Kết quả mong đợi:** 200 OK; persisted crawl run object kèm items array.

**Lưu biến** (`json` = `pm.response.json()`; chỉ lưu khi response thành công và có dữ liệu):

| Biến | Expression | Lưu ý |
| --- | --- | --- |
| `crawlRunId` | `json.id` | UUID run để GET lịch sử chi tiết. |

**Điều kiện và lưu ý:**

- Crawl vẫn tuần tự trong HTTP request; thiếu urls thì tự search/discover. JSON-LD ưu tiên, thiếu thì fallback selector HTML theo nguồn; không chạy JavaScript hoặc bypass challenge.
- Fetch timeout5s mỗi connection, body2MiB, redirect checked≤3; một run có thể dài hơn5s.
- Skills/requirements/responsibilities/benefits/employmentType/dates được lấy qua metadata/sections/dictionary khi có; imported_skills có thể tăng. Dictionary không bao quát mọi skill; dùng manual import để cung cấp dữ liệu nguồn chính xác.
- Mỗi item transaction riêng; search outcomes SEARCH_FETCHED/SEARCH_FETCH_FAILED và detail outcomes IMPORTED/DUPLICATE/EXPIRED/WRONG_REGION/REJECTED/FETCH_FAILED/IMPORT_FAILED; run COMPLETED/PARTIAL/FAILED. Kiểm tra items ngay cả khi HTTP200.
- Import enqueue embedding tự động, expiry theo source aliases không hủy nguồn khác còn hiệu lực. Scheduler mặc định tắt; cấu hình tại [job-collection-setup.md](job-collection-setup.md). Fixture tests không bảo đảm HTML website hiện tại tương thích.

<a id="api-117"></a>

#### API-117 — Xem lịch sử crawl runs

**Method/path:** `GET /api/v1/admin/jobs/crawl`  
**URL Postman:** `{{baseUrl}}/admin/jobs/crawl?limit=20&offset=0`  
**Quyền:** `ADMIN`  
**Body mode:** `none`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{adminAccessToken}}` |

**Params (query):**

| Key | Type | Bắt buộc | Default khi bỏ | Input mẫu | Quy tắc |
| --- | --- | --- | --- | --- | --- |
| `limit` | integer | Không | `20` | `20` | 1–100. |
| `offset` | integer | Không | `0` | `0` | 0–100000. |

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 200 OK; array market_crawl_runs mới nhất.

<a id="api-118"></a>

#### API-118 — Xem crawl run và từng item

**Method/path:** `GET /api/v1/admin/jobs/crawl/{id}`  
**URL Postman:** `{{baseUrl}}/admin/jobs/crawl/{{crawlRunId}}`  
**Quyền:** `ADMIN`  
**Body mode:** `none`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{adminAccessToken}}` |

**Path variables:**

| Param | Giá trị Postman | Điều kiện |
| --- | --- | --- |
| `id` | `{{crawlRunId}}` | UUID run được trả ở POST crawl. |

**Query:** Không có query parameter được khai báo.

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 200 OK; object run kèm items; không tồn tại404.

### 6.13. Admin RAG

[RagAdminController](../src/main/java/com/example/intelligent_cv_matching_ai_agent/controller/admin/rag/RagAdminController.java).

<a id="api-119"></a>

#### API-119 — Xem danh sách tài liệu RAG

**Method/path:** `GET /api/v1/admin/rag/documents`  
**URL Postman:** `{{baseUrl}}/admin/rag/documents`  
**Quyền:** `ADMIN`  
**Body mode:** `none`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{adminAccessToken}}` |

**Query:** Không có query parameter được khai báo.

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 200 OK; array rag_documents.

**Điều kiện và lưu ý:**

- Latest100 documents; gồm trạng thái embedding và metadata disabled.

<a id="api-120"></a>

#### API-120 — Nhập tài liệu RAG dạng text

**Method/path:** `POST /api/v1/admin/rag/documents`  
**URL Postman:** `{{baseUrl}}/admin/rag/documents`  
**Quyền:** `ADMIN`  
**Body mode:** `json`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{adminAccessToken}}` |
| `Content-Type` | `application/json` |

**Query:** Không có query parameter được khai báo.

**Input fields:**

| Field | Type | Bắt buộc | Validation / cách dùng |
| --- | --- | --- | --- |
| `title` | string | Có | Không rỗng, tối đa255. |
| `content` | string | Có | Không rỗng, tối đa100000; text được chia chunks4000 ký tự với overlap300. |
| `sourceType` | string | Không | Nếu gửi phải ADMIN, không được explicit null; bỏ field vẫn mặc định ADMIN. |
| `metadata` | object | Không | Nếu gửi phải object, không explicit null. Service buộc disabled=false lúc tạo. |

**Body → raw → JSON (input mẫu):**

~~~json
{
  "title": "Hướng dẫn chuẩn bị phỏng vấn Java",
  "content": "Nội dung kiến thức đã kiểm tra, dùng làm nguồn tham khảo cho tư vấn nghề nghiệp và phỏng vấn Java.",
  "sourceType": "ADMIN",
  "metadata": {
    "topic": "java",
    "language": "vi"
  }
}
~~~

**Kết quả mong đợi:** 200 OK; object document kèm chunks.

**Lưu biến** (`json` = `pm.response.json()`; chỉ lưu khi response thành công và có dữ liệu):

| Biến | Expression | Lưu ý |
| --- | --- | --- |
| `ragDocumentId` | `json.id` | UUID document dùng index/get/archive. |

**Điều kiện và lưu ý:**

- Tạo rag_documents PENDING và rag_chunks; chưa gọi embed; đây là JSON text import, không multipart file.
- Unknown top-level fields được DTO bỏ qua.

<a id="api-121"></a>

#### API-121 — Xem tài liệu RAG và chunks

**Method/path:** `GET /api/v1/admin/rag/documents/{id}`  
**URL Postman:** `{{baseUrl}}/admin/rag/documents/{{ragDocumentId}}`  
**Quyền:** `ADMIN`  
**Body mode:** `none`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{adminAccessToken}}` |

**Path variables:**

| Param | Giá trị Postman | Điều kiện |
| --- | --- | --- |
| `id` | `{{ragDocumentId}}` | UUID document đã tạo. |

**Query:** Không có query parameter được khai báo.

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 200 OK; object document + chunks; chunk rows có thể kèm provider/model/indexed_at.

<a id="api-122"></a>

#### API-122 — Index embedding tài liệu RAG

**Method/path:** `POST /api/v1/admin/rag/documents/{id}/index`  
**URL Postman:** `{{baseUrl}}/admin/rag/documents/{{ragDocumentId}}/index`  
**Quyền:** `ADMIN`  
**Body mode:** `none`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{adminAccessToken}}` |

**Path variables:**

| Param | Giá trị Postman | Điều kiện |
| --- | --- | --- |
| `id` | `{{ragDocumentId}}` | Document chưa archive. |

**Query:** Không có query parameter được khai báo.

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 200 OK; object document COMPLETED kèm chunks.

**Điều kiện và lưu ý:**

- Không body. Cần embedding provider bật; disabled embedding trả503, indexing đang PROCESSING/archive trả409.
- PROCESSING → embed thiếu chunks của đúng provider/model → COMPLETED. Các bước commit riêng; lỗi để FAILED/retryable và FAILED indexing job.
- Không index tài liệu metadata.disabled=true. Retry bỏ qua embeddings đã có cùng provider/model.

<a id="api-123"></a>

#### API-123 — Archive tài liệu khỏi retrieval

**Method/path:** `POST /api/v1/admin/rag/documents/{id}/archive`  
**URL Postman:** `{{baseUrl}}/admin/rag/documents/{{ragDocumentId}}/archive`  
**Quyền:** `ADMIN`  
**Body mode:** `none`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{adminAccessToken}}` |

**Path variables:**

| Param | Giá trị Postman | Điều kiện |
| --- | --- | --- |
| `id` | `{{ragDocumentId}}` | Document muốn ngừng sử dụng cho RAG. |

**Query:** Không có query parameter được khai báo.

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 200 OK; object document kèm chunks và disabled=true.

**Điều kiện và lưu ý:**

- Không body. Set metadata.disabled=true/archived_at và embedding_status=FAILED; không status ARCHIVED.
- Embeddings vẫn được lưu nhưng retrieval bỏ document disabled; index không reactivate được.

### 6.14. Announcement và notification

[AdminAnnouncementController](../src/main/java/com/example/intelligent_cv_matching_ai_agent/controller/admin/announcement/AdminAnnouncementController.java), [AnnouncementController](../src/main/java/com/example/intelligent_cv_matching_ai_agent/controller/shared/announcement/AnnouncementController.java).

<a id="api-124"></a>

#### API-124 — Admin xem mọi announcement

**Method/path:** `GET /api/v1/admin/announcements`  
**URL Postman:** `{{baseUrl}}/admin/announcements`  
**Quyền:** `ADMIN`  
**Body mode:** `none`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{adminAccessToken}}` |

**Query:** Không có query parameter được khai báo.

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 200 OK; array announcements theo created_at DESC.

**Điều kiện và lưu ý:**

- Latest100, gồm DRAFT/PUBLISHED/ARCHIVED; không audience filter.

<a id="api-125"></a>

#### API-125 — Tạo announcement nháp

**Method/path:** `POST /api/v1/admin/announcements`  
**URL Postman:** `{{baseUrl}}/admin/announcements`  
**Quyền:** `ADMIN`  
**Body mode:** `json`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{adminAccessToken}}` |
| `Content-Type` | `application/json` |

**Query:** Không có query parameter được khai báo.

**Input fields:**

| Field | Type | Bắt buộc | Validation / cách dùng |
| --- | --- | --- | --- |
| `title` | string | Có | Không rỗng, tối đa255. |
| `content` | string | Có | Không rỗng, tối đa50000. |
| `expiresAt` | datetime | Không | ISO-8601 OffsetDateTime; nếu không null phải tương lai. Ví dụ body bỏ field để không hết hạn. |
| `targets` | array&lt;object&gt; | Có | 1–100; array không rỗng. |
| `targets[].type` | string | Có | ALL \| ROLE \| USER. |
| `targets[].role` | string | Không | ROLE bắt buộc role CANDIDATE \| CAREER_CONSULTANT \| ADMIN. |
| `targets[].userId` | UUID | Không | USER bắt buộc UUID user ACTIVE; ALL không cần role/userId. |

**Body → raw → JSON (input mẫu):**

~~~json
{
  "title": "Thông báo thử nghiệm hệ thống",
  "content": "Đây là thông báo cho nhóm ứng viên trong môi trường thử nghiệm.",
  "targets": [
    {
      "type": "ROLE",
      "role": "CANDIDATE"
    }
  ]
}
~~~

**Kết quả mong đợi:** 200 OK; object DRAFT kèm targets.

**Lưu biến** (`json` = `pm.response.json()`; chỉ lưu khi response thành công và có dữ liệu):

| Biến | Expression | Lưu ý |
| --- | --- | --- |
| `announcementId` | `json.id` | UUID draft để edit/publish/archive. |

**Điều kiện và lưu ý:**

- Tạo DRAFT. Có thể targets:[{type:'ALL'}] hoặc [{type:'USER',userId:'UUID_ACTIVE'}] thay cho ROLE.
- Không gửi mail; chưa tạo notifications cho đến publish.

<a id="api-126"></a>

#### API-126 — Sửa nội dung và audience announcement

**Method/path:** `PUT /api/v1/admin/announcements/{id}`  
**URL Postman:** `{{baseUrl}}/admin/announcements/{{announcementId}}`  
**Quyền:** `ADMIN`  
**Body mode:** `json`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{adminAccessToken}}` |
| `Content-Type` | `application/json` |

**Path variables:**

| Param | Giá trị Postman | Điều kiện |
| --- | --- | --- |
| `id` | `{{announcementId}}` | UUID DRAFT announcement. |

**Query:** Không có query parameter được khai báo.

**Input fields:**

| Field | Type | Bắt buộc | Validation / cách dùng |
| --- | --- | --- | --- |
| `title` | string | Có | Không rỗng, tối đa255. |
| `content` | string | Có | Không rỗng, tối đa50000. |
| `expiresAt` | datetime | Không | ISO-8601 OffsetDateTime; nếu không null phải tương lai. Ví dụ body bỏ field để không hết hạn. |
| `targets` | array&lt;object&gt; | Có | 1–100; array không rỗng. |
| `targets[].type` | string | Có | ALL \| ROLE \| USER. |
| `targets[].role` | string | Không | ROLE bắt buộc role CANDIDATE \| CAREER_CONSULTANT \| ADMIN. |
| `targets[].userId` | UUID | Không | USER bắt buộc UUID user ACTIVE; ALL không cần role/userId. |

**Body → raw → JSON (input mẫu):**

~~~json
{
  "title": "Thông báo thử nghiệm đã cập nhật",
  "content": "Đây là thông báo cho nhóm ứng viên trong môi trường thử nghiệm.",
  "targets": [
    {
      "type": "ROLE",
      "role": "CANDIDATE"
    }
  ]
}
~~~

**Kết quả mong đợi:** 200 OK; object announcement kèm targets sau cập nhật.

**Điều kiện và lưu ý:**

- Chỉ DRAFT edit; PUBLISHED/ARCHIVED409. Thay targets bằng batch gửi mới.

<a id="api-127"></a>

#### API-127 — Publish announcement và tạo notifications

**Method/path:** `POST /api/v1/admin/announcements/{id}/publish`  
**URL Postman:** `{{baseUrl}}/admin/announcements/{{announcementId}}/publish`  
**Quyền:** `ADMIN`  
**Body mode:** `none`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{adminAccessToken}}` |

**Path variables:**

| Param | Giá trị Postman | Điều kiện |
| --- | --- | --- |
| `id` | `{{announcementId}}` | UUID DRAFT announcement. |

**Query:** Không có query parameter được khai báo.

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 200 OK; object PUBLISHED kèm targets.

**Điều kiện và lưu ý:**

- Không body; phải DRAFT, có target, chưa expired. Row lock + publish_at=now và notifications distinct ACTIVE audience trong transaction.
- Thành công không gửi SMTP/Mailtrap; GET /notifications bằng token candidate audience để kiểm.

<a id="api-128"></a>

#### API-128 — Archive announcement

**Method/path:** `POST /api/v1/admin/announcements/{id}/archive`  
**URL Postman:** `{{baseUrl}}/admin/announcements/{{announcementId}}/archive`  
**Quyền:** `ADMIN`  
**Body mode:** `none`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{adminAccessToken}}` |

**Path variables:**

| Param | Giá trị Postman | Điều kiện |
| --- | --- | --- |
| `id` | `{{announcementId}}` | UUID announcement muốn archive. |

**Query:** Không có query parameter được khai báo.

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 200 OK; {message: "Announcement archived"}.

**Điều kiện và lưu ý:**

- Không body; set ARCHIVED nên không còn trong GET announcements. Notifications đã tạo vẫn tồn tại.

<a id="api-129"></a>

#### API-129 — Xem thông báo đúng audience

**Method/path:** `GET /api/v1/announcements`  
**URL Postman:** `{{baseUrl}}/announcements`  
**Quyền:** `USER`  
**Body mode:** `none`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{activeToken}}` |

**Query:** Không có query parameter được khai báo.

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 200 OK; array announcements theo publish_at DESC.

**Điều kiện và lưu ý:**

- Chỉ PUBLISHED, publish_at≤now, chưa expires_at, audience ALL/ROLE/USER khớp current user; latest100.

<a id="api-130"></a>

#### API-130 — Xem thông báo riêng của tài khoản

**Method/path:** `GET /api/v1/notifications`  
**URL Postman:** `{{baseUrl}}/notifications?unreadOnly=true`  
**Quyền:** `USER`  
**Body mode:** `none`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{activeToken}}` |

**Params (query):**

| Key | Type | Bắt buộc | Default khi bỏ | Input mẫu | Quy tắc |
| --- | --- | --- | --- | --- | --- |
| `unreadOnly` | boolean | Không | `false` | `true` | true để chỉ lấy chưa đọc; tên camelCase chính xác. |

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 200 OK; array notifications.

**Điều kiện và lưu ý:**

- Own user only; latest100 theo created_at DESC.

<a id="api-131"></a>

#### API-131 — Đánh dấu một notification đã đọc

**Method/path:** `PATCH /api/v1/notifications/{id}/read`  
**URL Postman:** `{{baseUrl}}/notifications/{{notificationId}}/read`  
**Quyền:** `USER`  
**Body mode:** `none`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{activeToken}}` |

**Path variables:**

| Param | Giá trị Postman | Điều kiện |
| --- | --- | --- |
| `id` | `{{notificationId}}` | UUID notification của current user, lấy GET notifications. |

**Query:** Không có query parameter được khai báo.

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 200 OK; {message: "Notification read"}.

**Điều kiện và lưu ý:**

- Không body; khác user404. read_at giữ giá trị lần đầu.

<a id="api-132"></a>

#### API-132 — Đánh dấu tất cả notification đã đọc

**Method/path:** `POST /api/v1/notifications/read-all`  
**URL Postman:** `{{baseUrl}}/notifications/read-all`  
**Quyền:** `USER`  
**Body mode:** `none`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{activeToken}}` |

**Query:** Không có query parameter được khai báo.

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 200 OK; {message: "Notifications read"}.

**Điều kiện và lưu ý:**

- Không body; chỉ notifications chưa đọc thuộc current user.

### 6.15. Support tickets

[SupportController](../src/main/java/com/example/intelligent_cv_matching_ai_agent/controller/shared/support/SupportController.java), [AdminSupportController](../src/main/java/com/example/intelligent_cv_matching_ai_agent/controller/admin/support/AdminSupportController.java).

<a id="api-133"></a>

#### API-133 — Xem support tickets

**Method/path:** `GET /api/v1/support/tickets`  
**URL Postman:** `{{baseUrl}}/support/tickets`  
**Quyền:** `USER`  
**Body mode:** `none`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{activeToken}}` |

**Query:** Không có query parameter được khai báo.

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 200 OK; array support_tickets.

**Điều kiện và lưu ý:**

- User xem own; admin xem tất cả. Latest100 theo created_at DESC.

<a id="api-134"></a>

#### API-134 — Tạo yêu cầu hỗ trợ

**Method/path:** `POST /api/v1/support/tickets`  
**URL Postman:** `{{baseUrl}}/support/tickets`  
**Quyền:** `USER`  
**Body mode:** `json`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{activeToken}}` |
| `Content-Type` | `application/json` |

**Query:** Không có query parameter được khai báo.

**Input fields:**

| Field | Type | Bắt buộc | Validation / cách dùng |
| --- | --- | --- | --- |
| `subject` | string | Có | Không rỗng, tối đa255. |
| `category` | string | Có | Không rỗng, tối đa50; DTO không có enum cố định. |
| `description` | string | Có | Không rỗng, tối đa10000. |

**Body → raw → JSON (input mẫu):**

~~~json
{
  "subject": "Cần hỗ trợ khi xác nhận CV",
  "category": "RESUME",
  "description": "Mình muốn được hướng dẫn cách sửa dữ liệu trích xuất và xác nhận CV."
}
~~~

**Kết quả mong đợi:** 200 OK; object ticket mới kèm messages.

**Lưu biến** (`json` = `pm.response.json()`; chỉ lưu khi response thành công và có dữ liệu):

| Biến | Expression | Lưu ý |
| --- | --- | --- |
| `ticketId` | `json.id` | UUID ticket để xem, gửi message, admin PATCH. |

**Điều kiện và lưu ý:**

- Bất kỳ authenticated user; không bắt role candidate dù DTO nằm candidate package. Ticket tạo OPEN/NORMAL.

<a id="api-135"></a>

#### API-135 — Xem ticket và lịch sử hỗ trợ

**Method/path:** `GET /api/v1/support/tickets/{id}`  
**URL Postman:** `{{baseUrl}}/support/tickets/{{ticketId}}`  
**Quyền:** `USER`  
**Body mode:** `none`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{activeToken}}` |

**Path variables:**

| Param | Giá trị Postman | Điều kiện |
| --- | --- | --- |
| `id` | `{{ticketId}}` | UUID ticket thuộc current user hoặc admin có quyền. |

**Query:** Không có query parameter được khai báo.

**Body:** none; không cần JSON payload.

**Kết quả mong đợi:** 200 OK; object ticket kèm messages.

**Điều kiện và lưu ý:**

- Owner/admin, người khác404. Messages tăng theo created_at, tối đa500; không query phân trang.

<a id="api-136"></a>

#### API-136 — Gửi tin nhắn hỗ trợ

**Method/path:** `POST /api/v1/support/tickets/{id}/messages`  
**URL Postman:** `{{baseUrl}}/support/tickets/{{ticketId}}/messages`  
**Quyền:** `USER`  
**Body mode:** `json`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{activeToken}}` |
| `Content-Type` | `application/json` |

**Path variables:**

| Param | Giá trị Postman | Điều kiện |
| --- | --- | --- |
| `id` | `{{ticketId}}` | UUID ticket thuộc current user hoặc admin có quyền. |

**Query:** Không có query parameter được khai báo.

**Input fields:**

| Field | Type | Bắt buộc | Validation / cách dùng |
| --- | --- | --- | --- |
| `content` | string | Có | Không rỗng, tối đa10000. |

**Body → raw → JSON (input mẫu):**

~~~json
{
  "content": "Mình đã kiểm tra lại và cần trợ giúp ở bước xác nhận CV."
}
~~~

**Kết quả mong đợi:** 200 OK; object support_ticket_message mới.

**Điều kiện và lưu ý:**

- Owner/admin; lock ticket, CLOSED409. RESOLVED vẫn gửi được theo code hiện tại.
- Nếu ADMIN reply ticket user khác thì insert SUPPORT_REPLY notification cho owner; không gửi SMTP.

<a id="api-137"></a>

#### API-137 — Admin cập nhật trạng thái và phân công ticket

**Method/path:** `PATCH /api/v1/support/tickets/{id}`  
**URL Postman:** `{{baseUrl}}/support/tickets/{{ticketId}}`  
**Quyền:** `ADMIN`  
**Body mode:** `json`

**Headers:**

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{adminAccessToken}}` |
| `Content-Type` | `application/json` |

**Path variables:**

| Param | Giá trị Postman | Điều kiện |
| --- | --- | --- |
| `id` | `{{ticketId}}` | UUID ticket thuộc current user hoặc admin có quyền. |

**Query:** Không có query parameter được khai báo.

**Input fields:**

| Field | Type | Bắt buộc | Validation / cách dùng |
| --- | --- | --- | --- |
| `status` | string | Có | OPEN \| IN_PROGRESS \| RESOLVED \| CLOSED. |
| `priority` | string | Có | LOW \| NORMAL \| HIGH \| URGENT. |
| `assignedAdminId` | UUID | Không | Nếu gửi UUID phải user ADMIN/ACTIVE; bỏ field/null làm assigned_admin_id=null vì SQL luôn ghi trường này. |

**Body → raw → JSON (input mẫu):**

~~~json
{
  "status": "IN_PROGRESS",
  "priority": "NORMAL"
}
~~~

**Kết quả mong đợi:** 200 OK; object ticket cập nhật kèm messages.

**Điều kiện và lưu ý:**

- Path không có /admin nhưng service yêu cầu ADMIN; user khác403.
- Lock ticket; RESOLVED/CLOSED set resolved_at=now, các status khác reset resolved_at=null; audit.

## 7. API kỹ thuật và lỗi thường gặp

Ngoài138 route nghiệp vụ, các URL kỹ thuật do Spring/OpenAPI cung cấp:

| Method | URL | Auth/body | Mục đích |
| --- | --- | --- | --- |
| GET | http://localhost:8080/actuator/health | No Auth, none | Health; mail/Redis health indicator tắt nên UP chưa chứng minh các tích hợp |
| GET | http://localhost:8080/actuator/health/liveness | No Auth, none | Liveness khi probes được bật |
| GET | http://localhost:8080/actuator/health/readiness | No Auth, none | Readiness khi probes được bật |
| GET | http://localhost:8080/v3/api-docs | No Auth, none | OpenAPI JSON để tra hợp đồng |
| GET | http://localhost:8080/swagger-ui/index.html | No Auth, none | Giao diện Swagger trong browser |
| GET | http://localhost:8080/actuator/info | Bearer hợp lệ theo security hiện tại, none | Endpoint info được expose, không nằm trong public allowlist |

GET download/SSE/204 không dùng script `pm.response.json()`.

| HTTP | Thường gặp |
| --- | --- |
| 200 | Thành công; POST tạo tài nguyên trong controller hiện tại thường200, không tự giả định201 |
| 204 | Xóa/archive CV/conversation; body rỗng |
| 400 | JSON/UUID/query sai, thiếu field, salary/date/options không hợp lệ |
| 401 | Token/key/signature/timestamp sai hoặc hết hạn |
| 403 | Role sai, account pending/inactive, feature quota0 |
| 404 | Không tồn tại hoặc không có quyền owner/assignment |
| 409 | Duplicate/trạng thái xung đột, thiếu vector/job, prerequisite hoặc order terminal conflict |
| 413 | Multipart quá lớn |
| 429 | Cooldown OTP, attempt/IP limit, AI/guest quota |
| 502 | AI provider/output structured không hợp lệ |
| 503 | Mail/AI/Google/DB/OCR hoặc cấu hình dịch vụ chưa sẵn sàng |

Service/validation thường trả application/problem+json với status/detail/errors; security filter có JSON status/message. Postman web khi gọi localhost cần kết nối tới runtime local thích hợp; desktop app gọi trực tiếp.

Nếu variable vẫn còn `{{...}}` sau gửi, kiểm tra đã chọn đúng Environment, tên biến, raw body và UUID response. Nếu token role sai, kiểm biến từng role và activeToken. Nếu401 trên public, bỏ bearer đang được inherit từ collection.

File này đối chiếu code và kiểm tra cấu trúc ví dụ; SMTP, AI provider, website crawl và payment gateway thật cần cấu hình/test tích hợp riêng. Xem [project-guide.md](project-guide.md), [swagger-testing.md](swagger-testing.md) và [api.md](api.md).

