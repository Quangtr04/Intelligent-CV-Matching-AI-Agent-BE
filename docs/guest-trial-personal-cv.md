# Guest trial: tạo CV từ thông tin cá nhân

`POST /api/v1/guest/trials/build` nhận header `X-Guest-Session` từ
`POST /api/v1/guest/sessions`. Guest có thể điền thông tin, nhờ AI đề xuất cách diễn đạt,
chọn/sửa đề xuất và xem lại CV trước khi đăng ký tài khoản.

Chọn template từ `GET /api/v1/guest/templates`. Backend chỉ chấp nhận template đang hoạt động
và được bật cho trial (`is_trial_enabled`).

## Điền hoặc chỉnh sửa CV không dùng AI

```json
{
  "template_id": "<trial-template-uuid>",
  "language": "vi",
  "use_ai": false,
  "structured_data": {
    "full_name": "Nguyễn An",
    "email": "an@example.com",
    "position": "Java Developer",
    "summary": "Đã phát triển REST API bằng Java và Spring Boot trong dự án tốt nghiệp.",
    "skills": ["Java", "SQL"],
    "projects": [
      {
        "project_name": "Ứng dụng quản lý học tập",
        "role": "Backend developer",
        "description": "Xây dựng API quản lý khóa học và tiến độ học.",
        "technologies": ["Java", "Spring Boot", "PostgreSQL"]
      }
    ]
  },
  "raw_content": "Thông tin bổ sung do người dùng tự nhập."
}
```

`structured_data` dùng cùng schema CV chuẩn của candidate: `full_name`, `email`, `phone`,
`location`, `summary`, `position`, `experience`, `education`, `projects`, `certifications`,
`skills`, `frontend_fields`. Backend chuẩn hóa khoảng trắng, kiểu dữ liệu, ngày tháng,
kỹ năng và các giới hạn nội dung; không đọc CV của tài khoản khác qua ID.
Các trường ngoài schema không trở thành thuộc tính CV.

Personal mode yêu cầu `structured_data` là object và có nội dung CV sau chuẩn hóa hoặc
`raw_content` không rỗng. `raw_content` tùy chọn, tối đa 200.000 ký tự. `language` nhận
`vi`/`en` (cũng chấp nhận dạng viết hoa), mặc định theo template.

Nếu không truyền `use_ai` ở personal mode, backend mặc định `false`. Phản hồi chứa:

```json
{
  "trial": true,
  "mode": "PERSONAL",
  "template_id": "<trial-template-uuid>",
  "language": "vi",
  "status": "DRAFT",
  "persisted": false,
  "ai_generated": false,
  "structured_data": { "full_name": "Nguyễn An", "summary": "...", "skills": [] },
  "raw_content": "Thông tin bổ sung do người dùng tự nhập.",
  "registration_prompt": "Đăng ký để tạo và lưu CV cá nhân."
}
```

Đây là bản chỉnh sửa để hiển thị và xem lại, không phải CV đã lưu hoặc đã xác nhận.
Không gọi AI và không trừ quota AI cho bước nhập/chỉnh sửa này; session và template vẫn phải hợp lệ.

## Xin gợi ý AI và xem lại

Gửi cùng request với `use_ai: true`. Backend chuyển **dữ kiện CV đã chuẩn hóa** và
`raw_content` cho `CV_BUILD` bằng guest identity. Quy tắc AI hiện có yêu cầu chỉ dùng dữ kiện
được cung cấp, không bịa kinh nghiệm, công ty hoặc bằng cấp.

Phản hồi giữ `structured_data` là dữ liệu người dùng đã nhập và thêm
`suggested_structured_data` là bản đề xuất AI. `status` trở thành `REVIEW_REQUIRED` và
`ai_generated: true`. Giao diện có thể đặt hai bản cạnh nhau để người dùng chấp nhận,
từ chối hoặc sửa từng phần. AI không tự ghi đè bản gốc hoặc tự xác nhận nội dung là đúng.

Sau khi người dùng chọn/sửa, gửi bản cuối vào `structured_data` với `use_ai: false`
để chuẩn hóa và xem lại mà không gọi model thêm lần nữa. Những trường không được AI
trả về (ví dụ email hoặc số điện thoại) cần giữ từ bản gốc khi ghép bản cuối.

Lượt AI dùng cơ chế reserve/settle quota guest hiện có; client không được quyết định số token.
Hết hạn mức trả HTTP 429 với thông điệp đăng ký tài khoản. Lỗi inference được xử lý theo
cơ chế hoàn/ghi nhận usage của `AiService`.

CV cá nhân trial không được tạo vào bảng `resumes`, không có `resume_id`, PDF hoặc embedding.
Sau đăng ký, xác minh email và đăng nhập, client có thể gửi dữ liệu cuối qua API tạo CV candidate
để lưu và đi tiếp luồng xác nhận CV. Backend không tự chuyển dữ liệu trial sang tài khoản mới.

## Tương thích chế độ demo hiện có

Request cũ vẫn hoạt động:

```json
{
  "template_id": "<trial-template-uuid>",
  "demo_resume_id": "<system-demo-resume-uuid>"
}
```

Demo mode mặc định gọi AI như trước và trả bản được dựng trong `structured_data`.
Có thể đặt `use_ai: false` để chỉ chuẩn hóa/xem lại dữ liệu demo.
Chỉ CV hệ thống `is_demo=true`, `user_id IS NULL`, `status=READY` được dùng làm demo.

Một request phải chọn đúng một nguồn: `demo_resume_id` **hoặc** `structured_data`.
Trộn demo với thông tin cá nhân (kể cả `raw_content`) hoặc bỏ cả hai nguồn trả HTTP 400.
Request không hợp lệ bị từ chối trước khi gọi AI.
