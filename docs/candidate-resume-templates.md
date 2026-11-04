# Candidate xem và chọn mẫu CV

Candidate có thể mở thư viện mẫu do Admin quản lý, xem trước và chọn mẫu cho CV nhập tay hoặc CV tạo bằng AI. Lựa chọn được lưu vào `resumes.template_id`; trang chi tiết lấy HTML của mẫu từ backend để trình bày nội dung CV thật.

Từ V19, toàn bộ mẫu lưu nội dung trình bày HTML/CSS trong `html_content`. Frontend lấy HTML từ API rồi điền
nội dung CV vào các slot; không còn cấu hình layout JSON. Migration chuyển các mẫu cũ thành HTML, giữ nguyên
ID và liên kết CV. Xem [cách nhập ba mẫu HTML có sẵn](html-resume-templates.md).

## Dùng trên giao diện

1. Đăng nhập bằng **Tài khoản hệ thống** tại `/login?mode=system`, dùng tài khoản Candidate thật.
2. Vào **CV của tôi** tại `/candidate/resumes`, chọn **Xem mẫu CV**. Có thể mở trực tiếp `/candidate/resumes/templates`.
3. Tìm theo tên/nghề hoặc lọc ngôn ngữ, chọn một mẫu để xem trước. Thư viện chỉ hiển thị mẫu `is_active=true`; `career_path` là metadata gợi ý, không giới hạn ngành nghề được dùng mẫu.
4. Chọn **Tạo CV với mẫu này** hoặc **Tạo bằng AI**. Mẫu được chuyển sang màn tạo qua query `templateId`; có thể đổi lựa chọn trước khi gửi.
5. Nhập dữ kiện và tạo CV. Luồng hệ thống lưu vào backend. CV thủ công bắt đầu ở `DRAFT`; CV do AI sinh bắt đầu ở `REVIEW_REQUIRED`. Kiểm tra nội dung trước khi xác nhận.
6. Mở chi tiết CV, kiểm tra tên mẫu và bố cục. Chọn **Đổi mẫu**, xem trước bằng nội dung CV đang lưu rồi **Lưu mẫu đã chọn**. Chọn **Không dùng mẫu** để xóa liên kết và dùng bố cục mặc định.

Preview ở thư viện dùng nội dung minh họa, có nhãn phân biệt. Preview trong trang chi tiết dùng thông tin CV của Candidate. Mẫu thay đổi bố cục, màu nhấn, font/cỡ chữ và thứ tự sáu section; không tự bổ sung kinh nghiệm, chứng chỉ hoặc thông tin cá nhân.

Phiên demo tiếp tục dùng dữ liệu minh họa ở các màn CV cũ. Để lưu và đọc CV thật qua API, phải đăng nhập hệ thống; token demo không được gửi thay access token backend. Các màn nghiệp vụ khác còn dùng mock chưa được coi là đã tích hợp chỉ vì luồng mẫu CV này đã có adapter thật.

## Quy tắc lưu và thay đổi mẫu

| Thao tác | Hành vi |
| --- | --- |
| Lấy thư viện | `GET /api/v1/resumes/templates`, public, chỉ mẫu active |
| Tạo CV thủ công | `POST /api/v1/resumes`, gửi `template_id` optional |
| Tạo CV bằng AI | `POST /api/v1/resumes/build`, gửi `template_id` optional |
| Đọc chi tiết CV | `GET /api/v1/resumes/{id}`, trả `template_id` và object `template` có metadata, `html_content` |
| Đổi mẫu | `PATCH /api/v1/resumes/{id}` với `{ "template_id": "UUID thật" }` |
| Bỏ mẫu | Cùng endpoint PATCH với `{ "template_id": null }` |
| Không thay đổi mẫu | Bỏ hoàn toàn field `template_id` khỏi PATCH |

Các API CV riêng cần Bearer token của chủ sở hữu. Candidate khác đọc/đổi CV trả `404`. CV đã archive không đổi mẫu được (`409`). UUID sai định dạng trả `400`; UUID không tồn tại hoặc mẫu đã tắt trả `404` khi được chọn cho CV mới hay được gửi lại trong PATCH.

