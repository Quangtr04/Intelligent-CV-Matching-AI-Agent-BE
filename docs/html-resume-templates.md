# Mẫu CV lưu dưới dạng HTML

Từ V19, nội dung trình bày của mẫu CV chỉ lưu dưới dạng **HTML kèm CSS** trong `resume_templates.html_content`. Frontend nhận HTML từ API,
điền nội dung CV vào các vị trí dành sẵn rồi hiển thị bản CV. Admin có thể tải file `.html`, dán mã HTML hoặc
nhập PDF/Word để chuyển thành bản nháp HTML/CSS rồi chỉnh sửa trước khi lưu. Metadata tên, ngôn ngữ, mô tả
và quyền sử dụng vẫn nằm trong bản ghi template;
không còn cấu hình layout JSON hoặc file PDF làm nguồn trình bày.

Migration V19 chuyển mẫu cấu hình cũ thành HTML trước khi bỏ `layout_config`, giữ nguyên ID và liên kết CV.
Mẫu đã có HTML giữ nội dung đã lưu. Tổng số bảng ứng dụng vẫn là 83.

HTML là bố cục trình bày; dữ liệu cá nhân, học vấn, kinh nghiệm, dự án và kỹ năng vẫn được lưu riêng trong CV.
AI tiếp tục tạo nội dung CV có cấu trúc; không cần yêu cầu AI viết lại HTML của mẫu.

## Ba mẫu có sẵn

Các mẫu được dựng lại bằng HTML/CSS, tham khảo bố cục trong ba PowerPoint người dùng cung cấp.
Đây không phải chuyển đổi tự động hoặc bản sao từng pixel; dùng font hệ thống và không kèm chân dung mẫu.

| Mẫu | File HTML | JSON tạo bằng Postman |
| --- | --- | --- |
| Học thuật | [academic.html](../../../FE/FA26SE119-FE/public/resume-templates/academic.html) | [academic.request.json](examples/resume-template-html/academic.request.json) |
| Sinh viên | [student.html](../../../FE/FA26SE119-FE/public/resume-templates/student.html) | [student.request.json](examples/resume-template-html/student.request.json) |
| Tối giản | [minimal.html](../../../FE/FA26SE119-FE/public/resume-templates/minimal.html) | [minimal.request.json](examples/resume-template-html/minimal.request.json) |

Các mẫu được cung cấp dưới dạng file để Admin chọn nhập; không tự thêm bản ghi vào database.
Tên mẫu mô tả cách trình bày, không ràng buộc ứng viên phải làm một nghề cụ thể.

## Thao tác trên giao diện

1. Đăng nhập bằng tài khoản Admin hệ thống, mở `/admin/resume-templates`.
2. Chọn tạo mẫu. Tải một file HTML ở bảng trên, dán HTML hoặc nhập PDF/Word theo luồng bên dưới;
   điền tên, ngôn ngữ và quyền sử dụng.
3. Xem trước, sửa và lưu. `active` quyết định mẫu có hiện trong thư viện; `trialEnabled` dành cho quyền dùng thử.
4. Candidate hệ thống mở `/candidate/resumes/templates`, chọn mẫu rồi tạo CV thủ công hoặc bằng AI.
5. Trang chi tiết CV điền nội dung thực tế vào mẫu đã chọn. Đổi mẫu chỉ đổi cách trình bày, không tự đổi nội dung CV.
   Với mẫu HTML, nút tải HTML lưu bản đã điền dữ liệu; mở file bằng trình duyệt để đọc hoặc in/lưu PDF.

Các chữ minh họa trong file HTML không phải dữ liệu CV của ứng viên. Khi render, nội dung tại các slot được thay
bằng dữ liệu CV; các mục không có dữ liệu được ẩn.

## Nhập PDF/Word thành bản nháp HTML/CSS

