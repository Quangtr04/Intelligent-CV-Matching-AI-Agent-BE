# Admin quản lý mẫu CV

Admin quản lý danh mục mẫu tại `/api/v1/admin/resume-templates`: thêm, sửa HTML/CSS và metadata, bật/tắt mẫu. Trang quản trị frontend dùng route `/admin/resume-templates`. Các API cần access token do backend cấp cho tài khoản **ADMIN**; token hoặc tài khoản demo trên giao diện không thay thế quyền này.

Các request/response và assertion Postman bên dưới là **EXPECTED / CHƯA CHẠY TRÊN POSTMAN**. Kiểm thử tự động đã được thực hiện ngày **22/10/2026**, với phạm vi và bằng chứng ở cuối tài liệu; không đồng nhất kết quả đó với thao tác thủ công trên Postman hoặc giao diện quản trị.

## Phạm vi và dữ liệu đang lưu

Từ **V19**, nội dung trình bày của template chỉ lưu dưới dạng HTML/CSS trong `resume_templates.html_content`,
nhận qua field request `htmlContent` bắt buộc. Metadata như ID, tên, ngôn ngữ, mô tả và quyền sử dụng vẫn được lưu
để quản lý thư viện. Không còn cột `layout_config` hoặc chế độ cấu hình layout JSON.

Migration chuyển các mẫu cấu hình cũ thành HTML trước khi bỏ cột cấu hình, giữ nguyên ID mẫu và liên kết CV.
Mẫu đã có HTML được giữ nội dung. V11/V14 là các bước lịch sử, không sửa checksum của chúng.

Admin tải file `.html`, dán mã hoặc nhập PDF/DOC/DOCX thành bản nháp HTML/CSS rồi xem trước, sửa và đánh dấu
vị trí điền dữ liệu CV. Bản nháp chưa được lưu vào database; Admin lưu bằng POST/PUT thông thường sau khi kiểm tra.
Backend kiểm tra HTML/CSS và các slot nội dung, không nhận JavaScript. Có
[hướng dẫn nhập tài liệu, giới hạn và ba mẫu HTML/JSON Postman](html-resume-templates.md).

`GET /resumes/{id}/download` hiện trả file upload gốc nếu có, hoặc canonical TXT cho CV nhập tay/AI. Chỉnh template không biến endpoint này thành bộ render PDF/DOCX. Mẫu cũng không sinh nội dung AI khi chỉ tạo/sửa danh mục.

Không có hard DELETE. Tắt `active` ẩn mẫu khỏi danh sách công khai và chặn chọn mẫu đó cho CV mới. CV đã tồn tại vẫn giữ `template_id` và dữ liệu CV; đây là tham chiếu FK, không phải snapshot có phiên bản của toàn bộ cấu hình template. Không suy ra rằng sửa template sẽ viết lại nội dung CV cũ.

Candidate đăng nhập hệ thống có thể mở `/candidate/resumes/templates` để xem, lọc và preview mẫu, chọn mẫu để tạo CV thủ công/AI, rồi đổi hoặc bỏ mẫu trên trang chi tiết CV. GET chi tiết CV trả object `template` có `html_content`, kể cả mẫu cũ đã bị tắt. PATCH chỉ `template_id` giữ nội dung/hash, trạng thái và dữ liệu dẫn xuất; bỏ field thì giữ lựa chọn cũ, explicit null thì bỏ mẫu. Mẫu chọn mới phải active. Thay đổi HTML ở Admin được phản ánh khi đọc lại CV vì liên kết không có snapshot. Xem [hướng dẫn Candidate và dữ liệu test](candidate-resume-templates.md).

## Dùng trang quản trị

Mở `/login?mode=system` trên frontend, chọn **Tài khoản hệ thống** và đăng nhập bằng tài khoản Admin thật. Mở mục **Mẫu CV / CV Templates**, route `/admin/resume-templates`. Trang này đọc/ghi backend, có danh sách theo trang, thêm/sửa, bật/tắt và preview từ HTML/CSS.

