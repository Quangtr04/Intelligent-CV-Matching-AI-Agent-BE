# Manager Work Schedule & Automatic Slots

## Audit và tái sử dụng

- `V27__consultation_booking_and_session_packs.sql`: `consultant_profiles`, `consultant_busy_slots`, `consultation_bookings`, `consultation_ratings`.
- Không tìm thấy bảng Schedule/Consultant Availability dùng để phân công ca trong migrations hiện tại. Availability cũ chỉ trả khoảng bận (`BOOKING`, `BUSY`), không phải ca làm việc.
- Giữ nguyên dữ liệu busy cũ và tiếp tục dùng để loại slot. API đọc availability cũ được giữ nguyên.
- Tái sử dụng danh sách Consultant và booking trong trang Manager, phân quyền operations, khóa user `FOR NO KEY UPDATE`, kiểm tra booking PENDING/ACCEPTED.
- Frontend hiện đặt 60 phút; backend hiện chấp nhận 30/60 phút, mặc định 60. Giữ nguyên quy tắc này, không suy diễn thời lượng mới từ credit.

## Hành vi mới

- Manager/Admin chọn Consultant tại trang quản lý Consultant, phân công một ngày hoặc khoảng ngày (tối đa 93 ngày), tùy chọn thứ trong tuần và giờ nghỉ trưa. Múi giờ phân công: Asia/Ho_Chi_Minh.
- Lặp tuần được mở rộng thành các ca cụ thể trong khoảng ngày đã chọn; không tự kéo dài vô hạn. Các ca cùng đợt có chung `series_id`.
- Sửa/hủy từng ca. Hủy mềm giữ lịch sử. Ca đã bắt đầu hoặc có booking PENDING/ACCEPTED bị chặn sửa/hủy bằng HTTP 409; cần giải quyết booking qua luồng hiện có trước.
- Consultant chỉ xem ca của chính mình; không thể tự thêm/xóa giờ bận bằng API cũ (403).
- Candidate chỉ chọn slot do backend trả về, không tự dựng lịch cả ngày.
- Lưới slot neo tại đầu ca, bước = thời lượng hiện có + 15 phút. Loại slot vượt ca, trùng nghỉ trưa, khoảng bận cũ, booking đang giữ/đã xác nhận, booking của Candidate, thời gian đã qua. Không dồn hoặc dịch slot sau khi đặt/hủy.
- Với ca 07:00–17:00, 60 phút, không nghỉ trưa: 07:00, 08:15, 09:30, 10:45, 12:00, 13:15, 14:30, 15:45.
- Tạo booking kiểm tra lại chính xác mốc slot và khoảng nghỉ sau khi khóa Candidate/Consultant, trước khi dùng credit. Phân công/sửa/hủy ca khóa cùng Consultant trong transaction để tuần tự hóa với đặt lịch.
- Booking đã tồn tại ngoài ca mới vẫn dùng luồng accept/cancel/complete/meeting/rating hiện có; không di chuyển hay chuyển trạng thái hàng loạt.

## Database

Migration mới: `V37__consultant_work_shifts.sql`.

Bảng mới duy nhất: `consultant_work_shifts` (Consultant, Manager phân công, đầu/cuối ca, nghỉ trưa, series, thời điểm hủy/tạo).

- CHECK: ca hợp lệ, nghỉ trưa đủ cặp và nằm trong ca.
- GiST exclusion constraint trên Consultant + khoảng thời gian `[start,end)` cho ca chưa hủy; dùng extension `btree_gist`.
- Partial index theo Consultant/thời điểm bắt đầu.
- Không có bảng slot, không xóa bảng/dữ liệu cũ, không sửa bảng booking/credits/payment.

## API

Tất cả dưới `/api/v1`:

| Method | Path | Quyền / tác dụng |
|---|---|---|
| GET | `/consultant-work-shifts?consultantId=...&from=...&to=...` | Manager/Admin xem Consultant được chọn; Consultant luôn chỉ xem chính mình |
| POST | `/consultant-work-shifts` | Manager/Admin tạo các ca trong một transaction |
| PUT | `/consultant-work-shifts/{id}` | Manager/Admin sửa một ca, không đổi Consultant |
| DELETE | `/consultant-work-shifts/{id}` | Manager/Admin hủy mềm một ca |
| GET | `/consultants/{id}/slots?from=...&to=...&durationMinutes=60` | Người đăng nhập lấy slot trống; durationMinutes hỗ trợ 30/60 như booking hiện có |

