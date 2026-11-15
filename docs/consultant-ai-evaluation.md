# Career Consultant: AI đánh giá CV và ATS CV–job

Career Consultant được phân công có thể chọn CV đã xác nhận của candidate, tạo báo cáo chất lượng CV và so sánh CV với một job. Báo cáo được lưu trong lịch sử của candidate để hai bên xem lại. Đây là API backend; frontend gọi các endpoint dưới đây trong màn hình consultation.

Base URL: `http://localhost:8080/api/v1`. Mọi request trong luồng này gửi `Authorization: Bearer <accessToken-consultant>`. Request JSON dùng camelCase; các trường dữ liệu trả về từ database dùng snake_case. Những UUID và nội dung báo cáo mẫu bên dưới chỉ minh họa hợp đồng, không phải kết quả đánh giá model thật.

## Điều kiện thực hiện

- Tài khoản gọi là `CAREER_CONSULTANT`, trạng thái `ACTIVE`, và hiện được phân công cho thread.
- Candidate trong thread chưa bị xóa; CV được chọn thuộc candidate đó và có trạng thái `READY`.
- Hai thao tác tạo báo cáo yêu cầu thread `OPEN`. Thread đã đóng vẫn đọc được danh sách READY CV và báo cáo trong phạm vi quyền đọc hiện có.
- ATS nhận job `ACTIVE`, chưa hết `valid_through`. Nếu job có bản ghi nguồn, phải còn ít nhất một nguồn `ACTIVE` và chưa hết hạn.
- Tạo báo cáo mới cần AI được bật và cấu hình model hoạt động. ATS có thể trả lại cache hợp lệ khi AI đã tắt vì không phát sinh suy luận mới.

Luồng dùng nội dung CV đã xác nhận, JD và danh sách kỹ năng của job. Không cần tạo embedding CV/job, đợi worker index hoặc thay đổi schema database.

## 1. Chọn CV của candidate trong thread

```http
GET /api/v1/consultations/<THREAD_ID>/candidate-resumes
Authorization: Bearer <accessToken-consultant>
```

Response `200` là danh sách metadata của tối đa 100 CV `READY`, CV primary trước, sau đó theo `updated_at DESC, id DESC`. Không có CV phù hợp thì trả `[]`.

```json
[
  {
    "id": "11111111-1111-4111-8111-111111111111",
    "title": "Backend Java CV",
    "language": "vi",
    "status": "READY",
    "is_primary": true,
    "updated_at": "2026-10-15T03:00:00Z"
  }
]
```

Danh sách không trả raw text, structured content, file path hoặc URL tải file. Muốn xem nội dung CV đã xác nhận, dùng endpoint hiện có:

```http
GET /api/v1/consultations/<THREAD_ID>/candidate-profile?resumeId=11111111-1111-4111-8111-111111111111
Authorization: Bearer <accessToken-consultant>
```

## 2. AI đánh giá chất lượng CV

```http
POST /api/v1/consultations/<THREAD_ID>/resumes/11111111-1111-4111-8111-111111111111/analyze
Authorization: Bearer <accessToken-consultant>
```

Không gửi body. AI chạy feature `CV_ANALYZE`; response `200` chứa các trường sau. Mảng `suggestions` chứa các gợi ý được lưu trong database; bản ghi gợi ý còn có metadata như `id`, `analysis_id`, `accepted` và thời điểm tạo.

```json
{
  "id": "22222222-2222-4222-8222-222222222222",
  "resume_id": "11111111-1111-4111-8111-111111111111",
  "analysis_type": "AI_QUALITY",
  "overall_score": 76,
  "ats_score": 80,
  "summary": "CV nêu rõ kỹ năng backend nhưng cần bổ sung kết quả dự án.",
  "strengths_json": ["Có dự án sử dụng Java và Spring Boot"],
  "issues_json": ["Mô tả kinh nghiệm chưa nêu tác động đo lường được"],
  "suggestions": [
    {
      "category": "experience",
      "severity": "medium",
      "original_content": "Phát triển REST API",
      "suggested_content": "Bổ sung phạm vi API và kết quả thực tế có thể xác minh.",
      "explanation": "Làm rõ đóng góp và tác động của công việc."
    }
  ],
  "model_name": "<configured-chat-model>",
  "created_at": "2026-10-15T03:05:00Z"
}
```

`overall_score` và `ats_score` ở đây đánh giá CV nói chung; request chưa có job. Muốn phân tích phù hợp với JD cụ thể, dùng bước 3. Luồng consultant không thay thế AI bằng điểm kiểm tra số mục khi AI tắt.

## 3. AI so sánh CV với một job

Lấy `jobId` từ `GET /api/v1/jobs` hoặc màn hình chọn job rồi gọi:

```http
POST /api/v1/consultations/<THREAD_ID>/jobs/33333333-3333-4333-8333-333333333333/ats
Authorization: Bearer <accessToken-consultant>
Content-Type: application/json
```

```json
{"resumeId":"11111111-1111-4111-8111-111111111111"}
```

Không nhận `candidateId` hoặc `consultantId` từ body: backend xác định candidate từ thread và consultant từ phiên đăng nhập. Feature `JOB_ATS` nhận nội dung CV đã xác nhận, ngôn ngữ CV, JD và danh sách kỹ năng yêu cầu/ưu tiên của job.

