# Cấu hình thu thập việc làm tự động và xử lý tin hết hạn

Backend có hai tác vụ định kỳ: tìm và thu thập tin từ nguồn được cấu hình, và hết hạn tin dựa trên deadline nguồn. Thu thập tự động mặc định **tắt**; xử lý hết hạn mặc định **bật**. Tác vụ dùng PostgreSQL để giữ lease và lịch sử, không phụ thuộc một phiên đăng nhập HTTP.

Admin có thể lưu cấu hình collection qua API và áp dụng trong lúc ứng dụng đang chạy. Cấu hình đã lưu
trong database được giữ qua restart và ưu tiên hơn cấu hình khởi tạo từ môi trường. Expiration giữ lịch
riêng từ cấu hình môi trường.

## 1. Chuẩn bị

1. Khởi động phiên bản backend có các migration `V5__job_collection_automation.sql` và
   `V7__admin_crawl_settings.sql` để Flyway cập nhật PostgreSQL. Đây là lần restart cần thiết khi nâng cấp
   tính năng; những lần lưu cấu hình qua API sau đó không cần restart.
2. Có tài khoản thật `ADMIN/ACTIVE`, `deleted_at IS NULL`. Nếu chưa có admin, đăng ký/xác minh rồi dùng [bootstrap-admin.sql](../scripts/bootstrap-admin.sql).
3. Dùng access token admin để đọc `GET /api/admin/crawl-settings` và xem cấu hình đang có hiệu lực.
4. Cấu hình keyword, category và location mong muốn. Các nguồn hiện được hỗ trợ là `ITVIEC` và `TOPCV`.
5. Lưu `PUT /api/admin/crawl-settings` với `enabled=true`; scheduler kiểm tra mỗi khoảng 30 giây.
   Nếu đang crawl một nguồn, cấu hình mới được xử lý sau khi nguồn đó hoàn tất. Có thể dùng
   [run-local.ps1](../scripts/run-local.ps1) để chạy local cùng các dịch vụ phụ thuộc.

Không có account admin hay password mặc định. Scheduler kiểm tra quyền admin đang tồn tại trong DB trước mỗi lượt; nếu account bị đình chỉ/xóa/đổi role, lượt thu thập bị bỏ qua và ghi cảnh báo. UUID không hợp lệ trong cấu hình khởi tạo làm binding thất bại tại startup.

## 2. Cấu hình collection qua API admin

Các endpoint sau yêu cầu `Authorization: Bearer <adminAccessToken>` của tài khoản `ADMIN`:

| Method | Path | Alias | Kết quả |
| --- | --- | --- | --- |
| GET | `/api/admin/crawl-settings` | `/api/v1/admin/crawl-settings` | Cấu hình collection đang có hiệu lực. |
| PUT | `/api/admin/crawl-settings` | `/api/v1/admin/crawl-settings` | Lưu và trả cấu hình mới. |
| GET | `/api/admin/crawl-history` | `/api/v1/admin/crawl-history` | Mảng lịch sử các lượt crawl. |

Ví dụ bật collection Java tại Hà Nội:

~~~http
PUT /api/admin/crawl-settings
Authorization: Bearer <adminAccessToken>
Content-Type: application/json
~~~

~~~json
{
  "enabled": true,
  "fixedDelay": "PT6H",
  "sources": [
    {
      "sourceSite": "ITVIEC",
      "keyword": "Java",
      "roleCategory": "Backend",
      "location": "Hà Nội",
      "limit": 50,
      "maxPages": 5
    }
  ]
}
~~~

`enabled`, `fixedDelay` và `sources` bắt buộc. `fixedDelay` là duration ISO-8601 từ `PT1M` đến `P30D`.
`actorId` tùy chọn; khi bỏ trường này, tài khoản admin đang lưu cấu hình trở thành actor cho các lượt
crawl sau. Nếu gửi `actorId`, dùng UUID của admin còn `ACTIVE`, chưa xóa; có thể lấy từ `/api/v1/me`
hoặc danh sách user admin. Actor nhận attribution của crawl và quota embedding khi worker xử lý.

