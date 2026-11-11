# Google Meet và Google Calendar cho buổi tư vấn

Backend dùng một tài khoản Google tổ chức để tạo phòng Meet, tạo sự kiện trên lịch của tài khoản đó và mời **cả candidate và consultant** theo email lưu trong tài khoản dự án. Đây là OAuth grant riêng cho dịch vụ tư vấn, không phải Google Sign-In của người dùng.

## Cấu hình organizer một lần

1. Trong Google Cloud project của nhóm, bật **Google Calendar API** và **Google Meet REST API**. Cấu hình OAuth consent screen; nếu app ở Testing, thêm tài khoản organizer làm test user.
2. Tạo OAuth client loại Web application. Với cách thiết lập qua [Google OAuth Playground](https://developers.google.com/oauthplayground), thêm redirect URI `https://developers.google.com/oauthplayground` vào OAuth client.
3. Mở Playground, chọn cấu hình OAuth server-side, access type **Offline**, và **Use your own OAuth credentials**. Điền client ID/secret của project. Hướng dẫn chính thức của Google có [các bước dùng Playground với OAuth client riêng](https://developer.chrome.com/docs/webstore/using-api).
4. Xin consent cho đúng hai scope dưới đây. Đăng nhập bằng **cùng một organizer** sẽ sở hữu phòng Meet và lịch. Exchange authorization code for tokens và lấy refresh token. Nếu không có refresh token, thực hiện consent lại với offline access và `prompt=consent`; không lấy ID token đăng nhập để thay thế.

```text
https://www.googleapis.com/auth/calendar.events
https://www.googleapis.com/auth/meetings.space.created
```

Refresh token cho phép server hoạt động khi organizer không mở trình duyệt; access token được lấy lại tại token endpoint và chỉ cache trong bộ nhớ tới trước thời điểm hết hạn. Xem [OAuth offline access của Google](https://developers.google.com/identity/protocols/oauth2/web-server). Không có endpoint public trong ứng dụng để thu hoặc trả credential organizer.

5. Đặt các giá trị sau trong secret environment của backend hoặc `.env` không commit. `.env.example` chỉ cung cấp tên biến; không đặt refresh token ở FE/Vite hay trong response booking.

```dotenv
GOOGLE_CALENDAR_ENABLED=true
GOOGLE_CALENDAR_CLIENT_ID=<oauth-client-id>
GOOGLE_CALENDAR_CLIENT_SECRET=<oauth-client-secret>
GOOGLE_CALENDAR_REFRESH_TOKEN=<organizer-refresh-token>
GOOGLE_CALENDAR_ID=primary
GOOGLE_CALENDAR_TIME_ZONE=Asia/Ho_Chi_Minh
GOOGLE_MEET_ACCESS_TYPE=OPEN
GOOGLE_CALENDAR_REQUEST_TIMEOUT=PT20S
```

`primary` là lịch của organizer. Nếu đổi calendar ID, organizer phải có quyền ghi và nên giữ lịch do cùng organizer sở hữu. Request timeout phải dương, tối đa `PT30S`; mặc định 20 giây, connect timeout tối đa 10 giây. Giới hạn này giữ cả OAuth refresh và một lần thử lại 401 trong cửa sổ lease của worker. Production không nên bật HTTP body/header TRACE logging. Client không ghi credential, token hay raw error body của Google vào lỗi trả về.

App mặc định tắt tích hợp; credentials thiếu không làm application startup lỗi. Gọi provider khi tắt/thiếu cấu hình trả lỗi 503 có tên biến cần bổ sung, không chứa giá trị bí mật. `GOOGLE_CLIENT_ID` dùng cho đăng nhập không tự cấp quyền Calendar/Meet cho organizer.

Sau khi điền credential và bật tích hợp, khởi động lại backend để nạp cấu hình. Flyway áp dụng V30
và xếp tạo họp cho các booking ACCEPTED chưa hết giờ đã có trước migration. Không ghi token vào
Git, frontend hoặc file báo cáo. File `.env` hiện chỉ được bổ sung các tên biến còn thiếu,
mặc định tắt, không thay đổi giá trị sẵn có.

Google có thể thu hồi hoặc làm refresh token hết hạn. Đặc biệt, OAuth app External có publishing status Testing thường cấp token hết hạn sau **7 ngày** cho các scope này; xem [quy tắc hết hạn refresh token](https://developers.google.com/identity/protocols/oauth2). Cần thực hiện consent lại hoặc cấu hình publishing phù hợp trước vận hành dài hạn.

## Hành vi API và thời gian buổi tư vấn

Provider client `GoogleConsultationMeetingClient` không giữ transaction DB hoặc tự điều khiển booking. Worker của booking lưu tiến trình và gọi các bước cần thiết:

| Thao tác | Google API và kết quả |
|---|---|
| `createSpace()` | `POST https://meet.googleapis.com/v2/spaces`; lấy `name` và `meetingUri`, tạo ban đầu với `entryPointAccess=CREATOR_APP_ONLY` |
| `getSpace(name)` | `GET /v2/{name}`; đọc lại đúng resource đã lưu |
| `openSpace(name)` | `PATCH /v2/{name}?updateMask=config.entryPointAccess`, đặt `ALL` để cho trình duyệt vào khi đến giờ |
| `restrictSpace(name)` | PATCH cùng trường, đặt lại `CREATOR_APP_ONLY` để chặn các entry point thông thường trước/sau buổi |
| `endConference(name)` | `POST /v2/{name}:endActiveConference`, không có request body; kết thúc conference đang hoạt động |
| `createEvent(bookingId, booking, meetingUrl)` | Calendar events.insert với ID xác định từ booking UUID, cả hai attendees, start/end có UTC offset và timezone cấu hình, `sendUpdates=all` |
| `getEvent(bookingId)` | Calendar events.get theo cùng ID; dùng khôi phục khi insert trả 409 |
| `cancelEvent(bookingId)` | GET để xác minh đúng booking rồi events.delete với `sendUpdates=all`; 404/410 hoặc tombstone được coi đã xóa |

Google mô tả riêng [tạo space](https://developers.google.com/workspace/meet/api/reference/rest/v2/spaces/create), [đọc space](https://developers.google.com/workspace/meet/api/reference/rest/v2/spaces/get), [PATCH space](https://developers.google.com/workspace/meet/api/reference/rest/v2/spaces/patch) và [kết thúc conference](https://developers.google.com/workspace/meet/api/reference/rest/v2/spaces/endActiveConference). REST update mask dùng **`config.entryPointAccess`**; tên snake_case xuất hiện trong generated SDK không phải chuỗi REST của client này. Ví dụ cURL nằm trong [hướng dẫn cấu hình meeting space](https://developers.google.com/workspace/meet/api/guides/meeting-spaces-configuration).

Booking của ứng dụng chỉ nhận 30 hoặc 60 phút, không quá 60 phút. **Thời gian kết thúc trên Calendar không tự cắt cuộc gọi.** Đến giờ worker phải restrict entry points và gọi endActiveConference; khi hủy cũng cần dọn các tài nguyên đã tạo. EndActiveConference riêng lẻ không xóa space và không ngăn dùng lại link. Chặn entry points và kết thúc conference là hai thao tác riêng, cần thử lại độc lập khi một bước lỗi.

Giới hạn thời gian được thực thi bằng scheduler và Google API; độ trễ phụ thuộc chu kỳ worker, kết nối và Google. Không cam kết ngắt chính xác tới từng giây khi backend ngừng hoạt động, API lỗi hoặc tài khoản mất quyền. Space tạo xong nhưng mất response có thể để lại room chưa mở; API create space không có booking idempotency key. Worker cần lưu resource name ngay khi nhận được và không tạo lại room khi đã lưu.

Worker quét mỗi 5 giây, có nhóm thực thi riêng cho tạo phòng, mở phòng và đóng phòng để công việc
tạo mới không chặn các buổi tới giờ kết thúc. Lease được lưu DB và gia hạn trước từng bước;
kết quả HTTP đến muộn sau hủy/mất lease sẽ xếp cleanup lại thay vì làm sống lại lịch.
Retry tạo phòng dùng backoff tối đa 5 phút nhưng không vượt ends_at; cleanup được thử lại sau
5 giây. Nếu đến cuối khung giờ mà `meeting_opened_at` vẫn rỗng, cả hoàn tất thủ công và tác vụ tự
động đều chuyển lịch thành DECLINED và hoàn lượt một lần. Buổi đã mở thành công mới chuyển
COMPLETED và cho đánh giá. Việc người nhận có thật sự vào Meet hay không chưa được đo attendance.

## Quyền vào phòng và lời mời Calendar

`GOOGLE_MEET_ACCESS_TYPE` chỉ nhận `OPEN`, `TRUSTED`, `RESTRICTED`; mặc định **OPEN** để candidate và consultant vào trong giờ mà không chờ organizer trung tâm tham gia. Trong khoảng room mở, ai có link cũng có thể vào theo nghĩa của Google. Link chỉ nên hiển thị cho người có quyền booking và được gửi trong invitation. Đây không phải cơ chế Google chỉ cho đúng hai email tham gia.

Nếu chọn RESTRICTED hoặc TRUSTED, chính sách tài khoản Google có thể yêu cầu organizer chấp nhận người tham gia. Việc đưa URL vào Calendar location/description không tạo vai trò co-host hoặc quyền admission riêng trong Meet. Xem [AccessType và EntryPointAccess](https://developers.google.com/workspace/meet/api/reference/rest/v2/spaces). Tích hợp này không cấp thêm quyền organizer/co-host cho hai attendees.

Calendar event chứa Meet URL trong **location và description**. Không tự chế `conferenceData`, signature hoặc conference ID cho room do Meet API tạo. ID sự kiện là `consultation` cộng UUID bỏ dấu gạch, thuộc tập ký tự Calendar cho phép. Insert trả 409 thì client đọc sự kiện ID đó và kiểm tra private booking ID cùng Meet URL; không nhận sự kiện trùng ID của lịch khác, không sinh ID mới để gửi invitation lần nữa. Trước xóa cũng kiểm tra booking binding. Xem [events.insert](https://developers.google.com/workspace/calendar/api/v3/reference/events/insert) và [events.delete](https://developers.google.com/workspace/calendar/api/v3/reference/events/delete).

Sự kiện được ghi trực tiếp vào lịch organizer và gửi lời mời đến hai email với `sendUpdates=all`. Nó có thể xuất hiện trong lịch người nhận ngay hoặc sau khi RSVP, tùy cài đặt invitation của họ. Ứng dụng không có quyền ghi trực tiếp vào mọi lịch cá nhân chỉ nhờ email đăng nhập; xem [cách Google xử lý attendees](https://developers.google.com/workspace/calendar/api/concepts/inviting-attendees-to-events). Kiểm tra email, spam và nút RSVP nếu người nhận chưa thấy lịch.

## Kiểm tra cục bộ và nghiệm thu

`GoogleConsultationMeetingClientTests` dùng `MockRestServiceServer`; không gọi Google và không dùng credential thật. Kiểm tra OAuth form/cache/refresh, config thiếu, ban đầu chặn room, PATCH mở/khóa, kết thúc conference, hai attendees/timezone/duration, insert conflict, cancellation idempotent và che nội dung lỗi nhạy cảm.

```powershell
mvn -B '-Dtest=GoogleConsultationMeetingClientTests' test
```

Sau khi có organizer hợp lệ, nghiệm thu trên staging với hai tài khoản người dùng khác nhau: consultant nhận lịch tương lai → có cùng Meet link và invitation → link không dùng được trước giờ → vào đúng giờ → đến ends_at room bị chặn và conference kết thúc → thử vào lại → hủy lịch khác và kiểm tra cancellation invite. Kiểm tra thêm Google từ chối quyền, token hết hạn và worker restart; không đánh dấu live integration đạt chỉ từ test mock.
