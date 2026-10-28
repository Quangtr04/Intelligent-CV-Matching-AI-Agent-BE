# Cơ sở dữ liệu EduNexus

Schema hiện tại sau **V1–V20 có 84 bảng ứng dụng**, không tính bảng lịch sử `flyway_schema_history`.

V20 bổ sung `assessment_generations` để lưu yêu cầu tạo câu hỏi độc lập với request HTTP. Bảng lưu owner,
JD, trạng thái, assessment đã hoàn tất và lease của worker. Unique index chỉ cho phép một yêu cầu đang chờ/chạy
cho cùng user/JD; index hàng đợi phục vụ claim tuần tự. Đáp án vẫn nằm trong `assessment_questions`, không trả
qua API trạng thái tác vụ. Khi lease hết hạn, yêu cầu được đánh dấu gián đoạn và không tự gọi lại model.
Các cột embedding đang sử dụng có kiểu `vector(1024)` cho Ollama `qwen3-embedding:0.6b`;
vector cũ 1536 chiều được giữ trong cột riêng, theo phần chuyển đổi V10 bên dưới.

Riêng bản gốc `V1__edunexus_complete_schema.sql` chuyển DBML đã cung cấp sang PostgreSQL:
**74 bảng, 693 cột, 118 quan hệ**, gồm cả bảng phụ và các khóa ghép. Đây là số liệu của V1,
không phải toàn bộ schema hiện tại. JSON dùng `jsonb`, thời điểm dùng `timestamptz`, địa chỉ IP
dùng `inet`, danh sách UUID dùng `uuid[]`. V1 tạo embedding `vector(1536)`; V10 bổ sung cột 1024 chiều
và giữ lại các giá trị 1536 chiều đã có. Nội dung V1–V9 được giữ nguyên để bảo toàn checksum Flyway.

15 enum logic trong DBML được biểu diễn bằng `varchar(32)` với `CHECK` đúng tập giá trị. Cách này giữ ràng buộc giá trị và cho phép JDBC truyền chuỗi trực tiếp. Khóa UUID `id` có mặc định `gen_random_uuid()`; các thời điểm bắt buộc như `created_at`, `updated_at`, `started_at` có mặc định `now()`.

| Migration | Nội dung |
| --- | --- |
| V1 | Toàn bộ bảng, cột, PK, unique, index và FK của DBML; kiểm tra số không âm và miền điểm số |
| V2 | Bảo vệ luồng nghiệp vụ, index tìm kiếm/lịch sử, HNSW cosine, trigger cập nhật `updated_at` |
| V3 | Hai bảng vận hành `ai_token_reservations` và `payment_webhook_events` |
| V4 | Danh mục khởi tạo để chạy thử |
| V5 | Nguồn job đa nguồn, lease collection/expiration và metadata pipeline job |
| V6 | Danh mục 98 liên kết roadmap tham khảo; không thêm bảng |
| V7 | Bảng singleton `job_crawl_settings` lưu cấu hình collection runtime và metadata quản trị |
| V8 | Bảng `rag_course_links` cho khóa học được admin duyệt; bổ sung URL/provider của nguồn trích dẫn |
| V9 | Snapshot gợi ý học tập trên roadmap và snapshot tài nguyên; không thêm bảng |
| V10 | Embedding 1024 chiều cho Ollama; giữ vector cũ 1536 chiều, yêu cầu reindex và dựng lại HNSW |
| V11 | Quản trị mẫu CV: thêm `description`, cấu hình `layout_config` an toàn và mặc định cho mẫu cũ; không thêm bảng |
| V12 | Một mức lương kỳ vọng theo tháng: `candidate_profiles.expected_salary`; giữ cột min/max cũ để phục hồi, không còn dùng trong API |
| V13 | `V13__resume_writing_quality.sql`: thêm `resume_analyses.writing_quality_json` để lưu rubric cách viết CV, điểm nội dung và trọng số; không thêm bảng |
| V14 | `V14__html_resume_templates.sql`: thêm `resume_templates.html_content` để lưu HTML/CSS của mẫu CV; không thêm bảng |
| V15 | Bảng singleton `matching_salary_settings` lưu biên độ lương do Admin cấu hình, mặc định 10% |
| V16 | `resume_analyses.content_quality_json` lưu rubric chấm nội dung CV; nullable cho báo cáo cũ, không thêm bảng |
| V17 | Bảng `resume_profile_alignments` lưu kết quả đối chiếu CV–hồ sơ cùng hai snapshot; sao chép quota `CV_ANALYZE` sang feature mới theo từng tier |
| V18 | Bảng `resume_profile_alignment_refreshes` lưu yêu cầu đánh giá lại khi hồ sơ thay đổi; một yêu cầu bền vững mỗi CV, trạng thái, generation và lease của worker |
| V19 | Chuyển mẫu CV cấu hình cũ thành HTML, bắt buộc `resume_templates.html_content`, bỏ `layout_config`; giữ ID và FK, không thêm bảng |