Danh sách tối đa 20 nguồn, cần ít nhất một nguồn khi `enabled=true`; không gửi nguồn tìm kiếm trùng nhau.
Mỗi nguồn dùng `sourceSite=ITVIEC|TOPCV`, `keyword` không trống/tối đa 255 ký tự, `roleCategory` không
trống/tối đa 100, `location` tùy chọn/tối đa 120, `limit` từ 1 đến 100 và `maxPages` từ 1 đến 10.
Mặc định mỗi nguồn lấy tối đa 50 URL tin và tìm tối đa 5 trang. Đây là giới hạn URL xử lý,
không bảo đảm số tin mới được nhập vì tin trùng hoặc không hợp lệ có thể bị loại.
Lịch khởi tạo chia thành Java/Backend, React/Frontend và Data trên cả ITViec và TopCV.
Lịch đã lưu trong `job_crawl_settings` được ưu tiên hơn cấu hình môi trường.
Nguồn/actor không hợp lệ, nguồn trùng hoặc payload lỗi bị từ chối trước khi lưu.

GET và PUT trả các trường cấu hình cùng metadata `configurationSource`, `version`, `updatedBy`,
`updatedAt`. `configurationSource=ENVIRONMENT` khi chưa có cấu hình lưu trong database; sau khi lưu là
`DATABASE`. Một cấu hình đã lưu với `enabled=false` vẫn ưu tiên hơn `JOB_COLLECTION_ENABLED` trong `.env`.

PUT lưu cấu hình ngay nhưng không chạy crawl ngay. Scheduler bắt đầu kiểm tra sau khoảng 30 giây từ
startup và nghỉ 30 giây giữa các lượt kiểm tra. Nó lấy snapshot cấu hình cho lượt đó, rồi kiểm tra
version trước khi bắt đầu mỗi nguồn. Khi cấu hình thay đổi hoặc bị tắt, nguồn đang crawl được phép
hoàn tất; các nguồn còn lại theo cấu hình cũ không bắt đầu. Lượt kiểm tra tiếp theo lấy snapshot mới.
Thông thường thay đổi có hiệu lực sau khoảng 30 giây; một nguồn đang crawl lâu có thể làm chậm hơn.
Khoảng nghỉ của từng nguồn tính từ lần hoàn tất trước, hoặc lần bắt đầu nếu lượt đó bị dừng trước khi
ghi completion. Mốc này được giữ trong database nên restart hoặc replica khác không làm mất lịch.
API này điều khiển collection; expiration tiếp tục theo `JOB_EXPIRATION_*`.

## 3. Cấu hình khởi tạo bằng biến môi trường

`application.yaml` ánh xạ những biến dưới đây cho cấu hình khởi tạo. Chúng được dùng cho collection khi
chưa lưu cấu hình qua API; cấu hình database ưu tiên khi đã có. Thay UUID mẫu bằng UUID admin thật trước
khi bật bằng môi trường. Spring Boot nạp `.env` theo `spring.config.import`; đặt `APP_ENV_FILE` nếu dùng
file khác. Nếu đã có `.env`, giữ file và bổ sung key cần thiết từ `.env.example`.

~~~dotenv
JOB_COLLECTION_ENABLED=true
JOB_COLLECTION_ACTOR_ID=00000000-0000-0000-0000-000000000000
JOB_COLLECTION_FIXED_DELAY=PT6H
JOB_COLLECTION_LIMIT=50
JOB_COLLECTION_MAX_PAGES=5

JOB_COLLECTION_ITVIEC_KEYWORD=Java
JOB_COLLECTION_ITVIEC_ROLE=Backend
JOB_COLLECTION_ITVIEC_LOCATION=

JOB_COLLECTION_TOPCV_KEYWORD=Java
JOB_COLLECTION_TOPCV_ROLE=Backend
JOB_COLLECTION_TOPCV_LOCATION=

JOB_EXPIRATION_ENABLED=true
JOB_EXPIRATION_FIXED_DELAY=PT15M
JOB_EXPIRATION_BATCH_SIZE=500
~~~

