# Lưu và tái sử dụng Resume PDF

Ngày triển khai: 07/11/2026. Phạm vi: tạo, cập nhật, xác nhận và tải Resume của Candidate trên backend Spring Boot và frontend Web.

## Schema và nguồn Resume

Không thêm bảng, cột hoặc migration. Tận dụng các trường đang có trong `resumes`: `file_url`, `file_name`, `mime_type`, `file_size_bytes`, `original_file_url` và `pdf_input_hash`. Hai trường cuối đã tồn tại trong migration V24 từ trước công việc này; entity Resume đã ánh xạ chúng.

`source` giữ nguyên ba giá trị `MANUAL`, `AI_BUILDER`, `UPLOAD`, không chứa đường dẫn. `file_url` chứa storage key dạng `<UUID>.pdf`; đường dẫn vật lý là thư mục storage ghép với key. Binary không lưu trong PostgreSQL.

## Các API hiện có

| API | Hành vi sau thay đổi |
|---|---|
| `POST /api/v1/resumes` | Tạo `DRAFT` như trước; chưa tạo PDF. |
| `POST /api/v1/resumes/build` | Giữ luồng AI builder `REVIEW_REQUIRED`; chưa tạo PDF. |
| `POST /api/v1/resumes/upload` | Giữ file gốc và parse như trước; ghi key gốc vào `original_file_url`. PDF upload nhận fingerprint ban đầu để dùng lại nếu không sửa dữ liệu hiển thị. |
| `PATCH /api/v1/resumes/{id}` | Cập nhật dữ liệu như trước. Nếu vẫn `READY` thì kiểm tra/tạo PDF sau khi transaction cập nhật commit; nếu còn nháp/cần review thì chờ confirm. |
| `POST /api/v1/resumes/{id}/confirm` | Xác nhận dữ liệu và commit; sau đó kiểm tra/tạo PDF và trả lại đầy đủ Resume với metadata file mới nhất. |
| `GET /api/v1/resumes/{id}/download` | Xác thực, kiểm tra chủ sở hữu và `READY`, trả PDF đã lưu; chỉ tạo lại nếu mất/hỏng/khác nội dung hiện tại. |
| `GET /api/v1/resumes/{id}/download?text=true` | Giữ tùy chọn tải nội dung canonical dạng TXT. |

Không tạo API export/download trùng lặp. Response JSON của create/update/confirm vẫn là đối tượng Resume như trước. Download có `application/pdf`, `Content-Disposition` hỗ trợ tên UTF-8, `Cache-Control: no-store, private` và `X-Content-Type-Options: nosniff`. CORS chỉ bổ sung expose `Content-Disposition` để Web đọc tên file.

## Tạo PDF và cập nhật CV

1. Candidate hoàn tất CV bằng các hàm create/update + confirm đang có trên Web.
2. Controller chờ transaction của `ResumeService` hoàn tất trước khi gọi `ResumePdfExportService`; Chromium không chạy trong transaction ghi nội dung CV của endpoint.
3. Fingerprint SHA-256 được tính trên HTML thực sự được render và phiên bản renderer. Nội dung, style/template và dữ kiện hiển thị thay đổi sẽ làm fingerprint thay đổi. `is_primary`, `updated_at`, ID dòng editor và dữ liệu không được in không làm tạo lại PDF.
4. Dùng lại `ResumePdfHtml` và `ResumePdfRenderer`. Đã bổ sung soft skills, languages và awards đang có trong Builder vào PDF; giữ escape text và template validation.
5. PDF được kiểm tra bằng signature và PDFBox, giới hạn 20 MB/50 trang, lưu thành key UUID mới trong storage riêng tư.
6. Khi công bố, khóa lại Resume và template để kiểm tra ownership, `READY` và fingerprint một lần nữa. Nội dung đổi trong lúc render sẽ trả lỗi 409, không công bố file cũ.
7. Chỉ cập nhật bốn metadata file và `pdf_input_hash` khi file mới đã được ghi thành công. File gốc upload được giữ trong `original_file_url`.
8. PDF sinh trước đó chỉ được dọn sau commit thành công; rollback xóa output chưa được công bố và giữ file cũ. Lỗi dọn file không được xóa nhầm PDF mới đã commit.

