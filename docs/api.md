# API EduNexus v1

Base URL: `http://localhost:8080/api/v1`. Request JSON sử dụng tên trường của DTO; dữ liệu database trả về sử dụng `snake_case`. Mã lỗi nghiệp vụ dùng HTTP 400/401/403/404/409/429/502/503 và `application/problem+json` với `status`, `detail`; lỗi validation có thêm `errors`. Lỗi 401/403 từ bộ lọc xác thực hoặc phân quyền trả `application/json` với `status`, `message`. Danh sách endpoint đầy đủ có trong Swagger.

Sau đăng nhập, gửi `Authorization: Bearer <accessToken>`. Token access có hạn 15 phút; refresh token có hạn 30 ngày, xoay sau mỗi lần dùng và không được dùng làm bearer. Guest gửi `X-Guest-Session: <sessionKey>`. API không nhận user ID từ client để thay chủ sở hữu dữ liệu.

## Identity và trial

| Method | Path | Body / mục đích |
| --- | --- | --- |
| POST | `/auth/register` | `email`, `password` 10–72 byte, `fullName`, `preferredLanguage` vi/en |
| POST | `/auth/verify-email` | `email`, `code` gồm 6 số |
| POST | `/auth/login` | `email`, `password` |
| POST | `/auth/google` | `idToken` từ Google; cần cấu hình client ID |
| POST | `/auth/refresh` | `refreshToken`; trả cặp token mới |
| POST | `/auth/logout` | Thu hồi mọi session của tài khoản |
| GET / PUT | `/me` | Thông tin cá nhân |
| GET / PUT | `/me/career-profile` | Mục tiêu nghề nghiệp, lương, kinh nghiệm, địa điểm, kỹ năng |
| POST | `/guest/sessions` | Trả `sessionKey`, quota và thời điểm hết hạn |
| GET | `/guest/demo-resumes`, `/guest/templates` | CV/template hệ thống cho trial |
| POST | `/guest/trials/recommendations` | `demo_resume_id`; top 3 kết quả, so khớp kỹ năng |
| POST | `/guest/trials/build` | `demo_resume_id`, `template_id`; cần AI |

Ví dụ profile:

```json
{
  "targetJobTitle": "Java Developer",
  "careerGoal": "Làm backend với Java và Spring Boot",
  "yearsExperience": 1,
  "salaryCurrency": "VND",
  "expectedSalaryMin": 15000000,
  "expectedSalaryMax": 25000000,
  "preferredLocation": "Ho Chi Minh",
  "skills": [{"skillId": "<id từ GET /skills>", "proficiencyLevel": "intermediate", "yearsExperience": 1}]
}
```

## CV: nhập → xem lại → xác nhận

`POST /resumes` tạo CV manual. `POST /resumes/upload` nhận multipart field `file` và tùy chọn `title`, `language` (vi/en). CV upload/sinh bởi AI ở `REVIEW_REQUIRED`; CV manual ở `DRAFT`. Chỉnh sửa qua `PATCH /resumes/{id}` rồi xác nhận qua `POST /resumes/{id}/confirm`. Chỉ CV `READY` được dùng cho matching và phân tích.

```json
{
  "title": "Backend CV",
  "language": "vi",
  "structured_data": {
    "full_name": "Nguyen Van A",
    "summary": "Lập trình backend Java, SQL và Spring Boot",
    "skills": ["Java", "SQL", "Spring Boot"],
    "experience": [{"company_name": "Example", "job_title": "Developer", "start_date": "2025-01-01", "is_current": true}],
    "education": [],
    "projects": [],
    "certifications": []
  }
}
```