- `PT6H`: khoảng nghỉ 6 giờ cho từng nguồn, tính từ lúc lượt trước hoàn tất; nếu tiến trình bị dừng trước
  completion thì dùng mốc bắt đầu. Lượt kiểm tra đầu tiên diễn ra sau khoảng 30 giây từ startup.
- `PT15M`: khoảng nghỉ 15 phút cho expiration. Dùng duration dương.
- `limit`: số detail URL tối đa mỗi nguồn/lượt, từ 1 đến 20.
- `maxPages`: số trang search tối đa mỗi nguồn/lượt, từ 1 đến 5; mặc định 3.
- `role-category`: nhãn category ghi vào tin nhập, không phải quyền account hay tham số đảm bảo website đã lọc category.
- `location`: tùy chọn; mẫu để trống để tìm toàn quốc, không giới hạn một thành phố. Điền tên thành phố
  mong muốn, chẳng hạn `Hà Nội` hoặc `Đà Nẵng`, để giới hạn từng nguồn. Discovery dùng bộ lọc địa điểm
  của website nếu nhận diện được; địa điểm chưa có ánh xạ vẫn được chấp nhận và lọc sau parse.
- `batch-size`: tối đa số canonical jobs được kiểm tra cho expiration một lượt, mặc định 500 và giới hạn 1–1000.
- Collection bật yêu cầu actor và ít nhất một nguồn. Danh sách cấu hình tối đa 20 nguồn.

Các giá trị nguồn ở trên là dữ liệu search mẫu, không cam kết website có kết quả hoặc cho phép truy cập trong môi trường chạy. Collection không cần token Bearer lưu trong cấu hình.

Địa điểm tìm việc được chọn bằng `sources[].location` trong API hoặc các biến khởi tạo
`JOB_COLLECTION_*_LOCATION`. `Asia/Ho_Chi_Minh` là múi giờ:
nó dùng cho lịch theo giờ Việt Nam và cách tính cuối ngày của deadline, không giới hạn tin ở TP.HCM.
`BILLING_MAINTENANCE_ZONE` chỉ điều khiển múi giờ của lịch billing.

## 4. Cấu hình khởi tạo danh sách nguồn bằng YAML

Có thể thay danh sách khởi tạo trong profile/configuration riêng khi chưa lưu cấu hình database:

~~~yaml
app:
  jobs:
    collection:
      enabled: true
      actor-id: "00000000-0000-0000-0000-000000000000"
      fixed-delay: PT6H
      sources:
        - source-site: ITVIEC
          keyword: Java
          role-category: Backend
          location: ""
          limit: 10
          max-pages: 2
        - source-site: TOPCV
          keyword: Java
          role-category: Backend
          location: ""
          limit: 10
          max-pages: 2
    expiration:
      enabled: true
      fixed-delay: PT15M
      batch-size: 500
~~~

Tên property theo kebab-case; `actor-id` là UUID, `source-site` viết HOA. Keyword không trống/tối đa 255 ký tự, role-category không trống/tối đa 100, location tối đa 120. Cấu hình không hợp lệ bị Bean Validation từ chối tại startup.

Đổi cấu hình khởi tạo môi trường/YAML hoặc provider/key cần restart để nạp lại. Cấu hình collection đã
lưu trong database vẫn có hiệu lực sau restart; chỉnh qua PUT để thay nó. Actor phải là account thật
trong cùng database.

## 5. Luồng collection

`JobCollectionScheduler.collect → JobCrawlService.crawlAs(request, actor) → JobService.importJobAs → repositories`.

Scheduler gửi `CrawlRequest` với `urls=[]`. Dịch vụ discovery tạo URL search thuộc nguồn được hỗ trợ, thu detail URLs trên tối đa `maxPages`, rồi xử lý tối đa `limit` tin. Mỗi item được lưu bằng transaction riêng để một tin lỗi không rollback các tin đã nhập thành công.

Có thể kiểm tra thủ công bằng API admin trước khi bật lịch:

~~~http
POST /api/v1/admin/jobs/crawl
Authorization: Bearer <adminAccessToken>
Content-Type: application/json
~~~