Nếu Chromium/storage lỗi sau khi confirm đã commit, API trả lỗi tương ứng, dữ liệu CV đã review vẫn được giữ. Người dùng có thể gọi confirm hoặc download lại sau khi lỗi được khắc phục. Backend không trả thành công giả hoặc cho tải PDF có fingerprint cũ.

## Download và đồng thời

Frontend dùng authenticated Axios Blob tới API download, không tự generate PDF. Nút chỉ hoạt động cho CV Ready, khóa khi đang tải, báo lỗi thật và chỉ báo thành công sau khi bắt đầu tải Blob. Tên file từ server được làm sạch; lỗi JSON Blob được đọc để hiển thị thông báo; object URL được thu hồi.

Backend kiểm tra file tồn tại, MIME, dung lượng thực tế và cấu trúc PDF trước khi dùng cache. Mất/hỏng file sẽ tạo lại nếu Resume đủ điều kiện. Trước khi đọc bytes để trả về, backend khóa Resume và kiểm tra fingerprint hiện tại; thay đổi đồng thời không được làm Candidate nhận bản cũ. Các request cùng CV trong một process được gộp bằng lock có số lượng cố định. Nhiều replica vẫn kiểm tra lại publication dưới DB lock và bỏ output thừa; trường hợp cache miss đồng thời giữa nhiều replica có thể render trùng, nhưng không thay thế nhầm dữ liệu hoặc công bố bản stale.

## Resume upload

- PDF upload mới, không chỉnh sửa nội dung hiển thị hoặc chọn template khác: dùng lại nguyên byte file gốc sau khi confirm; không gọi Chromium.
- PDF đã sửa: tạo PDF từ dữ liệu đã review; giữ nguyên file gốc trong storage và `original_file_url`.
- DOCX/TXT/image upload: giữ file gốc, tiếp tục parsing/OCR hiện có, tạo PDF từ dữ liệu đã review và template sau confirm. Đây là render CV đã review, không phải chuyển đổi giữ nguyên layout tài liệu Word gốc.
- Resume cũ chưa có fingerprint đáng tin cậy: tạo lại bảo thủ một lần từ dữ liệu hiện tại, giữ file gốc, rồi dùng cache ở các lần sau. Không tự suy đoán rằng file upload cũ vẫn tương ứng với nội dung đã chỉnh sửa.

## Storage và giới hạn

Chạy native với `.env` hiện tại: `UPLOAD_DIR=./uploads`, tính từ thư mục làm việc backend. Docker dùng `/app/uploads` và named volume `resume_uploads` (`edunexus_resume_uploads` khi dùng Compose hiện tại). Volume đã có sẵn; không thay Compose và không xóa volume khi recreate container.

Không cung cấp URL public cho CV. Key file không phải link truy cập trực tiếp; tải qua API có xác thực/ownership. Đường dẫn traversal và symlink file bị từ chối. Người quản trị cần sao lưu cả database và volume file.

Các giới hạn còn lại:

- `original_file_url` giữ byte/key file gốc. Metadata `file_name`/MIME/dung lượng sau export mô tả PDF hiện hành; chưa có giao diện/API riêng tải lại file gốc theo tên ban đầu.
- Template lưu trong `template_id` được backend sử dụng. Các template local fallback trên Web khi catalog server trống chỉ là preview, không tạo bản ghi template mới; PDF dùng template đã lưu hoặc bố cục mặc định. Builder/Details đã ghi rõ điều này. Muốn dùng đúng mẫu tùy chỉnh trong PDF, quản trị viên cung cấp mẫu qua catalog template hiện có và Candidate chọn mẫu đó trong editor.
- Việc dọn file là best effort: nếu filesystem từ chối xóa, file cũ có thể còn trên đĩa nhưng không còn được dùng để tải.
- Không có thay đổi deploy/container đang chạy trong công việc này; cần build/recreate backend và build Web như quy trình triển khai hiện có để dùng code mới.

