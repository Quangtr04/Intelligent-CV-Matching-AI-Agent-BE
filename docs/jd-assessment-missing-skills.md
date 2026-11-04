# Chọn JD, chuẩn bị bài đánh giá và học từng kỹ năng còn thiếu

Luồng mới là **chọn JD → xem kỹ năng/tài liệu chuẩn bị → làm bài đánh giá theo JD → xem từng kỹ năng cần bổ sung**.
Hệ thống không đưa toàn bộ chương trình học theo tên nghề vào kết quả mới. Mỗi kỹ năng cần bổ sung có lý do,
nội dung luyện tập, thời lượng và tài nguyên riêng.

## Cách xác định kỹ năng cần bổ sung

Backend đối chiếu **kỹ năng bắt buộc đã được trích xuất của JD đang chọn** với kỹ năng khai báo trong hồ sơ,
kỹ năng đã xác nhận của CV được chọn và điểm bài đánh giá đã hoàn tất cho **đúng JD đó**.

| Bằng chứng của một kỹ năng bắt buộc | Kết quả |
| --- | --- |
| Bài đánh giá cùng JD có điểm dưới 70/100 | `NEEDS_PRACTICE`: cần luyện thêm, kể cả hồ sơ/CV đã khai báo kỹ năng |
| Bài đánh giá cùng JD có điểm từ 70/100 | Không đưa kỹ năng đó vào danh sách cần bổ sung |
| Chưa có điểm nhưng hồ sơ hoặc CV đã khai báo kỹ năng | Không đưa vào danh sách; đây là bằng chứng tự khai, chưa chứng minh mức thành thạo |
| Chưa có điểm và hồ sơ/CV chưa khai báo kỹ năng | `MISSING_EVIDENCE`: chưa có bằng chứng, không kết luận người dùng không biết |
| Kỹ năng chỉ là tùy chọn trong JD | Không bắt buộc đưa vào kế hoạch học bù |

Ví dụ JD bắt buộc **Java, SQL, Docker**; hồ sơ đã có Java và SQL, chưa có Docker và chưa có điểm đánh giá:
kết quả chỉ có **Docker — MISSING_EVIDENCE**. Nếu sau đó bài đánh giá cùng JD cho SQL 50 điểm và Docker
100 điểm, kết quả học bù chỉ có **SQL — NEEDS_PRACTICE**. Các kỹ năng chưa được hỏi vẫn được đối chiếu với
bằng chứng hồ sơ/CV; không coi điểm một vài câu là kết luận về toàn bộ kiến thức của người dùng.

Danh sách này được backend xác định trước khi gọi Gemini. AI chỉ viết hướng dẫn cho đúng các kỹ năng
được cho phép; backend từ chối kết quả thêm kỹ năng ngoài danh sách hoặc bỏ sót kỹ năng cần hướng dẫn.
Tài nguyên học lấy từ catalog/khóa học đã duyệt theo từng skill; AI không tự tạo URL khóa học.

## Thao tác trên giao diện

1. Khởi động frontend và backend với Gemini API, rồi mở `/login`. Chọn **Tài khoản hệ thống** và đăng nhập
   bằng tài khoản **Candidate đã xác minh ở backend**. Đây là lựa chọn mặc định khi mở `/login`
   thông thường. `/login?mode=demo` hoặc đường dẫn có tham số `registered` sẽ mở chế độ demo;
   nếu đang ở đó, chuyển lại tab **Tài khoản hệ thống** trước khi đăng nhập.
2. Mở `/candidate/assessment` (**Skill Assessment**). Chọn JD trong danh mục thật và xem nội dung JD.
   Phần **Chuẩn bị kiến thức theo JD** hiển thị từng skill, yêu cầu bắt buộc/bổ sung và tài liệu catalog
   để đọc trước. Chọn JD không tự sinh câu hỏi hoặc trừ quota sinh bài.
3. Bấm **Tạo bài đánh giá theo JD** khi đã sẵn sàng. Backend trả mã tác vụ ngay; giao diện đọc trạng thái
   bằng các request ngắn, không giữ một request HTTP trong suốt lúc Gemini sinh câu hỏi. Có thể tải lại
   trang và tiếp tục theo dõi tác vụ của đúng tài khoản/JD. Khi hoàn thành, chọn đáp án cho từng câu rồi bấm
   **Nộp bài và xem kết quả**; xem điểm theo skill, đáp án và giải thích sau khi nộp đủ bài.