~~~json
{
  "sourceSite": "ITVIEC",
  "keyword": "Java",
  "roleCategory": "Backend",
  "location": "Hà Nội",
  "urls": [],
  "limit": 10,
  "maxPages": 2
}
~~~

Nếu chỉ muốn xử lý detail URL đã biết, truyền `urls` chứa URL thật của nguồn thay vì array rỗng. Không tự điền URL minh họa để chạy request mạng.

API crawl và lịch collection dành cho admin nhập tin vào database. Người dùng chọn địa điểm trên
API tìm tin đã lưu, ví dụ `GET /api/v1/jobs?q=Java&location=H%C3%A0%20N%E1%BB%99i` cho `Hà Nội`.
Bỏ `location` hoặc gửi rỗng để tìm ở mọi địa điểm; request tìm việc này không kích hoạt crawl.

Hệ thống giữ `market_crawl_runs` và `market_crawl_items`, actor ở `requested_by`, thống kê search/discovery/import và audit của lượt chạy. Tin cùng nội dung ở nhiều nguồn có thể liên kết vào một canonical job qua `market_job_sources`; nguồn vẫn giữ URL, hash và deadline riêng.

Collection parse metadata bằng JSON-LD/HTML và dictionary, không gọi parser AI đồng bộ cho từng tin. Embedding được enqueue vào pipeline của backend; cấu hình AI và quota của admin được dùng khi worker xử lý. Bật collection không tự tạo ChatModel/EmbeddingModel hay API key. Theo dõi GET `/api/v1/admin/jobs/{id}/index` hoặc monitoring indexing-jobs khi cần semantic matching.

## 6. Lease khi chạy nhiều instance

Mỗi search có lease PostgreSQL trong `job_collection_leases`:

- Key `job-collection:<SHA-256>` dựa trên source/keyword/category/location đã chuẩn hóa.
- Lượt collection giữ lease 15 phút. Bộ giới hạn request nguồn hiện tại được thiết kế để lượt thu thập hữu hạn nằm trong khoảng này.
- Acquisition là `INSERT ... ON CONFLICT ... WHERE ... RETURNING`, chỉ một instance nhận lease còn hiệu lực.
- Collection dùng mốc gần nhất giữa `last_started_at` và `last_completed_at` để giữ khoảng nghỉ
  `fixedDelay`: bình thường tính từ completion, khi lượt bị dừng thì dùng thời điểm bắt đầu.
  Instance khác vẫn phải chờ khoảng nghỉ này sau khi lease hết hạn.
- Completion phải khớp `lease_token`; worker cũ không thể xóa lease của worker mới.
- Một nguồn lỗi vẫn cho nguồn khác trong danh sách tiếp tục. Kết quả `FAILED/PARTIAL` và exception được phản ánh trong `last_error`.

Expiration có lease riêng `job-expiration` giữ 5 phút, cũng có kiểm tra khoảng nghỉ. Không dùng lease expiration để xác thực user.

Nếu tiến trình bị dừng, lease có thời hạn thay vì khóa vĩnh viễn. Collection chỉ nhận lại lease khi
lease đã hết hạn **và** khoảng nghỉ từ mốc bắt đầu/hoàn tất gần nhất đã đủ. Expiration giữ cách tính
khoảng nghỉ từ `last_started_at`. Không có retry vô hạn ngay sau lỗi hoặc heartbeat gia hạn lease;
giữ giới hạn source nhỏ khi điều chỉnh pipeline hoặc thời gian xử lý.

## 7. Hết hạn theo nhiều nguồn

Expiration không fetch website hay gọi AI. Repository xử lý trong một transaction:

1. Chọn canonical jobs có nguồn ACTIVE đã quá `valid_through`, hoặc legacy job đã quá deadline mà không có alias còn hiệu lực.
2. Khóa jobs bằng `FOR UPDATE SKIP LOCKED`, giới hạn batch.
3. Chuyển nguồn có deadline `<= now()` từ ACTIVE sang EXPIRED.
4. Chỉ chuyển canonical job ACTIVE sang EXPIRED khi không còn nguồn ACTIVE có deadline tương lai hoặc deadline null.
5. Tính lại deadline canonical từ các nguồn còn hiệu lực. Một nguồn không khai báo deadline khiến deadline tổng thể là null.
6. Vô hiệu `market_job_embeddings.is_current` khi canonical job được hết hạn, cùng transaction.