V19 thống nhất nội dung trình bày template vào `html_content text NOT NULL`, giới hạn 200.000 ký tự và không trắng.
Các mẫu chưa có HTML được chuyển từ cấu hình cũ trước khi xóa cột `layout_config`; HTML có sẵn được giữ lại.
Metadata danh mục và các liên kết `resumes.template_id` không bị xóa. API Admin kiểm tra thêm HTML/CSS và slot CV;
constraint trong DB không thay thế kiểm tra nội dung ở API. FE lấy `html_content` qua API danh sách hoặc chi tiết CV.
Xem [mẫu CV chỉ lưu HTML](html-resume-templates.md).

V18 thêm **1 bảng**, nâng tổng số bảng ứng dụng lên **83**. `resume_profile_alignment_refreshes` dùng
`resume_id` làm PK, giữ user, hash hồ sơ yêu cầu, trạng thái, generation, lease, ID báo cáo thành công và
mốc thời gian/lỗi. Index lọc hỗ trợ lấy các dòng `QUEUED`/`RUNNING`. Yêu cầu được ghi trong cùng transaction
cập nhật hồ sơ; worker chỉ xử lý dữ liệu đã commit. Nhiều thay đổi liên tiếp được gộp về yêu cầu mới nhất;
không ghi đè lịch sử trong `resume_profile_alignments` hoặc báo cáo ATS. Đây cũng là bảng dùng SQL repository,
ngoài baseline của generator entity.

V17 thêm **1 bảng**, nâng tổng số bảng ứng dụng lên **82**. `resume_profile_alignments` giữ CV, chủ sở hữu,
hash và snapshot hai đầu vào, kết quả JSONB, model và thời gian tạo. Index hỗ trợ đọc lịch sử theo user/CV,
mới nhất trước. API không sửa lại snapshot/điểm lịch sử khi hồ sơ hoặc CV thay đổi; response tính các cờ stale
từ dữ liệu hiện tại. Feature quota `CV_PROFILE_ALIGNMENT` được seed bằng giá trị/kỳ của `CV_ANALYZE`
trên từng tier, không ghi đè quota mới đã tồn tại. Xem [hợp đồng đối chiếu CV–hồ sơ](cv-profile-alignment.md).
Đây là bảng dùng SQL repository, ngoài baseline của generator entity.

V15 thêm **1 bảng**, nâng tổng số bảng ứng dụng lên **81**. `matching_salary_settings` có duy nhất dòng `id = 1`,
lưu tỷ lệ 0–100 với tối đa hai chữ số thập phân, phiên bản, người và thời gian cập nhật. Matching dùng snapshot của
cấu hình cho từng lượt; thay đổi biên độ không sửa lương kỳ vọng của Candidate hoặc chính sách các run lịch sử.
Xem [cấu hình biên độ lương](admin-salary-policy.md). Đây là bảng dùng SQL repository, ngoài baseline của generator entity.

V16 bổ sung cột JSONB cho hợp đồng chấm nội dung CV hiện tại. Báo cáo cũ giữ NULL; không sinh điểm hoặc bằng chứng cho lịch sử.

