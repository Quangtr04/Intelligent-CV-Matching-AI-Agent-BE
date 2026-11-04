# Bóc tách CV bằng quy tắc

`POST /api/v1/resumes/upload` đọc PDF/DOCX/TXT hoặc OCR ảnh như trước, sau đó chạy
`ResumeTextParser` trong Java để tạo `structured_data`. Bước này không gọi Gemini,
không cần bật AI và không sử dụng quota AI.

## Nội dung được nhận diện

- Email, số điện thoại, địa chỉ có nhãn và liên kết GitHub/LinkedIn/website.
- Họ tên/chức danh có nhãn hoặc dòng tiêu đề rõ ràng.
- Các mục tiếng Việt/Anh: giới thiệu/mục tiêu, kỹ năng, kinh nghiệm, học vấn,
  dự án, chứng chỉ.
- Kỹ năng kỹ thuật từ từ điển alias. So khớp có ranh giới từ; tên dài được ưu tiên
  khi alias chồng nhau, ví dụ JavaScript không tạo thêm Java.
- Các khối kinh nghiệm/học vấn theo khoảng thời gian và trường có nhãn; mô tả
  gốc được giữ lại để review. Không suy luận số năm kinh nghiệm hay mức kỹ năng.

Ngày có đầy đủ ngày/tháng/năm mới được đưa vào trường ISO. Ngày chỉ có tháng/năm
hoặc năm được giữ trong trường hiển thị. Thời gian hiện tại không có `end_date`.

## Dữ liệu và review

Kết quả đi qua `ResumeData.normalize` và được lưu vào resume cùng parse result.
`parser_name` có hậu tố `_RULES`, ví dụ `PDF_RULES` hoặc `PDF_TESSERACT_RULES`.
`parser_version` là `1`. `raw_content`, `raw_text` và file gốc được giữ nguyên.
CV vẫn ở trạng thái `REVIEW_REQUIRED` cho tới khi người dùng kiểm tra và xác nhận.
Không trả điểm ATS hay phần trăm độ chính xác từ bước upload.

Không có văn bản thì parse result là `FAILED`, với hướng dẫn bật OCR hoặc nhập
thủ công. Văn bản không đủ dấu hiệu vẫn được giữ lại; các trường chưa nhận diện
được để trống. `COMPLETED` chỉ cho biết đã xử lý văn bản, không bảo đảm đã tìm được
mọi trường hay xác nhận thông tin nghề nghiệp.

## Giới hạn

Parser không suy luận ngữ nghĩa như model: CV nhiều cột, tiêu đề không phổ biến,
hoặc văn bản OCR sai có thể cần chỉnh sửa. Tên công ty/chức danh mơ hồ không được
tự tạo. Phần nội dung không ánh xạ được vẫn có trong văn bản/file gốc. Khâu OCR
cho file scan/ảnh có thể tiếp tục tốn thời gian dù không còn gọi model parse.

Giao diện upload cho sửa thông tin chung, kỹ năng và kinh nghiệm; dữ liệu học vấn,
dự án và chứng chỉ vẫn đi qua mapper và có thể sửa trong trình chỉnh sửa CV.

## Kiểm tra

```powershell
mvn -B formatter:format
mvn -B "-Dtest=ResumeTextParserTests,ResumeSkillMatcherTests,ResumeRuleBasedUploadTests,ResumeFileExtractorTests,ResumeLifecycleTests,ResumeOwnershipTests" package
```

Các test không cần provider AI hoặc database thực. Test upload kiểm tra giữ văn
bản/file gốc, lưu dữ liệu có cấu trúc, trạng thái review và không gọi AI.
