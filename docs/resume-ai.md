# CV, trợ lý AI, dùng thử và RAG

Các API dưới đây dùng tiền tố `/api/v1`. API riêng của ứng viên cần `Authorization: Bearer <access_token>`; API khách dùng `X-Guest-Session: <session_key>`. CV demo không thuộc người dùng và không thể truy cập qua API sở hữu CV riêng.

## Cấu hình AI

Backend hiện dùng adapter Ollama native cho model local trong Docker. `.env.example` bật AI; có thể
tắt bằng `AI_ENABLED=false`. Ứng dụng import `.env` qua `spring.config.import`; biến môi trường của process
có thể ghi đè giá trị file. Cấu hình backend host:

```powershell
$env:AI_ENABLED = 'true'
$env:AI_PROVIDER = 'ollama'
$env:AI_CHAT_PROVIDER = 'ollama'
$env:AI_EMBEDDING_PROVIDER = 'ollama'
$env:OLLAMA_BASE_URL = 'http://localhost:11434'
$env:AI_CHAT_MODEL = 'qwen2.5:7b'
$env:AI_EMBEDDING_MODEL = 'qwen3-embedding:0.6b'
$env:AI_EMBEDDING_DIMENSIONS = '1024'
$env:AI_MAX_OUTPUT_TOKENS = '2000'
```

Không cần API key thương mại. App trong Compose dùng URL nội bộ `http://ollama:11434`.
Khởi động model theo [hướng dẫn Ollama](ollama.md); test từng request theo
[dữ liệu và thứ tự Postman](postman-test-order.md). Backend gọi `ChatModel` và `EmbeddingModel` của
Spring AI qua adapter Ollama. Vector mới trong PostgreSQL/pgvector có **1024 chiều**; V10 giữ vector
1536 cũ ở cột legacy riêng và yêu cầu reindex khi có dữ liệu cũ. Vector sai kích thước/không hữu hạn bị từ chối.
Qdrant không nằm trong luồng hiện tại.

Admin có thể thay model chat bằng bản ghi `ai_configurations` loại `CHAT`, đang hoạt động và có provider
`ollama`; model phải có sẵn trên server local. Model này và prompt active được đọc cho mỗi lời gọi.
Thay URL/model embedding cần sửa cấu hình triển khai và khởi động lại backend; model embedding phải khớp
schema 1024 và dữ liệu cần index lại. Backend không có fallback sang API thương mại.

Khi AI tắt, upload vẫn trích xuất văn bản và yêu cầu người dùng nhập/review dữ liệu. Phân tích CV trả chế độ `RULE_BASED`, chấm độ đầy đủ của các mục, với `ats_score: null`. Tạo CV bằng AI, chat và embedding trả HTTP 503 khi provider tương ứng chưa bật.

## Quota và lỗi provider

Trước mỗi lời gọi mạng, máy chủ giữ một ngân sách gồm kích thước UTF-8 của input, system prompt, phần dự phòng và giới hạn output. Quota subscription dùng `ai_token_reservations`; quota khách dùng `trial_usage_events` cùng bộ đếm nguyên tử trong `guest_sessions`.

Sau khi nhận phản hồi, máy chủ quyết toán theo token provider báo cáo và trả phần giữ thừa. Nếu provider không báo token, giữ phí của ngân sách đã đặt trước để tránh lời gọi miễn phí không giới hạn. Phản hồi đã nhận nhưng JSON/sơ đồ kết quả không hợp lệ vẫn tính token đã sử dụng; event khách chuyển `FAILED`. Lỗi trước khi nhận phản hồi hủy phí giữ trước. Log provider trong `ai_usage_logs` được lưu độc lập với transaction nghiệp vụ nên vẫn còn khi xử lý kết quả thất bại.

Token khách mặc định là 5.000. Đây là giới hạn do máy chủ thiết lập; trường `tokens`, `limit` hoặc `token_used` trong body không điều khiển quota. Một request cần đủ quota cho toàn bộ ngân sách giữ trước, kể cả khi token thực dùng có thể thấp hơn. Quota có thể thay đổi qua `GUEST_TOKEN_LIMIT` khi tạo session mới.

