# Khóa học YouTube/Coursera do Admin duyệt

Admin nhập link cùng mô tả và kỹ năng, kiểm tra nội dung rồi duyệt. Hệ thống chỉ đề xuất khóa đã duyệt.
Khóa phù hợp được đưa vào gợi ý học tập, gắn với node của lộ trình và có thể được AI chat truy xuất qua RAG.

## Quy trình

1. Thêm khóa học: trạng thái `PENDING_REVIEW`, chưa xuất hiện trong gợi ý.
2. Admin kiểm tra nguồn, nội dung, ngôn ngữ, chi phí và kỹ năng rồi gọi `approve`.
3. Hệ thống tạo đồng thời tài nguyên học tập và tài liệu RAG chứa mô tả cùng URL.
   Khóa xuất hiện trong gợi ý học tập ngay sau khi duyệt, không cần gọi AI hoặc index embedding.
4. Gọi `index` để tài liệu được tìm bằng RAG trong AI chat. Bước này sử dụng embedding provider đã cấu hình.
5. Gọi `archive` khi muốn ngừng đề xuất: tắt cả tài nguyên học tập và tài liệu RAG.
   Link đã lưu trong lộ trình hoặc nguồn của tin nhắn cũ được giữ lại.

Admin là người đánh giá độ uy tín. Backend kiểm tra định dạng và tên miền của URL, không tự chấm chất lượng,
không tải transcript video hay tự lấy nội dung khóa học từ website. Mô tả và danh sách kỹ năng do Admin nhập.

## API quản trị

Các endpoint yêu cầu access token có role `ADMIN`.

| Method | Đường dẫn | Chức năng |
| --- | --- | --- |
| `POST` | `/api/v1/admin/rag/courses` | Thêm khóa chờ duyệt |
| `GET` | `/api/v1/admin/rag/courses` | Danh sách 200 khóa gần nhất |
| `POST` | `/api/v1/admin/rag/courses/{id}/approve` | Duyệt và đưa vào nguồn gợi ý |
| `POST` | `/api/v1/admin/rag/courses/{id}/index` | Index RAG cho khóa đã duyệt |
| `POST` | `/api/v1/admin/rag/courses/{id}/archive` | Ngừng đề xuất |

Body khi thêm khóa; thay URL minh họa bằng link thực tế đã kiểm tra:

```json
{
  "title": "Khóa Java căn bản đã kiểm duyệt",
  "url": "https://www.coursera.org/learn/YOUR_COURSE_SLUG",
  "description": "Nêu nội dung, trình độ đầu vào và lý do khóa này phù hợp để học Java.",
  "skills": ["Java", "Object Oriented Programming"],
  "language": "en",
  "isFree": false
}
```

`title`, `url`, `description`, `skills`, `language`, `isFree` đều bắt buộc. `skills` có 1–30 tên kỹ năng;
đặt tên khớp với kỹ năng cần học để hệ thống chọn nguồn phù hợp. `isFree` là thông tin do Admin kiểm tra,
không được suy đoán từ nhà cung cấp.

URL được hỗ trợ:

- YouTube: `youtube.com/watch?v=...`, `youtube.com/playlist?list=...`, `youtu.be/...`;
  chấp nhận host `www.youtube.com` và `m.youtube.com` tương ứng.
- Coursera: `/learn/...`, `/specializations/...`, `/professional-certificates/...`, `/projects/...`,
  `/guided-projects/...` trên `coursera.org` hoặc `www.coursera.org`.

Chỉ nhận HTTPS. Tracking query và fragment được loại bỏ; link rút gọn YouTube được chuẩn hóa thành link watch.
Link trùng sau chuẩn hóa trả `409`, kể cả khóa đã archive. Link sai định dạng hoặc tên miền giả trả `400`.
Gọi lại approve cho khóa đã duyệt hoặc archive cho khóa đã archive không tạo tài liệu/tài nguyên trùng.
Khóa đã archive không được duyệt lại qua endpoint approve.

Các tài liệu RAG văn bản cũ tiếp tục dùng `/api/v1/admin/rag/documents`.
Để nhập khóa học có quy trình duyệt và dùng trong roadmap, sử dụng endpoint `/rag/courses` mới.

## Gợi ý và lưu lịch sử

Candidate có thể xem nguồn phù hợp mà không cần AI:

```http
GET /api/v1/roadmaps/recommendations?targetJobTitle=Java%20Developer&skills=Java
Authorization: Bearer <candidate-token>
```

Các khóa đã duyệt xuất hiện trong `resources`, được ưu tiên trước tài nguyên catalog thông thường.
Khi tạo lộ trình, hệ thống chọn tài nguyên theo kỹ năng của node, lưu bản chụp tài nguyên và
`learning_recommendations`. Đọc lại lộ trình trả bản đã lưu; đổi catalog hoặc archive khóa không làm mất
link trong lịch sử. Quan hệ tài nguyên có sẵn trước migration được chụp theo dữ liệu tại thời điểm migration;
không thể phục hồi URL đã bị đổi trước đó.

AI chat dùng các tài liệu đã index khi Candidate gửi tin với `retrieve: true`.
Nếu khóa phù hợp được truy xuất, AI nhận cả URL và provider, response `sources` có `source_url` và
`source_provider`. Hai trường này được lưu theo câu trả lời, không đọc lại URL hiện hành của catalog.
Tài liệu chưa index không tham gia tìm kiếm RAG; chat Guest giữ cơ chế hiện có và không truy xuất các khóa này.

## Database và triển khai

Flyway bổ sung hai migration mới, giữ nguyên migration đã có:

- `V8__curated_course_links.sql`: bảng `rag_course_links`, thông tin người duyệt, liên kết catalog/RAG,
  cột URL/provider trong nguồn của câu trả lời.
- `V9__learning_recommendation_snapshots.sql`: bản chụp gợi ý và tài nguyên gắn với node.

Chạy migration khi triển khai backend trước khi gọi API mới. Repository này là backend;
frontend quản trị cần gọi các API trên để tạo màn hình nhập, duyệt và archive khóa học.

## Kết quả kiểm thử 15/10/2026

Nhóm test liên quan ghi nhận **90 test, 89 đạt, 0 lỗi, 1 bỏ qua**. Bao gồm 19 test luồng API có sẵn,
hai test API mới cho duyệt khóa học/quyền truy cập/lịch sử link, kiểm thử SQL thật cho lựa chọn nguồn và snapshot,
kiểm tra URL, lifecycle và nguồn chat. Log nằm tại `target/course-feature-tests.log`.

Database kiểm thử là cluster riêng trong `target/course-link-test-pg`, chỉ nghe `127.0.0.1:55449`.
V8/V9 được thực thi trên PostgreSQL; bản schema kiểm thử cục bộ dùng mảng thay vector cho các bảng có sẵn.
Test retrieval cần pgvector được bỏ qua vì extension chưa có. AI/embedding được mock trong kiểm thử API;
chưa gọi provider thật hoặc xác minh khả dụng của các link khóa học ngoài hệ thống.
