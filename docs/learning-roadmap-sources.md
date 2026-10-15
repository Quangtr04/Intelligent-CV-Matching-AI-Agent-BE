# Nguồn tham khảo cho roadmap học tập

EduNexus dùng danh sách liên kết đến roadmap.sh để gợi ý nguồn học theo nghề mục tiêu và kỹ năng.
Snapshot gồm 98 URL được trích từ [README của kho chính thức](https://github.com/nilbuild/developer-roadmap),
kho được liên kết trên [roadmap.sh/roadmaps](https://roadmap.sh/roadmaps). Danh sách được đối chiếu ngày
**14/10/2026** và lưu tại [roadmap-references.json](../src/main/resources/learning/roadmap-references.json).
`verified_on` ghi ngày đối chiếu danh sách nguồn; chưa kiểm HTTP 200 riêng cho từng URL.
Đây là snapshot được đóng gói cùng ứng dụng; backend không crawl roadmap.sh khi xử lý request.

## Dữ liệu nguồn và lộ trình cá nhân

Danh mục chứa `slug`, `title`, `url` và các tên gọi tương đương để chọn liên kết phù hợp. Mỗi liên kết
có nguồn catalog và ngày xác minh để đối chiếu. Nội dung bài viết, đồ thị và mô tả các topic của nhà
cung cấp không được đưa vào snapshot này.

Lộ trình cá nhân vẫn do EduNexus tạo từ nghề mục tiêu, job, CV đã xác nhận, hồ sơ kỹ năng và assessment
của người dùng. Các node, thứ tự, prerequisite và skill gap thuộc luồng hiện có của ứng dụng. Danh sách
nguồn tham khảo được đưa vào ngữ cảnh AI để chọn nguồn học phù hợp; URL tài nguyên được lưu từ catalog
đã kiểm soát thay vì lấy URL do model tự sinh. Lộ trình trả về là kế hoạch cá nhân của EduNexus.

Các liên kết đến roadmap.sh dẫn người dùng sang trang nguồn để xem nội dung đầy đủ. Tài nguyên từ
catalog tài liệu hiện có, như hướng dẫn Java, Spring hoặc PostgreSQL, tiếp tục được sử dụng khi phù hợp
với kỹ năng của node.

## Cách dùng API

Các endpoint dùng access token của tài khoản `CANDIDATE`. Request DTO giữ nguyên.

Lấy nguồn tham khảo trực tiếp theo nghề và kỹ năng, kể cả khi `AI_ENABLED=false`:

```http
GET /api/v1/roadmaps/recommendations?targetJobTitle=Java%20Backend%20Developer&skills=SQL
Authorization: Bearer <accessToken>
```

`targetJobTitle` bắt buộc; `skills` tùy chọn, có thể lặp tham số (`skills=SQL&skills=Java`) hoặc phân cách
bằng dấu phẩy (`skills=SQL,Java`). Response trực tiếp là object gồm `provider`, `source_catalog_url`,
`focus_skills`, `roadmaps`, `resources`, có cấu trúc như `learning_recommendations` trong bảng bên dưới.
Endpoint này chọn liên kết từ snapshot và catalog database, không gọi AI hoặc trừ quota AI.

Tạo một lộ trình cho nghề mục tiêu:

```http
POST /api/v1/roadmaps
Authorization: Bearer <accessToken>
Content-Type: application/json

{
  "targetJobTitle": "Java Backend Developer"
}
```

Có thể gửi thêm `jobId`, `resumeId` hoặc `assessmentId` đã có trong API. CV được chọn phải ở trạng thái
`READY`; assessment phải thuộc người dùng và phù hợp nghề mục tiêu. Các kiểm tra quyền, validation,
transaction và quota tạo roadmap vẫn áp dụng.

Đọc lộ trình bằng ID trả về từ bước tạo:

```http
GET /api/v1/roadmaps/<roadmapId>
Authorization: Bearer <accessToken>
```

Response giữ dữ liệu roadmap và thêm `learning_recommendations` để frontend hiển thị các nguồn học
liên quan. Những tài nguyên gắn với node được lưu qua `roadmap_node_resources` và trỏ tới catalog
`learning_resources` hiện có.

Các trường trong `learning_recommendations`:

| Trường | Ý nghĩa |
| --- | --- |
| `provider` | Nguồn catalog roadmap tham khảo: `roadmap.sh`. |
| `source_catalog_url` | Liên kết trang catalog nguồn để đối chiếu. |
| `focus_skills` | Danh sách tên kỹ năng được ưu tiên trong gợi ý. |
| `roadmaps` | Các liên kết được chọn, mỗi mục gồm `slug`, `title`, `url`. |
| `resources` | Các bản ghi tài nguyên đang hoạt động từ catalog database, gồm nguồn và metadata hiện có. |

Assessment cũng dùng nghề mục tiêu để chọn nguồn tham khảo:

```http
POST /api/v1/assessments
Authorization: Bearer <accessToken>
Content-Type: application/json

{
  "targetJobTitle": "Java Backend Developer"
}
```

Sau khi lưu đáp án qua `PUT /api/v1/assessments/<assessmentId>/answers`, nộp bài:

```http
POST /api/v1/assessments/<assessmentId>/submit
Authorization: Bearer <accessToken>
```

Kết quả bài đã hoàn thành, từ submit hoặc `GET /api/v1/assessments/<assessmentId>`, thêm
`learning_recommendations` và ưu tiên kỹ năng có điểm dưới 70 trong `skill_scores`. Frontend có thể
hiển thị các nguồn này ngay sau kết quả assessment, rồi cho người dùng tạo roadmap với `assessmentId`
đã hoàn thành. Việc chọn/trả liên kết tham khảo không tạo thêm lời gọi AI, crawl hay quota AI;
các bước tạo assessment và tạo roadmap vẫn dùng luồng AI hiện có.

## Lưu trữ và cập nhật

[V6__roadmap_reference_catalog.sql](../src/main/resources/db/migration/V6__roadmap_reference_catalog.sql)
thêm các liên kết vào `learning_resources` với `resource_type=ROADMAP`, `provider=roadmap.sh`,
`language=en` và `source_type=REFERENCE`. Metadata chứa `skill_slugs`, `reference_slug`,
`source_catalog_url` và `verified_on`. Các bản ghi mới mặc định active/free.

Seed dùng `ON CONFLICT (url) DO NOTHING`, nên không ghi đè tài nguyên quản trị viên đã tạo với cùng URL.
Không cần bảng mới. Khi cập nhật snapshot, đối chiếu liên kết với nguồn, cập nhật ngày xác minh và thêm
migration mới nếu cần thay đổi catalog trong database đã triển khai. Flyway giữ lịch sử migration;
không sửa V6 sau khi nó đã được áp dụng.

## Phạm vi nguồn bên ngoài

Kho [developer-roadmap](https://github.com/nilbuild/developer-roadmap) hiện công bố topic Markdown.
Script của nhà cung cấp tham chiếu `/api/v1-official-roadmap/{slug}`, nhưng hợp đồng API ổn định chưa
được xác minh. Kiểu dữ liệu công khai khai báo `edges` là `any[]`, không định nghĩa cạnh nào là
prerequisite học tập. Phần tích hợp này không gọi endpoint đó khi tạo lộ trình.
[Script đồng bộ chính thức](https://github.com/nilbuild/developer-roadmap/blob/master/scripts/sync-content-to-repo.ts),
[kiểu dữ liệu graph](https://github.com/nilbuild/developer-roadmap/blob/master/scripts/lib/official-roadmap.ts).

Giấy phép kho yêu cầu có sự đồng ý trước khi tái xuất bản nội dung; điều khoản website cũng hạn chế
việc sao chép và scraping tự động. Phần tích hợp này dùng liên kết tham khảo cùng thông tin nguồn,
không nhập bài viết, hình ảnh hoặc đồ thị của nhà cung cấp. Việc sao chép nội dung sâu hơn cần được
xác nhận quyền sử dụng riêng. [Giấy phép](https://github.com/nilbuild/developer-roadmap/blob/master/license),
[điều khoản roadmap.sh](https://roadmap.sh/terms).
