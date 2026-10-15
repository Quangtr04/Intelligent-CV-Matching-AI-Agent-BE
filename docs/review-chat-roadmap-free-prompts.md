# Kiểm tra lưu chat, link học tập, gói Free và prompt

**Cập nhật sau rà soát:** đã bổ sung chức năng khóa học YouTube/Coursera do Admin duyệt,
đưa vào RAG và nguồn gợi ý học tập, lưu snapshot link cho lộ trình mới và nguồn chat.
Xem [quy trình/API hiện tại](curated-course-links.md). Các nhận xét thiếu chức năng bên dưới
ghi nhận trạng thái trước lần bổ sung này; yêu cầu về gói Free và tin gửi khi AI lỗi chưa được sửa trong tính năng này.

Ngày kiểm tra: **15/10/2026**. Phạm vi: mã backend hiện tại trong workspace, gồm cả thay đổi chưa commit.
Đây là kết quả đọc controller, service, repository, migration và test; không xác nhận dữ liệu đang có trên production.
Không thay đổi mã xử lý nghiệp vụ.

Trong các tham chiếu dưới đây, `J/` là `src/main/java/com/example/intelligent_cv_matching_ai_agent/`,
`T/` là `src/test/java/com/example/intelligent_cv_matching_ai_agent/`.

| Yêu cầu | Kết quả |
| --- | --- |
| Lưu chat Candidate với AI | Đạt cho lượt gửi thành công; chưa lưu tin người dùng nếu AI lỗi. |
| Lưu chat Candidate với Career Consultant | Đã triển khai lưu và đọc lại lịch sử. |
| Lưu link YouTube/Coursera từng đề xuất trong lộ trình | Đạt một phần: có lưu tài nguyên catalog gắn với node, chưa giữ nguyên toàn bộ link từng trả cho người dùng. |
| Giữ nguyên gói Free, muốn thay đổi thì tạo gói mới | Chưa đạt: vẫn sửa được quota và một số thông tin của Free. |
| Muốn đổi prompt thì tạo mới, giữ nội dung prompt cũ | Đạt ở API quản trị: tạo phiên bản mới, giữ nội dung phiên bản cũ. |

## Chat Candidate với AI

- `J/service/candidate/ai/CareerAssistantService.java:174` lưu tin `USER`, dòng 191 lưu tin `ASSISTANT`
  vào `career_assistant_messages`, kèm model, token và thời gian cho câu trả lời AI.
- Dòng 104 đọc lại lịch sử; dòng 270 kiểm tra chủ sở hữu hội thoại.
- `J/repository/candidate/ai/CareerAssistantRepository.java:42` đọc lịch sử; giới hạn 20 tin ở dòng 62
  chỉ áp dụng cho ngữ cảnh gửi AI. Archive tại dòng 115 chỉ đổi trạng thái, giữ tin nhắn đã lưu.
- **Giới hạn:** service gọi AI ở dòng 164 trước khi ghi tin người dùng ở dòng 174.
  Nếu AI lỗi, lượt gửi đó không được lưu. Nếu yêu cầu là lưu mọi tin đã gửi, cần lưu tin người dùng
  trước khi gọi AI bằng giao dịch riêng và ghi trạng thái xử lý/thất bại cho lượt trả lời.

## Chat Candidate với Career Consultant

- `J/service/shared/consultation/ConsultationService.java:151` lưu người gửi, nội dung và thời điểm gửi;
  dòng 133 đọc lịch sử có kiểm tra quyền truy cập.
- `J/repository/shared/consultation/ConsultationRepository.java:108` truy vấn lịch sử theo thread,
  sắp xếp thời gian và phân trang; dòng 121 thực hiện INSERT vào `consultation_messages`.
- Đóng thread tại dòng 145 chỉ đổi trạng thái. Tin đã lưu vẫn còn để đọc lại.

## Link học tập từng đề xuất

- `J/service/candidate/learning/LearningService.java:296` chọn tài nguyên từ catalog theo kỹ năng;
  dòng 300 lưu quan hệ tài nguyên với node. URL nằm trong `learning_resources.url`;
  `roadmap_node_resources` giữ `resource_id`, thứ tự và lý do đề xuất.
- **Chưa giữ lịch sử chính xác:** dòng 391 tính lại `learning_recommendations` mỗi lần đọc roadmap.
  `J/repository/candidate/learning/LearningRepository.java:184` truy vấn URL hiện tại trong catalog
  và chỉ trả tài nguyên còn active. Sửa URL hoặc tắt tài nguyên có thể làm lộ trình cũ đổi hoặc mất link.
- Không tìm thấy xử lý riêng YouTube/Coursera trong mã, migration hay test. Seed hiện có tài liệu chính thức
  ở `src/main/resources/db/migration/V4__initial_catalog.sql:82` và roadmap.sh ở V6.
  Schema có thể chứa URL của hai nguồn này. RAG cũng nhận văn bản chứa URL do Admin nhập;
  xem phần kiểm tra bổ sung dưới đây. Chưa kiểm tra các URL người dùng đã nhập trong database.
- Tài nguyên do AI tự trả trong node không được lưu; service gắn tài nguyên từ catalog.
  `T/learning/LearningSourceFlowTest.java:223` kiểm tra hành vi này.