V14 ban đầu thêm `html_content text` nullable, cho phép cùng tồn tại với cấu hình cũ. V19 nâng cột này thành bắt buộc
và bỏ cấu hình layout JSON như mô tả ở trên; các migration đã áp dụng được giữ nguyên.

V13 thêm cột `writing_quality_json jsonb`, cho phép NULL cho báo cáo cũ và chế độ rule-based.
CHECK yêu cầu giá trị khác NULL phải là JSON object. Rubric mới lưu điểm/nhận xét/bằng chứng theo
từng tiêu chí, số tiêu chí đánh giá được và trọng số tổng hợp; không tự tạo điểm cho lịch sử cũ.
Xem [đánh giá cách viết CV và dữ liệu Postman](cv-writing-quality.md).

V11 từng bổ sung metadata và cấu hình trình bày JSON cho `resume_templates`; V19 thay phần cấu hình bằng HTML.
Admin tạo/sửa/bật-tắt qua `/api/v1/admin/resume-templates`, có audit cùng transaction.
Tắt mẫu giữ bản ghi và FK của CV cũ; danh sách public/Guest chỉ trả mẫu active. Xem
[quản lý mẫu CV và dữ liệu Postman](admin-resume-templates.md). Migration V1–V10 đã áp dụng được giữ nguyên.

V3 thêm **2 bảng**, tổng cộng **76 bảng ứng dụng**: `ai_token_reservations` đặt chỗ ngân sách AI trước khi gọi dịch vụ; `payment_webhook_events` chống xử lý lặp cùng thông báo thanh toán của nhà cung cấp. Các bảng này bổ sung triển khai, không thuộc DBML gốc.

V5 bổ sung **2 bảng**, nâng tổng số bảng ứng dụng lên **78**: `market_job_sources` giữ nguồn/deadline riêng cho job đa nguồn và `job_collection_leases` điều phối scheduler giữa các instance. Migration thêm employment type/deadline/fingerprint vào jobs, admin yêu cầu vào crawl runs, hash/actor/lease/retry vào vector indexing jobs. Các migration đã áp dụng được giữ nguyên; xem [crawling.md](crawling.md) và [kết quả kiểm chứng](job-automation-verification.md).