Luồng quản trị là **tải PDF/Word → chuyển thành bản nháp → xem trước/chỉnh sửa/đánh dấu vị trí điền dữ liệu CV
→ xác nhận đã kiểm tra → lưu HTML**. File đầu vào không trở thành nội dung trình bày được lưu trong database;
chỉ HTML/CSS sau khi Admin lưu mới nằm trong `html_content`. Luồng này không thêm migration hoặc bảng mới.

Hỗ trợ `.pdf`, `.doc`, `.docx`, tối đa **10 MB**; PDF tối đa **10 trang**. File phải đọc được nội dung chữ.
File mã hóa/có mật khẩu, sai định dạng, hỏng hoặc PDF scan không có text bị từ chối; luồng nhập template
không chạy OCR. `.pptx` không thuộc định dạng nhập này. Ba mẫu HTML dựng từ PowerPoint ở trên vẫn dùng được.
Với Word, không có bộ dàn trang Word để đếm chính xác số trang; metadata khai báo quá 10 trang hoặc quá nhiều
ngắt trang cũng bị từ chối. Bộ chuyển đổi còn giới hạn độ phức tạp: 2.000 khối chỉnh sửa, 100.000 ký tự text,
200.000 ký tự HTML và tối đa 20 MB nội dung DOCX sau giải nén.

Bộ chuyển đổi chạy trong backend bằng thư viện Java, không gọi API thương mại hoặc model AI và không trừ quota AI.
Với PDF có chữ đọc được, backend giữ vị trí các dòng, màu, cỡ chữ và kiểu đậm/nghiêng bằng HTML/CSS;
phần đồ họa tĩnh như hình nền, đường kẻ và ảnh được kết xuất riêng thành PNG nhúng trong cùng HTML.
Nội dung chữ Unicode được hỗ trợ vẫn là node văn bản chỉnh sửa/đánh dấu được, không biến toàn bộ trang CV
thành một ảnh. Font PDF được thay bằng font hệ thống phù hợp; không tải hoặc nhúng font từ bên ngoài.
Với DOC/DOCX, nội dung tiếp tục dàn lại thành đoạn/bảng HTML với định dạng cơ bản; ảnh Word chưa được nhập.

Bản nháp PDF dùng bố cục trang cố định (`pdf-page`, `pdf-line`); khi Admin gộp dòng để gắn slot,
frontend giữ vùng tọa độ bằng `pdf-region`. Đây không phải bộ tự dàn trang CV: nội dung Candidate dài hơn
có thể tràn vùng hoặc đè lên mục khác, không tự chuyển sang trang mới. Admin phải thử dữ liệu CV thực tế,
chỉnh kích thước/vị trí hoặc chuyển phần cần co giãn sang CSS flow/grid/flex trước khi dùng chung.
Font đặc biệt, ký tự không có ánh xạ Unicode, hiệu ứng clipping/transparency và lớp chồng phức tạp có thể khác nguồn.
Ảnh chân dung trong PDF là đồ họa tĩnh, không tự thay bằng ảnh của Candidate; cần xóa hoặc thay ảnh mẫu trước khi lưu.

HTML và CSS là một tài liệu tự chứa; không cần Bootstrap và không tải Bootstrap/CDN, stylesheet hoặc font bên ngoài.
PNG nhúng phải là PNG tĩnh hợp lệ, tối đa **200.000 byte**, **2.048 px mỗi chiều** và **4.000.000 pixel**.
HTML hoàn chỉnh, gồm cả chuỗi base64 của ảnh, vẫn tối đa **200.000 ký tự**; giới hạn ảnh không cộng thêm dung lượng
cho HTML. PDF có nhiều ảnh/trang có thể vượt giới hạn và bị từ chối thay vì âm thầm cắt bớt nội dung.
Trang PDF được hỗ trợ có kích thước mỗi chiều từ 1 đến 1.440 pt. Không cam kết giống từng pixel;
Admin cần kiểm tra bố cục, chữ tiếng Việt, màu, ảnh tĩnh và ngắt trang trước khi đánh dấu đã xem lại.

