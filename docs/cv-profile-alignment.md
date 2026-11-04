# Đối chiếu CV với hồ sơ nghề nghiệp cá nhân

Mỗi tài khoản có một hồ sơ nghề nghiệp trong `candidate_profiles`, có thể lưu mục tiêu, kinh nghiệm,
kỹ năng, địa điểm, hình thức làm việc và một mức lương kỳ vọng theo tháng. Khi chưa có CV, Candidate
vẫn dùng hồ sơ này để gợi ý việc làm theo luồng matching hiện có.

Chức năng `CV_PROFILE_ALIGNMENT` bổ sung một bước riêng: so sánh **nội dung CV đã xác nhận** với
**hồ sơ nghề nghiệp hiện tại của chính Candidate**. Candidate chủ động bấm đánh giá hoặc gửi POST lần đầu.
Sau đó, thay đổi nội dung hồ sơ nghề nghiệp tự xếp hàng đánh giá lại những CV `READY` đã từng đối chiếu.
Viết, upload và confirm CV không tự tạo lần đánh giá đầu tiên. Không cần embedding hoặc chọn JD.
Kết quả được lưu riêng trong `resume_profile_alignments`, không ghi đè báo cáo chất lượng/cách viết CV hoặc ATS.

ATS là luồng riêng: `CV_ANALYZE`/`resume_analyses` đánh giá CV, còn `/jobs/{jobId}/ats` đối chiếu với JD
và lưu `job_resume_analyses`. Cập nhật hồ sơ chỉ yêu cầu `CV_PROFILE_ALIGNMENT`, không chạy lại hai luồng
trên và không thay điểm ATS đã lưu.

## Thứ tự thử bằng Postman

Các ví dụ là dữ liệu giả lập và kết quả cần kiểm tra, không phải số đo độ chính xác model.
Dùng `baseUrl = http://localhost:8080/api/v1`. Đăng nhập bằng tài khoản **CANDIDATE** đã xác minh;
chọn Authorization → Bearer Token → `{{candidateAccessToken}}` cho các request cá nhân.
Các request JSON dùng Body → raw → JSON. Không nhập thêm `/api/v1` vào URL khi đã có trong `baseUrl`.

### 1. Lấy kỹ năng và lưu hồ sơ nghề nghiệp

Gọi `GET {{baseUrl}}/skills`, chọn một skill Java đang active và lưu UUID thật vào biến `skillId`.
Không dùng UUID ngẫu nhiên hoặc để nguyên biến chưa được resolve.

Gửi `PUT {{baseUrl}}/me/career-profile`:

```json
{
  "currentJobTitle": "Sinh viên CNTT",
  "currentCompany": null,
  "targetJobTitle": "Java Backend Developer",
  "careerGoal": "Tìm vị trí Java Backend Fresher, xây dựng REST API bằng Spring Boot và PostgreSQL.",
  "yearsExperience": 0,
  "expectedSalary": 7500000,
  "salaryCurrency": "VND",
  "preferredLocation": "Ho Chi Minh City",
  "workplacePreference": "HYBRID",
  "employmentType": "FULL_TIME",
  "profileSummary": "Sinh viên đã làm dự án học phần Java; chưa có kinh nghiệm làm việc tại công ty.",
  "preferences": {},
  "skills": [
    {
      "skillId": "{{skillId}}",
      "proficiencyLevel": "BEGINNER",
      "yearsExperience": 0.5
    }
  ]
}
```

Kỳ vọng HTTP `200`. Đọc lại `GET {{baseUrl}}/me/career-profile` và kiểm tra dữ kiện.
`expectedSalary` là số tiền mỗi tháng; `7500000` nghĩa là 7,5 triệu VND. Các field hồ sơ dùng camelCase
trong request; response dùng snake_case. PUT ghi lại các field hồ sơ, vì vậy gửi đủ những giá trị cần giữ.
Mảng `skills` mới thay toàn bộ danh sách kỹ năng; bỏ field hoặc gửi `null` thì giữ danh sách cũ.

### 2. Tạo một CV thủ công