Bộ lọc tên/nghề/trạng thái/ngôn ngữ áp dụng trên 50 mục của trang hiện tại. Chuyển trang để xem các mục khác; không coi kết quả lọc một trang là tìm kiếm toàn bộ danh mục trên server.

Chế độ demo không cấp quyền gọi API quản trị thật. Khi đang dùng phiên demo, trang template yêu cầu đăng nhập hệ thống; không cần dán token thủ công. Preview điền dữ liệu minh họa vào HTML của mẫu, không phải file PDF xuất từ backend. Nếu backend chưa chạy hoặc tài khoản không có role Admin, xử lý lỗi kết nối/quyền trước khi ghi nhận thao tác lưu thành công.

## Endpoint và quyền

`{{baseUrl}}` ví dụ `http://localhost:8080/api/v1`. Tất cả route admin dùng Bearer `{{adminAccessToken}}`.

| Method | URL | Body và expected |
| --- | --- | --- |
| GET | `{{baseUrl}}/admin/resume-templates?limit=50&offset=0` | `200`, mảng trực tiếp, gồm cả mẫu active/inactive; không có wrapper `content`/`totalPages` |
| GET | `{{baseUrl}}/admin/resume-templates/{{adminResumeTemplateId}}` | `200`, một bản ghi; mẫu inactive vẫn đọc được bằng Admin |
| POST | `{{baseUrl}}/admin/resume-templates/import` | Multipart `file` PDF/DOC/DOCX, `language` vi/en mặc định vi; `200`, bản nháp HTML và cảnh báo, chưa lưu template |
| POST | `{{baseUrl}}/admin/resume-templates` | JSON đầy đủ; `200`, bản ghi mới |
| PUT | `{{baseUrl}}/admin/resume-templates/{{adminResumeTemplateId}}` | JSON thay thế đầy đủ; `200`, cùng ID |
| PATCH | `{{baseUrl}}/admin/resume-templates/{{adminResumeTemplateId}}/status` | `{ "active": false }` hoặc `true`; `200`, bản ghi sau đổi trạng thái |

Danh sách admin mặc định `limit=50`, `offset=0`; limit 1..100, offset là integer 0..100000. Thứ tự `created_at DESC, id DESC`. Chuyển trang bằng offset, không dùng query `page` để thay thế. `PUT` không phải partial update; dùng endpoint status khi chỉ cần bật/tắt.

Candidate/public dùng **GET `{{baseUrl}}/resumes/templates`**, No Auth: chỉ mẫu `is_active=true`. Guest dùng `GET /guest/templates` kèm `X-Guest-Session`: chỉ mẫu active và `is_trial_enabled=true`. Bật trial không vượt qua điều kiện active.

## Xử lý 401 khi tạo mẫu trong Postman

URL đầy đủ để tạo mẫu local là **POST `http://localhost:8080/api/v1/admin/resume-templates`**. Kiểm tra URL đã resolve trong Postman để `/api/v1` xuất hiện đúng một lần:

| Giá trị `baseUrl` | URL request tạo mẫu |
| --- | --- |
| `http://localhost:8080` | `{{baseUrl}}/api/v1/admin/resume-templates` |
| `http://localhost:8080/api/v1` | `{{baseUrl}}/admin/resume-templates` |

Trong Authorization, chọn **Bearer Token** và nhập `{{adminAccessToken}}`. Tên `{{accessToken}}` cũng hợp lệ nếu biến đó thực sự chứa access token của Admin; tên biến không quyết định quyền. Ô token chỉ chứa JWT đã resolve, không thêm tiền tố `Bearer `, dấu ngoặc kép hoặc refresh token.

