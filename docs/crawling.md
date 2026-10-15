# Thu thập việc làm: discovery, parsing, nguồn và embedding

Admin có thể tự tìm detail URL theo keyword hoặc cung cấp URL đã biết. Backend parse metadata, chuẩn hóa dữ liệu, chống trùng đa nguồn và enqueue embedding. API crawl xử lý từng trang tuần tự; embedding dùng worker/hàng đợi PostgreSQL riêng. Collection định kỳ dùng cùng pipeline với admin cấu hình.

Admin thu thập và nhập tin vào database. Người dùng tìm các tin đã lưu bằng API danh sách việc làm;
thao tác tìm kiếm của người dùng không kích hoạt crawl nguồn bên ngoài.

Admin đọc/lưu lịch collection bằng `GET/PUT /api/admin/crawl-settings` và xem lịch sử bằng
`GET /api/admin/crawl-history`; các path có alias `/api/v1/admin/...` và đều yêu cầu Bearer ADMIN.
Cấu hình được lưu trong database và giữ qua restart. Scheduler kiểm tra mỗi khoảng 30 giây và giữa
các nguồn; nếu một nguồn đang crawl, nó được hoàn tất trước khi áp dụng thay đổi.
Xem [payload, giới hạn và cách chọn actor](job-collection-setup.md#2-cấu-hình-collection-qua-api-admin).

## Phạm vi đã triển khai

| Ưu tiên | Hành vi |
| --- | --- |
| 4 — Parsing | JobPosting JSON-LD (array/@graph/@type array), fallback HTML theo nguồn |
| 6 — Structured data | Skills, requirements/responsibilities/benefits, employmentType, dates |
| 8 — Multi-source dedup | Hash nguồn và fingerprint bảo thủ; giữ aliases riêng |
| 9 — Expiration | Deadline/HTTP404/410 và scheduler; bảo vệ alias còn hiệu lực |
| 12 — URL discovery | Search keyword, giới hạn pages/details, canonical URLs |
| 13 — Scheduled collection | Admin thật, cấu hình nguồn, PostgreSQL lease, monitoring |
| 14 — Normalization | HTML/text, location/employment/skill aliases, hash ổn định |
| 15 — Automatic embedding | Durable queue, lease/retry, content-hash fencing, admin quota |

Code được kiểm bằng fixtures, không phải chứng nhận HTML website đang hoạt động. Backend không chạy JavaScript/headless browser, đăng nhập nguồn hay bypass CAPTCHA/challenge. Trang chỉ render qua JavaScript hoặc bị chặn có thể tạo item lỗi.

## 1. Tự tìm URL hoặc dùng URL có sẵn

`POST /api/v1/admin/jobs/crawl` cần Bearer ADMIN. Bỏ `urls`, gửi null hoặc `[]` để discovery:

~~~json
{
  "sourceSite": "ITVIEC",
  "keyword": "Java",
  "roleCategory": "Backend",
  "location": "Hà Nội",
  "limit": 20,
  "maxPages": 3
}
~~~

Source ITVIEC/TOPCV; keyword required/max255, roleCategory required/max100, location optional/max120.
Keyword dùng tìm search pages; category là nhãn nhập tin. Admin tự chọn `location`, chẳng hạn `Hà Nội`,
`Đà Nẵng` hoặc `Remote`; bỏ trường, null hoặc để trống để tìm toàn quốc, không giới hạn một thành phố.
Discovery dùng bộ lọc địa điểm của website khi nhận diện được thành phố mà nguồn hỗ trợ, rồi kiểm
location sau parse bằng substring không dấu đã chuẩn hóa. Thành phố chưa có ánh xạ vẫn được chấp nhận
và lọc sau parse; hệ thống không tự dịch mọi tên địa điểm.

Bộ lọc nguồn được đối chiếu từ form tìm kiếm công khai ngày 14/10/2026:
[ITviec](https://itviec.com/it-jobs/java/ha-noi) dùng `city=ha-noi`,
[TopCV](https://www.topcv.vn/tim-viec-lam-java-tai-ha-noi-kl1) dùng `cityIds[]=1` cho Hà Nội
và `cityIds[]=8` cho Đà Nẵng. Ánh xạ 36 lựa chọn ITviec và 71 lựa chọn TopCV nằm trong
`src/main/resources/jobs/source-location-filters.json`; cập nhật file này nếu nguồn đổi bộ lọc.
`Remote` và địa điểm chưa có ánh xạ dùng discovery chung và bộ lọc sau parse; vì số trang/tin có giới hạn,
một lượt có thể chưa tìm được tin phù hợp dù nguồn còn kết quả ở các trang sau.

Người dùng có thể chọn địa điểm khi tìm tin đã lưu:

~~~http
GET /api/v1/jobs?q=Java&location=H%C3%A0%20N%E1%BB%99i
~~~

Giá trị đã URL-encode trên là `Hà Nội`. Bỏ tham số `location` hoặc gửi rỗng để xem tin ở mọi địa điểm
trong database. `Toàn quốc` hoặc `All Cities` cũng bỏ bộ lọc. Khi đổi city trong ô tìm kiếm,
frontend gửi giá trị người dùng chọn vào tham số này. API chuẩn hóa alias như `Hanoi`, `DaNang`,
`HCM`, `CanTho` và so khớp không dấu, nên `Da Lat` tìm được tin lưu là `Đà Lạt`.
Ký tự `%` và `_` trong địa điểm được tìm như ký tự thường, không mở rộng thành wildcard SQL.

Limit1–20 mặc định20; maxPages1–5 mặc định3. Discovery đọc links từ HTML search, chỉ nhận pattern detail URL phù hợp. Không fetch vô hạn khi nguồn không trả thêm links.

Chế độ URL đã biết:

~~~json
{
  "sourceSite": "TOPCV",
  "keyword": "Java",
  "roleCategory": "Backend",
  "urls": ["https://www.topcv.vn/viec-lam/<actual-slug>/<actual-id>.html"],
  "limit": 20,
  "maxPages": 3
}
~~~

Thay placeholder bằng URL thật. Explicit URLs tối đa20, mỗi URL required/max2000; canonical trùng fetch một lần. maxPages không dùng trong chế độ explicit.

Chỉ HTTPS đúng itviec.com/www.itviec.com/topcv.vn/www.topcv.vn; không credentials/explicit port/host khác. Kiểm host mỗi redirect; timeout5s mỗi connection, body2MiB, tối đa3 redirect. Canonicalization loại utm_* và tracking đã biết, giữ query khác. Toàn run có thể mất lâu hơn5s.

## 2. Parsing và manual override

Parser ưu tiên một schema.org JobPosting JSON-LD. Nhiều JobPosting trên detail page bị từ chối; block không liên quan bị malformed không che block hợp lệ khác. Khi thiếu JSON-LD, dùng selector HTML của ITVIEC/TopCV để lấy title/company/location/salary/sections/dates có trong nguồn.

Title và description phải tồn tại; description text tối thiểu30 ký tự. Không tạo tin từ trang challenge hoặc thiếu dữ liệu đủ nhận diện. Metadata thiếu để null, không invent deadline/company/salary.

Skills dò bằng dictionary/aliases, phân biệt requirements với nội dung khác. Đây không phải suy luận đầy đủ mọi công nghệ hoặc ý nghĩa phủ định trong câu; admin có thể override bằng dữ liệu thực qua `POST /api/v1/admin/jobs`:

~~~json
{
  "sourceSite": "ITVIEC",
  "sourceUrl": "<actual-HTTPS-detail-URL>",
  "jobTitle": "Java Backend Developer",
  "companyName": "Example Company",
  "location": "Đà Nẵng",
  "salaryText": "20000000 - 30000000 VND",
  "roleCategory": "Backend",
  "content": "Xây dựng và vận hành REST API bằng Java, Spring Boot và PostgreSQL.",
  "skills": [{"name": "Java", "required": true}],
  "employmentType": "FULL_TIME",
  "requirements": "Có kinh nghiệm Java, Spring Boot và SQL.",
  "responsibilities": "Xây dựng API và bảo trì dịch vụ backend.",
  "benefits": "Quyền lợi do nguồn cung cấp.",
  "postedAt": "2026-10-01T08:00:00+07:00",
  "validThrough": "2030-12-31T23:59:59+07:00"
}
~~~

Đủ15 fields; dùng dates/content thật, bỏ metadata tùy chọn nếu nguồn không có. Manual import không fetch URL hoặc gọi AI trong request.

Company/location/salary max255, employmentType max50; content/requirements/responsibilities/benefits max100000; skills max100, mỗi name required/max100, skillId optional nhưng phải active. required primitive boolean mặc địnhfalse. Dates ISO8601 có offset; deadline đã qua bị từ chối. HTML normalize thành text, nội dung gốc giữ riêng.

## 3. Version và deduplicate

Hash nguồn gồm source/canonical URL và metadata/content/skills chuẩn hóa. Thay đổi deadline/salary/requirements/skills cũng có thể tạo version mới, không chỉ title/description.

Fingerprint liên nguồn yêu cầu company và cùng company/title/location/description/requirements chuẩn hóa; thiếu company không merge. Đây là exact identity bảo thủ, không fuzzy/AI similarity. Khác nội dung có thể vẫn tạo hai tin dù người đọc coi là cùng vị trí.

`market_job_sources` giữ URL/hash/status/deadline riêng. Unchanged ACTIVE refresh alias, trả deduplicated=true. Cùng hash nhưng job inactive cần đổi status rõ ràng. Source chuyển version, canonical cũ chỉ REMOVED khi không còn alias ACTIVE. Alias khác còn hiệu lực giữ canonical/vector.

## 4. Hết hạn và lịch chạy

`Asia/Ho_Chi_Minh` là múi giờ dùng để diễn giải deadline chỉ có ngày: `validThrough` hợp lệ đến cuối
ngày theo giờ Việt Nam. Nó không chọn thành phố tìm việc; địa điểm do `location` trong crawl/search
hoặc `JOB_COLLECTION_*_LOCATION` quyết định. `BILLING_MAINTENANCE_ZONE` dùng múi giờ cho lịch billing,
không lọc địa điểm việc làm. Deadline quá hạn hoặc HTTP404/410 hết hạn alias đã có tại URL;
không hủy nguồn khác còn hiệu lực.

Expiration mặc định15 phút xử lý deadline đã lưu, không fetch website. Tin thiếu deadline không tự hết hạn theo số ngày từ crawl. Khi hết alias ACTIVE/unexpired, canonical EXPIRED và vectors noncurrent trong transaction. Deadline tổng hợp là null nếu còn alias không khai báo deadline.

Collection mặc định tắt, expiration bật. Cấu hình admin/nguồn/lịch và PostgreSQL lease xem [job-collection-setup.md](job-collection-setup.md).

API crawl-settings điều khiển collection; expiration giữ lịch riêng từ `JOB_EXPIRATION_*`.
Cấu hình đã lưu ưu tiên hơn giá trị khởi tạo trong môi trường, kể cả khi đã lưu `enabled=false`.
Tắt hoặc đổi cấu hình cho phép nguồn đang crawl hoàn tất, rồi dừng các nguồn còn lại theo cấu hình cũ;
lượt kiểm tra tiếp theo dùng snapshot mới.

## 5. Embedding tự động và /index

Import/refresh/status ACTIVE enqueue vào vector_indexing_jobs, unique job/provider/model/content_hash. Worker chạy khi embedding provider bật; quota/usage attributed admin yêu cầu. Enqueue chưa có nghĩa vector đã sẵn sàng.

Để dùng adapter hiện có, cấu hình `AI_ENABLED=true`, `AI_EMBEDDING_PROVIDER=openai`, `OPENAI_API_KEY` và model trả1536 chiều. Worker mặc định bật (`JOB_EMBEDDING_ENABLED=true`), kiểm queue mỗi30000ms (`JOB_EMBEDDING_FIXED_DELAY_MS`), trễ khởi động10000ms (`JOB_EMBEDDING_INITIAL_DELAY_MS`), tối đa10 task/lượt (`JOB_EMBEDDING_BATCH_SIZE`), lease300 giây (`JOB_EMBEDDING_LEASE_SECONDS`). Retry tối đa3 lần với khoảng nghỉ cơ sở30 giây (`JOB_EMBEDDING_RETRY_SECONDS`), tăng theo từng lần lỗi. Có thể tắt worker mà vẫn giữ task trong queue. Admin cần quota EMBEDDING còn đủ; xem error_message khi task lỗi.

Claim bằng FOR UPDATE SKIP LOCKED/lease, commit trước provider. Trước gọi provider kiểm admin còn hoạt động và job hợp lệ; sau response kiểm lại job ACTIVE/unexpired/hash/lease trước publish. Job đổi/hết hạn hoặc admin đã mất quyền khi chuẩn bị task → CANCELLED. Vector current cùng hash/provider/model được reuse.

QUEUED → PROCESSING → COMPLETED; lỗi retry hữu hạn rồi FAILED, dữ liệu không còn hợp lệ CANCELLED. Theo dõi attempt_count/error_message/next_attempt_at. Không cần RabbitMQ cho queue này.

POST /api/v1/admin/jobs/{id}/index không body: enqueue/reuse và thử xử lý khi claim được. Cần embedding provider; response là task state hiện tại COMPLETED/QUEUED/PROCESSING/FAILED/CANCELLED. HTTP200 không bảo đảm embed thành công; response id là task UUID.

GET /api/v1/admin/jobs/{id}/index không body, chỉ đọc ADMIN: {job_id,indexing_jobs:[...]} tối đa20 task mới nhất, không gọi AI. Chưa có task trả array rỗng. Cũng có GET /admin/monitoring/indexing-jobs.

## 6. Monitoring và tests

Crawl response: market_crawl_runs + items. `GET /api/admin/crawl-history?limit=20&offset=0` trả mảng
crawl runs mới nhất; alias `/api/v1/admin/crawl-history`. Limit mặc định 20/từ 1 đến 100, offset mặc định
0/từ 0 đến 100000. Endpoint cũ `GET /api/v1/admin/jobs/crawl` vẫn có hiệu lực; đọc chi tiết và các items
bằng `GET /api/v1/admin/jobs/crawl/{id}`. Các endpoint lịch sử/chi tiết dành ADMIN.

Search outcomes SEARCH_FETCHED/SEARCH_FETCH_FAILED. Detail outcomes IMPORTED/DUPLICATE/EXPIRED/WRONG_REGION/REJECTED/FETCH_FAILED/IMPORT_FAILED. Run COMPLETED/PARTIAL/FAILED; đọc items ngay cả khi HTTP200. Lỗi item không rollback tin khác.

Counters gồm search_pages_checked/candidate_urls_discovered/imported_skills và fetched/parsed/imported/duplicates/rejected/region. Imported skills chỉ tăng cho tin mới, không duplicate. Explicit mode không tăng search counters.

Tests dùng fixtures/mock fetch/AI và SQL trên DB thử nghiệm để kiểm leases/aliases/expiration/queue fences. Không gọi website nguồn thật; cần một lượt có chủ đích trong môi trường triển khai để xác nhận HTML thực và queue trước khi semantic matching.