## Xử lý và quản lý CV

| Method và path | Chức năng |
| --- | --- |
| `GET /resumes/templates` | Danh sách template đang hoạt động |
| `GET /resumes` | CV riêng chưa lưu trữ |
| `POST /resumes` | Tạo CV thủ công, trạng thái `DRAFT` |
| `POST /resumes/build` | Tạo dữ liệu CV bằng AI, trạng thái `REVIEW_REQUIRED` |
| `POST /resumes/upload` | Upload multipart gồm `file`, `title` tùy chọn và `language` |
| `GET /resumes/{id}` | CV, các section chuẩn hóa và lịch sử parse |
| `PATCH /resumes/{id}` | Sửa dữ liệu; thay đổi nội dung/tiêu đề/ngôn ngữ yêu cầu review lại |
| `POST /resumes/{id}/confirm` | Xác nhận nội dung, ghi các section và chuyển `READY` |
| `POST /resumes/{id}/primary` | Chọn một CV `READY` làm CV chính |
| `DELETE /resumes/{id}` | Lưu trữ CV và vô hiệu hóa embedding hiện tại |
| `POST /resumes/{id}/analyses` | Phân tích CV đã xác nhận |
| `GET /resumes/{id}/analyses` | Lịch sử phân tích |
| `GET /resumes/{id}/analyses/{analysisId}` | Kết quả và gợi ý sửa CV |
| `POST /resumes/{id}/embedding` | Tạo hoặc dùng lại embedding khớp provider/model/content hash |
| `GET /resumes/{id}/download` | File upload gốc, hoặc văn bản CV nếu không có file |
| `GET /resumes/{id}/download?text=true` | Văn bản dữ liệu CV đã review |

Phân tích AI gồm điểm nội dung và rubric cách viết `cv-writing-v1`: bullet, dấu câu nhất quán,
ngôn từ chuyên nghiệp, độ cô đọng và đóng góp rõ ràng. `writing_quality` trả điểm/nhận xét/bằng chứng
của năm tiêu chí; điểm tổng hợp gồm 80% nội dung và 20% cách viết khi có tiêu chí đánh giá được.
Tiêu chí không áp dụng hoặc thiếu bằng chứng có điểm null; nếu không có tiêu chí nào đánh giá được,
điểm tổng hợp dùng 100% điểm nội dung. AI tắt hoặc báo cáo cũ chưa có
rubric thì điểm cách viết chưa khả dụng. Xem [hợp đồng, giới hạn và hai mẫu Postman so sánh](cv-writing-quality.md).

Body tạo thủ công mẫu:

```json
{
  "title": "CV Java Backend",
  "language": "vi",
  "structured_data": {
    "full_name": "Nguyễn An",
    "summary": "Sinh viên đã xây dựng REST API bằng Java và Spring Boot.",
    "experience": [],
    "education": [],
    "projects": [{"project_name": "API quản lý học tập", "technologies": ["Java", "Spring Boot"]}],
    "certifications": [],
    "skills": [{"name": "Java", "proficiency_level": "BEGINNER", "years_experience": 1}]
  }
}
```

`structured_data` dùng tên section số ít `experience`, `education`, cùng `projects`, `certifications`, `skills`. Người dùng sửa kết quả trích xuất trước khi gọi `confirm`; body xác nhận có thể chứa `structured_data`, `raw_content`, `is_primary`. Ngày dùng `YYYY-MM-DD`, ngày kết thúc không trước ngày bắt đầu, kinh nghiệm hiện tại không có ngày kết thúc. Chỉ các cột CV được cho phép mới được giữ lại.

Upload hỗ trợ PDF, DOCX, TXT, PNG và JPEG, tối đa 10 MiB. Kiểm tra phần mở rộng, MIME và chữ ký file; TXT phải là UTF-8. PDF tối đa 50 trang, không mã hóa. DOCX phải là ZIP chứa `word/document.xml`, giới hạn số entry và dung lượng giải nén. File lưu dưới tên UUID trong `UPLOAD_DIR`, không dùng đường dẫn client. API tải file kiểm tra chủ sở hữu, khóa lưu trữ và symlink, trả `nosniff` cùng `Content-Disposition: attachment`. Transaction upload bị rollback sẽ xóa file vừa ghi.

