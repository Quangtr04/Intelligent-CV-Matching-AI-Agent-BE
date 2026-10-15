# Đối chiếu consultation với BoxChat

Ngày kiểm tra: 14/10/2026. Nguồn tham khảo là
[BoxChat-project](https://github.com/thangnguyen-max/BoxChat-project/tree/c9430d3184d69d4a7cfa8d6cddf5951e7629d46c),
commit `c9430d3184d69d4a7cfa8d6cddf5951e7629d46c` được đọc trong lần kiểm tra này.

## Kết luận

Backend hiện đi đúng hướng tư vấn bằng chat 1–1 giữa Candidate và Consultant được phân công.
Nó đã có phòng tư vấn, quyền truy cập, tin nhắn lưu database và nhận tin trực tiếp qua SSE.
Nó chưa triển khai đầy đủ trải nghiệm/protocol của repo mẫu: gửi ảnh và WebSocket/STOMP còn thiếu;
cách chọn người trò chuyện cũng khác vì consultation cần Admin phân công.

SSE hiện là một cơ chế nhận tin realtime. Khác biệt SSE/WebSocket không tự chứng minh nghiệp vụ sai,
nhưng frontend STOMP của repo mẫu không thể kết nối trực tiếp với API SSE hiện tại.

## Repo mẫu thực sự có gì

- [WebSocketConfig.java](https://github.com/thangnguyen-max/BoxChat-project/blob/c9430d3184d69d4a7cfa8d6cddf5951e7629d46c/src/main/java/com/example/boxchat_java/config/WebSocketConfig.java#L14):
  WebSocket/STOMP, SockJS endpoint `/chat`, destination `/app`, broker `/topic`.
- [UserController.java](https://github.com/thangnguyen-max/BoxChat-project/blob/c9430d3184d69d4a7cfa8d6cddf5951e7629d46c/src/main/java/com/example/boxchat_java/controller/UserController.java#L55):
  nhận message, lưu database, phát vào topic của cặp sender/receiver.
- [home.html](https://github.com/thangnguyen-max/BoxChat-project/blob/c9430d3184d69d4a7cfa8d6cddf5951e7629d46c/src/main/resources/templates/page/home.html#L199):
  chọn người dùng, tải lịch sử, gửi text hoặc upload rồi gửi ảnh, hiển thị bubble/ảnh.
- [Message.java](https://github.com/thangnguyen-max/BoxChat-project/blob/c9430d3184d69d4a7cfa8d6cddf5951e7629d46c/src/main/java/com/example/boxchat_java/domain/Message.java#L18):
  sender, receiver, text, image, thời điểm tạo.

Không tìm thấy implementation group chat, typing, presence hoặc read receipt trong repo mẫu.
Các icon group/camera chưa có handler tương ứng. README có nói upload file, nhưng message và UI
đang xử lý trường `image` và thẻ `<img>`; gửi file tải xuống tổng quát chưa hoàn chỉnh.

## Đối chiếu chức năng

| Chức năng | BoxChat mẫu | Consultation hiện tại |
| --- | --- | --- |
| Chat 1–1 | Theo cặp người dùng | Theo thread Candidate–Consultant; Admin có quyền quản trị |
| Bắt đầu chat | Chọn người từ danh sách | Candidate tạo yêu cầu; Admin phân công Consultant |
| Lưu lịch sử | Có, truy vấn theo cặp | Có, truy vấn theo thread và phân trang |
| Gửi/nhận trực tiếp | STOMP qua WebSocket/SockJS | POST REST để gửi, SSE để nhận |
| Ảnh trong chat | Có upload và preview | Chưa có API/DTO attachment cho consultation |
| Nội dung tin | Text và image | Text bắt buộc, tối đa 10.000 ký tự |
| Trạng thái đã đọc | Chưa có | Có một `read_at`, chưa có sự kiện receipt |
| Đóng phiên tư vấn | Không có workflow riêng | Có CLOSED; gửi tiếp bị từ chối |
| CV/báo cáo nghề nghiệp | Không có | Có candidate profile/CV và bảy nhóm analyses |
| Giao diện chat | Thymeleaf/JavaScript có sẵn | Không có frontend consultation trong workspace BE được kiểm tra |

## Vai trò các lớp được hỏi

| Lớp | Trách nhiệm thực tế |
| --- | --- |
| `CandidateConsultationController` | Candidate tạo yêu cầu/thread |
| `AdminConsultationController` | Admin phân công hoặc đổi Consultant |
| `ConsultationController` | List/detail, lịch sử, gửi tin, đánh dấu đọc, đóng, subscribe SSE |
| `ConsultantConsultationController` | Đọc profile/CV và báo cáo của Candidate trong thread được phép |
| `ConsultationService` | Nghiệp vụ phòng chat, kiểm quyền, lưu tin và quản lý subscriber |
| `ConsultationAnalysisService` | Lấy bảy nhóm báo cáo đã có để phục vụ tư vấn; không tự gửi lời tư vấn |

Các controller cùng base path `/api/v1/consultations` nhưng dùng method/path khác nhau,
không trùng route. Lời tư vấn do Consultant gửi qua `POST /{id}/messages`.

## Các điểm cần sửa hoặc hoàn thiện

1. **Kiểm tra vai trò Consultant khi quyền bị thay đổi.**
   `AdminService.edit()` đổi role và thu hồi token ở dòng 151–158, nhưng giữ assignment.
   `ConsultationService.access()` dòng 46–51 và `ConsultationAnalysisRepository.candidateIdForThread()`
   dòng 29–35 cho phép truy cập bằng `consultant_user_id` mà không đòi role Consultant hiện tại.
   Nếu thu hồi vai trò phải thu hồi quyền tư vấn, người được đổi sang Candidate có thể đăng nhập lại
   và vẫn đọc thread/profile/analyses của Candidate khác. Cần thống nhất policy giữ lịch sử và
   kiểm quyền xem dữ liệu tư vấn hiện tại theo role.

2. **Read receipt trả kết quả không phản ánh bản ghi.**
   `ConsultationService.read()` dòng 171–179 luôn trả `read: true`, kể cả UPDATE không thay đổi hàng nào.
   SQL ở `ConsultationRepository.updateConsultationMessagesById()` dòng 137 chỉ đổi tin của người khác.
   Một `read_at` chung còn khiến Admin đọc tin được tính như người nhận đã đọc. Không có sự kiện read
   để người gửi cập nhật giao diện trực tiếp.

3. **Trang lịch sử mặc định lấy các tin cũ nhất.**
   `ConsultationController.messages()` mặc định limit 50/offset 0; repository dòng 108–110 sắp tăng dần
   theo `sent_at,id`. Một phòng có hơn 50 tin sẽ không trả các tin mới nhất ở lần tải mặc định.
   Frontend phải chủ động lấy các trang sau; cursor lấy tin gần nhất rồi tải cũ hơn phù hợp hơn cho chat.

4. **Reconnect chưa tự bù sự kiện bị lỡ.**
   SSE dòng 194–216 timeout 5 phút, chỉ gửi `connected`; publish gửi `message` với message ID.
   Backend không xử lý `Last-Event-ID`, replay/catch-up hoặc heartbeat. Client cần tải lại lịch sử
   khi reconnect và deduplicate bằng message ID.

5. **Fan-out chỉ trong một process.**
   Registry `ConcurrentHashMap` ở `ConsultationService` dòng 37 không liên thông các replica.
   POST ở instance A không phát tới emitter ở instance B. Muốn nhiều instance cần cơ chế phát sự kiện
   dùng chung; bản mẫu cũng dùng simple broker cục bộ, nên đây là yêu cầu triển khai bổ sung.

6. **Lỗi phát sự kiện sau commit có thể làm gửi tin báo lỗi dù đã lưu.**
   `publish()` dòng 224 truy vấn quyền ngoài try/catch. Lỗi database có thể thoát callback
   `afterCommit()` dòng 252. Message đã được commit nhưng request có thể báo lỗi; gửi lại có thể
   tạo tin trùng. Cần tách lỗi delivery khỏi kết quả lưu tin và cân nhắc client message ID/idempotency.

7. **Cleanup subscriber có race.**
   Dòng 199–205 kiểm `list.isEmpty()` rồi xóa map trong hai bước. Nếu subscriber mới thêm vào giữa
   hai bước, emitter còn kết nối nhưng list đã bị bỏ khỏi registry, nên không nhận publication nữa.

8. **Đọc dữ liệu có thể đua với reassignment.**
   `messages()` và `candidateProfile()` kiểm access rồi đọc bằng truy vấn riêng, không giữ khóa thread.
   Consultant có thể vượt kiểm tra trước khi Admin đổi assignment rồi lấy dữ liệu sau lúc đổi.
   Endpoint analyses đã có transaction và `FOR SHARE OF t`; đây là mẫu để đồng bộ các luồng đọc khác.

9. **SSE chưa gắn vòng đời với token/session.**
   Subscriber chỉ giữ user ID. Publish kiểm tài khoản ACTIVE và membership, không kiểm token đã logout,
   hết hạn hoặc bị thu hồi. Kết nối có thể tiếp tục nhận tin trong thời gian emitter chưa timeout.

10. **Message payload chưa đồng nhất giữa history và realtime.**
    History JOIN users trả `full_name` và `role`; send/SSE dùng `SELECT * FROM consultation_messages`
    nên không có hai trường đó. Frontend cần tự bổ sung dữ liệu người gửi, hoặc backend cần một response
    DTO chung cho lịch sử, kết quả gửi và sự kiện.

Typing, online status và group chat là phần mở rộng tùy nhu cầu; chúng không phải feature đã hoàn chỉnh
trong repo mẫu. Với consultation 1–1, ưu tiên parity gửi ảnh, cơ chế realtime phù hợp frontend,
history/reconnect/read receipt và các vấn đề quyền/lifecycle ở trên.

## Phạm vi xác minh

Đã đọc source của hai repository, schema, DTO và các test consultation hiện có. Repo mẫu được clone
vào thư mục `target` bị Git ignore để đối chiếu; không chạy ứng dụng mẫu hoặc đọc cấu hình credential.
Không sửa mã Java, schema hoặc cấu hình chạy của ứng dụng trong lần review này.

`CoreFlowIntegrationTests` có kiểm assignment, privacy, gửi text, chặn gửi sau close và analyses.
Các test này yêu cầu `EDUNEXUS_TEST_DATABASE_URL` và đã bị bỏ qua ở lượt verify trước.
Không thấy test riêng cho SSE/reconnect, read receipt hoặc phân trang tin nhắn.
Các cạnh tranh đồng thời/vòng đời nêu trên được xác định từ source; chưa chạy kiểm thử bằng hai browser
hoặc nhiều instance thật. Không dùng kết quả verify của API crawl để chứng minh realtime consultation.