## File triển khai

Backend (đường dẫn tính từ thư mục backend):

| File | Thay đổi |
|---|---|
| `controller/candidate/resume/ResumeController.java` | Nối confirm/READY update/default download với PDF service, giữ full response và tùy chọn TXT. |
| `service/candidate/resume/ResumeService.java` | Lưu pointer file gốc và fingerprint upload PDF. |
| `service/candidate/resume/ResumePdfExportService.java` | Cache, validation, khóa/read-back, publication và cleanup sau commit/rollback. |
| `repository/candidate/resume/ResumePdfExportRepository.java` | Khóa template ngắn hạn bằng `FOR SHARE` khi kiểm tra/công bố/tải. |
| `util/candidate/resume/ResumePdfHtml.java` | Fingerprint theo dữ liệu thực in; soft skills/ngôn ngữ/giải thưởng và display date. |
| `config/shared/SecurityConfig.java` | Expose header tên file qua CORS. |

Các file Java trên thuộc `src/main/java/com/example/intelligent_cv_matching_ai_agent/`.

Frontend:

- `src/services/resumePdfService.ts` (mới).
- `src/pages/candidate/ResumeDetailsPage.tsx`.
- `src/pages/candidate/ResumeBuilderPage.tsx` (ghi chú template preview/fallback).
- `package.json` thêm `test:resume`.

Kiểm thử:

- Mở rộng `resume/ResumePdfExportTests.java`, `resume/ResumePdfHtmlTests.java`.
- Thêm `resume/ResumePdfFlowTests.java`, `resume/ResumeUploadPdfStateTests.java`, `resume/ResumePdfPersistenceTests.java`.
- `CoreFlowIntegrationTests` và `ResumeTemplateSelectionIntegrationTests` mock PDF export riêng để kiểm tra domain/template không phụ thuộc Chromium; kiểm tra PDF thực có suite riêng.
- Frontend thêm `tests/resume-pdf-download.test.mjs`, `tests/resume-pdf-page.test.mjs`.

## Kiểm chứng

Backend: bộ 113 test liên quan đã đạt, không lỗi/skip. Bao gồm 13 test persistence thực với PostgreSQL 17 dùng database/container cô lập; smoke test Chromium thật tạo CV tiếng Việt hai trang đã được đọc bằng PDFBox và kiểm tra ảnh. Formatter validate đạt.

Bổ sung 13 test `ResumeTemplateSelectionIntegrationTests` đã đạt với toàn bộ migration hiện có V1–V33 trong PostgreSQL/pgvector cô lập. Tổng hai lần chạy là 126 test backend, không lỗi/skip; không cộng lặp các suite. Suite template mock riêng bước export để không yêu cầu Chromium trên máy CI, còn smoke test ở trên dùng trình duyệt thật.

Frontend: `npm.cmd run test:resume` đạt 51/51; scoped ESLint đạt; TypeScript/Vite build đạt (còn cảnh báo bundle size hiện có).

Lệnh backend dùng để chạy lại (tại thư mục backend, cần Docker cho persistence và Chromium cho smoke):

```powershell
mvn '-Dtest=ResumePdfExportTests,ResumePdfFlowTests,ResumeUploadPdfStateTests,ResumePdfHtmlTests,ResumePdfPersistenceTests,ResumePdfRendererTests,ResumeLifecycleTests,ResumeOwnershipTests,ResumeRuleBasedUploadTests,ResumeCompletionScenarioTests,ResumeTemplateSelectionTests,RoleSecurityFilterTests' '-Dedunexus.pdf.smoke=true' test
```

Log triển khai: `target/resume-pdf-final-tests.log`; PDF/ảnh smoke ở `target/pdf-smoke/`. Báo cáo implementation và bản sao evidence ở `reports/resume-pdf-2026-11-07/` tính từ Project.
