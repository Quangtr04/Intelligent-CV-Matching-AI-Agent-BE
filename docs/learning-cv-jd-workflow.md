# AI Learning: hai luồng backend

Các endpoint dưới đây dùng tiền tố `/api/v1`, bearer token của `CANDIDATE` và kiểm tra chủ sở hữu CV,
bài đánh giá, lộ trình. Thay các giá trị UUID ví dụ bằng ID thật.

## Chưa dùng CV: chọn JD và tạo lộ trình trực tiếp

```http
POST /api/v1/roadmaps
Content-Type: application/json

{"jobId":"11111111-1111-1111-1111-111111111111"}
```

Backend so các kỹ năng bắt buộc của JD đang tuyển với kỹ năng trong hồ sơ nghề nghiệp. Không bắt buộc
tạo bài đánh giá. Một bài đánh giá cũ cùng JD và không gắn CV, nếu có, vẫn có thể bổ sung điểm kỹ năng.
Snapshot có sẵn phải còn khớp input hiện tại; bài tự chọn đã cũ bị bỏ qua để quay về so hồ sơ với JD.
Nếu người dùng truyền `assessmentId` rõ ràng nhưng snapshot đã cũ, backend trả `409` yêu cầu làm lại.
AI tạo hướng dẫn học cho danh sách kỹ năng còn thiếu; lộ trình và phân tích khoảng trống được lưu cùng nhau.
Khi không còn kỹ năng cần bổ sung, backend lưu lộ trình hoàn thành với danh sách node rỗng và không gọi AI.

Việc tài khoản có CV khác không khiến hệ thống tự chọn CV đó. Không truyền `resumeId` là lựa chọn rõ ràng
dùng hồ sơ nghề nghiệp cho yêu cầu tạo trực tiếp.

## Có CV: chọn hoặc dán JD, làm đánh giá, tạo lộ trình

CV phải thuộc tài khoản hiện tại, có trạng thái `READY` và có nội dung đã xác nhận. Backend dùng nội dung
CV đã review làm nguồn chính; bản trích xuất thô không ghi đè dữ liệu đã review.

Chọn JD đã lưu:

```http
POST /api/v1/assessments
Content-Type: application/json

{"jobId":"11111111-1111-1111-1111-111111111111","resumeId":"22222222-2222-2222-2222-222222222222"}
```

Hoặc dán JD riêng cho bài đánh giá:

```http
POST /api/v1/assessments
Content-Type: application/json

{"targetJobTitle":"Java Backend Developer","resumeId":"22222222-2222-2222-2222-222222222222","jdText":"Build Java backend services and use SQL transactions safely."}
```

`jobId` và `jdText` loại trừ nhau. JD dán cần 30–50.000 ký tự, `targetJobTitle` và `resumeId`.
JD dán được giữ trong snapshot riêng của tài khoản, không thêm vào danh mục `market_job_posts` công khai.
Bộ trích xuất xác định kỹ năng từ tên/alias công nghệ được hỗ trợ và tên kỹ năng đang hoạt động trong
catalog. Nếu không nhận diện được kỹ năng nào, API trả `409` để người dùng cung cấp JD rõ kỹ năng hơn.
Không tự suy diễn kỹ năng chỉ từ chức danh.

AI nhận cả CV và JD: nội dung CV, kỹ năng đã khai báo, văn bản JD và các kỹ năng JD. Prompt yêu cầu so
CV với JD trước khi chọn câu hỏi và tình huống phù hợp. Mỗi câu hỏi có `cv_evidence` là trích dẫn ngắn
chính xác từ CV hoặc chuỗi rỗng nếu CV chưa có bằng chứng. Trích dẫn bịa đặt bị từ chối.

