# Postman: dữ liệu mẫu và thứ tự kiểm thử với Gemini

Cập nhật **22/10/2026** theo controller, DTO và service trong backend. Bắt đầu bằng luồng tạo CV ở mục 2–3,
sau đó mở rộng theo bảng thứ tự cuối tài liệu. [Danh mục API đầy đủ](postman-api-guide.md) dùng để tra các
field tùy chọn, quyền và những nhánh ngoài luồng chính.

| Cần làm | Đọc phần |
| --- | --- |
| Tạo CV thật bằng Gemini | Chuẩn bị và đăng nhập ở mục 1–2 → [luồng CV ở mục 3](#tao-cv-ai) |
| So CV với mục tiêu và hồ sơ nghề nghiệp cá nhân | [Lưu hồ sơ → tạo/review/confirm CV → chủ động đối chiếu lần đầu → sửa hồ sơ → theo dõi đánh giá lại trong nền](cv-profile-alignment.md) |
| Chạy các nhóm chức năng theo đúng phụ thuộc | [Thứ tự folder ở mục 5](#thu-tu-toan-bo) |
| Tra body/quyền cho API ngoài ví dụ chính | [Danh mục Postman](postman-api-guide.md) |

Các response/status bên dưới là **kết quả cần kiểm tra**, không phải kết quả đã chạy Postman. Lượt viết
tài liệu này không tạo tài khoản, gửi mail, gọi model, crawl hoặc tạo giao dịch. Nội dung CV là dữ liệu giả lập.
Không lấy điểm ATS hoặc nội dung mẫu trên frontend làm bằng chứng Gemini đã sinh CV.

## 1. Chuẩn bị một lượt test

### 1.1. Backend và model

Nếu backend đang chạy, gửi `GET http://localhost:8080/actuator/health`, **No Auth**; cần HTTP `200`,
`status: "UP"`. Health chỉ xác nhận readiness, chưa chứng minh inference thành công.

Backend hiện dùng:

```dotenv
AI_ENABLED=true
AI_PROVIDER=gemini
AI_CHAT_PROVIDER=openai
AI_EMBEDDING_PROVIDER=gemini
AI_CHAT_MODEL=gemini-2.5-flash-lite
AI_EMBEDDING_MODEL=gemini-embedding-001
AI_EMBEDDING_DIMENSIONS=1024
GEMINI_BASE_URL=https://generativelanguage.googleapis.com/v1beta/openai
```

Cấu hình này dùng cho backend host/IDE và container. Đặt key thật vào `GEMINI_API_KEY` trong backend `.env`, không gửi key trong body API hoặc Postman Environment. Khởi động lại backend sau khi đổi `.env`. Thay biến Environment của Postman không thay cấu hình backend.

Schema phải áp dụng V10; database có vector cũ cần index lại bằng model 1024 chiều hiện tại. Không cắt/pad vector cũ. Xem [hướng dẫn Gemini](gemini.md).

Để đăng ký bằng email mẫu bên dưới: dùng profile **`dev` hoặc `docker`**, đặt
`AUTH_EXPOSE_DEV_OTP=true` trước khi khởi động backend. Register sẽ trả `developmentCode`, không gửi mail.
Profile `prod` luôn tắt lộ mã dù biến này là `true`; khi dùng SMTP thật, thay email mẫu bằng hộp thư bạn nhận được.
Nếu đã có tài khoản Candidate active, bỏ qua register/verify và bắt đầu ở bước đăng nhập.

### 1.2. Environment và cách gửi request

Tạo Environment **EduNexus Gemini Local**, chọn nó làm Environment đang dùng:

| Biến | Giá trị khởi tạo |
| --- | --- |
| `appUrl` | `http://localhost:8080` |
| `baseUrl` | `http://localhost:8080/api/v1` |
| `candidateEmail` | `candidate.20261022.01@example.com` — chỉ dùng với OTP dev; đổi email khi tạo lượt mới |
| `candidatePassword` | `PostmanDemo2026!` — mật khẩu mẫu cho tài khoản test |
| `otpCode` | Để trống; lấy từ register/resend hoặc hộp thư |
| `candidateAccessToken`, `candidateRefreshToken`, `activeToken` | Để trống; lấy từ login |
| `candidateId` | Để trống; lấy từ `GET /me` |
| `resumeId`, `analysisId`, `resumeEmbeddingId` | Để trống; lấy lần lượt từ build, analyses và embedding |
| `aiProvider` | `gemini` |
| `aiChatModel` | `gemini-2.5-flash-lite` — đổi nếu runtime đã chủ động cấu hình model khác |
| `aiEmbeddingModel` | `gemini-embedding-001` |
| `jobId`, `matchingRunId`, `matchResultId`, `ragDocumentId` | Để trống; chỉ dùng ở nhánh mở rộng |
| `assistantConversationId`, `assistantMessageId` | Để trống |
| `adminEmail`, `adminPassword`, `adminAccessToken` | Chỉ điền khi test nhánh quản trị bằng tài khoản thực của bạn |

Tạo Collection **EduNexus — Gemini**, đặt **No Auth** ở cấp Collection. Mỗi request cần đăng nhập chọn
**Bearer Token**, nhập `{{candidateAccessToken}}` hoặc `{{adminAccessToken}}`, không nhập thêm chữ `Bearer`.
Request public chọn **No Auth**. JSON dùng **Body → raw → JSON**, `Content-Type: application/json`;
request ghi **none** không gửi body. Các URL API đã có `/api/v1` trong `baseUrl`, không thêm lần nữa.

Đặt request timeout lớn hơn `AI_REQUEST_TIMEOUT=PT2M` của backend khi kiểm tra AI và gửi tuần tự.
Khi timeout, kiểm tra danh sách/lịch sử trước khi gửi lại POST vì server có thể đã hoàn tất request.
Không cố định ngưỡng 1–2 giây để đánh giá pass/fail inference.

Script dưới đây đặt trong **Scripts → Post-response** của đúng request. `pm.environment.set` giữ giá trị
cho request sau; xem [Postman variables](https://learning.postman.com/docs/tests-and-scripts/write-scripts/postman-sandbox-reference/pm-variables/)
và [Post-response tests](https://learning.postman.com/docs/tests-and-scripts/write-scripts/test-scripts/).
Response của backend là object/array trực tiếp, không bọc chung trong `data`.

Tạo lượt test mới thì đổi email và xóa ID/token của lượt cũ trong Environment. Khi bước trước lỗi hoặc còn
`{{bienChuaCo}}` trong URL/body, dừng để xử lý; không dùng UUID ngẫu nhiên hoặc ID của tài khoản khác.

## 2. Tài khoản, token và dữ liệu nghề nghiệp

### 2.1. Register — `POST {{baseUrl}}/auth/register`

**No Auth**, JSON:

```json
{
  "email": "{{candidateEmail}}",
  "password": "{{candidatePassword}}",
  "fullName": "Nguyen Minh An",
  "preferredLanguage": "vi"
}
```

Kỳ vọng `200`, `expiresIn: 600`; đây chưa phải token đăng nhập. Email đã tồn tại trả `409`:
dùng login nếu đã active, hoặc tiếp tục verify/resend nếu còn chờ xác minh. Script:

```javascript
pm.test("Register thành công", () => pm.response.to.have.status(200));
if (pm.response.code === 200) {
  const r = pm.response.json();
  if (typeof r.developmentCode === "string") {
    pm.environment.set("otpCode", r.developmentCode);
  } else {
    pm.environment.unset("otpCode");
  }
}
```

Không có `developmentCode` thì đọc OTP email hoặc kiểm tra profile/dev flag; không tự dùng mã `123456`.
OTP là chuỗi sáu chữ số và có hạn 10 phút. Để gửi lại:
`POST {{baseUrl}}/auth/resend-code`, No Auth, body `{"email":"{{candidateEmail}}"}`;
đợi ít nhất 60 giây giữa các lần yêu cầu và lưu mã mới nếu response trả `developmentCode`.

### 2.2. Verify — `POST {{baseUrl}}/auth/verify-email`

**No Auth**, JSON:

```json
{
  "email": "{{candidateEmail}}",
  "code": "{{otpCode}}"
}
```

Kỳ vọng `200`, thông báo đã xác minh. API này chưa trả access token; tiếp tục login.
Mỗi mã có giới hạn 5 lần kiểm tra và chịu giới hạn tần suất; xem lỗi trả về trước khi gửi lại.

### 2.3. Login — `POST {{baseUrl}}/auth/login`

**No Auth**, JSON:

```json
{
  "email": "{{candidateEmail}}",
  "password": "{{candidatePassword}}"
}
```

Kỳ vọng `200`, có `accessToken`, `refreshToken`, `tokenType: "Bearer"`, `expiresIn: 900`. Script:

```javascript
pm.test("Login thành công", () => pm.response.to.have.status(200));
if (pm.response.code === 200) {
  const r = pm.response.json();
  pm.test("Có đủ token", () => {
    pm.expect(r.accessToken).to.be.a("string").and.not.empty;
    pm.expect(r.refreshToken).to.be.a("string").and.not.empty;
  });
  if (typeof r.accessToken === "string" && typeof r.refreshToken === "string") {
    pm.environment.set("candidateAccessToken", r.accessToken);
    pm.environment.set("candidateRefreshToken", r.refreshToken);
    pm.environment.set("activeToken", r.accessToken);
  }
}
```

Access token có hạn 15 phút. Khi hết hạn, gửi `POST {{baseUrl}}/auth/refresh`, No Auth:

```json
{
  "refreshToken": "{{candidateRefreshToken}}"
}
```

Dùng cùng script login để lưu **cả hai token mới**. Refresh token được xoay và chỉ dùng một lần;
không tiếp tục gửi giá trị cũ. Nếu refresh không thành công, login lại.

### 2.4. Kiểm tra tài khoản — `GET {{baseUrl}}/me`

**Bearer Candidate**, body none. Kỳ vọng `200`, `role: "CANDIDATE"`, `status: "ACTIVE"`:

```javascript
pm.test("Tài khoản Candidate active", () => {
  pm.response.to.have.status(200);
  const r = pm.response.json();
  pm.expect(r.role).to.eql("CANDIDATE");
  pm.expect(r.status).to.eql("ACTIVE");
});
if (pm.response.code === 200) {
  pm.environment.set("candidateId", pm.response.json().id);
}
```

### 2.5. Kiểm tra quota — `GET {{baseUrl}}/subscriptions/me`

**Bearer Candidate**, body none. Kỳ vọng `200`, có `tier`, `quotas`, `tokensUsedThisMonth`.
Seed chuẩn đặt Free `AI_TOTAL_TOKENS=20000` và `CV_BUILD=20000` token/tháng; DB đã được quản trị sửa
có thể khác, nên dùng giá trị response thực tế. Tài khoản mới không cần tạo order thanh toán để thử CV.

Mỗi request phải đủ **ngân sách giữ trước** gồm byte UTF-8 của system/input, phần dự phòng và giới hạn
output; `429` có thể xuất hiện dù số token model thực dùng dự kiến thấp. Không hiểu 20000 token là
20000 lần gọi. Không tăng `AI_MAX_OUTPUT_TOKENS` quá lớn hoặc lặp AI nhiều lần khi test luồng cơ bản.

### 2.6. Hồ sơ nghề nghiệp — `PUT {{baseUrl}}/me/career-profile`

**Bearer Candidate**, JSON dưới đây dành cho **tài khoản test mới**:

```json
{
  "targetJobTitle": "Java Backend Developer",
  "careerGoal": "Ứng tuyển Java Backend Fresher, củng cố Spring Boot và PostgreSQL.",
  "yearsExperience": 0,
  "expectedSalary": 7500000,
  "salaryCurrency": "VND",
  "preferredLocation": "Ho Chi Minh City",
  "workplacePreference": "HYBRID",
  "employmentType": "FULL_TIME",
  "profileSummary": "Sinh viên mới tốt nghiệp, có dự án học tập REST API Java; chưa có kinh nghiệm làm việc chính thức.",
  "skills": []
}
```

Kỳ vọng `200`, `target_job_title: "Java Backend Developer"`. PUT có thể xóa các field hồ sơ bị bỏ qua;
`skills: []` xóa skill cũ, vì vậy không dùng nguyên body này để ghi đè tài khoản có dữ liệu thật.
Nếu muốn thêm skill theo catalog, `GET {{baseUrl}}/skills` (No Auth), chọn ID có thật rồi dùng
`skills: [{"skillId":"{{skillId}}","proficiencyLevel":"BEGINNER","yearsExperience":0}]`.
Không cần profile skill để test builder: dữ liệu kỹ năng sẽ được gửi trong CV ở mục sau.

<a id="tao-cv-ai"></a>

## 3. Tạo CV bằng Gemini — chạy lần lượt

Trạng thái: **EXPECTED / CHƯA CHẠY**. Luồng được đối chiếu source ngày 22/10/2026; không gọi API, model hoặc sửa dữ liệu trong quá trình soạn. Các assertion dưới đây mô tả kết quả cần kiểm tra, không phải kết quả đã nghiệm thu. Build thành công và JSON đúng schema chưa chứng minh chất lượng nội dung CV.

### 3.1. Chuẩn bị và thứ tự chạy

Dùng tài khoản test đã đăng nhập; chọn **Authorization → Bearer Token → `{{candidateAccessToken}}`** cho folder này. `GET /resumes/templates` public; các bước còn lại cần token và CV thuộc chính tài khoản đó. Không dùng header guest cho luồng này.

Backend cần migration V10, `GEMINI_API_KEY` hợp lệ và quota ứng dụng đủ. Mặc định provider `gemini`, chat `gemini-2.5-flash-lite`, embedding `gemini-embedding-001`, vector 1024 chiều. Model chat active do Admin lưu có thể ghi đè model trong môi trường; `aiChatModel` phải phản ánh model hiệu lực. Endpoint Postman là backend của ứng dụng.

Tăng request timeout trong Postman đủ cho suy luận local lần đầu, ví dụ 600000 ms nếu môi trường cần. Giới hạn output 256 token của smoke chào hỏi không phù hợp để sinh một CV đầy đủ; đối chiếu `AI_MAX_OUTPUT_TOKENS` của backend khi gặp output bị cắt. Cấu hình mặc định là 2000 token, context chat 8192; model vẫn phải trả JSON hoàn chỉnh.

| Biến Environment | Giá trị/nguồn |
| --- | --- |
| `baseUrl` | `http://localhost:8080/api/v1` hoặc URL backend đang kiểm tra; không thêm `/api/v1` lần thứ hai |
| `candidateAccessToken` | Access token của tài khoản test; không đưa token vào file báo cáo |
| `resumeTemplateId` | UUID thật từ mục 3.2; optional nếu bỏ `template_id` khỏi body build |
| `resumeId` | `id` từ build thành công |
| `reviewedStructuredData` | JSON object đầy đủ từ GET CV, sau khi người kiểm tra đã sửa/đối chiếu dữ kiện |
| `resumeEmbeddingId` | `id` từ endpoint embedding; khác `resumeId` |
| `analysisId` | `id` của báo cáo chất lượng CV; khác ATS report theo job |
| `aiChatModel` | Mặc định `gemini-2.5-flash-lite`, điều chỉnh theo model hiệu lực |
| `aiEmbeddingModel` | Mặc định `gemini-embedding-001` |

Thứ tự: **templates → build → GET review → PATCH review → confirm READY → primary → embedding → analyses → GET analyses → download**. Nếu chưa review xong thì dừng trước confirm.

Build dùng quota `CV_BUILD`; phân tích dùng `CV_ANALYZE`; embedding dùng `EMBEDDING` và tổng `AI_TOTAL_TOKENS`. Backend giữ chỗ budget trước khi gọi model; budget dựa trên byte đầu vào và giới hạn output, không chỉ số token cuối cùng. Vì vậy quota còn nhỏ có thể bị từ chối trước khi gọi Gemini. Quota ứng dụng và quota/số dư Gemini là hai giới hạn riêng. Đừng chạy Runner lặp build/analysis không cần thiết.

### 3.2. GET template thật

**GET `{{baseUrl}}/resumes/templates`**, No Auth, Body none. Expected `200`, response là mảng trực tiếp, mỗi template có `id`, `name`, `career_path`, `language`, `description`, `html_content`, `is_active`, `is_trial_enabled`, thời gian tạo/cập nhật. Từ V19 mọi mẫu đều có HTML; không còn `layout_config`.

Chọn template phù hợp sau khi đọc `name`/`career_path`. Post-response script dưới đây lấy phần tử đầu làm giá trị ban đầu; có thể đổi lựa chọn trong Environment:

```javascript
pm.test("Templates trả mảng", () => {
  pm.response.to.have.status(200);
  pm.expect(pm.response.json()).to.be.an("array");
});
const templates = pm.response.json();
if (templates.length > 0) {
  pm.environment.set("resumeTemplateId", templates[0].id);
} else {
  pm.environment.unset("resumeTemplateId");
}
```

Nếu mảng rỗng, **xóa field `template_id` trong JSON mục 3.3**. Không gửi `""`, UUID minh họa hay chuỗi `{{resumeTemplateId}}` chưa được resolve. Backend kiểm tra mẫu active, lưu ID và trả `template.html_content` trong chi tiết CV. Candidate đăng nhập hệ thống có thể xem/chọn/preview mẫu trên `/candidate/resumes/templates`, chuyển sang tạo CV thủ công/AI, rồi đổi mẫu ở chi tiết CV. Endpoint download vẫn không render layout PDF. Từ V19 chỉ lưu HTML/CSS cho bố cục; Admin có thể nhập [ba mẫu HTML và JSON Postman](html-resume-templates.md). Xem [luồng Candidate và ca kiểm tra đổi/bỏ mẫu](candidate-resume-templates.md).

### 3.3. POST build với dữ kiện đầy đủ

**POST `{{baseUrl}}/resumes/build`**, Body → raw → JSON, `Content-Type: application/json`.

Đây là **hồ sơ giả lập có chủ đích để test contract**. Dùng trên tài khoản test; khi thử CV thật, thay toàn bộ dữ kiện bằng thông tin ứng viên đã xác nhận. Không biến dự án học phần thành kinh nghiệm công ty, thêm chứng chỉ, URL hoặc số liệu thành tích không có nguồn. Backend cũng gửi career profile hiện có của tài khoản vào model, nên phải kiểm tra profile đó không mâu thuẫn với bộ dữ kiện test.

Body hoàn chỉnh:

```json
{
  "title": "CV Backend Java - Nguyen Minh An - Test",
  "language": "vi",
  "template_id": "{{resumeTemplateId}}",
  "raw_content": "Ho so gia lap de kiem thu, khong phai thong tin nguoi that. Ho ten: Nguyen Minh An. Email: an.cv@example.test. Dia diem: Ho Chi Minh. Da hoc Cu nhan Cong nghe thong tin tai Truong Dai hoc Demo tu 2022-09-01 den 2026-06-30. Chua co kinh nghiem lam viec toan thoi gian, chua co chung chi. Du an hoc phan Quan ly thu vien, tu 2026-01-01 den 2026-05-31; vai tro Backend trong nhom hoc phan. Xay dung REST API CRUD sach va doc gia bang Java va Spring Boot, luu du lieu PostgreSQL, thuc hanh SQL va Git. Du an chay local; chua trien khai thuong mai, chua co so lieu hieu nang, khong co URL cong khai. Muc do tu khai: Java beginner 0.5 nam thuc hanh; Spring Boot beginner 0.5 nam; PostgreSQL beginner 0.5 nam; Git beginner 0.5 nam. Dinh huong ung tuyen Fresher Backend Java.",
  "structured_data": {
    "full_name": "Nguyen Minh An",
    "email": "an.cv@example.test",
    "location": "Ho Chi Minh",
    "summary": "Ung vien fresher Backend Java, co du an hoc phan su dung Java, Spring Boot va PostgreSQL; chua co kinh nghiem lam viec toan thoi gian.",
    "experience": [],
    "education": [
      {
        "school_name": "Truong Dai hoc Demo",
        "degree": "Cu nhan",
        "major": "Cong nghe thong tin",
        "start_date": "2022-09-01",
        "end_date": "2026-06-30",
        "description": "Ho so gia lap; thuc hanh co so du lieu va phat trien ung dung backend."
      }
    ],
    "projects": [
      {
        "project_name": "Quan ly thu vien",
        "role": "Backend trong nhom hoc phan",
        "description": "Xay dung REST API CRUD sach va doc gia, luu du lieu PostgreSQL; du an chay local, chua trien khai thuong mai va chua co so lieu hieu nang.",
        "technologies": ["Java", "Spring Boot", "PostgreSQL", "SQL", "Git"],
        "start_date": "2026-01-01",
        "end_date": "2026-05-31"
      }
    ],
    "certifications": [],
    "skills": [
      { "name": "Java", "proficiency_level": "beginner", "years_experience": 0.5 },
      { "name": "Spring Boot", "proficiency_level": "beginner", "years_experience": 0.5 },
      { "name": "PostgreSQL", "proficiency_level": "beginner", "years_experience": 0.5 },
      { "name": "Git", "proficiency_level": "beginner", "years_experience": 0.5 }
    ]
  }
}
```

`title` bắt buộc, không trắng, tối đa 255 ký tự. `language` là `vi`/`en`, mặc định `vi`; `template_id` optional. `raw_content` tối đa 200000 ký tự. Với build, `structured_data` là dữ kiện cho model, **response `structured_data` là nội dung model đã sinh và normalize**, không bảo đảm giữ nguyên từng câu. Không có `prompt` bắt buộc.

Tên section đầu vào là `experience`, `education`, `projects`, `certifications`, `skills`. Ngày phải `YYYY-MM-DD`; ngày kết thúc không trước ngày bắt đầu. `is_current=true` không kèm `end_date`. Mỗi section tối đa 100 phần tử; `technologies` là mảng string, tối đa 100. `skills`/`certifications` cần `name`; `years_experience` là number 0..99. Field `skill_id` chỉ dùng nếu có UUID thật, không cần trong ví dụ này.

DTO hiện dùng JSON mở rộng, **không reject mọi field lạ**. Tuy nhiên `careerTrack`, `tone`, `fullName`, `experiences` không phải tên field được workflow lưu/chuẩn hóa theo contract này; không dùng chúng để thay cho dữ kiện/schema bên trên. Các top-level field được hỗ trợ không được đặt sai kiểu: `structured_data` phải là object, `is_primary` phải boolean nếu có.

Expected `200`, các key chính: `id`, `user_id`, `template_id`, `title`, `source="AI_BUILDER"`, `status="REVIEW_REQUIRED"`, `language`, `raw_content`, `structured_data`, `content_hash`, `is_primary`, `experiences`, `educations`, `projects`, `certifications`, `skills`, `parse_results`.

`structured_data.experience`/`education` là document AI. Các mảng top-level **`experiences`/`educations`** là bảng section đã xác nhận và có thể còn rỗng trước confirm. AI build không tạo parse result như upload; `parse_results=[]` là hợp lệ. Response build không expose `_model`/`_usage` của lần gọi AI; xem usage history để đối chiếu provider/model.

Post-response:

```javascript
pm.test("Build tạo CV cần review", () => {
  pm.response.to.have.status(200);
  const r = pm.response.json();
  pm.expect(r.id).to.match(/^[0-9a-f-]{36}$/i);
  pm.expect(r.source).to.eql("AI_BUILDER");
  pm.expect(r.status).to.eql("REVIEW_REQUIRED");
  pm.expect(r.language).to.eql("vi");
  pm.expect(r.structured_data).to.be.an("object");
  ["experience", "education", "projects", "certifications", "skills"].forEach(k => {
    pm.expect(r.structured_data[k], k).to.be.an("array");
  });
  ["experiences", "educations", "projects", "certifications", "skills", "parse_results"].forEach(k => {
    pm.expect(r[k], k).to.be.an("array");
  });
  pm.environment.set("resumeId", r.id);
});
```

Chỉ tiếp tục khi build trả `200` và `resumeId` mới được lưu. Gửi lại build tạo CV khác, không tự ghi đè CV trước.

### 3.4. GET để review nội dung AI

**GET `{{baseUrl}}/resumes/{{resumeId}}`**, Body none. Expected `200`, cùng `id`, `REVIEW_REQUIRED`.

Đọc toàn bộ `structured_data`, đối chiếu `raw_content` và career profile: tên, ngày, tổ chức, vai trò, kỹ năng, mức độ, URL, số liệu, chứng chỉ. Với bộ mẫu này, `experience` và `certifications` phải để rỗng nếu không có dữ kiện bổ sung đã xác minh. Không tự confirm chỉ vì JSON hợp lệ.

Có thể dùng post-response để lấy bản đầy đủ làm điểm bắt đầu chỉnh sửa:

```javascript
pm.test("Đọc đúng CV", () => {
  pm.response.to.have.status(200);
  pm.expect(pm.response.json().id).to.eql(pm.environment.get("resumeId"));
});
const cv = pm.response.json();
pm.environment.set("reviewedStructuredData", JSON.stringify(cv.structured_data));
```

**Người kiểm tra sửa giá trị `reviewedStructuredData` trong Environment trước mục 3.5.** Phải giữ nguyên object đầy đủ và các section muốn giữ. Nếu AI tạo kinh nghiệm/chứng chỉ/URL ngoài dữ kiện, bỏ hoặc sửa tại đây; đừng coi output AI là bằng chứng mới.

### 3.5. PATCH bản đã review

**PATCH `{{baseUrl}}/resumes/{{resumeId}}`**, raw JSON:

```json
{
  "title": "CV Backend Java - Nguyen Minh An - Reviewed",
  "language": "vi",
  "structured_data": {{reviewedStructuredData}}
}
```

`{{reviewedStructuredData}}` **không bọc trong dấu nháy**: biến phải chứa JSON object hợp lệ, không phải string chứa JSON. Có thể dán trực tiếp object đầy đủ thay biến. Nếu chỉ đổi tiêu đề, dùng `{ "title": "CV Backend Java - Nguyen Minh An - Reviewed" }` để giữ toàn bộ cấu trúc.

PATCH `structured_data` là **thay toàn bộ document**, không merge từng section. Chỉ gửi `summary`/`skills` sẽ làm `education`, `projects`... không gửi thành mảng rỗng. Không gửi `null` để giả lập bỏ qua field. Nếu sửa dữ kiện nguồn thực tế, cập nhật cả `raw_content` tương ứng để bản review nhất quán.

Có thể đổi mẫu bằng PATCH riêng `{ "template_id": "{{resumeTemplateId}}" }`, hoặc bỏ mẫu bằng `{ "template_id": null }`; bỏ field này thì giữ lựa chọn cũ. PATCH chỉ đổi mẫu không sửa nội dung/hash, trạng thái READY, cờ CV chính hay vô hiệu embedding/roadmap. Mẫu được chọn phải active; không gửi lại ID mẫu đã tắt. Object `template` trong chi tiết CV cung cấp config kể cả khi mẫu cũ đã tắt. [Hướng dẫn Candidate](candidate-resume-templates.md) có dữ liệu và thứ tự kiểm tra.

Expected `200`, `id` không đổi; nội dung/hash thay đổi làm `status="REVIEW_REQUIRED"`, `is_primary=false`, vô hiệu embedding hiện tại. PATCH không đổi nội dung/hash có thể giữ nguyên trạng thái cũ; không khẳng định mọi PATCH đều reset.

```javascript
pm.test("Đã lưu bản review trước confirm", () => {
  pm.response.to.have.status(200);
  const r = pm.response.json();
  pm.expect(r.id).to.eql(pm.environment.get("resumeId"));
  pm.expect(r.status).to.eql("REVIEW_REQUIRED");
});
```

### 3.6. Confirm thành READY

**POST `{{baseUrl}}/resumes/{{resumeId}}/confirm`**, **Body none** sau PATCH. Không gửi lại một `structured_data` rút gọn vì confirm cũng normalize/thay document khi field này có mặt.

Expected `200`, `status="READY"`; section top-level đã được lưu từ document review, skills có `skill_id`/`name`. Có thể gửi `{ "is_primary": true }` để xác nhận và đặt chính cùng lúc; để kiểm rõ endpoint primary, runbook này tách mục 3.7.

```javascript
pm.test("CV đã được xác nhận", () => {
  pm.response.to.have.status(200);
  const r = pm.response.json();
  pm.expect(r.id).to.eql(pm.environment.get("resumeId"));
  pm.expect(r.status).to.eql("READY");
  ["experiences", "educations", "projects", "certifications", "skills"].forEach(k => {
    pm.expect(r[k], k).to.be.an("array");
  });
});
```

Confirm chỉ xử lý `structured_data`, `raw_content`, `is_primary`; đổi title/language bằng PATCH. Nội dung chỉ có `full_name`/email nhưng không raw text, summary hay section có phần tử chưa đủ để confirm. `is_primary:false` không gỡ primary đang có; chọn CV khác qua endpoint primary khi cần.

### 3.7. Chọn CV chính

**POST `{{baseUrl}}/resumes/{{resumeId}}/primary`**, Body none. Expected `200`, `status="READY"`, `is_primary=true`; CV chính trước đó của tài khoản được bỏ cờ.

```javascript
pm.test("CV chính là CV READY", () => {
  pm.response.to.have.status(200);
  const r = pm.response.json();
  pm.expect(r.id).to.eql(pm.environment.get("resumeId"));
  pm.expect(r.status).to.eql("READY");
  pm.expect(r.is_primary).to.eql(true);
});
```

### 3.8. Sinh/reuse embedding

**POST `{{baseUrl}}/resumes/{{resumeId}}/embedding`**, Body none. Expected `200`; chỉ gọi được sau READY và embedding provider đang bật.

Response là **metadata**, gồm `id`, `resume_id`, `provider`, `model`, `content_hash`, `is_current`, `created_at`. Không có field `dimensions`, `vector`, `embedding` hoặc cờ `cached` trong response hiện tại. Việc kiểm vector 1024 chiều diễn ra tại backend/schema, không thể suy ra bằng đếm field của response này.

```javascript
pm.test("Embedding metadata đúng CV/model", () => {
  pm.response.to.have.status(200);
  const r = pm.response.json();
  pm.expect(r.resume_id).to.eql(pm.environment.get("resumeId"));
  pm.expect(r.provider).to.eql("gemini");
  pm.expect(r.model).to.eql(pm.environment.get("aiEmbeddingModel"));
  pm.expect(r.is_current).to.eql(true);
  pm.expect(r.content_hash).to.match(/^[0-9a-f]{64}$/i);
  pm.environment.set("resumeEmbeddingId", r.id);
});
```

Nếu kiểm reuse, gọi lại ngay với nội dung/model không đổi và **so sánh `id` với giá trị đã lưu trước khi ghi đè biến**. Expected cùng `id`, không có suy luận mới. Sau PATCH thay đổi nội dung, phải review/confirm lại rồi tạo embedding mới. Legacy 1536 chiều được giữ riêng bởi V10, không phải vector dùng cho model hiện tại.

### 3.9. Phân tích chất lượng bằng AI

**POST `{{baseUrl}}/resumes/{{resumeId}}/analyses`**, Body none. Expected `200` và **`analysis_type="AI_QUALITY"`** khi đang nghiệm thu Gemini. Không cần embedding để chạy quality analysis; thứ tự mục 3.8 chỉ để kiểm cả hai khả năng riêng biệt.

Key chính: `id`, `resume_id`, `analysis_type`, `overall_score`, `ats_score`, `summary`, `strengths_json`, `issues_json`, `model_name`, `created_at`, `suggestions`, `writing_quality`. Mỗi suggestion có thể gồm `id`, `analysis_id`, `category`, `severity`, `original_content`, `suggested_content`, `explanation`, `accepted`, `created_at`; không bắt buộc mọi giá trị đều khác null.

`writing_quality` gồm điểm cách viết, điểm nội dung, trọng số 80/20 và năm tiêu chí có nhận xét/bằng chứng:
bullet, dấu câu, ngôn từ, độ cô đọng, đóng góp rõ ràng. Không coi dấu câu của JSON là dấu câu của CV.
Tiêu chí chỉ có điểm khi `status="ASSESSED"`; không áp dụng hoặc thiếu bằng chứng có điểm null.
Nếu không tiêu chí nào đánh giá được, điểm tổng dùng 100% nội dung thay vì tạo điểm cách viết giả.
Xem [rubric và hai CV cùng dữ kiện để so sánh cách viết](cv-writing-quality.md) nếu cần kiểm tra riêng
chức năng này; bài kiểm tra đó tạo CV thủ công rồi confirm → analyses, không cần embedding.

```javascript
pm.test("Đã lưu phân tích AI đúng model", () => {
  pm.response.to.have.status(200);
  const r = pm.response.json();
  pm.expect(r.resume_id).to.eql(pm.environment.get("resumeId"));
  pm.expect(r.analysis_type).to.eql("AI_QUALITY");
  pm.expect(r.model_name).to.eql(pm.environment.get("aiChatModel"));
  pm.expect(r.overall_score).to.be.a("number").and.within(0, 100);
  pm.expect(r.ats_score).to.be.a("number").and.within(0, 100);
  pm.expect(r.summary).to.be.a("string").and.not.empty;
  ["strengths_json", "issues_json", "suggestions"].forEach(k => {
    pm.expect(r[k], k).to.be.an("array");
  });
  pm.environment.set("analysisId", r.id);
});
```

Không assert một điểm cố định hay khẳng định CV được chấm đúng chỉ vì score nằm trong 0..100. Đây là CV quality tổng quát chưa gắn JD, không phải độ phù hợp CV–job hay accuracy của matching.

AI tắt có thể vẫn trả `200`, `analysis_type="RULE_BASED"`, `model_name="rules-v1"`, `ats_score=null`; đây là baseline đủ mục CV, **không được ghi nhận là AI/Gemini thành công**. `writing_quality` chưa khả dụng trong baseline hoặc báo cáo cũ chưa có rubric; không gán điểm 0 thay cho thiếu đánh giá. Gọi analyses lần nữa tạo báo cáo mới; endpoint này không cam kết cache báo cáo.

### 3.10. Đọc lại báo cáo và usage

**GET `{{baseUrl}}/resumes/{{resumeId}}/analyses`**: expected `200`, mảng các bản ghi báo cáo, có ID vừa tạo. Danh sách không tự gắn mảng `suggestions` như endpoint chi tiết.

**GET `{{baseUrl}}/resumes/{{resumeId}}/analyses/{{analysisId}}`**: expected `200`, `id`/`resume_id` đúng, có `suggestions` dạng mảng. Không dùng ID báo cáo ATS của job ở vị trí `analysisId` này.

**GET `{{baseUrl}}/ai/usage?limit=20&offset=0`** bằng cùng token: đối chiếu các lần gọi `CV_BUILD`, `EMBEDDING`, `CV_ANALYZE`, provider `gemini`, model tương ứng, tokens/latency/success. Cache embedding hợp lệ không phát sinh lời gọi model mới. Không lấy số lần Send làm số lần model thực sự chạy; build/analysis thất bại sau provider response vẫn có thể đã tiêu token.

Nếu cần đánh giá CV có thể hiện đúng mục tiêu/hồ sơ cá nhân, sau confirm READY gửi riêng
`POST {{baseUrl}}/resumes/{{resumeId}}/profile-alignments`, **Body none**. Lưu `alignmentId` và đọc lại
GET cùng path hoặc `/{alignmentId}`. Luồng này không cần embedding hoặc chạy `/analyses` trước;
xem [JSON hồ sơ/CV mẫu, tiêu chí, quota và thứ tự kiểm tra stale](cv-profile-alignment.md).

Sau lần đầu, PUT hồ sơ với mục tiêu/kỹ năng thực sự thay đổi tự xếp hàng các CV `READY` đã từng đối chiếu.
GET `{{baseUrl}}/resumes/{{resumeId}}/profile-alignments/refresh-status` để theo dõi `QUEUED`/`RUNNING`;
`SUCCEEDED` thì dùng `alignment_id` đọc báo cáo mới. PUT hồ sơ vẫn thành công dù AI/quota lỗi; `FAILED`
cần khắc phục nguyên nhân rồi POST lại thủ công, không tự retry. POST khi còn `QUEUED`/`RUNNING` trả `409`.
GET lịch sử/trạng thái không gọi AI. Giữ báo cáo cũ để kiểm tra snapshot không đổi và đọc lại báo cáo ATS/chất lượng:
cập nhật hồ sơ **không chạy lại ATS**, không thay điểm đã lưu. Gửi lại cùng dữ kiện hoặc chỉ đổi thông tin liên hệ
không tạo lượt AI. Xem bước 6 trong tài liệu trên để kiểm tra hàng đợi V18, gộp thay đổi và quota.

### 3.11. Tải nội dung

**GET `{{baseUrl}}/resumes/{{resumeId}}/download?text=true`**. Expected `200`, `Content-Type: text/plain; charset=UTF-8`, `Content-Disposition: attachment`, `X-Content-Type-Options: nosniff`.

Chọn chức năng lưu response ra file trong Postman. Body là bytes/text, không gọi `pm.response.json()`. Canonical text bắt đầu bằng title; khi có document review, nội dung cấu trúc được ưu tiên rồi serialize thành JSON text. Đây không phải bản CV đã dàn trang.

Với CV AI build không có file gốc, `text=false` cũng trả TXT. Với CV upload có file gốc, `text=false` trả bytes file upload; `text=true` vẫn trả canonical text. Backend hiện không render CV AI thành PDF/DOCX.

### 3.12. Ca lỗi và tiêu chí ghi nhận

Chạy ca lỗi trên bản test riêng, tránh phá trạng thái CV đang dùng cho happy path. Các expected dưới đây chưa được thực thi trong tài liệu này.

| Trường hợp | Expected cần kiểm |
| --- | --- |
| Thiếu/sai access token ở route CV riêng | `401`; không suy luận model |
| UUID sai cú pháp; title thiếu/trắng; language khác vi/en; raw_content/object sai kiểu | `400` |
| `structured_data` là string hoặc array thay vì object | `400`; biến JSON phải để không có dấu nháy |
| Ngày không phải YYYY-MM-DD; end trước start; is_current là string; current experience có end_date; years_experience âm | `400` khi workflow normalize nội dung đó |
| CV không tồn tại hoặc thuộc tài khoản khác | `404` |
| `template_id` không tồn tại/không active | `404`; UUID sai định dạng là `400` |
| Gọi primary/embedding/analyses khi chưa READY | `409`; hoàn thành review/confirm trước |
| CV ARCHIVED rồi PATCH/confirm | `409` |
| Confirm không có raw text, summary hoặc section nào có nội dung | `400` |
| Quota feature bị cấu hình bằng 0 | `403`; khác trường hợp hết token |
| Tổng/feature quota không đủ để giữ chỗ | `429`; số token tiêu thực tế nhỏ chưa đảm bảo budget được chấp nhận |
| Free tier hoặc tổng quota chưa cấu hình | `503`; kiểm catalog/quota, không tự coi đây là lỗi Gemini |
| Build khi AI tắt; embedding khi provider tắt | `503` |
| Gemini không liên lạc được, key/model không có quyền hoặc sai tên, JSON output sai hoặc thiếu schema mảng/score | Thường `502` tại AiService; không có paid-provider fallback |
| JSON model qua kiểm tra ban đầu nhưng section chứa ngày/kiểu dữ liệu sai khi ResumeData.normalize | Có thể `400` ở bước normalize sau model; ghi lại status/detail thực tế, không hứa mọi output AI lỗi đều `502` |
| Embedding khác 1024 chiều, giá trị không hữu hạn, input vượt context với truncate=false | `502`; không cắt/nối vector để ép hợp lệ |
| CV đổi trong lúc embedding | `409`; lấy bản mới, review/confirm, thử lại |
| Download file upload bị mất | `404`; CV AI không có file gốc vẫn tải canonical TXT |

Lỗi nghiệp vụ từ advice thường là `application/problem+json` có `status`, `detail` và có thể `errors`; filter xác thực có thể trả JSON `status`/`message`. Lưu đúng HTTP status và thông điệp thực tế. Không cố tình làm model trả JSON sai bằng cách thêm “ignore instructions” vào dữ liệu rồi gọi đó là kiểm thử ổn định; các ca lỗi provider cần fixture/môi trường test riêng nếu muốn lặp lại có kiểm soát.

Kết quả cần ghi sau khi chạy: thời gian, model hiệu lực, CV ID/báo cáo ID, từng bước PASS/FAIL/NOT_RUN, status thực, các lỗi nội dung phát hiện khi review. Không lưu access token, mật khẩu, `.env` hoặc dữ liệu CV thật vào artifact công khai.

### 3.13. Nguồn hợp đồng trong repository

- `dto/request/candidate/resume/ResumeWriteRequest.java`: request JSON mở rộng và kiểm kiểu field.
- `util/candidate/resume/ResumeInput.java`: title/language/UUID/document.
- `util/shared/resume/ResumeData.java`: schema section, normalization, ngày và kiểu dữ liệu.
- `util/shared/ai/AiResponseData.java`: schema/validation output CV_BUILD và CV_ANALYZE.
- `controller/candidate/resume/ResumeController.java`: routes, request body optional, download headers.
- `service/candidate/resume/ResumeService.java`: build/review/confirm/primary/embedding/analysis/download.
- `repository/candidate/resume/ResumeRepository.java`: response metadata và persistence sections.
- `service/shared/ai/AiService.java`: OpenAI SDK options để gọi Gemini, quota reservation, output validation, 1024 chiều.
- `service/shared/billing/EntitlementService.java`: quota tổng/feature và lỗi 403/429/503.
- `util/candidate/resume/ResumeContent.java`: canonical text và RULE_BASED baseline.


## 4. Test mở rộng sau khi CV đã READY

### 4.1. Chuẩn bị Admin và job để test semantic matching

Không cần Admin để tạo CV riêng. Dùng Admin khi quản lý template hoặc chuẩn bị job/RAG/course. Đăng nhập Admin bằng
`POST /auth/login`, body `{"email":"{{adminEmail}}","password":"{{adminPassword}}"}`, No Auth;
lưu `accessToken` vào **`adminAccessToken`**, không ghi đè token Candidate. Không có mật khẩu Admin
mặc định hoặc API đăng ký tự chọn role. Nếu chưa có Admin, dùng quy trình
[bootstrap Admin](postman-api-guide.md#section-2) với tài khoản riêng đã xác minh; giữ Candidate ở role Candidate.

**Bearer Admin**, `POST {{baseUrl}}/admin/jobs`, JSON fixture nội bộ:

```json
{
  "sourceSite": "ITVIEC",
  "sourceUrl": "https://itviec.com/it-jobs/postman-fixture-java-backend-20261022-01",
  "jobTitle": "Java Backend Developer",
  "companyName": "Postman Fixture Company",
  "location": "Ho Chi Minh City",
  "salaryText": "10000000-15000000 VND",
  "roleCategory": "BACKEND",
  "content": "Dữ liệu kiểm thử nội bộ, không phải tin tuyển dụng thật. Vị trí Java Backend Fresher phát triển REST API bằng Spring Boot và PostgreSQL, sử dụng Git, viết JUnit test và làm việc theo nhóm.",
  "requirements": "Kiến thức Java, Spring Boot, REST API và SQL. Chấp nhận dự án học tập; không yêu cầu kinh nghiệm làm việc chính thức.",
  "responsibilities": "Xây dựng API, viết unit test và kiểm tra truy vấn PostgreSQL.",
  "benefits": "Dữ liệu kiểm thử.",
  "employmentType": "FULL_TIME",
  "skills": [
    {"name": "Java", "required": true},
    {"name": "Spring Boot", "required": true},
    {"name": "PostgreSQL", "required": true}
  ]
}
```

URL trên là định danh **fixture giả lập**, không phải link tuyển dụng tồn tại. Endpoint import nhận
nội dung đã gửi, không crawl URL này. Chỉ dùng fixture ở DB test; nếu test dữ liệu nguồn thật thì thay
bằng URL/nội dung thật phù hợp miền ITVIEC. Không gửi URL fixture vào endpoint crawl.

Kỳ vọng `200`, job `ACTIVE`, có `id`; lưu thành `jobId`. Backend có thể khử trùng lặp nên không giả định
mỗi POST tạo một job mới. Sau đó:

| Thứ tự | Request — Bearer Admin, body none | Điều kiện đi tiếp |
| --- | --- | --- |
| 1 | `POST {{baseUrl}}/admin/jobs/{{jobId}}/index` | Có tác vụ index; `id` trả về là ID tác vụ, không ghi đè `jobId` |
| 2 | `GET {{baseUrl}}/admin/jobs/{{jobId}}/index` | Có tác vụ đúng `gemini`/`gemini-embedding-001`, `status: "COMPLETED"` |
| 3 | `GET {{baseUrl}}/jobs/{{jobId}}` | Job vẫn `ACTIVE`; route GET này có thể chọn No Auth |

Nếu index `FAILED`, xử lý lỗi model/vector trước khi matching. Không chỉ dựa vào HTTP 200 của POST index.

Nếu Candidate có lương kỳ vọng, matching lọc job theo biên độ Admin cấu hình. Candidate có thể đọc bằng
`GET {{baseUrl}}/matching/salary-policy`. Admin dùng GET/PUT `/admin/matching/salary-policy` với
`{ "tolerancePercent": 20 }` để cho phép lương từ 80% kỳ vọng trở lên; mặc định 10%.
Xem [thứ tự test đổi biên độ, cache và snapshot lịch sử](admin-salary-policy.md).

**Bearer Candidate**, `POST {{baseUrl}}/matching`:

```json
{
  "basis": "SELECTED_CV",
  "resumeIds": ["{{resumeId}}"],
  "limit": 5
}
```

Kỳ vọng `200`, run và `results`; `profile_snapshot.mode` là `SEMANTIC_LLM` khi AI bật. Lưu `id` ngoài cùng vào
`matchingRunId`; `results[].id` là `matchResultId`, khác `results[].market_job_id`. Đọc lại bằng
`GET {{baseUrl}}/matching/{{matchingRunId}}`. Không yêu cầu một điểm số cố định hoặc kết quả xếp hạng
giống nhau tuyệt đối giữa các lần chạy model. Không có job vector hiện hành có thể trả `409`.

### 4.2. Assistant trước, RAG sau

Đầu tiên test chat theo CV, chưa retrieval. **Bearer Candidate**,
`POST {{baseUrl}}/assistant/conversations`:

```json
{
  "title": "Postman — tư vấn CV Java",
  "context_type": "resume",
  "related_resume_id": "{{resumeId}}"
}
```

Lưu `id` thành `assistantConversationId`. Gửi
`POST {{baseUrl}}/assistant/conversations/{{assistantConversationId}}/messages`:

```json
{
  "message": "Dựa trên CV của tôi, đề xuất ba điểm cần làm rõ khi ứng tuyển Java Backend Fresher. Không bịa kinh nghiệm hoặc thành tích.",
  "retrieve": false
}
```

Kỳ vọng `200`, `sender_type: "ASSISTANT"`, `message_text` không rỗng và `model_used` đúng model chat.
Response là **message**, không phải conversation hoặc field `answer`. Lưu `id` vào `assistantMessageId`.
`GET /assistant/conversations/{{assistantConversationId}}` cần đọc lại đủ USER/ASSISTANT của lượt thành công.

Để thử retrieval, chuẩn bị riêng **Bearer Admin**,
`POST {{baseUrl}}/admin/rag/documents`:

```json
{
  "title": "Postman — checklist Java Backend Fresher",
  "sourceType": "ADMIN",
  "content": "Tài liệu kiểm thử đã duyệt: Java Backend Fresher cần nắm Java Collections và exception handling; xây REST API bằng Spring Boot; kiểm tra dữ liệu đầu vào; viết JUnit test cho service; dùng PostgreSQL với khóa ngoại và index phù hợp. Khi trình bày dự án học tập, mô tả vai trò, chức năng và cách kiểm thử. Không tự thêm số người dùng, tỷ lệ cải thiện hoặc kinh nghiệm công ty nếu chưa có bằng chứng.",
  "metadata": {"topic": "java-backend-fresher", "language": "vi", "test_fixture": true}
}
```

Lưu `id` vào `ragDocumentId`; `POST /admin/rag/documents/{{ragDocumentId}}/index` (none), rồi
`GET /admin/rag/documents/{{ragDocumentId}}` đến khi `embedding_status: "COMPLETED"`.
Sau đó dùng **Bearer Candidate**, gửi vào conversation cũ:

```json
{
  "message": "Theo checklist Java Backend Fresher đã nhập, tôi cần học Java Collections, Spring Boot, PostgreSQL và viết JUnit test như thế nào?",
  "retrieve": true
}
```

Đọc `sources`, `retrieval_used`, `model_used`, `message_text`. `retrieve: true` là yêu cầu truy xuất,
không đảm bảo luôn có nguồn: còn bộ lọc quyền/provider/model, trạng thái và similarity tối thiểu 0,55.
Nguồn text này không có course URL; không assert mọi source có `source_url` khác null. Archive tài liệu
ở cuối lượt test rồi kiểm tra lượt chat mới không còn truy xuất nó.

### 4.3. Chọn JD, đánh giá và học từng skill cần bổ sung

Chọn **cùng một `jobId` thật** để liên kết assessment và hướng dẫn học; trùng tên nghề chưa đủ.
[Hướng dẫn chi tiết và tình huống kiểm thử](jd-assessment-missing-skills.md) giải thích điểm từng skill,
`MISSING_EVIDENCE`, `NEEDS_PRACTICE` và kết quả chỉ học bù thay cho lộ trình nghề đầy đủ.

| Thứ tự | Bearer Candidate | Body / dữ liệu cần lưu |
| --- | --- | --- |
| 1 | `GET {{baseUrl}}/jobs?q=Java` rồi `GET {{baseUrl}}/jobs/{{jobId}}` | Public/No Auth; chọn JD active/chưa hết hạn có nội dung và skill đã trích xuất; lưu `id` vào `jobId` |
| 2 | `GET {{baseUrl}}/assessments/preparation?jobId={{jobId}}` | none; đọc skill/tài liệu catalog trước khi làm bài; chưa gọi AI |
| 3 | `POST {{baseUrl}}/assessments` | `{"jobId":"{{jobId}}"}`; lưu `id` vào `assessmentId`; bài mới đúng 10 câu: 5 nền tảng + 5 kỹ thuật JD |
| 4 | `GET {{baseUrl}}/assessments/{{assessmentId}}` | Lấy toàn bộ `questions` và UUID câu thật; đáp án đúng được ẩn khi chưa submit |
| 5 | `PUT {{baseUrl}}/assessments/{{assessmentId}}/answers` | Mỗi câu gửi `{"questionId":"<id thật>","selectedOption":"A"}`; thay đáp án theo câu hỏi |
| 6 | `POST {{baseUrl}}/assessments/{{assessmentId}}/submit` | none; trả lời đủ mọi câu trước; cần `completed` và xem `skill_scores` |
| 7 | `POST {{baseUrl}}/roadmaps` | `{"jobId":"{{jobId}}","resumeId":"{{resumeId}}","assessmentId":"{{assessmentId}}"}`; bỏ `resumeId` nếu không dùng CV; lưu `id` và `gap_analysis_id` |
| 8 | `GET {{baseUrl}}/roadmaps/{{roadmapId}}` | Kiểm tra `learning_scope: MISSING_SKILLS_ONLY`, `missing_skills` và hướng dẫn/tài nguyên riêng từng skill |

Body trên là cách nhập trong từng request, không gửi cả bảng. Request trả lời là theo từng câu,
không gửi `questionId` giả hoặc cùng một ID cho mọi câu. Thêm các biến `assessmentId`, `roadmapId`,
`gapAnalysisId` vào Environment khi dùng nhánh này. Nếu bỏ `assessmentId`, backend dùng bài completed mới nhất
của chính người dùng cho **cùng JD**; chưa có bài thì đối chiếu bằng kỹ năng hồ sơ/CV.
Không tìm thấy skill cần bổ sung thì trả danh sách rỗng và không gọi AI tạo chương trình nghề đầy đủ.
Các body chi tiết xem [assessment/roadmap](postman-api-guide.md#section-8).

Nếu muốn test course xuất hiện trong recommendations/roadmap: Admin create course → approve → Candidate
gọi recommendations hoặc tạo roadmap **mới**. Resource được gợi ý cần approved/active; không cần đợi
RAG index. Nếu muốn assistant tìm được course thì phải index RAG `COMPLETED` riêng. Các URL YouTube/Coursera
dùng link thật do bạn kiểm tra; không lấy link mẫu làm bằng chứng link hoạt động. Xem
[luồng course](postman-api-guide.md#section-14).

<a id="admin-template-order"></a>

### 4.4. Admin quản lý template CV

Nhánh này dùng template test riêng và không cần gọi AI: Admin login → nếu có PDF/Word thì POST
`/admin/resume-templates/import` multipart `file` + `language=vi` → đọc cảnh báo, sửa HTML và gắn slot → POST template active →
GET public thấy ID → Candidate tạo một CV test tham chiếu template → Admin PUT cấu hình →
PATCH status tắt → GET public không còn ID → Candidate đọc CV cũ vẫn giữ `template_id` →
Admin bật lại → GET public thấy ID. Candidate gọi route admin phải `403`.

Giữ `adminResumeTemplateId` và `templateFixtureResumeId` riêng với `resumeId` chính; không dùng
token demo của frontend thay JWT Admin thật. POST/PUT phải có `htmlContent`, không dùng `layoutConfig`. Xem [JSON, script lưu ID và các ca HTML không hợp lệ](admin-resume-templates.md).
Template chỉ chứa metadata/cấu hình trình bày an toàn; endpoint download CV hiện không sinh PDF từ mẫu.
Import chỉ trả bản nháp, chưa tạo bản ghi; không gửi response nguyên dạng vào POST tạo. Để Postman tự tạo header
multipart khi import, sau đó đổi sang raw JSON khi lưu. Hỗ trợ PDF/DOC/DOCX tối đa 10 MB, PDF tối đa 10 trang;
file scan không có text, hỏng hoặc mã hóa bị từ chối. Xem
[dữ liệu và thứ tự thử import → xem lại → lưu](html-resume-templates.md#postman-tải-tài-liệu-sửa-bản-nháp-rồi-lưu).

<a id="thu-tu-toan-bo"></a>

## 5. Thứ tự test toàn bộ theo folder

Không chạy thẳng toàn bộ catalog từ trên xuống. Các số dưới là thứ tự folder gợi ý; mỗi folder dừng
khi prerequisite chưa đạt. Những nhánh quản trị/thanh toán/tư vấn không bắt buộc để test CV bằng Gemini.

| Folder | Nhóm test | Điều kiện và dữ liệu chuyển tiếp | Hướng dẫn |
| --- | --- | --- | --- |
| 00 | Health và cấu hình | Backend/DB UP; API key/model được cấp quyền; quota/timeout phù hợp | Mục 1 |
| 01 | Candidate auth | Register → verify → login → `/me`; lưu token/ID | Mục 2 |
| 02 | Profile và quota | Kiểm tra Free; cập nhật mục tiêu nghề trên tài khoản test | Mục 2 |
| 03 | CV AI | Templates → build → review/PATCH → confirm → primary → embedding → analysis → usage/download | Mục 3 |
| 04 | CV thủ công/upload | Dùng CV khác, không ghi đè `resumeId` chính ngoài ý muốn; review/confirm riêng | [CV](postman-api-guide.md#section-6) |
| 05 | Admin auth/dữ liệu nền | Login Admin riêng; tạo Consultant nếu cần; tạo job ACTIVE | [Admin](postman-api-guide.md#section-12), mục 4.1 |
| 06 | Job embedding | Index đúng model → xác nhận tác vụ `COMPLETED`; giữ `jobId` | Mục 4.1 |
| 07 | Matching và ứng tuyển | Candidate + CV READY + job vector → run/results → feedback → ATS/draft/link | [Matching](postman-api-guide.md#section-7) |
| 08 | Course/RAG nền | Admin create/approve course; document/course index cho nhánh retrieval | [RAG/course](postman-api-guide.md#section-14), mục 4.2 |
| 09 | Assessment | Chọn JD → đọc tài liệu chuẩn bị → trả lời đủ câu → submit `completed` → học từng skill cần bổ sung cùng JD | Mục 4.3 |
| 10 | Roadmap/nguồn học | CV READY; assessment completed cùng nghề nếu gửi; approved course sẵn trước khi tạo snapshot | Mục 4.3 |
| 11 | Assistant | Chat `retrieve:false` → đọc history → chat `retrieve:true` khi RAG COMPLETED → feedback | Mục 4.2 |
| 12 | Guest | No Auth + sessionKey riêng; session → demo/template → chat/build/recommendations; không dùng CV riêng | [Guest](postman-api-guide.md#section-4) |
| 13 | Tư vấn người thật | Candidate tạo thread → Admin assign → Consultant đúng assignment → nhắn/đọc/đóng | [Consultation](postman-api-guide.md#section-10) |
| 14 | Thông báo/hỗ trợ | Candidate ticket/message → Admin cập nhật; ID thông báo khác ID announcement | [Support](postman-api-guide.md#section-11) |
| 15 | Quản trị AI/gói/crawl | Dùng config/prompt/gói test riêng; crawl là nhánh có truy cập website ngoài | [Admin](postman-api-guide.md#section-12), [jobs](postman-api-guide.md#section-13) |
| 16 | Billing thử | Order → lưu amount/currency → webhook ký đúng → subscription/invoice; không cần cho CV | [Billing](postman-api-guide.md#section-5), [ký webhook](postman-api-guide.md#section-15) |
| 17 | Negative/cleanup | Sai quyền/field/state trước; archive tài nguyên test, logout/đổi mật khẩu/thu hồi phiên cuối cùng | Mục 6 |

Guest build dùng dữ liệu demo hệ thống, không tạo CV riêng và có quota riêng; không dùng `resumeId`
của Candidate trong API Guest. Luồng CV thủ công/upload là nhánh thay thế builder, không bắt buộc chạy cả ba
để test matching. Assessment/roadmap dùng job active không yêu cầu vector job; semantic matching mới cần vector.

## 6. Negative test, xử lý lỗi và ghi kết quả

Tạo request negative riêng, không sửa body của request chuẩn đang dùng. Chạy bài kiểm tra trạng thái CV
**ngay sau build và trước confirm** để còn dữ liệu `REVIEW_REQUIRED`.

| Test | Cách gửi | Kỳ vọng |
| --- | --- | --- |
| Thiếu đăng nhập | `POST /resumes/build`, No Auth | `401` |
| Thiếu tiêu đề | Build với `{"language":"vi","structured_data":{}}` | `400`, không dùng làm lượt inference |
| Sai kiểu ngôn ngữ | Build với `"language":"Vietnamese"` | `400`; backend dùng `vi`/`en` |
| Chưa confirm | `POST /resumes/{{resumeId}}/embedding` hoặc `/analyses` khi status REVIEW_REQUIRED | `409`; sau đó quay lại confirm ở luồng chuẩn |
| Sai quyền Admin | Candidate gọi `POST /admin/rag/documents` | `403` |
| Sai chủ sở hữu | Candidate B đọc UUID CV của Candidate A | `404`, không lộ nội dung CV; dùng hai tài khoản test riêng |
| Không có job vector mới | Matching khi job chưa index model Gemini | Có thể `409`; chuẩn bị job đúng model trước khi kết luận thuật toán lỗi |
| Quota không đủ | Tài khoản test được chuẩn bị hạn mức thấp | `429`; không lặp request để cố tiêu hết quota tài khoản đang dùng |
| AI tắt | Môi trường test riêng với `AI_ENABLED=false` | Build/embedding/chat `503`; analysis CV READY có nhánh RULE_BASED, không phải model pass |
| Model lỗi/JSON không hợp lệ | API key/model chưa sẵn sàng hoặc output sai schema | Thường `502`; đọc log/usage, không ghi CV_BUILD PASS |

Không thay model/AI_ENABLED/quota của môi trường đang có người dùng chỉ để ép negative case. Có thể đánh
dấu **BLOCKED/chưa chạy** nếu không có môi trường phù hợp. Test trạng thái/ownership cần giữ nguyên
UUID lấy từ response tương ứng, không thay bằng UUID ngẫu nhiên rồi coi đó là kiểm tra ownership.

Các lỗi thường gặp:

- `401`: access token 15 phút đã hết hạn; refresh/login và cập nhật token. Request public phải bỏ Bearer cũ.
- `403`: sai role, email chưa verify hoặc feature bị chặn bởi gói; kiểm tra `/me` và `/subscriptions/me`.
- `429`: xem quota/usage/reservation; output bị lỗi JSON sau khi model đã trả lời vẫn có thể tiêu token.
- `502`: kiểm tra API key/quyền model, quota Gemini, kết nối mạng và schema đầu ra.
- `503`: kiểm tra provider bật, SMTP/OTP profile hoặc dependency theo nội dung lỗi.
- Timeout client: xem `GET /resumes`, history/usage và log trước khi gửi lại POST; không giả định server đã hủy xử lý.

Sau khi hoàn tất các nhánh phụ thuộc, có thể `DELETE {{baseUrl}}/resumes/{{resumeId}}` bằng Candidate;
kỳ vọng `204` body rỗng, thao tác archive thay vì xóa vật lý. Archive job/RAG/conversation theo đúng API
trong catalog khi muốn dọn fixture. Logout/đổi mật khẩu/thu hồi phiên đặt sau cùng vì làm token mất hiệu lực.

Ghi kết quả thực tế theo bảng này; không tự điền PASS từ việc đã có ví dụ request:

| Test | HTTP thực tế | Giá trị cần kiểm tra | Kết quả | Bằng chứng |
| --- | --- | --- | --- | --- |
| CV_BUILD | Chưa chạy | AI_BUILDER, REVIEW_REQUIRED, dữ kiện không bị bịa | NOT RUN | Response + `resumeId` + giờ gửi |
| Confirm/primary | Chưa chạy | READY, is_primary=true | NOT RUN | Response |
| Embedding | Chưa chạy | provider/model/is_current đúng; vector mới được tạo hoặc tái dùng | NOT RUN | Metadata response + usage/log |
| CV_ANALYZE | Chưa chạy | AI_QUALITY, model đúng, score 0..100 | NOT RUN | Response + `analysisId` |
| Matching | Chưa chạy | CV READY, job vector đúng model, run/results hợp lệ | NOT RUN | Run/detail |
| Assistant/RAG | Chưa chạy | message/history và sources phù hợp | NOT RUN | Message/conversation |

PASS của request không chứng minh độ chính xác AI. Đánh giá riêng độ đúng dữ kiện CV, nội dung bịa,
ngôn ngữ, schema và chất lượng gợi ý; không ép nội dung, token, latency hoặc điểm ATS phải giống ví dụ.
Không đưa access/refresh token, OTP, password hoặc secret vào ảnh/log chia sẻ.
