# Đo token theo chức năng AI

Chạy từ thư mục backend có `pom.xml`:

Backend hiện dùng Gemini. Số liệu Gemini cần lấy từ usage API thật hoặc phép đếm của Google;
không dùng tokenizer OpenAI offline làm số token Gemini đã tính. Xem [cấu hình Gemini](gemini.md).

Đếm đầu vào bằng Google mà không sinh phản hồi:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/measure-ai-tokens.ps1 -CountOnly
```

`-CountOnly` gọi endpoint `countTokens` của Google bằng `GEMINI_API_KEY` và `GEMINI_NATIVE_BASE_URL`.
Phép đếm này không đo token đầu ra hoặc chất lượng kết quả.

Chế độ không có `-CountOnly` hoặc `-Live` dùng tokenizer offline làm số gần đúng để so sánh kích thước
prompt; không gửi request đến dịch vụ AI và không phải số tính phí Gemini. Công cụ dùng trực tiếp `AiService`
để dựng system prompt, schema, hướng dẫn nghiệp vụ và tùy chọn model. Repository, quota và telemetry
được thay bằng thành phần trong bộ nhớ; không khởi động Spring, không ghi database và không trừ quota
của người dùng. Maven có thể tải dependency khi chuẩn bị classpath.

Kết quả nằm tại `target/ai-token-measurements/measurements.json` và `measurements.md`.
Sau khi đã chuẩn bị classpath và build, thêm `-SkipBuild` để chạy lại nhanh; bỏ cờ này sau khi sửa mã Java.

## Dữ liệu và ý nghĩa số đếm

`scripts/ai-token-samples.json` có 18 mẫu giả lập: 11 chức năng sinh nội dung, 3 biến thể dài hơn và
4 loại embedding. CV mẫu khoảng 505 từ, JD khoảng 284 từ. Các biến thể được mô tả ngay trong fixture.
Không dùng nội dung CV hoặc dữ liệu cá nhân từ database đang chạy.

- Phép đếm offline dùng tokenizer OpenAI như một proxy. Các báo cáo cũ dùng `o200k_base` cho
  `gpt-4.1-mini` và `cl100k_base` cho `text-embedding-3-small` là snapshot của cấu hình cũ;
  không diễn giải các số đó thành usage Gemini.
- `input_content_tokens` đếm nội dung system/user message, gồm schema và hướng dẫn của backend.
  Chưa bao gồm phần đóng gói message của API, nên không được xem là `prompt_tokens` API đã tính phí.
- `max_output_tokens` là giới hạn đầu ra thực tế lấy từ tùy chọn của `AiService`, không phải kết quả sinh.
- `total_upper_content_tokens` cộng hai số trên; đây là dự toán nội dung cộng trần đầu ra, không phải
  số usage hoặc trần tổng token API chính xác.
- `application_reservation_units` là mức quota ứng dụng tạm giữ. Hiện code dự toán bằng byte UTF-8
  cộng giới hạn đầu ra; số này khác token đếm bằng tokenizer.
- Mẫu dùng model/tham số trong môi trường và prompt mặc định từ code, không đọc override trong DB.
  Đổi model cần kiểm tra lại tokenizer; số này không đại diện cho mọi độ dài CV/JD hoặc mức sử dụng trung bình.

## Đo usage API thật

Điền `GEMINI_API_KEY` trong backend `.env`, giữ `GEMINI_BASE_URL` trỏ endpoint Google và
`AI_PROVIDER=gemini`, rồi chạy:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/measure-ai-tokens.ps1 -Live -OutputDirectory target/ai-token-measurements-live
```

Chế độ này có tính phí API. Mặc định chạy một lần cho mỗi mẫu baseline: 11 request chat và 4 request
embedding, không tự retry. Thêm `-AllVariants` chỉ khi muốn chạy cả ba mẫu mở rộng. Key chỉ được đọc
từ `.env`/môi trường, không đưa vào tham số dòng lệnh hoặc báo cáo. Yêu cầu Gemini được gửi đến Google
qua endpoint tương thích OpenAI; usage và hạn mức thuộc tài khoản/dự án Google.

Các trường `actual_input_tokens`, `actual_output_tokens`, `actual_total_tokens` chỉ có khi API trả usage.
Với embedding Gemini native, công cụ ưu tiên `usageMetadata`, nếu thiếu thì dùng `countTokens`
để ghi nhận số token đầu vào; embedding không có token đầu ra dạng văn bản.
Chúng được ghi nhận ngay trước bước kiểm tra phản hồi, nên vẫn có thể đo lượng dùng của phản hồi
không qua kiểm tra. Trạng thái `API_RESPONSE_PASSED_SHARED_VALIDATION` chỉ xác nhận các kiểm tra chung
trong `AiService`; không xác nhận toàn bộ validator nghiệp vụ, lưu dữ liệu hoặc chất lượng nội dung.

## Một thao tác có thể gọi nhiều request

- Matching FULL: một embedding ứng viên và một request rerank cho mỗi nhóm tối đa 3 job.
  Tổng phụ thuộc số job thực sự được backend xử lý; không chỉ dựa vào giới hạn kết quả hiển thị.
- Chat bật truy xuất tài liệu: thêm một embedding câu hỏi.
- Assessment: một request sinh 10 câu hỏi.
- Email ứng tuyển và cover letter: chung một request.
- Roadmap không có kỹ năng thiếu, cache hit, matching nhanh và upload phân tích bằng quy tắc có thể
  không gọi model. `CV_PARSE` còn trong catalog nhưng không có callsite model ở luồng upload hiện tại.

Tham khảo [Google về token](https://ai.google.dev/gemini-api/docs/tokens) và
[endpoint tương thích OpenAI của Gemini](https://ai.google.dev/gemini-api/docs/openai).