Một tin có alias ITVIEC hết hạn nhưng alias TOPCV còn hiệu lực vẫn ACTIVE, giữ vector hiện tại. Khi tất cả nguồn đều hết hạn, tin biến mất khỏi nguồn ACTIVE và vector không còn được dùng như current.

Không suy deadline từ số ngày kể từ lần crawl nếu website không có deadline. Tin không có `valid_through` không tự bị expiration job loại bỏ. HTTP404/410 và thao tác status được xử lý tại pipeline crawl/import; expiration định kỳ chỉ dựa trên dữ liệu deadline đã lưu.

## 8. Theo dõi và thử nghiệm

Dùng token admin:

- `GET /api/admin/crawl-history?limit=20&offset=0`: mảng các crawl runs mới nhất, gồm
  `search_pages_checked`, `candidate_urls_discovered`, `imported_skills`; alias `/api/v1/admin/crawl-history`.
- `limit` mặc định 20, từ 1 đến 100; `offset` mặc định 0, từ 0 đến 100000. Tăng offset để lấy trang tiếp theo.
- `GET /api/v1/admin/jobs/crawl?limit=20&offset=0`: endpoint lịch sử hiện có vẫn dùng được.
- `GET /api/v1/admin/jobs/crawl/{id}`: từng item/status/message.
- `GET /api/v1/admin/monitoring/crawl-runs`: monitoring qua nhóm có sẵn.
- `GET /api/v1/jobs`: chỉ tin còn hiệu lực; GET detail trả các nguồn liên quan theo implementation hiện tại.

Có thể đọc lease bằng SQL quản trị:

~~~sql
SELECT name, lease_until, last_started_at, last_completed_at, last_error
FROM job_collection_leases
ORDER BY last_started_at DESC;
~~~

Test tự động không truy cập ITVIEC/TopCV thật:

- `JobCollectionPropertiesTest`: default an toàn và validation giới hạn cấu hình.
- `JobCollectionSchedulerTest`: disabled/actor/lease, nguồn lỗi không chặn nguồn tiếp theo, monitoring PARTIAL và expiration lease.
- `JobUrlDiscoveryTests`, `JobCrawlServiceTests`, `JobSearchLocationTest`: chọn địa điểm trong URL nguồn,
  alias/có dấu/không dấu, bỏ lọc để tìm mọi nơi, `WRONG_REGION` và giữ múi giờ deadline độc lập với city.
- `JobSearchRepositoryIntegrationTest`: chạy SQL tìm địa điểm thật trên PostgreSQL UTF-8 khi có
  `EDUNEXUS_LOCATION_TEST_DATABASE_URL`; username/password lấy từ `EDUNEXUS_LOCATION_TEST_DATABASE_USER`
  và `EDUNEXUS_LOCATION_TEST_DATABASE_PASSWORD` (mặc định `postgres` và rỗng). Test tạo/xóa schema riêng,
  không cần schema ứng dụng hoặc pgvector; kiểm tên có dấu, Unicode, ký tự `%`/`_`, trạng thái và phân trang.
- `JobCollectionRepositoryIntegrationTest`: hai replica cùng tranh lease, completion cũ không phá lease mới, nhiều alias/deadline null và invalidation vector; chỉ chạy khi có `EDUNEXUS_TEST_DATABASE_URL` trỏ DB test riêng đã có V5. Fixture expiration dùng temporary tables trong transaction, không cần pgvector để kiểm các điều kiện quan hệ này.

~~~powershell
mvn verify
~~~

Không trỏ integration tests vào DB đang phục vụ user. Test HTTP fixture chứng minh parser/discovery và lỗi được xử lý; cần chạy lượt có chủ đích trên môi trường của bạn để xác nhận website hiện tại trả dữ liệu và hàng đợi xử lý được tin thật.