- Để đạt yêu cầu, cần lưu URL, provider và thông tin đề xuất theo roadmap/node tại thời điểm trả kết quả;
  đọc lại từ dữ liệu đã lưu. Cần bổ sung nguồn YouTube/Coursera nếu đây là hai nguồn phải có.

## Bổ sung theo yêu cầu: thêm YouTube/Coursera qua RAG

RAG có thể nhận mô tả khóa học/video kèm URL dưới dạng văn bản, nhưng chưa có luồng nhập URL chuyên biệt
để tự lấy nội dung hoặc đưa nguồn đó vào lộ trình học.

- `J/controller/admin/rag/RagAdminController.java:26` có `POST /api/v1/admin/rag/documents`.
  `J/dto/request/admin/rag/CreateRagDocumentRequest.java:14` nhận `title`, `content` bắt buộc,
  cùng `sourceType` chỉ cho giá trị `ADMIN` và `metadata` tùy chọn. Không có trường URL riêng;
  DTO bỏ qua trường không biết. Chỉ gửi URL mà thiếu nội dung sẽ không hợp lệ.
- `J/service/admin/rag/RagDocumentService.java:74` chia chính `content` đã nhập thành chunk;
  dòng 76 lưu metadata và dòng 94 lưu chunk. Không có bước tải trang YouTube/Coursera,
  lấy transcript video hoặc trích nội dung khóa học từ URL.
- Sau khi gọi `POST /api/v1/admin/rag/documents/{id}/index` thành công, Candidate chat với
  `retrieve: true` có thể lấy tài liệu phù hợp. `J/service/candidate/ai/CareerAssistantService.java:145`
  tìm chunk, dòng 157 đưa kết quả vào `sources` cho AI. Link cần nằm trong nội dung chunk để AI thấy được.
- **Nếu URL chỉ nằm trong metadata:** `J/repository/candidate/ai/CareerAssistantRepository.java:70`
  chỉ SELECT id, title, content và similarity; không SELECT metadata hoặc file_url.
  URL đó được lưu ở tài liệu nhưng không được truyền tới AI qua retrieval hiện tại.
- Source trả về cho client chỉ có tham chiếu document/chunk, nhãn và similarity;
  `CareerAssistantService.java:212` không lưu URL riêng trong source của câu trả lời.
  Nếu câu trả lời có chứa link thì link nằm trong `message_text` đã lưu.
- **Lộ trình học:** `J/service/candidate/learning/LearningService.java:159` dùng
  `LearningReferenceService` với catalog `learning_resources` và roadmap.sh; không truy vấn các chunk RAG.
  Thêm tài liệu YouTube/Coursera vào RAG chưa tự làm tài nguyên đó xuất hiện trong roadmap.

Kết luận bổ sung: đã có nhập văn bản chứa link để Candidate AI chat tham khảo; chưa có chức năng nhập URL
và kết nối nguồn RAG đó vào gợi ý/lưu tài nguyên của lộ trình một cách đầy đủ.

## Gói Free

- `J/service/admin/management/AdminService.java:202` có nghiệp vụ tạo gói mới.
- Dòng 239 chỉ chặn đổi mã FREE, đổi giá khác 0 hoặc tắt gói; vẫn cho sửa tên hiển thị và tiền tệ.
- Dòng 259 xử lý cập nhật quota cho mọi gói; dòng 272 upsert quota không chặn FREE.
  API `PUT /api/v1/admin/subscription-tiers/{id}/quotas` vì vậy vẫn đổi được hạn mức và chu kỳ Free.
- `J/service/shared/billing/EntitlementService.java:37` đọc quota thực tế từ gói hiện hành,
  nên thay quota Free sẽ ảnh hưởng quyền sử dụng của người dùng Free.
- Cần chặn cập nhật Free ở cả luồng sửa thông tin và sửa quota, hướng thay đổi sang tạo gói mới.

## Prompt

- `J/service/admin/management/AdminService.java:373` khóa theo feature, lấy version tiếp theo,
  tắt trạng thái active của bản cũ rồi INSERT bản mới ở dòng 383.
- Restore ở dòng 414 cũng INSERT phiên bản mới ở dòng 438, không ghi đè nội dung bản được khôi phục.
  Bản cũ thay đổi trạng thái active và thời điểm cập nhật; nội dung và version được giữ lại.
- `T/CoreFlowIntegrationTests.java:872` có test tạo v1, tạo v2, restore thành v3 và kiểm tra nội dung
  các phiên bản cũ còn nguyên. Đây là test có sẵn; chưa chạy lại thành công trong lần rà soát này.

## Xác minh và giới hạn

Đã thử chạy các test `CareerAssistantOwnershipTests`, `LearningSourceFlowTest`,
`LearningReferenceServiceTest`, `BillingServiceTest`, `AiRequestContractTests` bằng Maven offline.
Build dừng trước khi chạy test do thiếu parent POM `spring-boot-starter-parent:4.1.1` trong cache.
Đã thử lại có tải dependency, nhưng kết nối Maven Central bị chặn với lỗi `Permission denied: getsockopt`.
Log: `target/requirements-review-unit.log` và `target/requirements-review-unit-online.log`.
Không có test nào chạy trong hai lần thử này; không kết luận test pass.

Chưa kiểm tra frontend, dữ liệu production, gọi AI thật hay tính khả dụng của link bên ngoài.
Các kết luận trên xác nhận hành vi thể hiện trong mã backend và chỉ ra phần còn thiếu so với yêu cầu.