V7 thêm **1 bảng**, nâng tổng số bảng ứng dụng lên **79**. `job_crawl_settings` chỉ có thể chứa bản ghi
`id=1`, gồm bật/tắt collection, khoảng nghỉ 1 phút–30 ngày, danh sách tối đa 20 nguồn, actor admin,
`version`, `updated_by` và `updated_at`. Không seed cấu hình từ môi trường vào bảng: bản ghi chỉ được
tạo khi admin lưu qua API. Khi chưa có bản ghi, ứng dụng dùng cấu hình môi trường; khi đã lưu, cấu hình
database ưu tiên và tồn tại qua restart, kể cả trạng thái tắt. Thay đổi được ghi audit cùng thông tin
admin cập nhật. Xem [API cấu hình collection](job-collection-setup.md#2-cấu-hình-collection-qua-api-admin).

V8 thêm **1 bảng**, nâng tổng lên **80 bảng ứng dụng**; V9 và V10 không thêm bảng.
`rag_course_links` lưu trạng thái duyệt, liên kết đến tài nguyên học tập/tài liệu RAG và người duyệt.
V9 giữ snapshot nội dung đã gợi ý cho roadmap, kể cả khi catalog sau đó thay đổi.

Generator entity/JPA repository chỉ đọc baseline V1/V3 và áp dụng các overlay vector V10, metadata mẫu CV V11, lương kỳ vọng V12, rubric CV V13, HTML mẫu CV V14 và bỏ cấu hình mẫu cũ theo V19:
**76 bảng, 713 cột trong phạm vi generator**. Bốn cột tăng thêm từ V10 là các cột embedding hiện tại; metadata/HTML mẫu CV hiện tại thêm hai cột sau khi bỏ `layout_config`, V12 thêm một cột lương kỳ vọng và V13 thêm một cột rubric cách viết;
cột gốc được đổi tên thành `embedding_legacy_1536`. Generator không dựng lại toàn bộ schema của
V1–V10 và không áp dụng các `ALTER TABLE` của V5/V8/V9. Khi sinh lại model, phải đối chiếu và giữ
các mapping được bổ sung sau baseline, bao gồm snapshot V9. Số model trong phạm vi generator
không phải số bảng của database; các workflow repository SQL cũng truy cập các bảng vận hành.

Các ràng buộc bổ sung gồm một chủ thể user/guest cho mỗi lần matching và hội thoại AI; một CV chính đã xác nhận và một subscription đang hoạt động trên mỗi người dùng; ngân sách guest không vượt giới hạn; tổng token bằng input cộng output và số token sử dụng không vượt đặt chỗ; khoảng lương/ngày hợp lệ; câu trả lời thuộc đúng assessment; tránh prerequisite tự tham chiếu; mỗi đơn thanh toán chỉ có một hóa đơn; tổng tiền hóa đơn bằng subtotal cộng tax. Hệ thống giữ FK về tài khoản và lịch sử thanh toán bằng `NO ACTION`; xóa tài khoản ở API nên thực hiện bằng trạng thái và `deleted_at`.

Điểm ATS, chất lượng, confidence và relevance dùng miền 0–100; điểm final/rerank chuẩn hóa dùng 0–1; cosine similarity có thể âm nên dùng -1–1. Embedding được phân biệt bởi provider/model; chỉ một bản `is_current` trên mỗi thực thể/provider/model. Truy vấn phải lọc provider/model trước khi so sánh embedding. Pgvector được bật bằng `CREATE EXTENSION vector`; các HNSW index dùng `vector_cosine_ops`. Tham khảo [tài liệu pgvector](https://github.com/pgvector/pgvector).

## Chuyển embedding sang Ollama trong V10

V10 áp dụng cho `career_profile_embeddings`, `resume_embeddings`, `market_job_embeddings`
và `rag_chunk_embeddings`. Tại mỗi bảng, cột `embedding` cũ được đổi tên thành
`embedding_legacy_1536`, giữ nguyên vector 1536 chiều; cột `embedding vector(1024)` mới ban đầu rỗng.
ID, provider, model, content hash và các liên kết đến dữ liệu nguồn được giữ nguyên. Entity ánh xạ
cột legacy ở chế độ chỉ đọc và không đưa trường này vào JSON.

Migration đặt `is_current=false` cho các embedding profile/CV/job đã có. Tài liệu RAG có trạng thái
`COMPLETED` và có embedding legacy được chuyển về `PENDING`. Với RAG, ràng buộc yêu cầu mỗi bản ghi
phải có vector hiện tại hoặc vector legacy, nên dữ liệu cũ còn hợp lệ trong khi chờ index lại.
Bốn HNSW index được dựng lại trên cột `embedding` 1024 chiều, bỏ qua các giá trị rỗng.

Sau khi Flyway áp dụng V10, index lại CV/job và tài liệu RAG bằng các API tương ứng với provider
`ollama`, model `qwen3-embedding:0.6b`. Migration không gọi model và không tự sinh vector mới.
Vector từ model cũ không được cắt chiều, đệm chiều hoặc dùng thay cho vector của model mới.
Các truy vấn chỉ lấy embedding hiện tại có giá trị và đúng provider/model; vector legacy không
tham gia matching hoặc RAG. Xem [CV và RAG](resume-ai.md), [index job](crawling.md)
và [hướng dẫn Ollama](ollama.md).

Seed có 3 gói FREE/BASIC/PRO, 30 hạn mức token theo tháng gồm `AI_TOTAL_TOKENS`, 15 kỹ năng, 2 mẫu CV trial, một CV demo không có tài khoản chủ sở hữu và 7 tài nguyên từ tài liệu chính thức. Ngân sách tổng lần lượt là 20.000/200.000/1.000.000 token; các feature dùng cùng mức trần ban đầu và vẫn chịu ngân sách tổng. Giá 99.000/199.000 VND và hạn mức là dữ liệu mẫu cho môi trường phát triển, cần cấu hình theo yêu cầu kinh doanh. Không seed tài khoản admin, mật khẩu, API key hay vector giả.

V6 bổ sung 98 liên kết vào `learning_resources` với `source_type=REFERENCE`, kèm nguồn catalog và
ngày đối chiếu trong metadata. `ON CONFLICT (url) DO NOTHING` giữ tài nguyên admin đã có cùng URL.
Đây là danh sách liên kết; xem [phạm vi nguồn roadmap học tập](learning-roadmap-sources.md).

## Chạy PostgreSQL có pgvector

Tạo `.env` từ `.env.example` nếu chưa có; nếu đã có, giữ cấu hình hiện tại và bổ sung các key cần thiết.
Đặt credentials database và `APP_JWT_SECRET` theo [README](../README.md), chạy
`docker compose up -d postgres`, rồi khởi động ứng dụng. Flyway áp dụng migration theo thứ tự.
PostgreSQL trong Compose dùng `pgvector/pgvector:0.8.7-pg17-bookworm`, được liệt kê trong
[các tag chính thức của pgvector](https://github.com/pgvector/pgvector#docker). Các dịch vụ Qdrant,
RabbitMQ và Redis được giữ dưới profile `optional`; bật tất cả bằng `docker compose --profile optional up -d`.

Redis được dùng cho cache kết quả phân tích CV, ATS và matching, nhưng không thay thế các bảng nghiệp vụ. Để chỉ
khởi động Redis, dùng `docker compose up -d --wait redis`, sau đó
`docker compose exec redis redis-cli PING` phải trả `PONG`. Cấu hình `REDIS_*` nằm trong
`.env.example` và `spring.data.redis` trong `application.yaml`; script `run-local.ps1`
nạp `.env` cho backend nhưng `-WithInfrastructure` chỉ khởi động PostgreSQL.

Redis lưu JSON envelope `schema_version`, `identity`, `response`, gồm toàn bộ kết quả trả cho FE:

| Luồng | Key Redis | TTL tối đa |
| --- | --- | --- |
| ATS CV–job | `edunexus:ats:response:v1:<userId>:<resumeId>:<jobId>:<inputHash>` | 24 giờ |
| Matching | `edunexus:matching:response:v1:<userId>:<requestHash>` | 1 giờ, không vượt tuổi run hoặc deadline job |
| Phân tích CV | `edunexus:resume-analysis:response:v1:<userId>:<resumeId>:<inputHash>` | 24 giờ |

Cache hit hợp lệ trả response trực tiếp, không đọc lại các dòng analysis/run/results trong PostgreSQL.
Backend vẫn đọc dữ liệu hiện tại để kiểm tra owner, trạng thái CV/job và tính hash; thay nội dung,
ngôn ngữ, model hoặc prompt làm đổi hash tương ứng. Matching còn kiểm tra catalog/vector/chính sách lương.
ATS và matching tìm lại cache database khi Redis miss/lỗi; DB hit sẽ ghi lại response vào Redis.
Phân tích CV không có DB lookup theo input hash: Redis miss thì tạo phân tích mới và lưu lịch sử trong DB.
Các lần ghi Redis được thực hiện sau commit; rollback không publish cache. Key cũ chỉ chứa ID không được đọc
bằng namespace response mới. PostgreSQL vẫn là nguồn dữ liệu nghiệp vụ và lịch sử.
Xem [cấu hình Redis local](../README.md#redis-cho-cache-ats-va-matching)
và [cách kiểm tra key/TTL](swagger-testing.md#redis-ats).

## Kiểm chứng cục bộ

`scripts/start-local-test-postgres.ps1` tạo cluster PostgreSQL riêng trong `target/local-pg`, chỉ lắng nghe `127.0.0.1:55439`, user/database `edunexus_test`, xác thực trust cho môi trường kiểm thử. Script không dùng database đang có trên máy. `scripts/stop-local-test-postgres.ps1` dừng đúng cluster này và giữ dữ liệu trong `target`.

Script dự phòng dành cho PostgreSQL local không có extension pgvector. Script xếp migration theo
số phiên bản, gồm V10, và chỉ biến đổi bản SQL kiểm thử trong `target/local-pg-schema.sql`:
thay cả `vector(1536)` và `vector(1024)` bằng `real[]`, bỏ extension và HNSW. Migration nguồn
giữ nguyên pgvector. Cách kiểm thử này xác minh DDL, dữ liệu seed và nghiệp vụ quan hệ;
không xác minh kích thước vector, toán tử cosine hay HNSW. Script chỉ cài schema khi chưa có bảng
`users`, không nâng cấp schema của cluster thử nghiệm đã tồn tại; cần database thử nghiệm sạch
để kiểm chứng đầy đủ chuỗi migration mới bằng script này.

Sau khi cluster riêng đã chạy, dùng kết nối `jdbc:postgresql://127.0.0.1:55439/edunexus_test`, user `edunexus_test`, mật khẩu rỗng và `SPRING_FLYWAY_ENABLED=false` vì script đã cài schema. Chạy kiểm tra ràng buộc bằng:

```powershell
& 'C:\Program Files\PostgreSQL\18\bin\psql.exe' -h 127.0.0.1 -p 55439 -U edunexus_test -d edunexus_test -v ON_ERROR_STOP=1 -f scripts/schema-smoke.sql
```

Smoke test tạo fixture trong một transaction, kiểm tra 22 thao tác không hợp lệ rồi rollback toàn bộ,
bao gồm prerequisite thuộc đúng roadmap. Kết quả V1–V4 trong cluster dùng `real[]` là kiểm chứng
lịch sử, không thay cho kiểm thử vector hiện tại.

Ngày 21/10/2026, `EmbeddingMigrationIntegrationTests` đã đạt trên PostgreSQL 17.11 trong Docker có
pgvector: tạo dữ liệu vector 1536 chiều ở V9, nâng lên V10, đối chiếu vector legacy nguyên vẹn,
trạng thái cần reindex và bốn HNSW index; ghi/tìm cosine bằng vector 1024 chiều, từ chối vector
1536 chiều trong cột mới và từ chối bản ghi RAG không còn vector nào. Đây là database kiểm thử
riêng, không phải xác nhận V10 đã được áp dụng vào database ứng dụng của người dùng.

Ngày 22/10/2026, database ứng dụng local `edunexus` đã được sao lưu và nâng lên V10 cùng container
backend Ollama mới. `target/ollama-app-runtime.json` ghi Flyway 10, pgvector 0.8.7, bốn cột vector 1024
và bốn cột legacy 1536; kiểm kê cả bốn bảng embedding đều có 0 dòng, nên không có vector cũ cần
index lại ở database local này. Kết quả này không xác nhận database production.

## Một mức lương kỳ vọng trong V12

`candidate_profiles.expected_salary numeric(14,2)` lưu một số tiền kỳ vọng mỗi tháng,
theo `salary_currency` (`VND`, `USD` hoặc `EUR` trong API). Số tiền phải lớn hơn 0;
`NULL` nghĩa là chưa đặt kỳ vọng lương. Ví dụ 7,5 triệu VND lưu `7500000.00`.
API cập nhật dùng `expectedSalary`; response trả `expected_salary` và ẩn hai cột khoảng lương cũ.

V12 giữ nguyên `expected_salary_min` và `expected_salary_max` để có thể đối chiếu/phục hồi dữ liệu.
Nếu cả hai mức cũ dương, migration lấy trung bình và làm tròn hai chữ số thập phân;
nếu chỉ có một mức dương, dùng mức đó; nếu cả hai trống hoặc bằng 0 thì để `NULL`.
Đây là giá trị chuyển đổi từ dữ liệu cũ, candidate có thể sửa thành số tiền chính xác mong muốn.
Repository không ghi lại hai cột legacy và entity chỉ ánh xạ chúng ở chế độ đọc, không đưa vào JSON.
Embedding hồ sơ cũ được đánh dấu hết hiện hành để tạo lại theo hợp đồng mới.