Body POST/PUT:

```json
{
  "consultantId": "UUID",
  "fromDate": "2026-12-01",
  "toDate": "2026-12-31",
  "startsAt": "07:00",
  "endsAt": "17:00",
  "lunchStartsAt": "12:00",
  "lunchEndsAt": "13:00",
  "weekdays": ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY"]
}
```

PUT yêu cầu fromDate=toDate. Bỏ trống weekdays để phân công mọi ngày trong khoảng. Bỏ cả hai trường lunch để không cấu hình nghỉ trưa. API slot trả `[{starts_at, ends_at}]` và không lộ chi tiết booking người khác.

API đã tích hợp:
- POST `/consultation-bookings`: thêm guard slot được phân công; giữ nguyên phần credit và booking sau guard.
- POST `/consultants/me/busy`, DELETE `/consultants/me/busy/{id}`: ngừng cho phép tự sửa lịch (403). GET cũ giữ nguyên.
- API danh sách Consultant/booking của Manager hiện có được tái sử dụng.

## File thuộc thay đổi này

Backend (đường dẫn Java tính từ `src/main/java/com/example/intelligent_cv_matching_ai_agent/`):

- `dto/request/consultant/consultation/WorkShiftRequest.java` — mới.
- `controller/shared/consultation/WorkShiftController.java` — mới.
- `service/shared/consultation/WorkShiftService.java` — mới.
- `service/shared/consultation/WorkShiftSlots.java` — mới.
- `service/shared/consultation/ConsultationBookingService.java` — guard slot và ngừng tự sửa busy.
- `repository/shared/consultation/ConsultationBookingRepository.java` — query Candidate intervals và kiểm tra mốc slot.
- `src/main/resources/db/migration/V37__consultant_work_shifts.sql` — mới.
- `src/test/java/com/example/intelligent_cv_matching_ai_agent/consultation/WorkShiftTests.java` — mới.
- `src/test/java/com/example/intelligent_cv_matching_ai_agent/billing/ConsultationBookingConcurrencyDatabaseTests.java` — fixture ca phân công, kiểm tra constraint và cạnh tranh booking.

Frontend:

- `src/services/workShiftService.ts` — mới.
- `src/components/consultation/WorkShiftPanel.tsx` — mới, dùng chung Manager/Consultant.
- `src/pages/admin/ManagerConsultantsPage.tsx` — tích hợp phân công bên cạnh theo dõi booking.
- `src/components/consultation/ConsultantBookingManager.tsx` — xem ca và bỏ UI tự sửa busy.
- `src/pages/candidate/consultation/CandidateBookingForm.tsx` — lấy slot backend, giữ nguyên CV/job/credits/booking.
- `tests/booking-attachments.test.mjs` — cập nhật fixture và assertions theo slot backend.
- `tests/booking-work-shifts.test.mjs` — mới, kiểm tra API client lịch và slot.

## Kiểm thử và triển khai

- Backend: formatter:format, formatter:validate và Maven verify với WorkShiftTests, ConsultationBookingConcurrencyDatabaseTests cùng các test hồi quy BookingService, BookingScenario, MeetingScheduling, MeetingService, Payment.
- Frontend: `npm.cmd run build`, `npm.cmd run test:booking`, `npm.cmd run test:roles`.
- Migration V37 chạy trên PostgreSQL localhost thử nghiệm (`edunexus_test`, port 55439). Môi trường thử nghiệm dùng schema bản sao không có pgvector, không thay đổi các migration production.
- Khi triển khai, chạy Flyway V37 trước khi mở backend mới. Database cần cho phép cài `btree_gist`. Chưa áp dụng vào database ứng dụng.
- Sau triển khai, Manager cần phân công ca tương lai trước khi Candidate thấy slot. Không tự tạo ca từ booking hoặc dữ liệu busy cũ.
- Không chỉnh logic thời lượng, Credits, SePay, Google Calendar/Meet, Rating hoặc trạng thái booking.

Kết quả cuối: Backend verify thành công, 74/74 test đã chọn qua (gồm 5 test PostgreSQL thật); frontend build thành công, 56/56 test booking và 17/17 test roles qua. Không chạy toàn bộ test suite ngoài phạm vi này.

Cập nhật runtime local ngày 13/11/2026: đã thay container edunexus-app-1 bằng JAR có API lịch; Flyway đã áp dụng V37 thành công vào database local edunexus. Đây là bước khắc phục 404 do container cũ chưa có controller lịch.