4. Bấm **Xem kỹ năng thiếu và cách học**. Liên kết sang `/candidate/skill-gap` giữ đúng JD và bài đã
   hoàn tất. Có thể mở trực tiếp `/candidate/skill-gap` hoặc `/candidate/learning` để chọn JD,
   CV đã xác nhận nếu cần và bài completed cùng JD; bỏ lựa chọn bài sẽ dùng bài completed gần nhất
   của đúng JD nếu có.
5. Bấm **Tìm kỹ năng thiếu và cách học** để tạo kết quả. Mỗi thẻ là một skill cần luyện/chưa có
   bằng chứng, kèm lý do và nội dung học riêng. Không có skill cần bổ sung thì hiển thị kết quả rỗng.

Tab **Trải nghiệm demo** dùng tài khoản/dữ liệu mẫu và giữ các màn hình demo cũ; kết quả ở chế độ đó
không phải kết quả Gemini hay dữ liệu học bù của tài khoản hệ thống. Các chức năng khác trên frontend
có thể vẫn là demo; hướng dẫn này chỉ mô tả luồng backend đã kết nối ở các route nêu trên.
Nếu chưa có tài khoản backend, dùng luồng đăng ký/xác minh trong [hướng dẫn Postman](postman-test-order.md)
hoặc tài khoản Candidate backend đã được cấp. Backend API mặc định ở `http://localhost:8080/api/v1`;
frontend đọc `VITE_API_BASE_URL` khi cần đổi địa chỉ.

## Dữ liệu và quyền để thử bằng Postman

- `baseUrl`: `http://localhost:8080/api/v1`.
- `candidateAccessToken`: access token của Candidate đang đăng nhập, không dùng refresh token.
- `jobId`: UUID thật lấy từ `GET /jobs`; JD phải active, chưa hết hạn, có nội dung và kỹ năng đã trích xuất.
- `resumeId`: tùy chọn, UUID CV của chính Candidate, trạng thái `READY` sau xác nhận nội dung/kỹ năng.
- `assessmentId`, `questionId`, `roadmapId`: lấy từ response tương ứng, không dùng UUID ví dụ cố định.

Các request cá nhân bên dưới chọn **Authorization → Bearer Token → `{{candidateAccessToken}}`**.
JSON dùng **Body → raw → JSON** và `Content-Type: application/json`. `GET /jobs` và `GET /jobs/{id}`
là API danh mục public. Request tạo/nộp bài và kế hoạch dùng tài khoản Candidate.
Các response là object/array trực tiếp; field response chủ yếu dùng snake_case, request dùng camelCase.

Nếu cần khai báo Java/SQL cho ví dụ trên, tra UUID kỹ năng bằng `GET {{baseUrl}}/skills`, rồi cập nhật
hồ sơ ở giao diện hoặc `PUT /me/career-profile`. Giữ các thông tin hồ sơ hiện có khi gửi PUT;
mảng `skills` mới thay thế toàn bộ danh sách kỹ năng cũ. Không gửi tên skill thay cho `skillId`.

## Thứ tự kiểm thử

### 1. Chọn một JD thật

```http
GET {{baseUrl}}/jobs?q=Java&limit=20&offset=0
GET {{baseUrl}}/jobs/{{jobId}}
```

Danh sách trả mảng job; lưu `id` của job muốn chuẩn bị vào `jobId`. Chi tiết job có `job_title`, nội dung JD
và `skills`. Hai JD cùng tên nhưng khác UUID vẫn là hai đối tượng đánh giá riêng.
Nếu danh mục chưa có JD phù hợp, cần Admin import/crawl và kiểm tra kỹ năng đã trích xuất trước.
Luồng assessment/học bù không đòi hỏi job có embedding vector.

### 2. Xem kiến thức cần chuẩn bị trước khi làm bài

```http
GET {{baseUrl}}/assessments/preparation?jobId={{jobId}}
```

Response chứa `job_id`, `job_title` và `skills`; mỗi phần tử có `skill_name`, `is_required`, `resources`.
Resource có thể chứa `id`, `title`, `url`, `provider`, `resource_type`, `is_free`. Danh sách resource có thể
rỗng nếu catalog chưa có tài liệu phù hợp. Mở URL được trả về để đọc trước khi bắt đầu bài kiểm tra.

Đây là **danh sách kiến thức của JD để chuẩn bị**, chưa phải kết luận kỹ năng nào người dùng thiếu.
Endpoint đọc catalog, không gọi model và không tạo assessment. Chọn JD mới thì tải lại tài liệu cho JD mới.