Response `200`:

```json
{
  "id": "44444444-4444-4444-8444-444444444444",
  "user_id": "55555555-5555-4555-8555-555555555555",
  "resume_id": "11111111-1111-4111-8111-111111111111",
  "market_job_id": "33333333-3333-4333-8333-333333333333",
  "match_result_id": null,
  "similarity_score": null,
  "ats_score": 72,
  "strengths_json": ["Java và Spring Boot phù hợp yêu cầu backend"],
  "missing_skills_json": ["Chưa có bằng chứng kinh nghiệm AWS trong CV"],
  "improvement_suggestions_json": ["Làm rõ kinh nghiệm triển khai ứng dụng nếu có"],
  "model_name": "<configured-chat-model>",
  "created_at": "2026-10-15T03:06:00Z",
  "cached": false
}
```

`user_id` là candidate sở hữu báo cáo. `ats_score` nằm trong 0–100; `similarity_score` không được điền bằng điểm embedding giả. `improvement_suggestions_json` luôn là mảng trong response, không chứa object metadata cache hay `input_hash`.

Cache ATS lưu trong database theo candidate, CV, job và hash của canonical CV text, ngôn ngữ CV, snapshot các trường job được truy vấn (gồm JD và `raw_content_hash`), danh sách skills có thứ tự, AI provider/model và phiên bản phân tích `consultation-ats-v1`. Gọi lại với cùng đầu vào trả cùng báo cáo với `cached=true`, không gọi AI hoặc trừ thêm quota cho suy luận. Đổi các đầu vào trên làm lần gọi sau tạo phân tích mới. Phiên bản prompt Admin hiện không nằm riêng trong hash; chỉ sửa prompt mà không đổi các đầu vào trên chưa tự vô hiệu hóa cache này.

## 4. Đọc lịch sử và gửi lời tư vấn

```http
GET /api/v1/consultations/<THREAD_ID>/candidate-analyses?resumeId=11111111-1111-4111-8111-111111111111&jobId=33333333-3333-4333-8333-333333333333&limit=20&offset=0
Authorization: Bearer <accessToken-consultant>
```

Báo cáo mới xuất hiện trong `resume_analyses` hoặc `ats_analyses`. Candidate cũng đọc được báo cáo chất lượng qua `GET /api/v1/resumes/{resumeId}/analyses` bằng token của chính mình. Quyền đọc candidate-analyses vẫn dành cho owner/admin/assigned consultant như trước; ba endpoint mới có yêu cầu vai trò consultant riêng.

Kết quả AI không tự trở thành tin nhắn, không sửa CV và không ứng tuyển. Consultant xem báo cáo rồi gửi nội dung tư vấn bằng `POST /consultations/{id}/messages` hiện có. Lần gọi model ghi nhận usage cho consultant thực hiện; vai trò `CAREER_CONSULTANT` được miễn quota token và số lượt gọi AI của gói. Quyền sở hữu báo cáo thuộc candidate. Audit lưu actor, thread, candidate, CV và job khi có.

## Lỗi và cách kiểm tra

| HTTP | Trường hợp |
| --- | --- |
| `400` | UUID sai định dạng; body ATS thiếu `resumeId`, JSON không hợp lệ hoặc UUID không hợp lệ |
| `401` | Không có access token hợp lệ |
| `403` | Người gọi không phải `CAREER_CONSULTANT` |
| `404` | Thread ngoài phạm vi phân công; candidate đã xóa; CV không READY/sai owner; job không ACTIVE, hết hạn hoặc không còn nguồn hợp lệ |
| `409` | Gọi POST analyze/ATS khi thread đã đóng |
| `429` | Nhà cung cấp AI giới hạn tần suất hoặc quota; consultant được miễn quota của gói ứng dụng |
| `502` | Provider thất bại hoặc output AI không hợp lệ |
| `503` | Cần suy luận mới nhưng AI chưa bật/cấu hình chưa sẵn sàng |

Lỗi nghiệp vụ trả `application/problem+json` với `status` và `detail`; lỗi validation có thể thêm `errors`. Lỗi từ authentication/authorization filter có thể dùng `application/json` với `status`, `message`, theo hợp đồng chung của API.

Để kiểm tra bằng Swagger/Postman, tạo và confirm một CV bằng token candidate, tạo consultation, phân công consultant bằng token admin rồi thực hiện lần lượt bốn bước trên bằng token consultant. Gọi ATS hai lần để kiểm tra `cached`, xem candidate-analyses và lịch sử CV bằng token candidate, sau đó kiểm tra `403/404/409` bằng token sai vai trò, consultant khác và thread đã đóng. Chọn CV của candidate khác hoặc job đã hết hạn cũng phải bị từ chối. Không dùng các UUID minh họa nếu database chưa có bản ghi tương ứng.

Kiểm thử tự động với AI mock xác minh quyền, hợp đồng đầu ra, lưu dữ liệu, cache, actor trong audit và quyền sở hữu báo cáo. Quota reservation và usage log không được chạy xuyên suốt trong các test này vì `AiService` được mock. Các test này không đo độ chính xác của điểm CV/ATS từ model thật. Muốn đánh giá chất lượng, cần bộ cặp CV–JD có người đánh giá đối chiếu và chạy provider thật với cấu hình model/prompt cụ thể; tài liệu này không công bố kết quả benchmark live.