Body xác nhận có thể là `{ "is_primary": true }`, hoặc kèm `structured_data` đã sửa. `POST /resumes/{id}/primary` chọn CV chính; `DELETE /resumes/{id}` archive; `GET /resumes/{id}/download` tải file gốc, thêm `?text=true` để tải nội dung text đã xem lại. `POST /resumes/{id}/analyses` tạo báo cáo chất lượng, `GET /resumes/{id}/analyses` xem lịch sử. `POST /resumes/build` nhận thông tin CV và profile để sinh nội dung AI; kết quả vẫn cần xác nhận.

## Job discovery, matching và ứng tuyển

`GET /jobs?q=Java&location=Ho&limit=20&offset=0`, `GET /jobs/{id}` và `GET /skills` công khai. Job phải được import/crawl bởi admin trước.

```http
POST /matching
Authorization: Bearer <accessToken>
Content-Type: application/json
```

```json
{"basis":"SELECTED_CV","resumeIds":["<resume_id>"],"limit":20}
```

`basis` nhận `CAREER_PROFILE`, `ALL_CVS`, `SELECTED_CV`. `SELECTED_CV` cần đúng 1 CV của người dùng; `ALL_CVS` hỗ trợ tối đa 100 CV đã xác nhận. Kết quả có run ID, điểm 0–1, giải thích, kỹ năng khớp/thiếu và thông tin job. `GET /matching/{id}` xem lại, `GET /matching` xem lịch sử. Cache phụ thuộc nội dung CV/profile, danh mục job, provider/model và request; thay nội dung làm mất hiệu lực kết quả cũ. Kết quả tắt AI ghi `SKILL_OVERLAP`, không có vector/LLM score giả.

| Method | Path | Body / hành vi |
| --- | --- | --- |
| PUT | `/matching/results/{id}/feedback` | `feedbackType` LIKE/DISLIKE, `rating` 1–5, `comment` |
| POST | `/jobs/{id}/ats` | `resumeId`; phân tích AI CV–job, có cache Redis/database |
| POST | `/jobs/{id}/application-drafts` | `resumeId`, `language`; trả email và cover letter draft |
| POST | `/jobs/{id}/resume-draft` | Thông tin CV manual và `title` tùy chọn; AI tạo CV hướng tới job, vẫn cần xác nhận |
| GET | `/application-drafts?jobId=...` | Lịch sử draft của người dùng |
| PATCH | `/application-drafts/{id}` | `content` đã sửa |
| POST | `/jobs/{id}/external-apply` | `resumeId` tùy chọn; trả `target_url` và lưu event |
| POST | `/jobs/{id}/salary-estimation` | Ước lượng AI dựa trên profile/job và lưu reasoning |

Frontend mở `target_url` để người dùng ứng tuyển tại trang gốc. Draft không được gửi tự động.

## Assessment và roadmap

Tạo assessment: `POST /assessments` với `{ "targetJobTitle": "Java Developer", "jobId": "<optional>" }`. Khi thiếu target, backend lấy từ job hoặc career profile. API sinh câu hỏi foundation và role-specific; dữ liệu câu hỏi chưa nộp không chứa đáp án đúng hay lời giải.

```http
PUT /assessments/{id}/answers
```

```json
{"questionId":"<question_id>","selectedOption":"B"}
```

Nộp qua `POST /assessments/{id}/submit` sau khi đã trả lời đủ. Backend chấm theo đáp án lưu trên server, lưu điểm từng kỹ năng và giữ kết quả để tạo roadmap. Có thể gọi nộp lại để xem cùng kết quả; assessment đã nộp không sửa đáp án.

`POST /roadmaps` nhận `targetJobTitle`, cùng các ID tùy chọn `jobId`, `resumeId`, `assessmentId`. Assessment được chọn phải đã hoàn thành và cùng mục tiêu nghề nghiệp. Roadmap gồm skill gap, các node theo thứ tự, estimated hours, prerequisite và tài nguyên từ catalog. ID tài nguyên đến từ database, không lấy URL AI tự tạo.

`GET /roadmaps`, `GET /roadmaps/{id}` xem lịch sử/detail. Cập nhật tiến độ:

