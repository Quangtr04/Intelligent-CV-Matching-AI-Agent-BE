# Đặt lịch tư vấn theo gói lượt

Migration V27 bổ sung gói lượt, hồ sơ chuyên môn, lịch bận, lịch tư vấn và đánh giá.
V28 tách lượt khỏi thời lượng; V30 bổ sung tiến trình Google Meet và Google Calendar.
V35 lưu CV và việc làm do ứng viên chọn trên lịch và thread tư vấn; lịch cũ giữ tham chiếu rỗng.
Chạy backend với Flyway để áp dụng các migration mới. Không sửa migration cũ hoặc xóa dữ liệu.

Ứng viên mua gói trước, sau đó cung cấp vị trí ứng tuyển, nội dung cần tư vấn, cố vấn và thời gian.
Mỗi lịch dùng một lượt của gói đã thanh toán; mỗi buổi dài 30 hoặc 60 phút, tối đa một giờ.
Một lượt được dùng cho cả hai thời lượng; không buộc khớp thời lượng ghi trên gói cũ.
Gói tư vấn độc lập với thuê bao AI. Gói một lượt vẫn là gói trả trước.
Giá gói năm lượt ban đầu bằng năm lần giá một lượt; quản trị viên điều chỉnh trong màn hình gói tư vấn.
Số lượt và giá được chụp tại thời điểm mua; thay đổi catalog không ảnh hưởng đơn đã mua.

Webhook thanh toán đã xác thực cấp lượt đúng một lần; không tạo lịch hoặc phân công tự động.
Đơn cũ không phải gói vẫn giữ đường hoàn tất cũ. Dịch vụ bất đồng bộ cũ được ngừng bán.

Ứng viên tìm cố vấn theo vị trí. Cố vấn cần khai báo các vị trí chuyên môn trong màn hình tư vấn.
Danh sách hiển thị điểm trung bình, số đánh giá và số buổi hoàn thành, sắp xếp theo các chỉ số đó.
Lịch trống hiển thị các khoảng đã được đặt hoặc cố vấn tự đánh dấu bận; không tiết lộ nội dung lịch khác.

Trạng thái lịch: PENDING → ACCEPTED → COMPLETED, hoặc PENDING → DECLINED,
hoặc PENDING/ACCEPTED → CANCELLED trước khi bắt đầu.
Yêu cầu đang chờ cũng giữ khung giờ để tránh đặt trùng. Các khoảng kề nhau được phép.
Một lượt được giữ ngay lúc tạo lịch trong giao dịch khóa hàng; hết lượt, gói chưa thanh toán,
thời lượng không hợp lệ, sai chủ sở hữu hoặc trùng lịch đều bị từ chối.
Từ chối hoặc hủy trước giờ bắt đầu hoàn lượt đúng một lần.
Nếu cố vấn chưa tiếp nhận khi đến giờ bắt đầu, hệ thống tự từ chối và hoàn lượt.

Cố vấn chỉ được tiếp nhận yêu cầu của mình. Khi tiếp nhận, trạng thái lịch thành ACCEPTED,
tiến trình tạo cuộc họp thành PENDING và được lưu trong cùng giao dịch. Worker tạo phòng
Google Meet và sự kiện Calendar, mời cả email candidate lẫn consultant. Phòng chỉ mở cho
trình duyệt vào giờ bắt đầu; đến giờ kết thúc worker khóa quyền vào lại và kết thúc conference.
Không có chat nội bộ cho SESSION_BOOKING; các API chat của lịch này trả lỗi.
Lịch sử các ca tư vấn cũ được giữ riêng.

Buổi đã tiếp nhận và mở phòng được kết thúc khi hết khung giờ;
hệ thống thông báo cho ứng viên đánh giá từ 1 đến 5 sao kèm mô tả. Mỗi buổi chỉ được đánh giá một lần.
Không cho đánh giá buổi chưa kết thúc hoặc của người khác.
Tác vụ chạy mỗi 30 giây để hoàn tất buổi đã hết giờ và gửi lời nhắc đánh giá.
Nếu hết giờ nhưng phòng Google Meet chưa từng mở thành công, hệ thống từ chối buổi và
hoàn lượt thay vì tính là buổi đã cung cấp. Quy tắc này áp dụng cả tự động lẫn nút hoàn tất.
Hủy trước giờ tự hủy invitation, khóa phòng và hoàn lượt đúng một lần.

Các trạng thái `meeting_status` là NOT_REQUESTED, PENDING, READY, FAILED, CANCELLED, ENDED.
READY nghĩa là đã tạo phòng và lịch; `meeting_opened_at` xác nhận đã mở phòng vào giờ hẹn.
Candidate và consultant cùng nhận `meeting_url`, `calendar_event_url`, trạng thái và giờ mở/đóng.
Lỗi Google được thử lại với backoff; sự kiện dùng ID cố định theo booking để tránh mời trùng.
Tác vụ đóng phòng có luồng thực thi riêng, không chờ hàng đợi tạo các buổi mới.
Khi Google hoặc backend gặp sự cố, việc đóng thực tế có thể chậm; lịch luôn giới hạn tối đa 60 phút.
Xem [cấu hình OAuth organizer và nghiệm thu](consultation-google-meet.md) trước khi bật tích hợp.