### 3. Sinh bài đánh giá từ JD đã chọn

```http
POST {{baseUrl}}/assessments/generations
```

```json
{
  "jobId": "{{jobId}}"
}
```

Response **202 Accepted** trả tác vụ, ví dụ:

```json
{
  "id": "{{generationId}}",
  "job_id": "{{jobId}}",
  "status": "QUEUED",
  "assessment_id": null,
  "error_code": null,
  "message": null
}
```

Lưu `id` vào `generationId`, rồi đọc trạng thái khoảng mỗi 2–3 giây:

```http
GET {{baseUrl}}/assessments/generations/{{generationId}}
GET {{baseUrl}}/assessments/generations?jobId={{jobId}}
```

GET theo mã tác vụ trả object; GET danh sách trả mảng tác vụ gần nhất cho mỗi JD (tối đa 20 JD nếu không
lọc `jobId`). Chỉ tài khoản sở hữu được đọc. Trạng thái là `QUEUED`, `RUNNING`, `SUCCEEDED` hoặc `FAILED`.
Khi `SUCCEEDED`, lấy `assessment_id` làm `assessmentId` và gọi `GET /assessments/{{assessmentId}}`.
Khi `FAILED`, hiển thị `message` và chỉ tạo lại khi người dùng chủ động bấm. `AI_TIMEOUT` nghĩa là
model vượt thời gian chờ; `INTERRUPTED` nghĩa là worker bị gián đoạn, không tự chạy lại để tránh trừ quota lặp.

Gửi POST lại khi tác vụ cùng tài khoản/JD đang chờ/chạy trả về **cùng mã tác vụ**, không tạo thêm lời gọi AI.
Hàng đợi giới hạn 3 tác vụ đang hoạt động mỗi tài khoản, 50 toàn hệ thống; quá giới hạn trả `429`.
Tác vụ đã hoàn tất trả bài assessment có `status: "in_progress"`, `market_job_id`
bằng `jobId`, **10 câu hỏi**: 5 câu nền tảng (`part: 1`) và 5 câu kỹ thuật theo JD (`part: 2`).
Mỗi câu có `id`, `question_text`, `options` A/B/C/D, `related_skill_name` và `sequence_order`.
AI phải chọn skill có trong JD và cung cấp trích dẫn JD để backend kiểm tra trước khi lưu;
trích dẫn kiểm tra nội bộ này không phải field được đảm bảo trong response câu hỏi.
Đáp án đúng và giải thích bị ẩn khi bài chưa nộp.

`jobId` là bắt buộc. Không còn tạo bài bằng body chỉ có `targetJobTitle`. Nếu gửi thêm `targetJobTitle`,
nó phải khớp tên JD được chọn; cách đơn giản là chỉ gửi `jobId` như trên. API key/model và quota Gemini cần sẵn sàng,
AI được bật và tài khoản còn quota. Tác vụ nền không đảm bảo AI luôn trả kết quả hợp lệ hoặc nhanh;
thời gian còn phụ thuộc phần cứng, cold start và tải model. Tác vụ chờ được lưu trong database; tác vụ
đang chạy bị gián đoạn chuyển lỗi khi hết lease 30 phút, thay vì âm thầm gọi lại model.

Endpoint đồng bộ cũ `POST /assessments` vẫn được giữ để tương thích Postman/client cũ, nhưng phải chờ model
trong một request. FE dùng luồng tác vụ nền ở trên để tránh timeout của browser/proxy.

Model sinh câu hỏi được cấu hình bằng `AI_ASSESSMENT_MODEL=gemini-2.5-flash-lite` và `AI_ASSESSMENT_MAX_OUTPUT_TOKENS=12000`. Client có timeout `AI_REQUEST_TIMEOUT=PT2M`, không tự retry lời gọi thất bại. Các chức năng CV/chat/hướng dẫn học dùng `AI_CHAT_MODEL=gemini-2.5-flash-lite`; embedding dùng `gemini-embedding-001` với 1024 chiều. Đặt `GEMINI_API_KEY` trong backend `.env` và khởi động lại sau khi đổi cấu hình. Xem [hướng dẫn Gemini](gemini.md).

### 4. Đọc và trả lời từng câu

```http
GET {{baseUrl}}/assessments/{{assessmentId}}
PUT {{baseUrl}}/assessments/{{assessmentId}}/answers
```

Body của **mỗi lần lưu một câu**:

