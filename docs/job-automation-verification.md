# Kiểm chứng các chức năng thu thập Job — 13/10/2026

Đã triển khai các mục 4, 6, 8, 9, 12, 13, 14, 15: JSON-LD/HTML parsing; skills và các trường yêu cầu; dedup đa nguồn; expiry; URL discovery; scheduled collection; normalization; automatic embedding.

## Kết quả

- `mvn verify` với PostgreSQL thử nghiệm tại localhost:55439: **185 tests, 0 failures, 0 errors, 2 skipped**; đóng gói JAR thành công.
- Sau thay đổi cuối ngăn lấy skills từ phần việc làm liên quan, chạy lại `mvn verify '-Dtest=JobPostingParserTests,JobCrawlServiceTests'`: **20 tests, 0 failures, 0 errors**; đóng gói lại thành công.
- Formatter đã chạy và validate qua Maven. Những thay đổi mới qua kiểm tra whitespace của Git.
- Migration `V5__job_collection_automation.sql` đã chạy thành công trên database thử nghiệm có schema V1–V4.

## Những luồng đã kiểm tra

- JSON-LD array/@graph, HTML fallback riêng theo nguồn, metadata/skills/dates, từ chối dữ liệu thiếu hoặc mơ hồ.
- Discovery URL hợp lệ, loại tracking, giới hạn trang và xử lý search/detail failure, HTTP404/410.
- Import/refresh idempotent, metadata chuẩn hóa, tạo queue; dedup hai nguồn, đổi version và quay lại version cũ.
- Alias còn hiệu lực giữ canonical job/vector; hết mọi nguồn mới hết hạn; link ứng tuyển chọn nguồn còn hoạt động.
- Collection lease giữa nhiều transaction, completion token fencing, cấu hình admin, source failure và monitoring.
- Queue unique/upsert, SKIP LOCKED thật, lease reclaim, retry/backoff, quota/usage theo admin, không publish vector khi hash/lease/job thay đổi.

Full integration suite còn phát hiện JWT access token có thể trùng khi cấp/refresh trong cùng một giây. JWT nay có `jti` riêng; test đăng ký/xác minh/login/refresh qua.

## Giới hạn của lần kiểm chứng

PostgreSQL cài tại máy không có pgvector; bản schema test dùng `real[]` thay vector và bỏ HNSW. Hai test Docker/Flyway/pgvector được bỏ qua vì Docker daemon chưa chạy. Production migration vẫn dùng `vector(1536)`; chưa xác minh numerical cosine/HNSW trong lần chạy này.

Tests dùng HTML fixture và AI mock, không gọi website ITVIEC/TopCV hoặc provider trả phí. Cần kiểm tra tương thích HTML nguồn thực và cấu hình provider trong môi trường triển khai. Tin render bằng JavaScript/challenge có thể được ghi thành item lỗi.

Collection mặc định tắt; queue không gọi AI khi provider chưa bật. Cấu hình và request mới xem [crawling.md](crawling.md), [job-collection-setup.md](job-collection-setup.md), [postman-api-guide.md](postman-api-guide.md).