Trên trang Admin, phần xem trước bản nháp cho thấy nội dung vừa trích xuất; phần xem trước với dữ liệu CV minh họa
cho thấy kết quả sau khi gắn các vị trí động. Admin có thể sửa mã HTML/CSS, chọn khối để gắn `fullName`, `headline`,
`contact` hoặc một trong sáu section được hỗ trợ. Khối gắn slot sẽ được FE thay nội dung khi dùng template;
không gắn tiêu đề "Kinh nghiệm" làm toàn bộ section nếu muốn giữ tiêu đề đó bên ngoài phần dữ liệu động.
Xóa thông tin cá nhân/chữ mẫu còn sót lại và bỏ các khối không cần trước khi lưu. Phải có `fullName`, ít nhất
một section hợp lệ và xác nhận đã xem lại bản nhập trên giao diện; API lưu vẫn kiểm tra HTML và các slot.

### Postman: tải tài liệu, sửa bản nháp rồi lưu

`baseUrl` trong ví dụ là `http://localhost:8080/api/v1`. Đăng nhập Admin thật và dùng Bearer `{{adminAccessToken}}`:

```http
POST {{baseUrl}}/admin/resume-templates/import
Authorization: Bearer {{adminAccessToken}}
```

Chọn **Body → form-data**:

| Key | Type | Giá trị |
| --- | --- | --- |
| `file` | File | Chọn PDF/DOC/DOCX mẫu trên máy |
| `language` | Text | `vi` hoặc `en`; bỏ field thì mặc định `vi` |

Để Postman tự tạo `Content-Type: multipart/form-data` và boundary. Không giữ header `application/json`
của request tạo template. Expected `200`: response có `html_content`, danh sách khối để đánh dấu, `warnings`
và `requires_review=true`. **Chưa có template mới hoặc ID được lưu ở bước này.** Xem bản nháp, chỉnh các khối
có chữ minh họa thành slot phù hợp rồi mới POST tạo hoặc PUT sửa template theo hợp đồng bên dưới.
Gửi nguyên bản nháp chưa gắn slot vào POST tạo sẽ không qua kiểm tra template.

Các field response:

| Field | Ý nghĩa |
| --- | --- |
| `source_file_name` | Tên file nguồn, chỉ phục vụ hiển thị bản nhập |
| `source_format` | Loại tài liệu đã đọc |
| `html_content` | HTML/CSS bản nháp, các khối có ID `import-block-N` để chọn/đánh dấu |
| `blocks` | Mảng `{ "id": "import-block-1", "tag": "p", "text": "..." }` theo nội dung trích xuất |
| `warnings` | Danh sách cảnh báo chuyển đổi và phần cần kiểm tra |
| `requires_review` | Luôn `true`, bản nhập cần Admin xem lại |
| `page_count` | Số trang PDF; có thể null với Word |

Ví dụ nội dung sau khi Admin đã chỉnh sửa và đánh dấu, dùng **Body → raw → JSON** cho request lưu:

```json
{
  "name": "Mẫu nhập từ Word - đã kiểm tra",
  "careerPath": null,
  "language": "vi",
  "description": "HTML đã được kiểm tra và đánh dấu các vị trí dữ liệu CV.",
  "active": true,
  "trialEnabled": false,
  "htmlContent": "<style>.cv{font-family:Arial;color:#24303b;padding:24px}.cv h2{border-bottom:1px solid #ddd}</style><main class='cv'><h1 data-cv-field='fullName'></h1><p data-cv-field='headline'></p><p data-cv-field='contact'></p><section class='cv-section'><h2>Giới thiệu</h2><div data-cv-section='summary'></div></section><section class='cv-section'><h2>Kinh nghiệm</h2><div data-cv-section='experience'></div></section><section class='cv-section'><h2>Kỹ năng</h2><div data-cv-section='skills'></div></section></main>"
}
```

