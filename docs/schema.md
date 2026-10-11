# Cơ sở dữ liệu EduNexus

`V1__edunexus_complete_schema.sql` chuyển đầy đủ DBML đã cung cấp sang PostgreSQL: **74 bảng, 693 cột, 118 quan hệ**, gồm cả bảng phụ và các khóa ghép. JSON dùng `jsonb`, thời điểm dùng `timestamptz`, địa chỉ IP dùng `inet`, danh sách UUID dùng `uuid[]`, embedding dùng `vector(1536)`.

15 enum logic trong DBML được biểu diễn bằng `varchar(32)` với `CHECK` đúng tập giá trị. Cách này giữ ràng buộc giá trị và cho phép JDBC truyền chuỗi trực tiếp. Khóa UUID `id` có mặc định `gen_random_uuid()`; các thời điểm bắt buộc như `created_at`, `updated_at`, `started_at` có mặc định `now()`.

| Migration | Nội dung |
| --- | --- |
| V1 | Toàn bộ bảng, cột, PK, unique, index và FK của DBML; kiểm tra số không âm và miền điểm số |
| V2 | Bảo vệ luồng nghiệp vụ, index tìm kiếm/lịch sử, HNSW cosine, trigger cập nhật `updated_at` |
| V3 | Hai bảng vận hành `ai_token_reservations` và `payment_webhook_events` |
| V4 | Danh mục khởi tạo để chạy thử |

V3 thêm **2 bảng**, tổng cộng **76 bảng ứng dụng**: `ai_token_reservations` đặt chỗ ngân sách AI trước khi gọi dịch vụ; `payment_webhook_events` chống xử lý lặp cùng thông báo thanh toán của nhà cung cấp. Các bảng này bổ sung triển khai, không thuộc DBML gốc.

Các ràng buộc bổ sung gồm một chủ thể user/guest cho mỗi lần matching và hội thoại AI; một CV chính đã xác nhận và một subscription đang hoạt động trên mỗi người dùng; ngân sách guest không vượt giới hạn; tổng token bằng input cộng output và số token sử dụng không vượt đặt chỗ; khoảng lương/ngày hợp lệ; câu trả lời thuộc đúng assessment; tránh prerequisite tự tham chiếu; mỗi đơn thanh toán chỉ có một hóa đơn; tổng tiền hóa đơn bằng subtotal cộng tax. Hệ thống giữ FK về tài khoản và lịch sử thanh toán bằng `NO ACTION`; xóa tài khoản ở API nên thực hiện bằng trạng thái và `deleted_at`.

Điểm ATS, chất lượng, confidence và relevance dùng miền 0–100; điểm final/rerank chuẩn hóa dùng 0–1; cosine similarity có thể âm nên dùng -1–1. Embedding được phân biệt bởi provider/model; chỉ một bản `is_current` trên mỗi thực thể/provider/model. Truy vấn phải lọc provider/model trước khi so sánh embedding. Pgvector được bật bằng `CREATE EXTENSION vector`; các HNSW index dùng `vector_cosine_ops`. Tham khảo [tài liệu pgvector](https://github.com/pgvector/pgvector).

Seed có 3 gói FREE/BASIC/PRO, 30 hạn mức token theo tháng gồm `AI_TOTAL_TOKENS`, 15 kỹ năng, 2 mẫu CV trial, một CV demo không có tài khoản chủ sở hữu và 7 tài nguyên từ tài liệu chính thức. Ngân sách tổng lần lượt là 20.000/200.000/1.000.000 token; các feature dùng cùng mức trần ban đầu và vẫn chịu ngân sách tổng. Giá 99.000/199.000 VND và hạn mức là dữ liệu mẫu cho môi trường phát triển, cần cấu hình theo yêu cầu kinh doanh. Không seed tài khoản admin, mật khẩu, API key hay vector giả.

## Chạy PostgreSQL có pgvector

Sao chép `.env.example` thành `.env`, thay mật khẩu mẫu, chạy `docker compose up -d postgres`, rồi khởi động ứng dụng. Flyway áp dụng migration theo thứ tự. PostgreSQL trong Compose dùng `pgvector/pgvector:0.8.7-pg17-bookworm`, được liệt kê trong [các tag chính thức của pgvector](https://github.com/pgvector/pgvector#docker). Các dịch vụ Qdrant, RabbitMQ và Redis được giữ dưới profile `optional`; bật tất cả bằng `docker compose --profile optional up -d`.

Redis được dùng cho cache ATS và matching, nhưng không thay thế các bảng nghiệp vụ. Để chỉ
khởi động Redis, dùng `docker compose up -d --wait redis`, sau đó
`docker compose exec redis redis-cli PING` phải trả `PONG`. Cấu hình `REDIS_*` nằm trong
`.env.example` và `spring.data.redis` trong `application.yaml`; script `run-local.ps1`
nạp `.env` cho backend nhưng `-WithInfrastructure` chỉ khởi động PostgreSQL.

Key ATS có dạng `edunexus:ats:<userId>:<resumeId>:<jobId>:<inputHash>`, lưu UUID analysis
với TTL 24 giờ. Nội dung phân tích và kiểm tra quyền/hash vẫn lấy từ PostgreSQL; khi Redis
không kết nối được, backend tìm cache database. Matching `/api/v1/matching` dùng cache
database một giờ, và có thêm key Redis `edunexus:matching:<userId>:<requestHash>` TTL 1 giờ.
Xem [cấu hình Redis local](../README.md#redis-cho-cache-ats-va-matching)
và [cách kiểm tra key/TTL](swagger-testing.md#redis-ats).

## Kiểm chứng cục bộ

`scripts/start-local-test-postgres.ps1` tạo cluster PostgreSQL riêng trong `target/local-pg`, chỉ lắng nghe `127.0.0.1:55439`, user/database `edunexus_test`, xác thực trust cho môi trường kiểm thử. Script không dùng database đang có trên máy. `scripts/stop-local-test-postgres.ps1` dừng đúng cluster này và giữ dữ liệu trong `target`.

PostgreSQL 18 trên máy hiện tại có binary nhưng chưa có extension pgvector. Script chỉ biến đổi bản SQL kiểm thử trong `target/local-pg-schema.sql`: thay `vector(1536)` bằng `real[]`, bỏ extension và HNSW. Migration nguồn giữ nguyên pgvector. Cách kiểm thử này xác minh DDL, dữ liệu seed và nghiệp vụ quan hệ; không xác minh kích thước vector, toán tử cosine hay HNSW. Chạy integration vector trên PostgreSQL Compose có pgvector để kiểm chứng các phần đó.

Sau khi cluster riêng đã chạy, dùng kết nối `jdbc:postgresql://127.0.0.1:55439/edunexus_test`, user `edunexus_test`, mật khẩu rỗng và `SPRING_FLYWAY_ENABLED=false` vì script đã cài schema. Chạy kiểm tra ràng buộc bằng:

```powershell
& 'C:\Program Files\PostgreSQL\18\bin\psql.exe' -h 127.0.0.1 -p 55439 -U edunexus_test -d edunexus_test -v ON_ERROR_STOP=1 -f scripts/schema-smoke.sql
```

Smoke test tạo fixture trong một transaction, kiểm tra 22 thao tác không hợp lệ rồi rollback toàn bộ, bao gồm prerequisite thuộc đúng roadmap. Toàn bộ V1–V4 đã chạy thành công trong cluster riêng với biến đổi vector nêu trên.