```json
{
  "questionId": "{{questionId}}",
  "selectedOption": "A"
}
```

Thay `questionId` bằng `questions[].id` thật và chọn đáp án theo câu hỏi. Gọi đủ mọi câu;
không gửi một mảng đáp án vào endpoint này. Có thể sửa đáp án trước khi submit.
Response lưu đáp án có `question_id`, `selected_option`.

### 5. Nộp bài và xem điểm từng kỹ năng

```http
POST {{baseUrl}}/assessments/{{assessmentId}}/submit
```

Không gửi body. Thành công có `status: "completed"`, `skill_scores[]` gồm `skill`, `total`, `correct`,
`score` tính trên thang 100. Câu hỏi lúc này có đáp án đúng và giải thích để người dùng ôn lại.
Nguồn học sau bài đánh giá tập trung vào skill cần luyện, không trả chương trình nghề đầy đủ.
Nộp thiếu câu trả lời trả `400`; nộp lại bài đã hoàn tất trả kết quả đã lưu.
Hoàn tất bài mới đánh dấu kế hoạch cũ của **cùng JD** là `is_outdated`; không tự tạo kế hoạch mới.

### 6. Tạo hướng dẫn học từng kỹ năng cần bổ sung

```http
POST {{baseUrl}}/roadmaps
```

```json
{
  "jobId": "{{jobId}}",
  "resumeId": "{{resumeId}}",
  "assessmentId": "{{assessmentId}}"
}
```

Nếu không dùng CV, bỏ hẳn `resumeId`; không để biến Postman chưa có giá trị. `assessmentId` tùy chọn:
bỏ field này thì backend tìm **bài completed gần nhất của chính Candidate cho cùng `jobId`**.
Nếu chưa có bài completed, hệ thống vẫn có thể đối chiếu bằng hồ sơ/CV, nhưng kết quả không có bằng
chứng điểm kiểm tra. Luồng đề nghị là hoàn tất bài đánh giá trước rồi mới tạo kế hoạch.
Assessment được gửi rõ phải thuộc người đăng nhập, đã hoàn tất và liên kết đúng JD;
trùng tên công việc chưa đủ. `targetJobTitle` không thay thế `jobId`.

Lưu `id` vào `roadmapId`. Response mới có:

- `learning_scope: "MISSING_SKILLS_ONLY"`.
- `missing_skills[]`: mỗi skill có `skill_name`, `gap_status`, `reasoning`, có `assessment_score` nếu đã được chấm,
  và `nodes[]` hướng dẫn chỉ cho skill đó.
- `nodes[]`, `skill_gaps[]`, `gap_analysis_id`: dữ liệu để theo dõi và truy vết kế hoạch.
- `learning_recommendations`: tài liệu theo skill; `roadmaps: []`, không ghép chương trình học nghề đầy đủ.

Khi không tìm thấy skill bắt buộc nào cần bổ sung theo bằng chứng hiện có, kết quả có
`missing_skills: []`, `nodes: []`, `status: "completed"`, `progress_percent: 100` và **không gọi AI sinh kế hoạch**.
Đây là kết quả đối chiếu dữ liệu, không phải chứng nhận người dùng đã thành thạo toàn bộ nghề.

### 7. Mở lại kế hoạch và cập nhật tiến độ

```http
GET {{baseUrl}}/roadmaps
GET {{baseUrl}}/roadmaps/{{roadmapId}}
PATCH {{baseUrl}}/roadmaps/{{roadmapId}}/nodes/{{roadmapNodeId}}
```

Body PATCH:

```json
{
  "status": "completed"
}
```

`status` nhận `not_started`, `in_progress`, `completed`. Node phải thuộc kế hoạch của Candidate;
hoàn tất prerequisite trước khi chuyển node phụ thuộc sang đang học/hoàn tất. Các link và nguồn học
được lưu snapshot để mở lại kết quả đã tạo. Bản kế hoạch cũ có
`learning_scope: "LEGACY_FULL_ROADMAP"`; hệ thống giữ lịch sử và không đổi nhãn thành kết quả chỉ học bù.
Muốn có kết quả mới thì chọn JD và tạo kế hoạch mới.

## Các trường hợp cần kiểm tra

