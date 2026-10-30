# Cache đánh giá CV và hồ sơ nghề nghiệp

`POST /api/v1/resumes/{id}/profile-alignments` dùng lại kết quả khi nội dung CV,
hồ sơ nghề nghiệp và cấu hình đánh giá không đổi. Cache hit không gọi lại AI,
không tính thêm quota và không tạo thêm một bản lịch sử giống hệt.

Luồng đọc: kiểm tra tài khoản ứng viên còn hoạt động, quyền sở hữu CV, trạng thái
`READY` và hồ sơ có thông tin đối chiếu; sau đó tìm Redis, rồi lịch sử PostgreSQL,
cuối cùng mới gọi AI. Tài khoản hoặc CV không hợp lệ không được đọc kết quả từ cache.

Khóa Redis có dạng `edunexus:cv-profile:response:v1:{userId}:{resumeId}:{inputHash}`,
TTL 24 giờ. Fingerprint chứa định danh chủ sở hữu/CV, content hash và snapshot bằng
chứng CV thực sự gửi tới AI, snapshot hồ sơ cùng kỹ năng, ngôn ngữ, provider/model,
rubric và fingerprint prompt/schema. Sửa nội dung, bằng chứng, kỹ năng, hồ sơ,
ngôn ngữ, model hoặc prompt sẽ cần kết quả cho bộ đầu vào mới. Đổi timestamp hoặc
mẫu trình bày mà không đổi đầu vào đối chiếu không làm mất hiệu lực kết quả.

Redis chứa toàn bộ bản kết quả cùng snapshot và metadata nhận diện. Backend kiểm tra
định danh, snapshot, rubric, bằng chứng và tính lại các điểm tổng trước khi dùng lại.
Redis lỗi kết nối, hết hạn, không có key hoặc dữ liệu sai định dạng sẽ chuyển sang
lịch sử PostgreSQL. Fingerprint nội bộ `_cache_input_hash` được lưu trong
`resume_profile_alignments.result_json`; không cần thay schema và không trả trường
này trong API. Lịch sử cũ chưa có fingerprint cần đánh giá một lần để tạo bản có
fingerprint; các lần sau được tái sử dụng. Kết quả trong PostgreSQL không có TTL,
nhưng chỉ dùng lại khi toàn bộ fingerprint và snapshot hiện tại vẫn khớp.

Redis chỉ được ghi sau khi transaction commit. Trước khi trả kết quả hoặc lưu báo
cáo mới, backend khóa và đọc lại CV/hồ sơ để phát hiện thay đổi giữa lúc chạy; thay
đổi đầu vào trả `409`, không phát hành báo cáo cũ dưới đầu vào mới. Cache của worker
tự đối chiếu lại sau khi sửa hồ sơ vẫn kiểm tra claim token và generation trước khi
đọc, và kiểm tra lại claim khi hoàn tất. Cache không bỏ qua lease hoặc quyền tài khoản.

API POST trả thêm `cached: true` và `cache_source: REDIS|DATABASE` khi dùng lại kết
quả; lần gọi AI mới trả `cached: false`. Các API GET lịch sử giữ nguyên snapshot và
các cờ `resume_changed`, `profile_changed`, `is_stale`.

Đánh giá chất lượng/ATS độc lập của CV vẫn dùng endpoint `/resumes/{id}/analyses`.
Đánh giá CV với một JD cụ thể dùng `/jobs/{jobId}/ats`, có khóa riêng theo chủ sở
hữu, CV, JD và fingerprint đầu vào; Redis 24 giờ cùng fallback PostgreSQL. Cache
đối chiếu hồ sơ không thay thế hai loại đánh giá này và không dùng điểm embedding
làm điểm ATS.
