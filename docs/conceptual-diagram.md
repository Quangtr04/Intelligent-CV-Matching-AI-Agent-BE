# Conceptual Diagram EduNexus

Sơ đồ được vẽ trên **một trang thống nhất** theo bố cục mẫu đã cung cấp:
Guest Session ở trên trái, User ở trên giữa, Plan ở trên phải; các khái niệm nối trực tiếp.
Nền tối, ô chữ nhật viền màu và ký hiệu Crow’s Foot. Mỗi khái niệm chỉ xuất hiện một lần.

- [Ảnh PNG, 6000 × 3800](diagrams/edunexus-conceptual-diagram.png)
- [Bản vector SVG](diagrams/edunexus-conceptual-diagram.svg)
- [File draw.io chỉnh sửa được, một trang](diagrams/edunexus-conceptual-diagram.drawio)

Sơ đồ bao gồm 31 khái niệm và 48 quan hệ, được đối chiếu với code/schema ngày 14/10/2026.
Tên khái niệm và tên quan hệ bằng tiếng Anh, ghi chú nghiệp vụ bằng tiếng Việt.
Các bảng nối, embedding, token, webhook chống lặp và chi tiết audit nằm trong [schema](schema.md).

Crow’s Foot biểu thị bội số: `1` là đúng một, `0..1` là không hoặc một,
`0..n` là không hoặc nhiều. Quan hệ nét đứt là ngữ cảnh hoặc điều phối nghiệp vụ,
không ngụ ý khóa ngoại trực tiếp.

Các quy tắc đọc cùng sơ đồ:

- User có ba vai trò Candidate, Career Consultant và Admin. Hai quan hệ User–Career Consultation
  phân biệt một Candidate bắt buộc và tối đa một Consultant được phân công.
- AI Conversation và Matching Session thuộc đúng một User hoặc Guest Session (XOR).
- AI Usage có tối đa một User hoặc Guest; tác vụ hệ thống có thể không thuộc chủ thể nào.
- CV cá nhân có một User sở hữu; CV demo có thể không có chủ. Guest matching dùng CV demo;
  guest chat không dùng RAG. Trích nguồn RAG thuộc AI Message.
- Mỗi Consultation Message và Support Message có đúng một User gửi.
  Support Ticket có tối đa một Admin được phân công.
- User sở hữu Skill Gap Analysis và Learning Roadmap. Assessment, CV, Job và Career Profile
  là các đầu vào/ngữ cảnh tùy chọn; submit Assessment không tự tạo Roadmap.
- Gap–Roadmap có bội số theo schema: mỗi Roadmap có tối đa một Gap Analysis; một Gap Analysis
  có thể liên quan nhiều Roadmap. Luồng tạo hiện tại tạo một cặp trong mỗi yêu cầu.
- User có lịch sử nhiều subscription, với tối đa một subscription ACTIVE.
  Plan bao gồm giá và quota theo tính năng. Payment Order có thể chưa gắn subscription;
  một order có tối đa một invoice.
- External Apply Event chỉ ghi nhận mở link ứng tuyển, không xác nhận nộp hồ sơ hay kết quả tuyển dụng.
- Admin tạo Announcement và cấu hình Crawl Settings. Settings điều phối Crawl Run;
  run thu thập/cập nhật Job Post. Job Source URL là bản ghi URL nguồn của job chuẩn hóa.
- Learning Resource bao gồm các liên kết tham khảo roadmap.sh. Sơ đồ không ngụ ý crawl trực tiếp
  nội dung website này.

Mở file `.drawio` bằng diagrams.net để chỉnh sửa. Tái tạo từ
[model JSON](diagrams/edunexus-conceptual-model.json) bằng lệnh chạy tại thư mục dự án:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/dev/GenerateConceptualDiagram.ps1
```

Generator xuất PNG, SVG và file draw.io một trang.
