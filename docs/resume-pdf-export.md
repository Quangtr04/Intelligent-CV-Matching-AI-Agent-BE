# Xuất PDF từ CV đã xác nhận

Candidate dùng Bearer token và CV thuộc tài khoản, trạng thái `READY`.

- `POST /api/v1/resumes/{id}/export`: sinh PDF, lưu file và metadata; trả `id`, `file_url`, `file_name`,
  `mime_type`, `file_size_bytes`, `cached`.
- `GET /api/v1/resumes/{id}/download`: xuất nếu cần, rồi trả PDF hiện tại bằng attachment.
- `GET /api/v1/resumes/{id}/download?text=true`: vẫn trả nội dung văn bản.

`resumes.source` giữ loại nguồn `UPLOAD`, `MANUAL`, `AI_BUILDER`; không phải cột đường dẫn.
PDF được lưu dưới `UPLOAD_DIR` và khóa file tương đối được ghi vào `resumes.file_url`.
File upload gốc được giữ lại với `original_file_url`; việc sửa CV và xuất PDF không thay đổi file gốc.
Không mở trực tiếp thư mục upload ra public; tải tài liệu qua endpoint có kiểm tra chủ sở hữu.

Renderer dùng nội dung đã xác nhận và HTML template đang gắn với CV. Nếu chưa có template, dùng mẫu A4
mặc định. Giá trị CV được chèn dưới dạng văn bản đã escape; HTML template vẫn qua bộ kiểm tra mẫu hiện có.
Quá trình in dùng profile trình duyệt riêng, CSP chặn script/tài nguyên ngoài và tắt truy cập mạng.

Fingerprint gồm nội dung CV, tiêu đề, ngôn ngữ, các dữ liệu trình bày và nội dung template. Khi không đổi,
file đã lưu được dùng lại. Sau sửa CV phải xác nhận lại trước khi xuất; đổi template cũng tạo PDF mới.
Nếu CV/template đổi trong lúc renderer chạy, file chưa công bố được bỏ và API trả `409` để thử lại.
Render không giữ transaction/row lock; việc công bố file được kiểm tra lại trong transaction ngắn.

## Cấu hình

```properties
APP_PDF_BROWSER_PATH=
APP_PDF_TIMEOUT_SECONDS=45
APP_PDF_MAX_CONCURRENT=2
APP_PDF_NO_SANDBOX=false
```

Trên Windows, backend tự tìm Chrome hoặc Edge ở vị trí cài đặt mặc định. Có thể chỉ rõ executable bằng
`APP_PDF_BROWSER_PATH` (dùng dấu `/`, không bọc giá trị trong dấu nháy). Trên Linux, backend tìm Chromium
hoặc Google Chrome trong `/usr/bin`. Docker image đã cài Chromium và font, không yêu cầu cài riêng.
Compose đặt `APP_PDF_NO_SANDBOX=true` cho browser chạy trong container; host giữ sandbox mặc định.
Nếu hết slot export trả `429`, timeout trả `504`, thiếu browser hoặc lỗi storage trả `503`.

Flyway `V24__resume_pdf_exports_and_analysis_cache.sql` thêm metadata PDF và giữ đường dẫn upload gốc.
Không chạy trực tiếp migration bằng tay lên database thật; backend áp dụng khi khởi động như các migration khác.

## Kiểm tra renderer thật

```powershell
mvn -B "-Dtest=ResumePdfRendererTests" "-Dedunexus.pdf.smoke=true" test
```

Smoke test xuất mẫu tiếng Việt nhiều trang vào `target/pdf-smoke/confirmed-cv.pdf`, trích xuất lại text
để kiểm tra nội dung cuối tài liệu và render từng trang PNG để kiểm tra hiển thị.