| Tình huống | Kết quả mong đợi |
| --- | --- |
| Tạo assessment hoặc kế hoạch không có `jobId` | `400` |
| JD không còn active, hết hạn hoặc không tồn tại | Không được dùng để sinh bài/kế hoạch mới |
| JD không có kỹ năng đã trích xuất / không có nội dung để sinh câu hỏi | Từ chối và yêu cầu hoàn thiện dữ liệu JD |
| AI trả câu hỏi ngoài skill JD, trích dẫn không có trong JD hoặc sai cấu trúc 5 + 5 | `502`; không lưu bài không hợp lệ |
| Bài chưa trả lời đủ | Submit `400` |
| Kế hoạch gửi assessment của JD khác | `400`, kể cả hai JD có cùng tên |
| Hồ sơ khai Java nhưng điểm Java cùng JD là 50 | Java vẫn `NEEDS_PRACTICE` |
| Skill không có bằng chứng và chưa được hỏi | `MISSING_EVIDENCE`, không tuyên bố đã đo được việc thiếu kiến thức |
| AI thêm skill đã có bằng chứng/ngoài whitelist cần học | `502`; không lưu kế hoạch sai phạm vi |
| Không còn skill cần bổ sung | Kế hoạch rỗng đã hoàn tất; không sinh lộ trình nghề tổng quát |

Các bảng và ví dụ trên là hợp đồng/cách kiểm thử, không phải chứng cứ đã chạy inference thành công
trên mọi JD. Chất lượng câu hỏi và hướng dẫn vẫn cần đánh giá bằng JD, đáp án và dữ liệu thực tế.

## Giới hạn của việc đối chiếu

- So sánh skill dùng tên đã chuẩn hóa rồi khớp chính xác, chưa suy luận mọi tên đồng nghĩa. Các cách gọi
  khác nhau cần được chuẩn hóa về cùng skill khi nhập/trích xuất dữ liệu; nếu không có thể xuất hiện
  kết quả `MISSING_EVIDENCE` dù người dùng đã khai báo một tên tương đương.
- Kỹ năng tự khai trong hồ sơ/CV là bằng chứng khai báo, chưa phải năng lực đã được kiểm chứng.
  Điểm dưới 70 trong bài đã hoàn tất cho cùng JD vẫn ưu tiên đánh dấu `NEEDS_PRACTICE`, kể cả đã khai báo skill.
- Tài liệu chuẩn bị và câu hỏi có thể bao gồm skill tùy chọn của JD để người dùng ôn tập.
  Kế hoạch học bù chỉ đối chiếu các skill **bắt buộc**; vì vậy không phải mọi skill xuất hiện trong bài
  đều trở thành mục cần học trong kế hoạch.
- Validator kiểm số câu, cấu trúc, skill/trích dẫn JD và câu hỏi trùng; chưa kiểm chứng mọi đáp án
  về chuyên môn. Câu hỏi phải kiểm tra kiến thức cần cho JD, không chỉ việc nhớ nội dung JD.
  cần rà soát độc lập đáp án và giải thích trước khi dùng điểm làm bằng chứng năng lực.
  Giao diện ghi rõ điểm dùng để định hướng luyện tập. Chuyển model/prompt không tự chứng minh đã giải quyết giới hạn này.

## Kết quả kiểm tra ngày 23/10/2026

- Backend `mvn -B verify`: **BUILD SUCCESS**, 731 test được ghi nhận, **675 đạt, 56 bỏ qua**, không có failure/error.
  Các test tích hợp mới cho bài đánh giá, tài liệu chuẩn bị và học từng skill đã chạy với PostgreSQL/pgvector
  trong Docker; AI trong những test nghiệp vụ này được mock. Log local: `target/jd-learning-verify.log`.
- Frontend: **29 kiểm tra adapter/SSR đạt**, lint phần thay đổi và TypeScript/Vite build thành công;
  vẫn có cảnh báo kích thước bundle. Log nằm trong `FE/FA26SE119-FE/skill-learning-*.log`.
- Chưa chạy tương tác trình duyệt hoặc toàn bộ luồng đăng nhập → làm bài → học bù trên tài khoản thật.
  Những kết quả này được kiểm bằng test API tích hợp và render/adapter, không được ghi nhận là browser E2E.
- Không thêm migration cho chức năng này; hệ thống giữ schema V13 hiện có.
- Backend Docker local đã cập nhật: JAR trong container khớp SHA-256 của JAR vừa verify, health `UP`,
  OpenAPI có endpoint chuẩn bị và yêu cầu `jobId` cho assessment/kế hoạch. Danh mục job trả `200`;
  request chưa đăng nhập tới các API cá nhân trả `401`. Kết quả local: `target/jd-learning-app-runtime.json`.