API bổ sung dưới `/api/v1`:

| API | Chức năng |
| --- | --- |
| GET /consultants?targetRole=BE | Tìm và xếp hạng cố vấn theo vị trí |
| GET /consultants/{id}/availability?from=...&to=... | Các khoảng bận trong khoảng ISO có múi giờ |
| GET, PUT /consultants/me/profile | Chuyên môn và giới thiệu của cố vấn |
| GET, POST /consultants/me/busy | Xem, tạo khoảng bận |
| DELETE /consultants/me/busy/{id} | Xóa khoảng bận của mình |
| GET, POST /consultation-bookings | Xem hoặc tạo lịch |
| GET /consultation-bookings/{id}/preparation | Xem CV, JD và báo cáo đã lưu đúng tài liệu được đính kèm |
| POST /consultation-bookings/{id}/accept | Cố vấn tiếp nhận |
| POST /consultation-bookings/{id}/meeting/retry | Người tham gia hoặc Admin yêu cầu thử lại tạo/mở cuộc họp chưa hết giờ |
| POST /consultation-bookings/{id}/decline | Cố vấn từ chối và hoàn lượt |
| POST /consultation-bookings/{id}/cancel | Ứng viên hủy trước giờ và hoàn lượt |
| POST /consultation-bookings/{id}/complete | Hoàn tất sau giờ kết thúc |
| POST /consultation-bookings/{id}/rating | Ứng viên gửi đánh giá |

Đặt lịch: `{consultantId, creditOrderId, targetRole, subject, description, startsAt, durationMinutes, resumeId, jobId}`.
Giao diện ứng viên cho chọn các khung tròn giờ 60 phút, hiển thị cả giờ bắt đầu và kết thúc;
gửi `durationMinutes: 60`. Giờ đã qua, lịch bận cố vấn và lịch PENDING/ACCEPTED của ứng viên không chọn được.
Đổi ngày hoặc cố vấn yêu cầu chọn lại khung giờ. Khung giờ được tính theo múi giờ thiết bị;
chưa có cấu hình giới hạn giờ làm việc cố vấn. Backend vẫn kiểm tra trùng lịch trong giao dịch khi tạo yêu cầu.
`resumeId` bắt buộc và phải là CV READY của chính ứng viên; `jobId` tùy chọn, khi có phải là việc làm
ACTIVE chưa hết hạn. Backend xác minh tài liệu trước khi giữ lượt. Response lịch có `resume_id`,
`resume_title`, `job_id`, `job_title`, `company_name`; tài liệu cũ có thể rỗng.

Preparation trả `{resume, job, resume_analysis, ats_analysis}`. CV chỉ gồm dữ liệu cấu trúc và metadata
an toàn, không có đường dẫn file nội bộ; JD được giữ theo ID đã chọn, vẫn xem được khi tin hết hạn.
Báo cáo CV chỉ lấy từ CV đã đính kèm; ATS phải khớp cả CV và job. Chưa có báo cáo hoặc không chọn job
thì trường tương ứng là null. Lịch cũ không tự lấy CV mới nhất hay CV chính của ứng viên.
Ứng viên, cố vấn được chọn và Admin được xem; hủy/từ chối/hoàn lượt thu hồi quyền đọc tài liệu của
cố vấn, còn ứng viên và Admin giữ quyền xem lịch sử. Lịch PENDING cho cố vấn đọc để chuẩn bị.

Các API `candidate-profile`, `candidate-resumes`, `candidate-analyses` và ghi báo cáo của SESSION_BOOKING
cũng chỉ nhận đúng tài liệu đính kèm. Danh sách báo cáo không mở rộng sang các CV, job hoặc lịch sử AI
khác của ứng viên. Muốn tạo đánh giá mới, cố vấn chủ động gọi POST
`/consultations/{threadId}/resumes/{resumeId}/analyze` hoặc
`/consultations/{threadId}/jobs/{jobId}/ats` với `{resumeId}`; chỉ cho lịch ACCEPTED đang mở.
Việc đọc preparation không gọi AI hoặc tính quota. Các lần tạo AI rõ ràng dùng quota của cố vấn như trước.
Mua gói: POST `/consultation-orders` với `{serviceId}`; subject và description là tùy chọn.
Catalog trả `sessions_count`, `duration_minutes`, `is_session_pack`.
Đơn trả `sessions_total`, `sessions_remaining`, `is_session_pack`.
Giờ gửi từ trình duyệt được chuyển thành ISO có múi giờ và lưu bằng timestamptz.

Kiểm thử database dùng PostgreSQL cục bộ tách biệt qua thuộc tính
`consultation.testDatabaseUrl`, chỉ chấp nhận localhost và tên database kết thúc bằng `_test`.