Đăng nhập lại bằng **POST `http://localhost:8080/api/v1/auth/login`**, chọn **No Auth**, rồi dùng `accessToken` từ response để gọi **GET `http://localhost:8080/api/v1/me`**. Chỉ tiếp tục tạo mẫu khi GET này trả `200` và `role="ADMIN"`; dùng cùng token cho hai request. Token demo trên frontend không dùng cho bước này.

Backend trước bản sửa có thể trả `401 Authentication required` khi URL không tồn tại hoặc method không được hỗ trợ, dù token Admin hợp lệ: error dispatch đã che `404`/`405` bằng `401`. Bản sửa cho phép dispatcher `ERROR` giữ status lỗi gốc, đồng thời giữ xác thực/phân quyền cho request trực tiếp. Với token Admin hợp lệ, URL lặp `/api/v1/api/v1/...` phải trả `404`; `PUT /api/v1/admin/resume-templates` thiếu `/{id}` phải trả `405`. Token thiếu/sai vẫn trả `401`, token hợp lệ của Candidate vẫn trả `403`. Phạm vi kiểm thử HTTP riêng cho bản sửa được ghi bên dưới.

## Request và response

Request dùng camelCase; response metadata dùng tên cột snake_case. Request gửi `htmlContent`, response trả `html_content`.

| Field request | Quy tắc |
| --- | --- |
| `name` | Bắt buộc, không trắng sau trim, tối đa 150 ký tự |
| `careerPath` | Optional/null, tối đa 150 ký tự; metadata nghề, không phải dữ kiện kinh nghiệm ứng viên |
| `language` | Bắt buộc `vi` hoặc `en` |
| `description` | Optional/null, tối đa 2000 ký tự; bỏ field/null thì lưu chuỗi rỗng |
| `active` | Bắt buộc boolean; không dùng chuỗi `"true"` |
| `trialEnabled` | Bắt buộc boolean |
| `htmlContent` | Bắt buộc, không null/trắng, tối đa 200.000 ký tự; có `data-cv-field="fullName"` và ít nhất một `data-cv-section` hợp lệ |

Màu, font, cỡ chữ và thứ tự nội dung nằm trực tiếp trong HTML/CSS. Không gửi `layoutConfig` hoặc file PDF làm nội dung template.

Với PUT, bỏ `careerPath` đặt lại về null; bỏ/null `description` đặt về `""`. Các field bắt buộc phải gửi lại, không lấy mặc định từ bản ghi trước. Không gửi response snake_case làm nguyên body request.

Các key response chính: `id`, `name`, `career_path`, `language`, `description`, `is_active`, `is_trial_enabled`, `html_content`, `created_at`, `updated_at`. UUID/thời gian trong ví dụ chỉ minh họa, phải lấy từ server thực tế:

```json
{
  "id": "11111111-1111-4111-8111-111111111111",
  "name": "Postman HTML - Tối giản",
  "career_path": null,
  "language": "vi",
  "description": "Mẫu HTML dùng chung cho nhiều ngành nghề.",
  "is_active": true,
  "is_trial_enabled": true,
  "html_content": "<main style='font-family:Arial;padding:24px;color:#1E3A8A'><h1 data-cv-field='fullName'></h1><section class='cv-section'><h2>Giới thiệu</h2><div data-cv-section='summary'></div></section></main>",
  "created_at": "2026-10-22T00:00:00Z",
  "updated_at": "2026-10-22T00:00:00Z"
}
```

## Postman: tạo → sửa → tắt → đọc CV cũ → bật lại

Thêm các biến `adminResumeTemplateId`, `templateFixtureResumeId` vào Environment. Giữ `candidateAccessToken` riêng, không ghi đè bằng token Admin. Dùng template test mới để không làm mất lựa chọn của người dùng đang hoạt động.

### 1. Admin login và xác nhận role

POST `{{baseUrl}}/auth/login`, **No Auth**, JSON:

```json
{
  "email": "{{adminEmail}}",
  "password": "{{adminPassword}}"
}
```