Kết quả là 10 câu trắc nghiệm: 5 câu nền tảng và 5 câu tình huống thực hành. Mỗi câu phải thuộc kỹ năng
trong JD, có bốn lựa chọn khác nhau và trích dẫn JD hợp lệ. Đáp án đúng được ẩn khi bài đang làm.
Có thể hướng dẫn Bloom trong prompt template quản trị cho `ASSESSMENT_GENERATE`; backend không gắn
một taxonomy Bloom riêng vào dữ liệu câu hỏi.

Lưu lần lượt các câu trả lời:

```http
PUT /api/v1/assessments/33333333-3333-3333-3333-333333333333/answers
Content-Type: application/json

{"questionId":"44444444-4444-4444-4444-444444444444","selectedOption":"A"}
```

Nộp sau khi trả lời đủ:

```http
POST /api/v1/assessments/33333333-3333-3333-3333-333333333333/submit
```

Chấm trắc nghiệm bằng đáp án đã lưu, tính phần trăm đúng theo từng kỹ năng và lưu `skill_scores`.
Không gọi AI để chấm lại đáp án. Bài đã nộp có thể đọc lại và nộp lặp mà không chấm lại.
Response hoàn thành có `roadmap_request` chứa `assessmentId` để gọi bước tiếp theo:

```http
POST /api/v1/roadmaps
Content-Type: application/json

{"assessmentId":"33333333-3333-3333-3333-333333333333"}
```

Backend lấy lại đúng CV và JD của bài đã hoàn thành, kể cả JD dán không có `jobId`.
Cũng có thể truyền `jobId`, `resumeId`, `assessmentId` cùng nhau; chúng phải khớp bài đánh giá.
Truyền `jobId` và `resumeId` mà chưa có bài CV/JD hoàn thành tương ứng trả `409`.
Các bài đánh giá cũ chỉ gắn JD không thể thay cho bài đánh giá gắn CV.

Điểm kỹ năng dưới 70 tạo khoảng trống `NEEDS_PRACTICE`. Kỹ năng JD bắt buộc chưa được đánh giá và
chưa có trong hồ sơ/CV tạo `MISSING_EVIDENCE`; điều này không kết luận ứng viên không biết kỹ năng đó.
AI chỉ tạo hoạt động học cho danh sách khoảng trống do backend xác định. Tài liệu học đến từ catalog
đã duyệt; không dùng URL khóa học do model tự bịa. Kết quả lưu phân tích khoảng trống, lộ trình,
node, tài liệu và phụ thuộc. `input_context` trong response cho biết JD/CV/bài đánh giá đã dùng.

## Sinh câu hỏi nền và bảo vệ snapshot

Với yêu cầu có thể mất nhiều thời gian, gửi cùng body như trên đến:

```http
POST /api/v1/assessments/generations
```

API trả `202` và ID yêu cầu; poll `GET /api/v1/assessments/generations/{id}` đến `SUCCEEDED` hoặc `FAILED`.
Khi thành công dùng `assessment_id` để đọc câu hỏi tại `GET /api/v1/assessments/{id}`.
Gửi lại cùng input đang xử lý trả cùng yêu cầu; CV/JD khác tạo ngữ cảnh khác.

Cả luồng đồng bộ và nền lưu snapshot CV/JD/profile và hash đầu vào. Worker dùng owner đã lưu,
không dựa vào security context của thread HTTP. Trước khi gọi AI và trước khi công bố, backend kiểm tra
lại quyền sở hữu, trạng thái CV/JD và nội dung/hash. Nếu CV, JD hoặc dữ liệu profile dùng cho bài đã đổi,
yêu cầu thất bại thay vì âm thầm sinh/lưu kết quả cho input khác. Khi tạo roadmap từ bài CV/JD, input
cũng phải còn khớp snapshot; cần tạo bài mới sau khi sửa input. Các snapshot đầy đủ không nằm trong
response polling/danh sách bài đánh giá.

Các request cũ chỉ có `jobId` để tạo quiz vẫn được hỗ trợ để không làm hỏng client hiện có.
Luồng mới có CV phải truyền `resumeId` rõ ràng; hệ thống không tự chọn một CV bất kỳ.