Có thể upload hoặc dùng AI builder rồi review theo [luồng CV hiện có](postman-test-order.md#tao-cv-ai).
Để kiểm tra riêng phép đối chiếu mà không tiêu quota sinh CV, gửi `POST {{baseUrl}}/resumes`:

```json
{
  "title": "CV Java Fresher - Nguyen Minh An - Test",
  "language": "vi",
  "raw_content": "",
  "structured_data": {
    "full_name": "Nguyễn Minh An",
    "email": "minhan@example.test",
    "phone": "0900000000",
    "location": "Ho Chi Minh City",
    "summary": "Sinh viên CNTT định hướng Java Backend Developer. Đã xây dựng REST API bằng Spring Boot trong dự án học phần, chưa có kinh nghiệm làm việc tại công ty.",
    "experience": [],
    "education": [
      {
        "school_name": "Trường đại học minh họa",
        "degree": "Đang học cử nhân",
        "major": "Công nghệ thông tin",
        "start_date": "2022-09-01"
      }
    ],
    "projects": [
      {
        "project_name": "Quản lý thư viện - dự án học phần",
        "role": "Thành viên phát triển backend",
        "description": "Xây dựng REST API CRUD sách và độc giả bằng Java, Spring Boot và PostgreSQL. Dự án chạy local, chưa triển khai thương mại và chưa có số đo hiệu năng.",
        "technologies": ["Java", "Spring Boot", "PostgreSQL"],
        "start_date": "2026-02-01",
        "end_date": "2026-06-01"
      }
    ],
    "certifications": [],
    "skills": [
      {
        "name": "Java",
        "skill_id": "{{skillId}}",
        "proficiency_level": "BEGINNER",
        "years_experience": 0.5
      }
    ]
  }
}
```

Kỳ vọng `200`, `status = DRAFT`. Lưu ID bằng Post-response:

```javascript
if (pm.response.code === 200) {
  pm.environment.set("resumeId", pm.response.json().id);
}
```

Thử `POST {{baseUrl}}/resumes/{{resumeId}}/profile-alignments` lúc này sẽ trả `409`, chưa gọi model.
Đây là ca kiểm tra trạng thái; không cần gửi lặp nhiều lần.

### 3. Review và xác nhận CV

Gọi `GET {{baseUrl}}/resumes/{{resumeId}}`, đọc toàn bộ `structured_data`; nếu cần sửa, dùng PATCH CV
và gửi đầy đủ document để giữ các section. Không xác nhận dữ kiện do AI tự thêm hoặc chưa được kiểm chứng.

Gửi `POST {{baseUrl}}/resumes/{{resumeId}}/confirm`, **Body → none**. Kỳ vọng `200`, `status = READY`.
Không bắt buộc đặt CV chính hoặc tạo embedding để đối chiếu hồ sơ.

### 4. Chủ động tạo báo cáo đối chiếu

Gửi `POST {{baseUrl}}/resumes/{{resumeId}}/profile-alignments`, **Body → none**.
Backend đọc CV và hồ sơ từ database theo tài khoản đăng nhập; không nhận hồ sơ, điểm, người sở hữu hoặc
lương do client gửi trong request so sánh. Đặt timeout đủ cho model Gemini API.

Kỳ vọng `200` khi inference và xác thực kết quả thành công. Response là object trực tiếp, có `id`,
`resume_id`, `user_id`, `resume_content_hash`, `profile_hash`, `profile_snapshot`, `resume_snapshot`,
`result_json`, `model_name`, `created_at`, `resume_changed`, `profile_changed`, `is_stale`.
Lưu ID báo cáo, khác `analysisId` của báo cáo chất lượng CV:

```javascript
if (pm.response.code === 200) {
  const report = pm.response.json();
  pm.environment.set("alignmentId", report.id);
  pm.expect(report.resume_id).to.eql(pm.environment.get("resumeId"));
  pm.expect(report.is_stale).to.eql(false);
  pm.expect(report.result_json.criteria).to.have.lengthOf(8);
  pm.expect(report.result_json.salary_expectation.score).to.eql(null);
}
```

Mỗi POST thành công tạo một báo cáo mới và thực hiện inference; endpoint không cam kết cache.
Sau timeout, kiểm tra lịch sử trước khi gửi lại để tránh tạo thêm báo cáo ngoài ý muốn.

### 5. Đọc lịch sử và kiểm tra dữ liệu đã thay đổi

| Method | URL | Hành vi |
| --- | --- | --- |
| GET | `{{baseUrl}}/resumes/{{resumeId}}/profile-alignments?limit=20&offset=0` | Mảng báo cáo, mới nhất trước; không gọi lại AI. |
| GET | `{{baseUrl}}/resumes/{{resumeId}}/profile-alignments/{{alignmentId}}` | Một báo cáo của đúng CV và tài khoản; không gọi lại AI. |
| GET | `{{baseUrl}}/resumes/{{resumeId}}/profile-alignments/refresh-status` | Trạng thái đánh giá lại tự động của đúng CV/tài khoản; không gọi AI. |

Mặc định `limit = 20`, `offset = 0`. Giới hạn `limit` từ 1–100 và `offset` từ 0–100000; ngoài khoảng trả `400`.

Hai snapshot lưu đầu vào của lần đánh giá. `profile_changed` và `resume_changed` so sánh đầu vào đã lưu
với dữ liệu hiện tại; `is_stale` bằng OR của hai cờ. Sửa kỹ năng, mục tiêu hoặc lương trong hồ sơ có thể
làm báo cáo cũ bị đánh dấu; không tự thay điểm hoặc diễn giải lại báo cáo cũ bằng hồ sơ mới.
Đổi thời gian cập nhật hồ sơ hoặc chỉ đổi template mà giữ nguyên nội dung không làm phép đối chiếu cũ hết hiện hành.
Hồ sơ bị xóa vẫn đọc được lịch sử đã lưu và được đánh dấu thay đổi.

Để thử: lưu báo cáo đầu tiên → đổi mục tiêu trong hồ sơ → GET báo cáo cũ, kiểm tra `profile_changed = true`
và snapshot/điểm vẫn giữ nguyên → theo dõi trạng thái tự động ở bước 6. Nếu sửa nội dung CV thì cần review/confirm
lại; thay đổi CV không tự tạo yêu cầu đánh giá mới từ luồng cập nhật hồ sơ.
Nếu CV hoặc hồ sơ đổi **trong lúc inference**, backend trả `409` và không ghi báo cáo cho đầu vào đã lỗi thời.

### 6. Cập nhật hồ sơ và theo dõi đánh giá lại tự động

Giữ lại `alignmentId` đầu tiên để xem lịch sử. Gửi lại **đầy đủ** PUT hồ sơ ở bước 1, đổi `targetJobTitle`
hoặc `careerGoal` thành mục tiêu thực sự khác. PUT thành công vẫn trả `200` và dữ liệu hồ sơ;
việc gọi model diễn ra sau commit, không bắt người dùng chờ AI ngay trong request lưu hồ sơ.

Backend so sánh snapshot chuẩn hóa trước/sau cập nhật: đổi mục tiêu, kỹ năng, kinh nghiệm hoặc dữ kiện nghề nghiệp
được dùng trong snapshot mới tạo yêu cầu. Gửi lại cùng dữ kiện, chỉ thay timestamp hoặc thông tin liên hệ không
tạo yêu cầu. Chỉ CV đang `READY` và đã có ít nhất một báo cáo `resume_profile_alignments` của chủ sở hữu được
xếp hàng; CV chưa từng đối chiếu vẫn cần lần POST đầu tiên, CV chưa sẵn sàng không tự được confirm.

Gọi `GET {{baseUrl}}/resumes/{{resumeId}}/profile-alignments/refresh-status`, **Body none**:

| `status` | Ý nghĩa và cách tiếp tục |
| --- | --- |
| `NONE` | CV chưa có yêu cầu tự động; response có thể chỉ gồm `{"status":"NONE"}`. |
| `QUEUED` | Đang chờ worker; giữ báo cáo cũ để xem. |
| `RUNNING` | Model đang xử lý; chưa có báo cáo mới. |
| `SUCCEEDED` | Đã lưu báo cáo mới; đọc `alignment_id` bằng GET chi tiết hoặc tải lại danh sách. |
| `FAILED` | Lỗi quota/AI/đầu vào hoặc lượt chạy bị gián đoạn; xem `error_code`, `message`, xử lý nguyên nhân rồi chủ động POST lại. |
| `CANCELLED` | Đầu vào không còn hợp lệ, CV không còn sẵn sàng hoặc đã thay đổi; review dữ liệu trước khi thử lại. |

Khi có yêu cầu, response còn có `requested_at`, `started_at`, `completed_at`, `alignment_id`, `error_code`,
`message`; các field chưa có giá trị có thể là `null`. `alignment_id` là ID báo cáo, không phải ID yêu cầu.
Ví dụ script đọc trạng thái, không tự gửi inference:

```javascript
const refresh = pm.response.json();
pm.expect(["NONE", "QUEUED", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED"]).to.include(refresh.status);
if (refresh.status === "SUCCEEDED" && refresh.alignment_id) {
  pm.environment.set("refreshedAlignmentId", refresh.alignment_id);
}
```

Trong lúc `QUEUED`/`RUNNING`, POST thủ công trả `409` để tránh tạo hai lượt cùng CV. Sau `SUCCEEDED`, GET
`.../profile-alignments/{{refreshedAlignmentId}}`, kiểm tra snapshot hồ sơ mới; GET báo cáo cũ vẫn giữ điểm và
snapshot cũ. Nếu trước đó đã có báo cáo ATS/chất lượng, đọc lại chúng để xác nhận ID/nội dung không bị ghi đè.
Gửi lại cùng PUT hồ sơ và kiểm tra `requested_at` không đổi để thử nhánh không thay đổi dữ kiện.

Worker có scheduler riêng, mặc định kiểm tra mỗi 5 giây, xử lý lần lượt một CV. Đây là khoảng kiểm tra hàng đợi,
không phải cam kết hoàn tất trong 5 giây. Mỗi CV chỉ giữ một yêu cầu; các lần sửa tiếp theo được gộp về hồ sơ
mới nhất. Nếu hồ sơ đổi trong lúc model chạy, kết quả đã lỗi thời không được lưu thành báo cáo; yêu cầu mới nhất
được xử lý tiếp. Lượt model đã chạy vẫn có thể tiêu token trước khi bị loại.

Mỗi lượt nền dùng quota **`CV_PROFILE_ALIGNMENT`** và **`AI_TOTAL_TOKENS`** giống POST thủ công. Lỗi quota/AI
kết thúc ở `FAILED`, không âm thầm lặp lại và không làm rollback hồ sơ đã lưu. Lease mặc định 900 giây:
lượt chạy bị gián đoạn/hết lease chuyển `FAILED` với `error_code = INTERRUPTED` khi worker kiểm tra, cần thử lại
thủ công vì lượt trước có thể đã tiêu quota. Ở nhánh tự động, cần một thay đổi hồ sơ thực sự khác để tạo yêu cầu mới sau lỗi.

`.env.example` đặt `CV_PROFILE_ALIGNMENT_REFRESH_ENABLED=true` theo mặc định. Đặt `false` dừng worker tự động
(các test kiểm soát worker dùng giá trị này); yêu cầu đã lưu không bị xóa. GET lịch sử/trạng thái không tiêu quota AI.

## Tiêu chí và cách đọc phần trăm

`result_json.rubric_version` là `cv-profile-alignment-v1`. Báo cáo có bảy tiêu chí nghề nghiệp;
bốn tiêu chí có thể được model chấm từ bằng chứng CV, ba tiêu chí ưu tiên công việc được giữ chưa đánh giá:

| Code | Nội dung hồ sơ | Cách xử lý hiện tại |
| --- | --- | --- |
| `TARGET_ROLE` | Vị trí mong muốn | Đối chiếu với bằng chứng vị trí/nội dung công việc trong CV. |
| `CAREER_GOAL` | Mục tiêu nghề nghiệp | Model đối chiếu khi hai nguồn có đủ bằng chứng. |
| `SKILLS` | Kỹ năng và thông tin kỹ năng đã lưu | Model đối chiếu khi hai nguồn có đủ bằng chứng. |
| `EXPERIENCE` | Số năm kinh nghiệm, công việc và công ty hiện tại nếu có | Model đối chiếu khi hai nguồn có đủ bằng chứng. |
| `LOCATION` | Địa điểm làm việc mong muốn | `MISSING_CV_EVIDENCE`/`null` nếu đã đặt; cần đối chiếu với job/JD. |
| `WORKPLACE` | Hình thức làm việc mong muốn | `MISSING_CV_EVIDENCE`/`null` nếu đã đặt; cần đối chiếu với job/JD. |
| `EMPLOYMENT_TYPE` | Loại hợp đồng/công việc mong muốn | `MISSING_CV_EVIDENCE`/`null` nếu đã đặt; cần đối chiếu với job/JD. |

Schema CV hiện có địa chỉ liên hệ, summary và lịch sử công việc, chưa có trường riêng cho địa điểm,
hình thức hoặc loại công việc **mong muốn**. Backend chủ động giữ ba tiêu chí cuối chưa được chấm,
không suy ưu tiên từ địa chỉ, nơi/công việc từng làm hoặc câu trong summary. Nếu hồ sơ chưa đặt các
ưu tiên này thì trạng thái là `NOT_APPLICABLE`, điểm vẫn `null`. Dù `structured_data.location` trong
CV mẫu giống `preferredLocation` của hồ sơ, `LOCATION` vẫn chưa được chấm.

Mỗi tiêu chí có `code`, `status`, `score`, `feedback`, `profile_evidence`, `cv_evidence`, `suggestions`.
`ASSESSED` có điểm từ 0–100 và trích dẫn từ cả hai nguồn. Khi thiếu bằng chứng CV, chưa đủ thông tin
hoặc hồ sơ chưa đặt tiêu chí, điểm là **`null`**, không tự thay bằng 0 hoặc 100.
Các trạng thái tương ứng là `MISSING_CV_EVIDENCE`, `INSUFFICIENT_EVIDENCE`, `NOT_APPLICABLE`.
Nội dung cấu trúc đã review được ưu tiên; không lấy `raw_content` cũ để thay dữ kiện CV đã chỉnh sửa.

Khi hồ sơ có `target_job_title` và CV đã review có `experience[].job_title`, `TARGET_ROLE` phải được
đánh giá với trích dẫn hai phía. Khác ngành/vị trí là một sự khác biệt có thể đối chiếu, không tự chuyển
thành “thiếu bằng chứng” chỉ vì hai tên nghề khác nhau. Điểm phù hợp vẫn do model suy luận theo rubric;
quy tắc này không tự ấn định điểm thấp/cao hoặc chứng minh nhận xét ngữ nghĩa luôn đúng.

Backend tính lại `result_json.score` bằng **trung bình cộng các tiêu chí `ASSESSED`**, làm tròn hai chữ số.
`assessed_criteria` là số tiêu chí có điểm; `applicable_criteria` là số tiêu chí hồ sơ có thông tin áp dụng;
`coverage_percent = assessed_criteria / applicable_criteria × 100`. Mẫu số vẫn bao gồm cả ba ưu tiên
công việc đã được đặt trong hồ sơ dù CV chưa có trường để chấm chúng. Nếu không có tiêu chí áp dụng
thì coverage bằng 0; nếu không có tiêu chí được đánh giá thì tổng điểm là `null`.

Ví dụ minh họa, không phải kết quả model đã đo: hồ sơ có 5 tiêu chí áp dụng, nhưng chỉ 2 tiêu chí có đủ
bằng chứng và được chấm 80, 60 thì tổng điểm là **70%**, coverage là **40%**. Cần đọc cả coverage và
những tiêu chí chưa đánh giá; điểm cao từ ít tiêu chí không chứng minh toàn bộ CV phù hợp mục tiêu.

Một ví dụ minh họa khác: cả bảy tiêu chí đều được đặt trong hồ sơ và bốn tiêu chí đầu đều được đánh giá
thì coverage là **4/7 = 57,14%**, dù điểm phù hợp trung bình có thể cao hoặc thấp. Ba ưu tiên công việc
còn lại giữ `null`; không đổi thành 0 để kéo điểm xuống và cũng không đổi thành 100 để tăng coverage.

Đây là nhận xét và điểm do model suy luận theo rubric, chưa phải phần trăm được hiệu chuẩn bằng tập kiểm định.
Không diễn giải điểm thành độ chính xác AI, khả năng trúng tuyển, xác suất tuyển dụng hoặc thu nhập dự kiến.
Khi cần đối chiếu với một JD cụ thể, dùng luồng CV–job/ATS riêng.

### Lương kỳ vọng

Tiêu chí thứ tám `SALARY_EXPECTATION` được backend bổ sung. Khi có lương kỳ vọng, trạng thái là
`REQUIRES_JOB_DATA`; `score` luôn là **`null`**. `result_json.salary_expectation` lưu `expected_salary`,
`currency`, `period = MONTHLY`, `status`, `score`. Khi chưa đặt lương, trạng thái là `NOT_APPLICABLE`.

CV không cung cấp mức trả của nhà tuyển dụng, vì vậy hệ thống không suy ra CV này “xứng đáng bao nhiêu tiền”
hoặc tự tạo phần trăm phù hợp lương. Muốn kết luận lương phù hợp cần JD/job offer có mức trả, đơn vị tiền tệ
và kỳ trả lương so sánh được. Matching job vẫn dùng [biên độ lương do Admin cấu hình](admin-salary-policy.md).
Lương không tham gia mẫu số điểm hoặc coverage của phép đối chiếu CV–hồ sơ.

## Gemini, quota và giới hạn đầu vào

Inference dùng model chat **Gemini API** đang được cấu hình; kết quả được kiểm tra ở backend trước khi lưu.
Chức năng này dành cho Candidate, không mở route Guest hoặc route chạy thay của Admin/Consultant.
AI tắt trả `503`, không tạo báo cáo giả bằng rule-based.

Backend yêu cầu JSON object qua Gemini, đưa schema vào system prompt và kiểm tra trạng thái/kiểu điểm, trích dẫn và tính lại điểm tổng sau khi nhận phản hồi. Đúng cấu trúc và đúng đoạn trích không tự chứng minh điểm hoặc diễn giải có chất lượng ngữ nghĩa hoàn hảo.

V17 seed quota feature `CV_PROFILE_ALIGNMENT` bằng cách sao chép `quota_value` và `quota_period` của
`CV_ANALYZE` cho từng tier hiện có, giữ nguyên giá trị quản trị đã cấu hình; không tự ghi đè quota feature
đã tồn tại. Việc giữ chỗ/tiêu token vẫn đi qua feature quota và tổng **`AI_TOTAL_TOKENS`** của ứng dụng.
Đọc `GET {{baseUrl}}/subscriptions/me` và `GET {{baseUrl}}/ai/usage?limit=20&offset=0` bằng cùng token
để kiểm tra cấu hình/thực dùng; không mặc định mọi database có cùng quota seed.
Quota do Admin đặt trong ứng dụng độc lập với số dư và giới hạn API của Google.

Lượt đối chiếu đặt giới hạn đầu ra 4000 token. Trước khi giữ chỗ quota, backend tính
budget bảo thủ bằng **byte UTF-8 của system prompt + byte UTF-8 của JSON đầu vào + 512 + 4000**.
Nếu vượt 16384, trả `400` yêu cầu rút gọn nội dung, không tự cắt dữ kiện và không gọi model.
Đây là kiểm tra giới hạn bảo thủ, không phải số token tokenizer thực tế; văn bản nhiều dấu có thể
chạm ngưỡng sớm hơn. Giới hạn chung JSON đầu vào 250.000 ký tự cũng vẫn được áp dụng.

## Các ca lỗi cần kiểm tra

| Tình huống | HTTP và hành vi |
| --- | --- |
| Thiếu/sai/hết hạn token | `401`. |
| Token không có role Candidate | `403`, kể cả token Admin. |
| CV/báo cáo không tồn tại, sai chủ sở hữu hoặc báo cáo thuộc CV khác | `404`. |
| CV chưa `READY`; không có hồ sơ hoặc hồ sơ chỉ có lương mà thiếu dữ kiện nghề nghiệp | `409`, chưa gọi model. |
| UUID/query sai hoặc đầu vào vượt budget bảo thủ | `400`; trường hợp vượt budget bị chặn trước khi giữ chỗ quota. |
| AI đang tắt | `503`, không lưu báo cáo. |
| Feature quota bằng 0 hoặc thiếu cấu hình quota | Có thể `403` hoặc `503` theo kiểm tra entitlement; đọc `detail` thực tế. |
| Không đủ tổng/feature quota để giữ chỗ | `429`; số token output dự kiến nhỏ không có nghĩa budget đủ. |
| CV hoặc hồ sơ đổi trong khi đang suy luận | `409`, không lưu báo cáo mới. |
| POST thủ công khi CV có lượt tự động `QUEUED`/`RUNNING` | `409`; chờ trạng thái kết thúc rồi xem lịch sử trước khi thử lại. |
| Gemini lỗi, sai schema, điểm/trạng thái sai hoặc evidence không phải trích dẫn đúng nguồn | `502`, không lưu báo cáo mới. |

Trích dẫn được đối chiếu sau chuẩn hóa Unicode/khoảng trắng; paraphrase, dịch lại, ghép nhiều field hoặc
trích nội dung không có trong nguồn có thể bị từ chối. Không sửa báo cáo cũ để che lỗi model.
Lỗi sau khi provider đã phản hồi vẫn có thể đã tiêu token; “không lưu báo cáo” không đồng nghĩa chưa gọi model.

## Lưu trữ

Flyway `V17__resume_profile_alignments.sql` thêm bảng `resume_profile_alignments`, index lịch sử theo
người dùng/CV/thời gian và các quota feature cần thiết. API chỉ tạo và đọc lịch sử; không có endpoint sửa
snapshot hoặc kết quả đã lưu. Đây là snapshot dữ liệu đưa vào model, không phải bản sao toàn bộ thông tin tài khoản.

V18 `resume_profile_alignment_refreshes` bổ sung trạng thái yêu cầu bền vững theo CV, generation và lease để
gộp các lần sửa và chặn công bố kết quả lỗi thời. Schema hiện tại có **83 bảng ứng dụng**. Lịch sử báo cáo
tiếp tục chỉ thêm mới; bảng hàng đợi không thay thế dữ liệu trong báo cáo đã lưu.

## Phần bổ sung đánh giá lại tự động ngày 24/10/2026

Đã kiểm tra flow V18 và giao diện trạng thái:

| Kiểm tra | Kết quả |
| --- | --- |
| Backend verify đầy đủ, kết thúc 01:46 giờ Việt Nam | `BUILD SUCCESS`: **875 test được ghi nhận, 817 đạt, 0 failure, 0 error, 58 bỏ qua**. |
| Bộ kiểm tra tập trung | **61/61 đạt**, gồm API đối chiếu hiện có, worker mới, actor/quota AI và schema sau V18. |
| Integration tự động đánh giá lại | **13/13 đạt** trên PostgreSQL/pgvector riêng: hàng đợi sau lưu hồ sơ, no-op, phạm vi CV/owner, thay đổi skill, gộp thay đổi, sửa hồ sơ giữa inference, lỗi AI, phân quyền, lease hết hạn, retry thủ công và tái sử dụng báo cáo còn mới. Có kiểm tra hai transaction đồng thời để tránh deadlock khi lưu hồ sơ và báo cáo. |
| Frontend | **29 kiểm tra alignment**, 25 kiểm tra salary liên quan, ESLint các file sửa và TypeScript/Vite build đạt. Chỉ còn cảnh báo bundle lớn hiện hữu. |

Log: [verify đầy đủ V18](../target/cv-profile-refresh-verify.log),
[kiểm tra tập trung V18](../target/cv-profile-refresh-focused-passed.log).
Các kiểm tra xác nhận báo cáo ATS được giữ nguyên; luồng worker mới dùng AI giả lập để kiểm chứng trigger,
transaction và lỗi, không gọi model hoặc tiêu quota của tài khoản thật. Hợp đồng model không đổi so với V17.

Lượt verify V18 dùng bản POM kiểm tra tạm giống POM dự án, chỉ đổi thư mục output sang
`target/cv-profile-refresh-verified` để tránh file class do IDE đang biên dịch vào `target/classes`.
Đã dùng `javac` biên dịch mã nguồn và chạy đầy đủ formatter/test/package; không bỏ qua formatter hay test.
JAR được kiểm tra sau đó được chép sang vị trí build Docker hiện có.

Runtime Docker được xác nhận lúc **01:48 ngày 24/10/2026**, giờ Việt Nam: health `UP`, Flyway đã áp dụng V18,
JAR đang chạy khớp SHA-256 của artifact verify. Swagger có endpoint `refresh-status`; endpoint này và ba API
đối chiếu hiện có trả `401` khi chưa đăng nhập; jobs công khai vẫn trả `200`.
Xem [bằng chứng runtime V18](../target/cv-profile-refresh-app-runtime.json).
Lượt runtime không tạo yêu cầu đánh giá hoặc dùng dữ liệu/quota tài khoản thật trên DB ứng dụng;
luồng có đăng nhập, chạy worker và ATS độc lập đã được kiểm chứng trên DB test riêng.

## Kiểm chứng sau khi đổi provider

Các kết quả kiểm thử cũ không xác nhận chất lượng phản hồi hoặc thời gian chạy của Gemini. Chạy lại kiểm tra nghiệp vụ và kiểm tra một request với dữ liệu giả lập khi đã cấu hình API key.
