# Quản trị AI và hỗ trợ sửa CV

`/admin/ai` hiển thị cấu hình thực tế của CHAT, ASSESSMENT và EMBEDDING, kể cả khi chưa có bản ghi trong database.
Nguồn ENVIRONMENT dùng `.env`; nguồn DATABASE dùng phiên bản đang bật. Lưu hoặc khôi phục tạo một phiên bản mới,
giữ lịch sử và audit. Không cần khởi động lại backend để áp dụng cấu hình hoặc prompt mới cho lần gọi model tiếp theo.

## Cấu hình model

- CHAT áp dụng model, temperature và numPredict cho các chức năng dùng model chat.
- ASSESSMENT áp dụng riêng model, temperature và giới hạn output cho sinh bài đánh giá.
- EMBEDDING chỉ hiển thị: đổi model cần quy trình tạo lại vector, không đổi trực tiếp tại đây.
- Model phải được tài khoản Gemini cấp quyền. Lưu cấu hình không gọi API để kiểm tra model.

Tham số chấp nhận: temperature từ 0 đến 2; numPredict là số nguyên từ 256 đến 16000.
Các alias num_predict/maxOutputTokens/maxTokens/max_output_tokens được chuẩn hóa thành cùng giới hạn output.
Tham số không hỗ trợ hoặc bị khai báo trùng qua alias sẽ bị từ chối.

Các tác vụ có kết quả cấu trúc vẫn giữ giới hạn tối thiểu và quy tắc bắt buộc:

| Chức năng | Đầu ra tối thiểu | Temperature |
| --- | ---: | --- |
| CV_ANALYZE, CV_PROFILE_ALIGNMENT | 4000 | 0 |
| CV_AI_SUPPORT | 2000 | Theo CHAT |
| JOB_MATCH_RERANK | 256 | 0 |
| ASSESSMENT_GENERATE, ROADMAP_GENERATE | 4000 | Theo cấu hình tương ứng |

## Prompt

`/admin/ai/prompts` lấy danh sách chức năng từ backend. Prompt mặc định luôn có sẵn trong code; database chỉ lưu
phiên bản tùy chỉnh. Prompt bổ sung hướng dẫn xử lý dữ liệu; schema, quyền truy cập, quy tắc không bịa thông tin và
ngôn ngữ vẫn do backend kiểm tra. Hướng dẫn người dùng ở đây được ghép vào yêu cầu cùng dữ liệu JSON do backend
chuẩn bị; đây không phải mẫu thay thế biến tùy ý.

Thay đổi model, tham số hoặc prompt làm thay đổi dấu vân tay cache của kết quả được sinh mới. Báo cáo đã lưu vẫn
được giữ nguyên; dùng thao tác phân tích lại để áp dụng hướng dẫn mới.

## API

- GET `/api/v1/admin/ai/runtime`: cấu hình đang có hiệu lực, chỉ dành cho ADMIN.
- GET `/api/v1/admin/ai/features`: mã chức năng, hướng dẫn mặc định, schema và giới hạn, chỉ dành cho ADMIN.
- Các API lưu/khôi phục cấu hình và prompt hiện có tiếp tục quản lý phiên bản.
- POST `/api/v1/resumes/AI-support` hoặc `/api/v1/resumes/ai-support`: hỗ trợ sửa CV của ứng viên đang đăng nhập.

Ví dụ yêu cầu hỗ trợ một phần CV:

```json
{
  "question": ["Viết rõ hơn trách nhiệm trong phần kinh nghiệm này"],
  "is_urgent": false,
  "language": "vi",
  "field": "experience",
  "currentText": "Phát triển API bằng Java và Spring Boot.",
  "skills": ["Java", "Spring Boot"]
}
```

Có thể thêm resumeId để lấy ngữ cảnh CV đã lưu; backend xác minh CV thuộc ứng viên. Nội dung đang chỉnh sửa được
gửi cùng ngữ cảnh đó. field nhận summary, experience hoặc project. Payload cũ chỉ có question và is_urgent vẫn dùng
được. is_urgent yêu cầu trả lời ngắn, ưu tiên việc cần làm; không thay đổi thứ tự xử lý tác vụ.

Kết quả gồm answer, suggestions với original_content/suggested_content/explanation, cùng thông tin usage và model.
Luồng này chỉ trả đề xuất. CV chỉ thay đổi khi ứng viên chọn áp dụng và lưu trong trình soạn thảo. Gợi ý viết CV
của người dùng đăng nhập đã dùng API này; chế độ khách tiếp tục dùng API khách hiện có.
