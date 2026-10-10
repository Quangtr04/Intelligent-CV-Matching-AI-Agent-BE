# Kết quả kiểm chứng backend

Kiểm chứng ngày 10/10/2026 trên Java 25, Maven 3.9.11 và cluster PostgreSQL 18 riêng tại `127.0.0.1:55439`.

| Kiểm tra | Kết quả |
| --- | --- |
| `mvn verify` | BUILD SUCCESS; 115 test, 113 đạt, 0 lỗi, 2 bỏ qua |
| Định dạng Java | 336 file ứng dụng, test và công cụ; thụt lề 4 dấu cách; annotation và tham số tách dòng; không có dòng vượt 120 ký tự |
| Bảo toàn logic khi format | So sánh cây cú pháp của 334 file có sẵn: không đổi logic, thứ tự annotation hoặc giá trị chuỗi; 2 công cụ kiểm tra mới nằm ngoài baseline |
| API integration với PostgreSQL | 11 test đạt: OTP/session, CV, matching, assessment, roadmap, tư vấn, ATS/draft/assistant, quota, webhook, OpenAPI và ánh xạ JPA |
| Entity và repository JPA | Đọc User qua JPA và DTO hồ sơ; lưu Skill với UUID tự sinh; đọc JSON của Resume và khóa ghép ResumeSkill |
| Phân lớp và vai trò | 23 controller; 47 request DTO và 2 response DTO; 76 entity cùng 6 lớp khóa ghép; 76 repository JPA và 27 repository workflow; 28 service và 7 util |
| Ràng buộc database | 22 thao tác sai nghiệp vụ bị từ chối; fixture hợp lệ được chấp nhận và rollback |
| JAR chạy thực tế, AI tắt | Health UP; `/api/v1/jobs` và `/v3/api-docs` trả 200; `/api/v1/me` chưa đăng nhập trả 401 |
| OpenAPI | Giữ đủ 106 đường dẫn API; các endpoint job, thông báo, phân công tư vấn, xem hồ sơ ứng viên và support đã tách controller theo vai trò |
| Log HTTP/SQL | Giữ INFO khi môi trường kế thừa `DEBUG=release`; cấu hình cuối đã được đóng gói và kiểm tra lại bằng JAR |
| Cấu hình | Compose parse thành công; script PowerShell parse thành công; `git diff --check` không phát hiện lỗi whitespace |

Hai test Testcontainers kiểm tra Flyway trên PostgreSQL/pgvector và toán tử vector bị bỏ qua vì Docker daemon chưa khả dụng. Schema cục bộ chỉ thay vector bằng `real[]` trong bản SQL sinh ở `target`; migration nguồn không bị sửa. Kết quả trên không xác minh semantic search, HNSW hoặc kích thước vector thực tế.

Các test AI dùng phản hồi giả lập có kiểm soát để kiểm tra hợp đồng, quota, quyền sở hữu và lưu dữ liệu. Crawling dùng fixture HTML; chưa gọi model trả phí, gửi email SMTP, thanh toán thật hoặc kiểm chứng crawling trên website đang hoạt động. JAR được kiểm tra với AI tắt. Cần cấu hình các dịch vụ ngoài theo [README](../README.md) và chạy lại test pgvector khi Docker khả dụng.

Log build sau khi format và báo cáo JUnit nằm trong `target/clean-code-verify.log` và `target/surefire-reports`; log formatter nằm trong `target/java-format.log`. JAR sau khi chia thư mục theo vai trò được kiểm tra trong `target/role-runtime.log`. Backend dùng cho smoke test đã được dừng sau kiểm tra. Xem [cấu trúc lớp](architecture.md) để tra cứu controller, request/response DTO, entity, repository, service và util; quy ước định dạng nằm trong [Java coding style](java-coding-style.md).