```http
PATCH /roadmaps/{id}/nodes/{nodeId}
```

```json
{"status":"completed"}
```

Trạng thái node nhận `not_started`, `in_progress`, `completed`. Phải hoàn thành prerequisite trước khi học/hoàn thành node sau. Muốn reset node trước, reset các node phụ thuộc trước. Tiến độ roadmap được tính từ node đã hoàn thành; sửa CV/profile hoặc nộp assessment mới đánh dấu roadmap cũ `is_outdated`.

## Tư vấn và trợ lý AI

Candidate tạo `POST /consultations` với `subject`, `requestType` tùy chọn và `description`. Admin phân công qua `PUT /consultations/{id}/consultant` với `consultantId`. Candidate và consultant được phân công đọc/gửi tin nhắn tại `/consultations/{id}/messages`. `/consultations/{id}/candidate-profile` cung cấp career profile, kỹ năng và CV chính đã xác nhận để tư vấn; có thể chọn `?resumeId=...` của candidate trong thread. `POST /consultations/{id}/close` đóng thread.

`GET /consultations/{id}/events` phát SSE `connected` và `message`. Dùng fetch streaming có bearer header; khi reconnect, tải lại lịch sử từ `/messages`. SSE có thời hạn 5 phút, hoạt động trong cùng process; triển khai nhiều instance cần một adapter phát sự kiện chung.

Trợ lý AI: tạo `/assistant/conversations`, sau đó gửi `POST /assistant/conversations/{id}/messages` với `{ "message": "...", "retrieve": true }`. Context có thể liên kết `related_job_id`, `related_resume_id`, `related_gap_analysis_id`, `related_roadmap_id`; quyền sở hữu được kiểm tra. `retrieve=true` dùng pgvector RAG và trả các nguồn đã thực sự truy xuất. Guest có endpoint tương ứng dưới `/guest/assistant/conversations`, dùng session header và chịu quota trial.

## Admin, RAG và thanh toán

Admin quản lý user tại `/admin/users`, gói/quota tại `/admin/subscription-tiers`, AI tại `/admin/ai/configurations` và `/admin/ai/prompts`; xem audit/usage/index jobs qua `/admin/monitoring/{category}`. Thông báo tạo tại `/admin/announcements`, publish/archive bằng endpoint tương ứng; người dùng xem `/announcements` và `/notifications`. Support ticket ở `/support/tickets`; chỉ chủ ticket hoặc admin truy cập.

RAG: `POST /admin/rag/documents` với `title`, `content`, `sourceType: "ADMIN"`, `metadata` tùy chọn. Nội dung được chia chunk; gọi `/admin/rag/documents/{id}/index` để sinh embedding và `/archive` để dừng sử dụng cho retrieval.

Gói công khai: `GET /subscriptions/tiers`; quyền/quota hiện tại: `GET /subscriptions/me`. Tạo đơn: `POST /payments/orders` với `{ "tierId": "...", "durationMonths": 1 }`. Số tiền lấy từ tier trên server. Chi tiết chữ ký webhook, invoice, payment report và cấu hình gateway trong [identity/billing](identity-billing.md).

Ví dụ import job thủ công:

```http
POST /admin/jobs
```

```json
{
  "sourceSite": "ITVIEC",
  "sourceUrl": "https://itviec.com/it-jobs/example",
  "jobTitle": "Java Developer",
  "companyName": "Example",
  "location": "Ho Chi Minh",
  "roleCategory": "Backend",
  "content": "Job description copied from the authorized source, including Java and SQL requirements.",
  "skills": [{"name":"Java","required":true},{"name":"SQL","required":true}]
}
```

Gọi `/admin/jobs/{id}/index` khi bật embedding; `/admin/jobs/crawl` để thu thập URL nguồn và `/admin/jobs/{id}/status` với ACTIVE/EXPIRED/REMOVED/INVALID để quản lý vòng đời.