Sau `200`, lưu `accessToken` vào `adminAccessToken`, rồi GET `{{baseUrl}}/me` bằng Bearer Admin, kiểm tra `role="ADMIN"`. Không có mật khẩu Admin mặc định; [quy trình bootstrap](../README.md#chạy-trên-windows) cần tài khoản riêng đã xác minh.

```javascript
pm.test("Admin login có access token", () => {
  pm.response.to.have.status(200);
  const token = pm.response.json().accessToken;
  pm.expect(token).to.be.a("string").and.not.empty;
  pm.environment.set("adminAccessToken", token);
});
```

### 2. Tạo template

Nếu bắt đầu bằng PDF/Word, trước bước này gọi **POST `{{baseUrl}}/admin/resume-templates/import`**, Bearer Admin,
Body `form-data`: `file` loại File và `language=vi` loại Text. Để Postman tự đặt Content-Type multipart.
Giới hạn 10 MB, PDF tối đa 10 trang, phải có text; không nhận tài liệu mã hóa, hỏng hoặc scan cần OCR.
Response `html_content`, `blocks`, `warnings`, `requires_review=true` là bản nháp để Admin biên tập/gắn slot,
không phải một template đã lưu. Luồng này không gọi AI hoặc trừ quota. Đọc
[thao tác và ví dụ HTML sau biên tập](html-resume-templates.md#nhập-pdfword-thành-bản-nháp-htmlcss).

POST `{{baseUrl}}/admin/resume-templates`, **Bearer Admin**, raw JSON:

```json
{
  "name": "Postman HTML - Tối giản",
  "careerPath": null,
  "language": "vi",
  "description": "Mẫu HTML dùng chung cho nhiều ngành nghề.",
  "active": true,
  "trialEnabled": true,
  "htmlContent": "<main style='font-family:Arial;padding:24px;color:#1E3A8A'><h1 data-cv-field='fullName'></h1><section class='cv-section'><h2>Giới thiệu</h2><div data-cv-section='summary'></div></section></main>"
}
```

Post-response:

```javascript
pm.test("Template moi active va co HTML", () => {
  pm.response.to.have.status(200);
  const t = pm.response.json();
  pm.expect(t.id).to.match(/^[0-9a-f-]{36}$/i);
  pm.expect(t.is_active).to.eql(true);
  pm.expect(t.is_trial_enabled).to.eql(true);
  pm.expect(t.career_path).to.eql(null);
  pm.expect(t.html_content).to.be.a("string").and.include("data-cv-field");
  pm.expect(t).not.to.have.property("layout_config");
  pm.environment.set("adminResumeTemplateId", t.id);
});
```

Gửi GET detail bằng Admin để kiểm cùng ID/HTML. Gửi GET `{{baseUrl}}/resumes/templates`, **No Auth**, và kiểm mẫu vừa tạo có trong danh sách:

```javascript
pm.test("Public thay template active", () => {
  pm.response.to.have.status(200);
  const templates = pm.response.json();
  pm.expect(templates).to.be.an("array");
  pm.expect(templates.some(t => t.id === pm.environment.get("adminResumeTemplateId"))).to.eql(true);
});
```

### 3. Gắn template vào một CV test trước khi tắt

Bước này chỉ để kiểm FK của CV cũ, không gọi AI. Nếu đã có một CV test dùng đúng template vừa tạo, lưu ID đó và bỏ qua tạo mới.

POST `{{baseUrl}}/resumes`, **Bearer `{{candidateAccessToken}}`**, JSON:

```json
{
  "title": "Postman template FK fixture",
  "language": "vi",
  "template_id": "{{adminResumeTemplateId}}",
  "structured_data": {
    "summary": "Ho so gia lap de kiem tra tham chieu template.",
    "experience": [],
    "education": [],
    "projects": [],
    "skills": [],
    "certifications": []
  }
}
```

Expected `200`, `source="MANUAL"`, `status="DRAFT"`, `template_id` đúng. Lưu `id` vào `templateFixtureResumeId`; không ghi đè CV chính đang kiểm thử trong `resumeId`. Chưa cần confirm để kiểm FK này. [Luồng CV đầy đủ](postman-test-order.md#tao-cv-ollama) xử lý review/confirm khi muốn dùng CV cho matching.

```javascript
pm.test("CV fixture tham chieu dung template", () => {
  pm.response.to.have.status(200);
  const cv = pm.response.json();
  pm.expect(cv.template_id).to.eql(pm.environment.get("adminResumeTemplateId"));
  pm.environment.set("templateFixtureResumeId", cv.id);
});
```

### 4. Sửa toàn bộ template

PUT `{{baseUrl}}/admin/resume-templates/{{adminResumeTemplateId}}`, **Bearer Admin**, JSON:

```json
{
  "name": "Postman HTML - Màu mận",
  "careerPath": null,
  "language": "vi",
  "description": "Mẫu HTML đã đổi màu và thêm vị trí kỹ năng.",
  "active": true,
  "trialEnabled": false,
  "htmlContent": "<main style='font-family:Georgia;padding:24px;color:#703D57'><h1 data-cv-field='fullName'></h1><section class='cv-section'><h2>Giới thiệu</h2><div data-cv-section='summary'></div></section><section class='cv-section'><h2>Kỹ năng</h2><div data-cv-section='skills'></div></section></main>"
}
```

Expected `200`, cùng `id`, tên/HTML mới, `is_trial_enabled=false`. Public vẫn thấy mẫu vì active; guest không còn thấy vì trial tắt. Body PUT `{ "name": "Only name" }` không phải cách sửa hợp lệ.

### 5. Tắt và kiểm public/old CV

PATCH `{{baseUrl}}/admin/resume-templates/{{adminResumeTemplateId}}/status`, **Bearer Admin**:

```json
{ "active": false }
```

Expected `200`, cùng ID, `is_active=false`. GET danh sách admin/detail vẫn thấy mẫu. GET public bằng No Auth phải không có ID đó:

```javascript
pm.test("Public an template da tat", () => {
  pm.response.to.have.status(200);
  pm.expect(pm.response.json().some(t => t.id === pm.environment.get("adminResumeTemplateId"))).to.eql(false);
});
```

GET `{{baseUrl}}/resumes/{{templateFixtureResumeId}}`, **Bearer Candidate sở hữu CV**. Expected `200`, CV vẫn tồn tại và `template_id` vẫn bằng `adminResumeTemplateId`:

```javascript
pm.test("CV cu giu template_id", () => {
  pm.response.to.have.status(200);
  pm.expect(pm.response.json().template_id).to.eql(pm.environment.get("adminResumeTemplateId"));
});
```

Thử tạo **CV test mới khác** bằng ID template đang tắt phải `404`. Điều này không làm CV cũ mất FK. Không dùng GET CV bằng token Admin để kiểm ownership của Candidate.

### 6. Bật lại

PATCH status cùng URL, **Bearer Admin**:

```json
{ "active": true }
```

Expected `200`, `is_active=true`; GET public lại thấy ID. `trialEnabled` không tự bật theo status: sau PUT bước 4, trial vẫn false. Muốn cho guest dùng lại cần PUT đầy đủ với `trialEnabled=true`.

Để dọn fixture sau khi các kiểm tra xong, có thể tắt template test và archive CV test bằng `DELETE /resumes/{id}` của chủ sở hữu. Không có endpoint DELETE template.

## Ca lỗi và bảo mật cần kiểm tra

| Ca kiểm thử | Expected |
| --- | --- |
| Route admin thiếu/sai token | `401` |
| Candidate/Consultant gọi route admin với token hợp lệ | `403`, kể cả GET danh sách/detail |
| UUID không hợp lệ; JSON sai cú pháp hoặc sai kiểu boolean | `400` |
| ID template không có | `404` |
| `name` trắng/quá 150; careerPath quá 150; description quá 2000; metadata chứa ký tự NUL; language ngoài vi/en | `400` |
| Thiếu/null/trắng `htmlContent`, HTML quá 200.000 ký tự | `400` |
| Thiếu slot `fullName`, không có section được hỗ trợ hoặc slot sai | `400` |
| HTML có script/event handler, iframe/form, tài nguyên ngoài hoặc CSS không được phép | `400` |
| Import thiếu file, file hỏng/mã hóa, sai định dạng, PDF quá 10 trang hoặc không có text | `400`, không ghi template |
| Import PDF/DOC/DOCX trên 10 MB | `413` ở bộ chuyển đổi hoặc bị lớp multipart từ chối trước; không ghi template |
| Thiếu/null `active`, `trialEnabled`; PUT chỉ gửi một field | `400` |
| PATCH status thiếu/null `active` | `400` |
| `limit=0`, `limit=101`, `offset=-1`, `offset=100001` | `400` |

Thay từng giá trị trong **bản sao request test** để biết chính xác điều kiện bị từ chối. Chỉ sửa nội dung trong `htmlContent`; JavaScript và các tài nguyên tải từ bên ngoài không thuộc phạm vi template được hỗ trợ.

Ghi HTTP status thực, ID template, kết quả public visibility, FK CV cũ và PASS/FAIL/NOT_RUN sau khi chạy. Không ghi access token/mật khẩu vào artifact. JSON lỗi nghiệp vụ thường dùng `status`/`detail`; authentication filter có thể trả `status`/`message`.

## Lịch sử kiểm chứng trước V19

Các số liệu và cấu hình bên dưới mô tả phiên bản tại ngày chạy; không phải kết quả kiểm chứng contract chỉ HTML của V19.

Lượt `mvn verify` hoàn tất lúc **05:22:44 ngày 22/10/2026, giờ Việt Nam**: BUILD SUCCESS, tổng 467 test, 414 đạt, 0 failure/error, 53 skip. Xem [log verify cục bộ](../target/resume-template-verify.log). Số tổng này thuộc toàn backend; hai suite template mới có **34 test và không skip**:

| Suite | Kết quả | Bằng chứng cục bộ |
| --- | --- | --- |
| `ResumeTemplateAdminServiceTest` | 12/12 đạt | [JUnit XML service](../target/surefire-reports/TEST-com.example.intelligent_cv_matching_ai_agent.admin.ResumeTemplateAdminServiceTest.xml) |
| `ResumeTemplateAdminIntegrationTests` | 22/22 đạt | [JUnit XML integration](../target/surefire-reports/TEST-com.example.intelligent_cv_matching_ai_agent.ResumeTemplateAdminIntegrationTests.xml) |

Integration chạy MockMvc với PostgreSQL/pgvector thật qua Docker/Testcontainers và Flyway, gồm CRUD/status, quyền 401/403, dữ liệu/config sai, strict boolean/fontSize, phân trang, audit, public visibility, CV cũ giữ FK và ràng buộc layout trong database. Các test này tắt AI và không gọi model. Test lifecycle tạo dữ liệu trong database thử nghiệm, không thay danh mục người dùng đang hoạt động.

Kiểm thử hồi quy HTTP thật qua server và Testcontainers được bổ sung riêng để kiểm cả error dispatch của servlet container; kết quả MockMvc ở trên không thay thế kiểm tra này. [Log trước bản sửa](../target/security-http-before.log) đã tái hiện token Admin hợp lệ nhưng URL lặp trả `401` thay vì `404`, và PUT collection trả `401` thay vì `405`; các ca GET Admin `200`, Candidate `403`, thiếu/sai token `401` vẫn đạt. Sau bản sửa, `SecurityHttpIntegrationTests` có **5 test đạt, 0 failure/error/skip**, bao phủ cả POST tạo mẫu bằng Admin trả `200`, lỗi route/method và quyền truy cập trực tiếp `/error`. Xem [JUnit XML HTTP](../target/surefire-reports/TEST-com.example.intelligent_cv_matching_ai_agent.SecurityHttpIntegrationTests.xml) và [log verify mới](../target/security-http-verify.log). Lượt `mvn -B verify` mới hoàn tất lúc **05:56:18 ngày 22/10/2026, giờ Việt Nam**: **BUILD SUCCESS**, tổng **472 test, 419 đạt, 0 failure/error và 53 skip**, thời gian Maven 52,337 giây. Tổng này gồm cả 34 test tính năng và 5 test HTTP; không ghi 472 test pass.

Frontend đã qua `npm run build` và ESLint cho bốn file template mới cùng các module auth/client được thêm hoặc sửa. Kiểm tra riêng đã xác nhận đăng nhập/profile, định dạng refresh token, gom refresh đồng thời, xử lý phiên bị thu hồi, giữ phiên khi request retry lỗi validation và tách refresh theo tài khoản/token. SSR preview kiểm hai layout, sáu section, ngôn ngữ legacy `fr`, escape text và fallback style. Đây là kiểm tra build/logic/SSR, chưa phải nghiệm thu thao tác create/edit/toggle trong browser bằng tài khoản Admin thật.

Runtime local đã được kiểm tra lúc **05:28:37 ngày 22/10/2026, giờ Việt Nam**: app chạy tại port 8080, health `UP`, JAR khớp bản Maven đã verify; Flyway ở version 11, không có migration lỗi. Cả 2 template hiện có đã có cấu hình, constraint layout đã validate. GET public trả `200` với 2 mẫu active có `description`/`layout_config`; OpenAPI có đủ 5 cặp method/path mới và GET admin không token trả `401`. Xem [bằng chứng runtime](../target/resume-template-app-runtime.json).

Bản sửa error dispatch đã được rebuild và cập nhật riêng container backend. Kiểm tra lại lúc **06:00:13 ngày 22/10/2026, giờ Việt Nam** xác nhận JAR trong container khớp bản verify mới, health `UP`, GET public template `200`, GET admin không token `401` và endpoint POST tạo mẫu có trong OpenAPI. Xem [bằng chứng runtime sau bản sửa](../target/security-http-app-runtime.json). Kiểm tra này không dùng token người dùng để tạo dữ liệu trong database ứng dụng; ca tạo mẫu qua HTTP đã chạy trên PostgreSQL thử nghiệm như mô tả ở trên.

Trước cập nhật đã tạo backup mới khi database còn ở V10 và kiểm tra archive: [metadata backup](../target/resume-template-app-backup.json). Đây là bằng chứng backup được tạo/kiểm tra archive, không phải báo cáo diễn tập restore hoàn chỉnh.

Kiểm tra runtime trên chỉ bao phủ readiness, migration, public read, đăng ký route và từ chối anonymous. CRUD/persistence, quyền Admin/Candidate và audit được kiểm bằng 34 test mới nêu trên; **chưa thao tác create/edit/toggle trong browser Admin hoặc gửi các request mẫu bằng Postman**. Các file trong `target` là bằng chứng sinh cục bộ, có thể không tồn tại trong checkout mới; không coi link log thiếu ở máy khác là báo cáo test đã chạy tại máy đó.

## Tài liệu liên quan

- [Danh mục Postman: Admin template CV](postman-api-guide.md#admin-resume-templates)
- [Thứ tự kiểm thử: nhánh template quản trị](postman-test-order.md#admin-template-order)
- [Candidate xem, chọn và đổi mẫu CV](candidate-resume-templates.md)
- [CV build/review/confirm và download](postman-api-guide.md#section-6)
- [Cấu trúc backend](architecture.md)