PDF có văn bản được đọc bằng PDFBox; DOCX dùng Apache POI. PDF scan không có OCR giữ văn bản rỗng và yêu cầu nhập thủ công. Để bật OCR bằng Tess4J, cài Tesseract/language data và xuất `APP_OCR_ENABLED=true`, `APP_OCR_TESSDATA=<đường dẫn tessdata>`, `APP_OCR_LANGUAGE=eng+vie`. PNG/JPEG cần OCR; hệ thống không tự tạo kết quả OCR khi chưa cấu hình.

Dữ liệu đã review là nguồn chính cho matching, phân tích và embedding, thay cho văn bản trích xuất có thể sai. Content hash bao gồm tiêu đề, ngôn ngữ và dữ liệu CV với thứ tự key ổn định. Sửa hoặc lưu trữ CV làm cũ embedding và đánh dấu các roadmap phụ thuộc `is_outdated=true`. Tạo embedding kiểm tra lại CV đã khóa sau lời gọi mạng, tránh xuất bản vector của nội dung đã bị sửa trong lúc chờ AI. Job provider lỗi được giữ trạng thái `FAILED` để admin kiểm tra và người dùng thử lại.

File tải từ CV được tạo bằng dữ liệu là văn bản; backend hiện chưa render PDF/DOCX theo template.

## Trợ lý nghề nghiệp

Tạo, liệt kê, đọc và lưu trữ hội thoại tại `/assistant/conversations`. Gửi `{ "message": "...", "retrieve": true }` tới `POST /assistant/conversations/{id}/messages`. Body tạo có `title`, `context_type` và các UUID tùy chọn: `related_resume_id`, `related_job_id`, `related_gap_analysis_id`, `related_roadmap_id`.

Hệ thống kiểm tra CV, gap analysis và roadmap thuộc người gửi; job tham chiếu phải đang hoạt động khi tạo hội thoại. Mỗi request đưa tối đa 20 tin gần nhất, career profile và dữ liệu liên quan vào AI. Tin người dùng và câu trả lời có timestamp riêng để giữ thứ tự hội thoại trong cùng transaction. Hội thoại bị lưu trữ trong khi chờ mạng sẽ không nhận thêm tin nhắn. Feedback tại `POST /assistant/messages/{id}/feedback` chỉ thuộc người sở hữu hội thoại, với `rating` nguyên từ 1 đến 5, `is_helpful` boolean và `feedback_text` tùy chọn.

Khi `retrieve=true`, hệ thống tạo query embedding rồi lấy tối đa 5 chunk có độ tương đồng từ 0,55, khớp provider/model. Chỉ tài liệu do người dùng sở hữu hoặc admin upload, đã `COMPLETED` và chưa bị vô hiệu hóa được dùng. Sources được lưu gắn với đúng câu trả lời; chúng là các đoạn đã cung cấp cho model, không phải bằng chứng rằng model đã sử dụng hoặc trích dẫn từng nguồn. CV, job, lịch sử chat và nội dung tài liệu được đưa vào model như dữ liệu không đáng tin cậy, cùng yêu cầu không bịa thông tin nghề nghiệp hoặc nguồn.

## Khách dùng thử

Khởi tạo `POST /guest/sessions`, lấy demo tại `GET /guest/demo-resumes` và template trial tại `GET /guest/templates`.