Đây là ví dụ HTML tối giản sau biên tập, không phải khẳng định bộ chuyển đổi luôn tạo đúng bố cục này.
Sau khi lưu, GET public `/resumes/templates` phải thấy mẫu nếu `active=true`; Candidate chọn mẫu và kiểm tra
nội dung CV thực tế đã thay chữ mẫu. Dùng mẫu/CV test riêng, giữ access token ngoài artifact.

Các ca lỗi cần kiểm tra riêng: thiếu/sai token `401`, Candidate gọi import `403`, thiếu file/ngôn ngữ không hợp lệ
hoặc tài liệu không đọc được `400`; file quá giới hạn kích thước có thể bị lớp multipart từ chối trước controller.
Ở bộ chuyển đổi, file trên 10 MB trả `413`.
Nhập tài liệu lỗi không được tạo/sửa template trong database.

Tài liệu thư viện: [PDFBox về thứ tự và giới hạn trích xuất text](https://pdfbox.apache.org/3.0/faq.html#text-extraction),
[Apache POI HWPF/XWPF cho DOC và DOCX](https://poi.apache.org/components/document/index.html).
Các thư viện đọc nội dung tài liệu; cấu trúc HTML an toàn, slot CV và quy trình Admin xem lại là phần ứng dụng này triển khai.

## Hợp đồng API

Endpoint quản trị giữ nguyên:

```http
POST {{baseUrl}}/admin/resume-templates
PUT {{baseUrl}}/admin/resume-templates/{{templateId}}
Authorization: Bearer {{adminAccessToken}}
Content-Type: application/json
```

`baseUrl` ví dụ `http://localhost:8080/api/v1`. Request dùng **`htmlContent`**, response dùng **`html_content`**.
`htmlContent` bắt buộc, không null/trắng, tối đa 200.000 ký tự. Màu, font và bố cục được viết trực tiếp trong
HTML/CSS; request không dùng `layoutConfig` và response không còn `layout_config`.

POST và PUT dùng cùng body đầy đủ. PUT phải gửi lại HTML; không thể bỏ/null HTML để chuyển về cấu hình cũ.
Ví dụ tối giản dùng được cho cả hai method:

```json
{
  "name": "Mẫu CV tối giản",
  "careerPath": null,
  "language": "vi",
  "description": "Bố cục HTML dùng chung, dữ liệu CV được điền khi hiển thị.",
  "active": true,
  "trialEnabled": true,
  "htmlContent": "<main style='font-family:Arial;padding:24px;color:#24303b'><h1 data-cv-field='fullName'></h1><p data-cv-field='headline'></p><p data-cv-field='contact'></p><section class='cv-section'><h2>Giới thiệu</h2><div data-cv-section='summary'></div></section><section class='cv-section'><h2>Kinh nghiệm</h2><div data-cv-section='experience'></div></section><section class='cv-section'><h2>Kỹ năng</h2><div data-cv-section='skills'></div></section></main>"
}
```

Các slot trống sẽ được FE điền bằng dữ liệu CV; không đặt kinh nghiệm hoặc thông tin ứng viên cố định trong template.

Để tạo mẫu trong Postman, mở một file `*.request.json` ở bảng trên, sao chép toàn bộ vào **Body → raw → JSON**,
gửi bằng token Admin. Đây là request có HTML thực tế, không cần tự escape dấu xuống dòng hoặc dấu nháy.
Lưu `id` response vào `templateId` để kiểm tra tiếp.

```http
GET {{baseUrl}}/resumes/templates
```

API public trả mảng metadata và `html_content` của các mẫu active; FE dùng trực tiếp field này cho renderer HTML,
không cần ghép từ mã layout hoặc gọi thêm endpoint lấy file mẫu. Dùng token Candidate để tạo CV với
`"template_id": "{{templateId}}"` theo [hướng dẫn Candidate](candidate-resume-templates.md).
Chi tiết `GET /resumes/{id}` chứa `template.html_content`, kể cả mẫu đã tắt sau khi CV được tạo.

## Quy ước viết HTML cho mẫu

Các slot thông tin chung:

```html
<h1 data-cv-field="fullName">HỌ VÀ TÊN</h1>
<p data-cv-field="headline">Vị trí hoặc định hướng nghề nghiệp</p>
<p data-cv-field="contact">Email • Điện thoại • Địa chỉ</p>
```

Các slot nội dung nhận một trong sáu mã: `summary`, `experience`, `education`, `projects`, `skills`, `certifications`.
Đặt tiêu đề bên ngoài slot; frontend thay phần nội dung bên trong bằng các entry của CV:

```html
<section class="cv-section">
  <h2>Kinh nghiệm</h2>
  <div data-cv-section="experience"></div>
</section>
```

Mẫu phải có `fullName` và ít nhất một slot section hợp lệ. Chỉ ghi các slot đã được hỗ trợ, không dùng biểu thức
JavaScript hoặc cú pháp `{{...}}` để truy cập object tùy ý. Nội dung CV được điền dưới dạng văn bản và node do ứng dụng
tạo; chuỗi `<script>` hoặc HTML trong tên/kinh nghiệm không được thực thi.

Các entry dùng class `cv-entry`, `cv-entry-title`, `cv-entry-subtitle`, `cv-entry-period`, `cv-entry-text`,
`cv-entry-items`. CSS của mẫu có thể định dạng các class này. CSS đặt trong `<style>` hoặc thuộc tính `style`;
hỗ trợ bố cục grid/flex và `@page`/`@media` cho trang in. Dùng font hệ thống để không phải tải font ngoài.

## Phạm vi HTML và xuất file

Backend kiểm tra HTML trước khi lưu: không nhận script, event handler, iframe/embed, form, URL tài nguyên bên ngoài
hoặc CSS có tải tài nguyên/mã thực thi. Frontend hiển thị trong iframe bị giới hạn quyền, kèm CSP; không chèn HTML
mẫu trực tiếp vào DOM của ứng dụng. Quy ước này phục vụ bố cục CV, không phải môi trường chạy website tùy ý.
Ảnh được phép chỉ là PNG tĩnh nhúng bằng `data:image/png;base64,...` đã qua kiểm tra dung lượng/kích thước/cấu trúc;
không dùng ảnh từ URL, SVG, ảnh động, `srcset`, `@import` hoặc `url()` trong CSS. Giữ CSS trong thẻ `<style>` hoặc
thuộc tính `style`. Không dùng thẻ `<link>` để gọi Bootstrap CDN.

PDF/DOC/DOCX có thể nhập thành bản nháp qua endpoint `/admin/resume-templates/import`; Admin phải xem lại và
gắn các slot trước khi lưu. PPTX chưa được hỗ trợ nhập trực tiếp. Không lưu file PDF/Word làm nguồn template.
Endpoint backend `GET /resumes/{id}/download` vẫn giữ ý nghĩa tải file gốc/TXT; lưu HTML không tự biến endpoint này
thành bộ xuất PDF. Có thể mở HTML đã điền nội dung trong trình duyệt và dùng chức năng in/lưu PDF của trình duyệt.

Tham khảo cơ chế trình duyệt: [HTML template](https://html.spec.whatwg.org/multipage/scripting.html#the-template-element),
[iframe sandbox](https://html.spec.whatwg.org/multipage/iframe-embed-object.html#attr-iframe-sandbox).

## Kiểm chứng nhập tài liệu ngày 24/10/2026

Backend verify đầy đủ kết thúc **06:23:58 giờ Việt Nam**: `BUILD SUCCESS`, **884 test được ghi nhận,
826 đạt, 0 failure, 0 error, 58 bỏ qua**. Riêng chức năng mới có **11 test chuyển đổi** và **5 integration test
đạt, không bỏ qua**. Đã kiểm tra PDF/DOCX tạo bằng thư viện và file DOC nhị phân thật; các ca có tài liệu lỗi,
PDF mã hóa/không có text, giới hạn file/trang/nội dung, escaping và bản nháp qua cùng bộ kiểm tra HTML an toàn.
Integration dùng PostgreSQL/pgvector trong Docker, xác minh quyền Admin, import không ghi template/audit/quota AI,
không thể lưu bản nháp chưa đánh dấu và toàn luồng đánh dấu → lưu inactive → bật mẫu → Candidate chọn và đọc cùng HTML.

Log: [verify nhập tài liệu](../target/template-import-verify.log),
[JUnit XML](../target/template-import-verified/surefire-reports).
POM kiểm chứng chỉ đổi thư mục output sang `target/template-import-verified` để tránh xung đột với file class
do IDE ghi; formatter, toàn bộ test và đóng gói đều chạy. Các bài test cần model thật hoặc điều kiện môi trường
khác vẫn bị bỏ qua theo điều kiện riêng; con số 884 không phải số test đạt.

Frontend đã qua 8 kiểm tra adapter/render, 17 kiểm tra Chrome về HTML/mapping và 18 kiểm tra contract hiện có;
ESLint phần thay đổi cùng TypeScript/Vite build đều đạt, còn cảnh báo bundle lớn hiện hữu.
Smoke Chrome bổ sung mount `TemplateEditor` thật xác minh 11 bước tương tác, gồm upload, mapping họ tên/summary,
xem dữ liệu CV minh họa, xác nhận đã kiểm tra và payload gửi khi lưu. Smoke này dùng response/tài khoản giả lập
trong harness, không được coi là E2E đăng nhập hoặc ghi dữ liệu lên backend thật.
Có kiểm tra kết quả đến muộn sau khi đổi phiên/tài khoản hoặc đóng trình chỉnh sửa. Nếu token được làm mới
trong lúc chuyển đổi, giao diện yêu cầu chọn lại file để tránh ghi đè bản nháp bằng kết quả từ phiên cũ.
Xem [tóm tắt kiểm tra frontend](../../../FE/FA26SE119-FE/target/template-document-import-ui-verification.json).

Docker được cập nhật, kiểm tra lúc **06:25 ngày 24/10/2026**: health `UP`, JAR khớp artifact đã verify,
Swagger có endpoint import multipart, request import không token trả `401`. Cả **5 mẫu active** có trước
cập nhật giữ nguyên ID, tên và HTML. Xem [bằng chứng runtime nhập tài liệu](../target/template-import-app-runtime.json).
Không tạo/sửa template hoặc CV của tài khoản thật khi kiểm tra runtime; luồng có đăng nhập và ghi dữ liệu
được kiểm trên database test riêng. Chức năng import không gọi model AI hoặc dịch vụ chuyển đổi thương mại.

## Kiểm chứng HTML-only V19 ngày 24/10/2026

Backend verify đầy đủ kết thúc **02:13 giờ Việt Nam**: `BUILD SUCCESS`, **868 test được ghi nhận,
810 đạt, 0 failure, 0 error, 58 bỏ qua**. Các suite liên quan template/HTML, selection, security và writing quality
ghi nhận tổng cộng **102 test đạt** trong lượt verify này. Migration test dùng PostgreSQL/pgvector riêng,
nâng từ V18 lên V19 với bố cục cũ đã đổi font/màu/thứ tự và một mẫu HTML tùy chỉnh.
Test xác nhận giữ ID, dữ liệu quản lý và FK CV; HTML tùy chỉnh giữ nguyên nội dung và timestamp.
Mẫu được backfill có `updated_at` cập nhật theo trigger hiện có vì nội dung trình bày đã chuyển đổi.

Frontend có **18 kiểm tra contract/render và 11 kiểm tra trình duyệt thật đạt**, ESLint các file sửa và
TypeScript/Vite build thành công. Build còn cảnh báo bundle lớn hiện hữu.

Bằng chứng: [log verify V19](../target/template-html-only-verify.log),
[log migration riêng](../target/template-html-only-migration-passed.log),
[JUnit XML](../target/template-html-only-verified/surefire-reports).
Lượt verify dùng POM tạm chỉ đổi output sang `target/template-html-only-verified` để tránh file class
do IDE đang ghi vào thư mục build chung; formatter, test và đóng gói đều được chạy đầy đủ.
Không gọi model hoặc sử dụng dữ liệu/quota của tài khoản thật trong các kiểm tra này.

Docker được cập nhật và xác nhận lúc **02:15 ngày 24/10/2026**, giờ Việt Nam: health `UP`, Flyway đã áp dụng V19,
JAR đang chạy khớp artifact verify. API public trả đủ **5 mẫu active đã có trước chuyển đổi**, giữ ID/tên,
mỗi mẫu có HTML cùng CV slots và không còn trường `layout_config`. Swagger yêu cầu `htmlContent` và không còn
`layoutConfig`. Xem [bằng chứng runtime V19](../target/template-html-only-app-runtime.json).
Không tạo/sửa thêm template hay CV của tài khoản thật trong lượt kiểm tra runtime; các luồng có đăng nhập
đã được kiểm chứng trên PostgreSQL/pgvector test riêng.

## Lịch sử kiểm chứng ngày 23/10/2026

Đây là lịch sử phiên bản V14, khi còn hỗ trợ cấu hình layout cũ; không thay thế kết quả kiểm tra V19 chỉ HTML.

| Phần kiểm tra | Kết quả |
| --- | --- |
| Backend `mvn -B verify` | BUILD SUCCESS: 771 test được ghi nhận, 715 đạt, 56 bỏ qua, 0 failure/error |
| Tạo/sửa/đọc mẫu HTML và giữ luồng cấu hình cũ | Test API với PostgreSQL/pgvector trong Docker đạt; gồm Candidate, Guest và CV dùng mẫu đã tắt |
| Ba file mẫu và JSON Postman | Đạt validator backend; HTML trong JSON trùng file nguồn |
| Frontend adapter/SSR | 18 kiểm tra đạt |
| Chrome headless: renderer và HTML không tin cậy | 10 kiểm tra đạt; không có request tải tài nguyên trong phép thử, giữ dữ liệu ứng viên và thuộc tính bố cục hợp lệ |
| Frontend lint phần thay đổi và build | Đạt; còn cảnh báo bundle lớn |
| Bố cục ba mẫu | Đã xem ảnh render với dữ liệu minh họa; cả ba vừa A4 ở desktop, mẫu Sinh viên xuống dòng đúng ở viewport 480 px |
| Backend Docker local | Health UP, JAR khớp SHA-256 bản đã verify, Flyway áp dụng V14; 5 mẫu cũ trả thêm `html_content`, Admin không token trả 401 |

Log backend local: `target/template-html-verify.log`, `target/html-template-app-runtime.json`.
Log frontend: `html-template-tests.log`, `html-template-browser.log`, `html-template-lint.log`, `html-template-build.log`.
Ảnh và số đo bố cục nằm trong `reports/template-html-references/html-layout-qa.md` ở workspace.

Chưa thao tác đăng nhập/tải file/lưu mẫu qua giao diện bằng tài khoản thật. Việc ghi dữ liệu được kiểm trên database
thử nghiệm; ba mẫu không tự được import vào database ứng dụng. Kiểm tra Chrome ở trên dùng renderer thật với fixture,
không được ghi nhận là E2E đăng nhập Admin/Candidate hoặc kiểm thử xuất PDF backend. CV dài hơn vẫn có thể cần nhiều trang.
