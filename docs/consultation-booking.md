# Đặt lịch tư vấn theo gói lượt

Migration V27 bổ sung gói lượt, hồ sơ chuyên môn, lịch bận, lịch tư vấn và đánh giá.
Chạy backend với Flyway để áp dụng V26 rồi V27. Không sửa migration cũ hoặc xóa dữ liệu.

Ứng viên mua gói trước, sau đó cung cấp vị trí ứng tuyển, nội dung cần tư vấn, cố vấn và thời gian.
Mỗi lịch dùng một lượt của gói đã thanh toán có cùng thời lượng (30 hoặc 60 phút).
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
khác thời lượng, sai chủ sở hữu hoặc trùng lịch đều bị từ chối.
Từ chối hoặc hủy trước giờ bắt đầu hoàn lượt đúng một lần.
Nếu cố vấn chưa tiếp nhận khi đến giờ bắt đầu, hệ thống tự từ chối và hoàn lượt.

Cố vấn chỉ được tiếp nhận yêu cầu của mình. Buổi đã tiếp nhận kết thúc khi hết khung giờ;
hệ thống thông báo cho ứng viên đánh giá từ 1 đến 5 sao kèm mô tả. Mỗi buổi chỉ được đánh giá một lần.
Không cho đánh giá buổi chưa kết thúc hoặc của người khác.
Tác vụ chạy mỗi 30 giây để hoàn tất buổi đã hết giờ và gửi lời nhắc đánh giá.
Chat của lịch mới chỉ mở trong khung giờ đã tiếp nhận; lịch sử vẫn được giữ sau buổi tư vấn.

API bổ sung dưới `/api/v1`:

| API | Chức năng |
| --- | --- |
| GET /consultants?targetRole=BE | Tìm và xếp hạng cố vấn theo vị trí |
| GET /consultants/{id}/availability?from=...&to=... | Các khoảng bận trong khoảng ISO có múi giờ |
| GET, PUT /consultants/me/profile | Chuyên môn và giới thiệu của cố vấn |
| GET, POST /consultants/me/busy | Xem, tạo khoảng bận |
| DELETE /consultants/me/busy/{id} | Xóa khoảng bận của mình |
| GET, POST /consultation-bookings | Xem hoặc tạo lịch |
| POST /consultation-bookings/{id}/accept | Cố vấn tiếp nhận |
| POST /consultation-bookings/{id}/decline | Cố vấn từ chối và hoàn lượt |
| POST /consultation-bookings/{id}/cancel | Ứng viên hủy trước giờ và hoàn lượt |
| POST /consultation-bookings/{id}/complete | Hoàn tất sau giờ kết thúc |
| POST /consultation-bookings/{id}/rating | Ứng viên gửi đánh giá |

Đặt lịch: `{consultantId, creditOrderId, targetRole, subject, description, startsAt, durationMinutes}`.
Mua gói: POST `/consultation-orders` với `{serviceId}`; subject và description là tùy chọn.
Catalog trả `sessions_count`, `duration_minutes`, `is_session_pack`.
Đơn trả `sessions_total`, `sessions_remaining`, `is_session_pack`.
Giờ gửi từ trình duyệt được chuyển thành ISO có múi giờ và lưu bằng timestamptz.

Kiểm thử database dùng PostgreSQL cục bộ tách biệt qua thuộc tính
`consultation.testDatabaseUrl`, chỉ chấp nhận localhost và tên database kết thúc bằng `_test`.