PATCH chỉ có `template_id` giữ nguyên dữ liệu CV, `content_hash`, trạng thái xác nhận, cờ CV chính và dữ liệu dẫn xuất như embedding/roadmap. Nếu cùng request còn sửa nội dung thực sự, backend xử lý thay đổi nội dung theo vòng đời review hiện có. Đặc biệt, `structured_data` là thay toàn bộ document; không gửi một document rút gọn khi chỉ muốn đổi mẫu.

Tắt mẫu ở Admin không xóa mẫu và không làm mất liên kết của CV cũ. Chủ sở hữu vẫn đọc được HTML của mẫu đang tắt qua chi tiết CV. Có thể sửa nội dung CV mà không gửi lại `template_id` để giữ lựa chọn cũ; muốn chọn mẫu khác thì chọn mẫu active hoặc bỏ mẫu bằng null.

Liên kết này là tham chiếu đến HTML của mẫu hiện tại, **không phải snapshot có phiên bản**. Khi Admin sửa HTML, lần đọc CV tiếp theo sử dụng HTML mới; nội dung CV không bị viết lại. Thay mẫu không tạo một file PDF/DOCX mới. Endpoint download hiện trả file upload gốc nếu có, hoặc canonical TXT cho CV nhập tay/AI.

## Kiểm tra nhanh bằng Postman

`baseUrl = http://localhost:8080/api/v1`. Các bước bên dưới là **EXPECTED**, cần ghi nhận kết quả thực sau khi chạy; không thay thế bằng chứng browser hoặc đánh giá chất lượng model.

### 1. Chọn ID thật

GET `{{baseUrl}}/resumes/templates`, **No Auth**. Đọc `name`, `description`, `language`, `html_content` và lưu một ID vào `resumeTemplateId`. FE render `html_content` trong iframe giới hạn quyền và điền dữ liệu vào các slot được hỗ trợ.

```javascript
pm.test("Thu vien tra mau active", () => {
  pm.response.to.have.status(200);
  const templates = pm.response.json();
  pm.expect(templates).to.be.an("array");
  pm.expect(templates.every(t => t.is_active === true)).to.eql(true);
});
```

Nếu không có mẫu, bỏ field `template_id` ở bước tạo; không gửi chuỗi rỗng hay biến chưa resolve. Admin có thể tạo mẫu theo [hướng dẫn quản trị](admin-resume-templates.md).

### 2. Tạo CV thủ công để kiểm liên kết

POST `{{baseUrl}}/resumes`, **Bearer `{{candidateAccessToken}}`**, raw JSON:

```json
{
  "title": "CV kiem tra chon mau",
  "language": "vi",
  "template_id": "{{resumeTemplateId}}",
  "structured_data": {
    "full_name": "Nguyen Minh An",
    "email": "candidate@example.test",
    "summary": "Sinh vien cong nghe thong tin, co du an hoc tap Java va PostgreSQL.",
    "experience": [],
    "education": [],
    "projects": [],
    "skills": [{ "name": "Java" }, { "name": "PostgreSQL" }],
    "certifications": []
  }
}
```

```javascript
pm.test("CV luu dung mau va HTML", () => {
  pm.response.to.have.status(200);
  const cv = pm.response.json();
  pm.expect(cv.template_id).to.eql(pm.environment.get("resumeTemplateId"));
  pm.expect(cv.template.id).to.eql(cv.template_id);
  pm.expect(cv.template.html_content).to.be.a("string").and.include("data-cv-field");
  pm.expect(cv.template).not.to.have.property("layout_config");
  pm.environment.set("templateFixtureResumeId", cv.id);
});
```