- `POST /guest/trials/build` nhận `template_id`, `demo_resume_id`. Máy chủ chỉ lấy template đang hoạt động có `is_trial_enabled=true` và CV hệ thống `is_demo=true`, `user_id IS NULL`, `READY`. Dữ liệu CV cá nhân gửi thêm không được đưa vào builder. Kết quả trả cho khách mà không lưu thành CV riêng.
- `POST /guest/trials/recommendations` nhận `demo_resume_id`. Đây là preview `SKILL_OVERLAP` bằng từ khóa, dùng tối đa 500 job đang hoạt động và trả tối đa 3 kết quả. Máy chủ lưu `job_matching_runs`, `matching_run_resumes`, `job_match_results`, thu 100 trial credit cố định khi thành công. Kết quả không được gắn nhãn semantic matching hoặc điểm ATS.
- `POST /guest/assistant/conversations`, `GET /guest/assistant/conversations/{id}` và `POST /guest/assistant/conversations/{id}/messages` hỗ trợ chat thử. Session chỉ truy cập hội thoại của chính nó. Chat khách có context trial và lịch sử riêng, không lấy CV cá nhân hoặc truy xuất RAG.

Các phản hồi trial gợi ý đăng ký để dùng thông tin cá nhân và lưu CV. Quá quota trả HTTP 429; session hết hạn hoặc đã chuyển thành user trả HTTP 401.

## Import và index RAG

Khóa học YouTube/Coursera có luồng riêng tại `/api/v1/admin/rag/courses`: nhập link, mô tả và kỹ năng,
Admin duyệt rồi mới đưa vào gợi ý. Sau khi index, AI chat nhận được URL/provider của khóa được truy xuất;
roadmap giữ bản chụp link đã đề xuất. Xem [quy trình và API khóa học đã duyệt](curated-course-links.md).

Các route dưới `/admin/rag/documents` yêu cầu role `ADMIN`:

| Method và path | Chức năng |
| --- | --- |
| `GET /admin/rag/documents` | Tối đa 100 tài liệu gần nhất |
| `POST /admin/rag/documents` | Import `title`, `content`, `metadata` tùy chọn |
| `GET /admin/rag/documents/{id}` | Chi tiết tài liệu, chunk và trạng thái embedding |
| `POST /admin/rag/documents/{id}/index` | Tạo embedding còn thiếu cho từng chunk |
| `POST /admin/rag/documents/{id}/archive` | Vô hiệu hóa tài liệu khỏi truy xuất |

```json
{
  "title": "Hướng dẫn Java nội bộ",
  "content": "Nội dung đã được admin kiểm tra...",
  "metadata": {"language": "vi", "topic": "java"}
}
```

Import nhận văn bản tối đa 100.000 ký tự, tách thành chunk tối đa 4.000 ký tự với overlap khoảng 300 ký tự, bảo toàn surrogate pair. Nguồn luôn là `ADMIN`; trường tùy chọn `sourceType` chỉ chấp nhận `ADMIN`. Người import được ghi vào `uploaded_by`, metadata ban đầu `disabled=false`, trạng thái `PENDING`.

Index chuyển `PROCESSING`, tạo embedding thật và ghi `vector_indexing_jobs` cho từng chunk. Provider lỗi giữ tài liệu/job `FAILED` và các chunk đã index trước đó. Gọi lại `/index` bỏ qua chunk đã có vector của provider/model hiện tại. Khi mọi chunk thành công, tài liệu chuyển `COMPLETED`, lưu metric vector và audit log. Không index tài liệu không có chunk hoặc đã lưu trữ. Archive đặt `metadata.disabled=true`; kiểm tra trước/sau lời gọi provider và bộ lọc retrieval ngăn tài liệu được đưa lại vào RAG.

## Kiểm tra

Các suite CV/AI có thể chạy bằng `mvn '-Dtest=AiServiceTests,RagDocumentServiceTests,ResumeLifecycleTests,ResumeOwnershipTests,ResumeFileExtractorTests,CareerAssistantOwnershipTests,GuestTrialTests' test`.
Chúng kiểm tra file, dữ liệu, quyền sở hữu, invalidation, retry embedding, chunk RAG và quota; model trong
các suite này được mock. Test adapter và migration riêng không thay đánh giá chất lượng CV.
Để kiểm thử inference/semantic retrieval thật, cần Ollama local đã tải model và PostgreSQL có pgvector;
không cần API key. Xem [luồng Postman](postman-test-order.md) và [các smoke test Ollama](ollama.md).
