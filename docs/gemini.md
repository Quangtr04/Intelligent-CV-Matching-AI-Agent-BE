# Gemini API cho chat và embedding

Backend gọi Gemini của Google bằng `GEMINI_API_KEY`. Chat dùng adapter Spring AI/OpenAI Java SDK để kết nối endpoint tương thích của Google. Embedding dùng Google REST native `:embedContent`. Cả hai gửi yêu cầu đến Google; usage và giới hạn API thuộc dự án Google của key này.

## Cấu hình key và model

Trong thư mục backend có `pom.xml`, điền key trong `.env` đang có:

```dotenv
GEMINI_API_KEY=your-gemini-api-key
GEMINI_BASE_URL=https://generativelanguage.googleapis.com/v1beta/openai
GEMINI_NATIVE_BASE_URL=https://generativelanguage.googleapis.com/v1beta
AI_ENABLED=true
AI_PROVIDER=gemini
AI_CHAT_PROVIDER=openai
AI_EMBEDDING_PROVIDER=gemini
AI_CHAT_MODEL=gemini-2.5-flash-lite
AI_ASSESSMENT_MODEL=gemini-2.5-flash-lite
AI_EMBEDDING_MODEL=gemini-embedding-001
AI_EMBEDDING_DIMENSIONS=1024
AI_REASONING_EFFORT=none
AI_REQUEST_TIMEOUT=PT2M
```

Lấy key tại [Google AI Studio](https://aistudio.google.com/apikey). Key chỉ nằm ở backend; `.env` được Git ignore. Không thêm key vào frontend, commit hoặc log. Thay đổi `.env` cần khởi động lại backend. Biến môi trường của tiến trình được ưu tiên hơn file.

`AI_PROVIDER=gemini` là danh tính provider được dùng cho telemetry, cấu hình quản trị, cache và vector. `AI_CHAT_PROVIDER=openai` chọn giao thức chat tương thích. `AI_EMBEDDING_PROVIDER=gemini` chọn adapter embedding native của backend; `spring.ai.model.embedding=none` tắt auto-configuration embedding OpenAI. Tên property `spring.ai.openai.*` và lớp `OpenAiChatOptions` chỉ phục vụ transport chat của Spring.

`AI_REASONING_EFFORT=none` tắt thinking cho Gemini 2.5 Flash-Lite qua endpoint tương thích. Nếu đổi model, kiểm tra lại hỗ trợ tham số này; một số dòng Gemini không cho tắt thinking. Xem [tài liệu tương thích của Google](https://ai.google.dev/gemini-api/docs/openai).

## Chạy backend

Chạy trên host bằng IDE hoặc `mvn spring-boot:run`; database/Redis giữ cấu hình đang dùng. Để chạy container local, tạo JAR và dựng lại app:

```powershell
mvn verify
docker compose --profile app --profile optional up -d --build app
```

App chỉ chờ Redis và gọi Gemini qua mạng. Compose production chạy app với database/Redis bên ngoài; xem [runtime profiles](runtime-profiles.md).

## Token, quota và lỗi API

`AI_REQUEST_TIMEOUT=PT2M` giới hạn thời gian chờ client. `spring.ai.openai.max-retries=0` tắt tự gửi lại yêu cầu khi lỗi. Giới hạn đầu ra lấy từ `AI_MAX_OUTPUT_TOKENS`, `AI_ASSESSMENT_MAX_OUTPUT_TOKENS`, `AI_LEARNING_MAX_OUTPUT_TOKENS` và cấu hình quản trị.

Quota token của ứng dụng vẫn do gói/quyền trong database quyết định. Đổi provider không tự tăng hoặc cấp lại quota. Quota/số dư API của Google là giới hạn riêng; request có thể bị từ chối nếu key không có quyền model, hết hạn mức hoặc dự án chưa được cấp mức sử dụng phù hợp.

Kiểm tra token Gemini bằng usage thực tế từ API hoặc phép đếm của Google; không xem tokenizer OpenAI offline là số token được Gemini tính. Xem [công cụ đo token](ai-token-measurement.md). Health endpoint chỉ xác nhận backend sẵn sàng, không chứng minh một request model đã thành công.

## Embedding 1024 chiều

Database dùng `vector(1024)`. Request native `:embedContent` gửi model `gemini-embedding-001`, nội dung trong `content.parts` và `outputDimensionality=1024`. Backend chuẩn hóa L2 vector nhận được trước khi lưu/so sánh. Không cắt/pad vector cũ hoặc sửa schema để tái sử dụng vector khác model. Xem [Google embeddings](https://ai.google.dev/gemini-api/docs/embeddings).

Adapter embedding đọc usage từ `usageMetadata` khi có; nếu thiếu, gọi `countTokens` của Google qua `GEMINI_NATIVE_BASE_URL` để ghi nhận token đầu vào. Cùng Gemini key dùng cho cả hai request native. Nếu phép đếm không thành công, cách giữ chỗ/settle quota bảo thủ vẫn áp dụng thay vì coi request là miễn token.

Vector theo provider/model cũ không tương thích với bộ Gemini mới. Cần tạo lại embedding CV và index lại job/RAG để semantic matching sử dụng cùng bộ vector:

- CV: xác nhận nội dung rồi tạo embedding theo API hiện có.
- Job: import/crawl enqueue tự động, hoặc POST `/api/v1/admin/jobs/{id}/index` để index/retry.
- RAG: index lại tài liệu trong quản trị.

Migration V10 và dữ liệu legacy được giữ nguyên để bảo toàn checksum/lịch sử Flyway. Đổi provider không phải lý do sửa migration đã áp dụng.