GET `{{baseUrl}}/resumes/{{templateFixtureResumeId}}` bằng cùng token: expected `200`, cùng ID mẫu/HTML. Với CV AI, dùng bộ dữ kiện đầy đủ trong [luồng Gemini](postman-test-order.md#tao-cv-ai), không dùng mẫu CV này làm bằng chứng chất lượng model.

### 3. Đổi mẫu và bỏ mẫu

Lấy ID của một mẫu active khác, lưu vào `secondResumeTemplateId`. Nếu chỉ có một mẫu thì tạo thêm bằng Admin hoặc chỉ kiểm bước bỏ mẫu. PATCH `{{baseUrl}}/resumes/{{templateFixtureResumeId}}`, **Bearer Candidate**:

```json
{ "template_id": "{{secondResumeTemplateId}}" }
```

Expected `200`, `template_id` và `template.id` cùng bằng ID mới; `structured_data`, `content_hash`, `status`, `is_primary` không đổi so với trước request. Sau đó bỏ mẫu:

```json
{ "template_id": null }
```

Expected `200`, cả `template_id` và `template` bằng null; GET lại vẫn giữ kết quả đó. Không dùng `""` để bỏ mẫu.

### 4. Kiểm tình huống mẫu bị tắt

Tạo CV test tham chiếu một mẫu test, rồi Admin tắt mẫu đó. Expected: thư viện không còn ID, GET CV cũ vẫn có ID/HTML, tạo CV mới với ID đó bị `404`. Không chọn lại ID đang tắt trong PATCH; dùng một mẫu active khác hoặc null. Xem [trình tự Admin và Candidate](admin-resume-templates.md#5-tắt-và-kiểm-publicold-cv).

## Phạm vi kiểm tra

Sau khi chạy, ghi riêng kết quả build/kiểm thử tự động, thao tác browser và request Postman. Kiểm thử backend dùng AI mock chỉ xác minh contract/persistence và quyền truy cập, không chứng minh Gemini đã sinh CV đúng. Các log trong `target` là artifact cục bộ, có thể không tồn tại trong checkout khác.

Các số liệu ngày 22/10/2026 bên dưới là lịch sử trước V19 và trước khi bỏ cấu hình layout JSON.

Kiểm tra frontend cho thay đổi Candidate ngày **22/10/2026**:

| Kiểm tra | Kết quả và phạm vi |
| --- | --- |
| Build frontend | Thành công; [log build](../../../FE/FA26SE119-FE/candidate-template-build.log) |
| ESLint các file tính năng | 8 file được kiểm tra, thành công; không đồng nghĩa toàn bộ frontend đã hết lỗi lint |
| `node scripts/test-candidate-resume.mjs` | 11 kiểm tra contract/render đạt, gồm truyền lựa chọn/ngôn ngữ vào tạo thủ công và AI, tách phiên demo, hiển thị CV dùng mẫu đã tắt, escape text và giới hạn CSS; [script kiểm tra](../../../FE/FA26SE119-FE/scripts/test-candidate-resume.mjs) |
| Browser/Postman thủ công | Chưa thực hiện thao tác trọn luồng; phiên công cụ không có browser khả dụng |

**Backend:** `mvn verify` khi Docker sẵn sàng hoàn tất lúc **18:10:03 ngày 22/10/2026, giờ Việt Nam**, thời gian Maven **2 phút 01 giây**, **BUILD SUCCESS**. Toàn backend ghi nhận **488 test: 435 đạt, 0 failure/error, 53 skip**; không ghi 488 test pass. Xem [log verify](../target/candidate-template-verify.log).

Hai suite riêng cho lựa chọn mẫu có **16 test đạt và không skip**: [ResumeTemplateSelectionIntegrationTests: 13 ca](../target/surefire-reports/TEST-com.example.intelligent_cv_matching_ai_agent.ResumeTemplateSelectionIntegrationTests.xml) chạy MockMvc với PostgreSQL/pgvector qua Testcontainers, và [ResumeTemplateSelectionTests: 3 ca](../target/surefire-reports/TEST-com.example.intelligent_cv_matching_ai_agent.resume.ResumeTemplateSelectionTests.xml) kiểm logic/DTO. Các ca bao phủ lưu mẫu khi tạo thủ công/AI, trả cấu hình ở chi tiết CV, mẫu đã tắt, đổi/bỏ mẫu giữ dữ liệu dẫn xuất, kiểm ownership/xác thực/archive và UUID không hợp lệ. Ca AI builder dùng mock để kiểm contract; không đo suy luận Gemini hay chất lượng CV thật.

Container backend local đã được rebuild và kiểm tra lúc **18:16:10 ngày 22/10/2026, giờ Việt Nam**: health `UP`, JAR đang chạy khớp bản đã verify, API public trả `200` với **5 mẫu active** có `layout_config`, API danh sách CV riêng không token vẫn trả `401`. Xem [bằng chứng runtime](../target/candidate-template-app-runtime.json). Không dùng token người dùng để tạo/sửa CV trên database ứng dụng trong lượt xác minh runtime này; các ca ghi dữ liệu chạy trên database thử nghiệm.

- [Thứ tự kiểm thử CV bằng Gemini](postman-test-order.md#tao-cv-ai)
- [Admin quản lý mẫu](admin-resume-templates.md)
