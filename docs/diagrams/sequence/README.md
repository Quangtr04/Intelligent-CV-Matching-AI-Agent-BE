# Sequence và Class diagrams FA26SE119

Có **16 sequence diagram và 16 class diagram**, xuất sẵn thành PNG/SVG.

- [Mở trang xem toàn bộ](index.html) bằng trình duyệt. Trang hoạt động offline; bấm hình để mở SVG phóng to.
- [Tài liệu và mã Mermaid gốc](../../sequence-diagrams.md). Markdown Preview hiển thị ảnh PNG mà không cần hỗ trợ Mermaid.
- PNG có độ phân giải 1,5 lần kích thước bố cục; SVG giữ nét khi phóng to.
- Các file .mmd được xuất từ Markdown, không chỉnh hai nơi độc lập.

| Luồng | Sequence diagram | Class diagram |
| --- | --- | --- |
| SD-01 Đăng ký xác minh email và đăng nhập | [PNG](sd-01-sequence.png) · [SVG](sd-01-sequence.svg) · [Mermaid](sd-01-sequence.mmd) | [PNG](sd-01-class.png) · [SVG](sd-01-class.svg) · [Mermaid](sd-01-class.mmd) |
| SD-02 Xác thực request refresh token và logout | [PNG](sd-02-sequence.png) · [SVG](sd-02-sequence.svg) · [Mermaid](sd-02-sequence.mmd) | [PNG](sd-02-class.png) · [SVG](sd-02-class.svg) · [Mermaid](sd-02-class.mmd) |
| SD-03 Tạo upload review confirm và phân tích CV | [PNG](sd-03-sequence.png) · [SVG](sd-03-sequence.svg) · [Mermaid](sd-03-sequence.mmd) | [PNG](sd-03-class.png) · [SVG](sd-03-class.svg) · [Mermaid](sd-03-class.mmd) |
| SD-04 Nhập JD và lập chỉ mục embedding | [PNG](sd-04-sequence.png) · [SVG](sd-04-sequence.svg) · [Mermaid](sd-04-sequence.mmd) | [PNG](sd-04-class.png) · [SVG](sd-04-class.svg) · [Mermaid](sd-04-class.mmd) |
| SD-05 Gợi ý việc làm matching | [PNG](sd-05-sequence.png) · [SVG](sd-05-sequence.svg) · [Mermaid](sd-05-sequence.mmd) | [PNG](sd-05-class.png) · [SVG](sd-05-class.svg) · [Mermaid](sd-05-class.mmd) |
| SD-06 Đánh giá CV với một JD JOB_ATS | [PNG](sd-06-sequence.png) · [SVG](sd-06-sequence.svg) · [Mermaid](sd-06-sequence.mmd) | [PNG](sd-06-class.png) · [SVG](sd-06-class.svg) · [Mermaid](sd-06-class.mmd) |
| SD-07 Tạo bài đánh giá bằng hàng đợi AI | [PNG](sd-07-sequence.png) · [SVG](sd-07-sequence.svg) · [Mermaid](sd-07-sequence.mmd) | [PNG](sd-07-class.png) · [SVG](sd-07-class.svg) · [Mermaid](sd-07-class.mmd) |
| SD-08 Nộp bài phân tích gap và lộ trình học | [PNG](sd-08-sequence.png) · [SVG](sd-08-sequence.svg) · [Mermaid](sd-08-sequence.mmd) | [PNG](sd-08-class.png) · [SVG](sd-08-class.svg) · [Mermaid](sd-08-class.mmd) |
| SD-09 Trợ lý nghề nghiệp và RAG tùy chọn | [PNG](sd-09-sequence.png) · [SVG](sd-09-sequence.svg) · [Mermaid](sd-09-sequence.mmd) | [PNG](sd-09-class.png) · [SVG](sd-09-class.svg) · [Mermaid](sd-09-class.mmd) |
| SD-10 Tạo phân công và trao đổi ca tư vấn | [PNG](sd-10-sequence.png) · [SVG](sd-10-sequence.svg) · [Mermaid](sd-10-sequence.mmd) | [PNG](sd-10-class.png) · [SVG](sd-10-class.svg) · [Mermaid](sd-10-class.mmd) |
| SD-11 Thanh toán SePay và kích hoạt gói | [PNG](sd-11-sequence.png) · [SVG](sd-11-sequence.svg) · [Mermaid](sd-11-sequence.mmd) | [PNG](sd-11-class.png) · [SVG](sd-11-class.svg) · [Mermaid](sd-11-class.mmd) |
| SD-12 Admin lưu và khôi phục cấu hình AI và prompt | [PNG](sd-12-sequence.png) · [SVG](sd-12-sequence.svg) · [Mermaid](sd-12-sequence.mmd) | [PNG](sd-12-class.png) · [SVG](sd-12-class.svg) · [Mermaid](sd-12-class.mmd) |
| SD-13 Pipeline gọi AI chung | [PNG](sd-13-sequence.png) · [SVG](sd-13-sequence.svg) · [Mermaid](sd-13-sequence.mmd) | [PNG](sd-13-class.png) · [SVG](sd-13-class.svg) · [Mermaid](sd-13-class.mmd) |
| SD-14 Tạo embedding cho CV | [PNG](sd-14-sequence.png) · [SVG](sd-14-sequence.svg) · [Mermaid](sd-14-sequence.mmd) | [PNG](sd-14-class.png) · [SVG](sd-14-class.svg) · [Mermaid](sd-14-class.mmd) |
| SD-15 Ticket hỗ trợ và phản hồi Admin | [PNG](sd-15-sequence.png) · [SVG](sd-15-sequence.svg) · [Mermaid](sd-15-sequence.mmd) | [PNG](sd-15-class.png) · [SVG](sd-15-class.svg) · [Mermaid](sd-15-class.mmd) |
| SD-16 Soạn nội dung ứng tuyển | [PNG](sd-16-sequence.png) · [SVG](sd-16-sequence.svg) · [Mermaid](sd-16-sequence.mmd) | [PNG](sd-16-class.png) · [SVG](sd-16-class.svg) · [Mermaid](sd-16-class.mmd) |

## Tạo lại hình

Nguồn chính là các khối Mermaid trong docs/sequence-diagrams.md. Công cụ dùng Mermaid Tiny 12.2.0 và Playwright/Chromium, không thay đổi dependency ứng dụng Java/React. Chuẩn bị mermaid.tiny.js từ https://cdn.jsdelivr.net/npm/@mermaid-js/tiny@12.2.0/dist/mermaid.tiny.js, cài Playwright ở môi trường công cụ riêng hoặc dùng runtime có sẵn. Chạy từ thư mục backend:

```powershell
node scripts/dev/RenderSequenceDiagrams.mjs --mermaid-js <path-to-mermaid.tiny.js> --playwright-module <path-to-playwright> --browser <path-to-chrome.exe>
node scripts/dev/RenderSequenceDiagrams.mjs --check
```

Bỏ --browser khi Playwright đã có Chromium. Nếu dùng Node trong Codex runtime, công cụ tự tìm Playwright cùng runtime. Dùng --only SD-01 để render thử một cặp. Công cụ lưu source .mmd, SVG, PNG, metadata kích thước và trang HTML; --check xác nhận đủ 32 bộ ảnh/nguồn.
