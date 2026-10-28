# Đánh giá cách viết CV bằng AI

Phân tích chất lượng CV đánh giá thêm **cách viết bullet point (gạch đầu dòng), dấu câu, ngôn từ, độ cô đọng và cách mô tả đóng góp**. Model Ollama đang cấu hình được dùng trong luồng `CV_ANALYZE`; không có bước gọi dịch vụ AI thương mại hoặc huấn luyện model trong luồng này.

Đây là đánh giá trên văn bản và cấu trúc CV đã được người dùng xác nhận. Hệ thống không suy ra font, căn lề, vị trí trên trang hoặc chất lượng bố cục PDF từ văn bản trích xuất. Điểm này cũng không chứng minh CV được một nhà cung cấp ATS cụ thể chấp nhận.

## Tiêu chí và cách tính điểm

Mỗi tiêu chí có trạng thái, nhận xét và trích đoạn bằng chứng từ CV. Chỉ tiêu chí đủ bằng chứng và có thể áp dụng (`ASSESSED`) mới có điểm từ 0 đến 100; `NOT_APPLICABLE` hoặc `INSUFFICIENT_EVIDENCE` có `score: null`. Những tiêu chí đã đánh giá có trọng số bằng nhau trong điểm cách viết:

| Mã | Tiêu chí | Cần xem xét |
| --- | --- | --- |
| `BULLET_STRUCTURE` | Tổ chức gạch đầu dòng | Tách các ý dễ đọc, mỗi ý có nội dung rõ và cấu trúc song song; cách dùng bullet nhất quán trong cùng mục. Không bắt buộc biến tiêu đề, thông tin liên hệ hoặc danh sách kỹ năng thành bullet. |
| `PUNCTUATION_CONSISTENCY` | Dấu câu nhất quán | Cách kết thúc câu, viết hoa và dùng dấu phân cách phù hợp. Các bullet là cụm từ có thể cùng không có dấu chấm cuối; không tự trừ điểm chỉ vì thiếu dấu chấm ở mọi dòng. |
| `PROFESSIONAL_WORDING` | Ngôn từ chuyên nghiệp | Diễn đạt rõ, phù hợp ngôn ngữ CV; tránh từ lóng, viết tắt khó hiểu, cường điệu và cách xưng hô thiếu phù hợp. Giữ đúng tên công nghệ và tên riêng. |
| `CONCISENESS` | Độ cô đọng | Hạn chế lặp ý, câu quá dài, từ đệm và mô tả chung chung; vẫn giữ đủ thông tin cần thiết. |
| `ACHIEVEMENT_CLARITY` | Đóng góp và kết quả rõ ràng | Nêu hành động, phần việc, sản phẩm hoặc kết quả có căn cứ. Không yêu cầu mọi bullet phải có phần trăm hoặc số liệu khi người dùng chưa cung cấp. |

Rubric `cv-writing-v1` dùng công thức:

```text
writing_quality.score = trung bình điểm của các tiêu chí ASSESSED
overall_score = round(content_score × 0.80 + writing_quality.score × 0.20, 2)
```

`assessed_criteria` cho biết số tiêu chí có điểm. Khi không tiêu chí nào đủ điều kiện đánh giá, điểm cách viết là `null`, `content_weight: 1`, `writing_weight: 0` và `overall_score = content_score`. Không tạo điểm giả để đủ năm điểm. Ví dụ PDF mất dấu bullet trong quá trình trích xuất thì phải ghi giới hạn hoặc `INSUFFICIENT_EVIDENCE`, không kết luận người dùng trình bày bullet sai từ thông tin không quan sát được.

`content_score` là điểm nội dung do model đánh giá, tách khỏi điểm cách viết. `ats_score` được giữ riêng; không phải điểm dấu câu hoặc điểm khớp CV–job. Không cộng lại `ats_score` vào công thức trên.

AI cần tôn trọng CV tiếng Việt/tiếng Anh và đặc thù thuật ngữ kỹ thuật. Nhận xét phải chỉ ra vấn đề trong văn bản thật; không trích ví dụ do model tự nghĩ ra như thể đã có trong CV. Gợi ý viết lại giữ nguyên dữ kiện, không thêm kỹ năng, thành tích, số người dùng, tỷ lệ cải thiện hoặc kinh nghiệm chưa được xác nhận.

Ollama nhận JSON Schema bắt buộc đủ các trường đánh giá, bao gồm `evidence` ở từng tiêu chí. Backend tiếp tục kiểm tra mã tiêu chí không trùng, quan hệ trạng thái–điểm và trích dẫn khớp nội dung CV; JSON hợp lệ tự nó chưa chứng minh nhận xét đúng. Điểm và góp ý là kết quả tham khảo từ model, cần người dùng đối chiếu trước khi sửa CV.

## Hợp đồng API

`baseUrl` trong tài liệu này là `http://localhost:8080/api/v1`. Các request dùng **Bearer Token của Candidate sở hữu CV**. CV phải ở trạng thái `READY` trước khi phân tích; không cần tạo embedding.

| Thứ tự | Request | Body và kết quả cần kiểm tra |
| --- | --- | --- |
| 1 | `POST {{baseUrl}}/resumes` | JSON CV thủ công bên dưới; trả `id`, trạng thái `DRAFT`. |
| 2 | `POST {{baseUrl}}/resumes/{{writingResumeId}}/confirm` | Body none; kiểm tra `status: "READY"`. |
| 3 | `POST {{baseUrl}}/resumes/{{writingResumeId}}/analyses` | Body none; khi AI bật, kiểm tra `analysis_type: "AI_QUALITY"` và `writing_quality`. |
| 4 | `GET {{baseUrl}}/resumes/{{writingResumeId}}/analyses/{{writingAnalysisId}}` | Đọc lại điểm, tiêu chí, bằng chứng và `suggestions` đã lưu. |
| 5 | `GET {{baseUrl}}/resumes/{{writingResumeId}}/analyses` | Danh sách lịch sử có báo cáo vừa tạo. Danh sách không tự gắn `suggestions` như API chi tiết. |

`writing_quality` là object thuận tiện để đọc qua API; dữ liệu được lưu ở `writing_quality_json` của báo cáo. Ví dụ dưới đây **chỉ minh họa schema và công thức**, không phải điểm model đã đo:

```json
{
  "analysis_type": "AI_QUALITY",
  "overall_score": 80.8,
  "writing_quality": {
    "rubric_version": "cv-writing-v1",
    "score": 84,
    "content_score": 80,
    "content_weight": 0.8,
    "writing_weight": 0.2,
    "assessed_criteria": 5,
    "criteria": [
      {
        "code": "BULLET_STRUCTURE",
        "status": "ASSESSED",
        "score": 90,
        "feedback": "Các phần việc được tách thành từng ý rõ ràng.",
        "evidence": ["- Xây dựng REST API CRUD sách và độc giả bằng Java, Spring Boot."]
      },
      {
        "code": "PUNCTUATION_CONSISTENCY",
        "status": "ASSESSED",
        "score": 85,
        "feedback": "Các bullet dự án cùng kết thúc bằng dấu chấm.",
        "evidence": ["- Lưu dữ liệu trong PostgreSQL; quản lý mã nguồn bằng Git."]
      },
      {
        "code": "PROFESSIONAL_WORDING",
        "status": "ASSESSED",
        "score": 80,
        "feedback": "Dùng động từ cụ thể và đúng tên công nghệ.",
        "evidence": ["Xây dựng REST API CRUD sách và độc giả"]
      },
      {
        "code": "CONCISENESS",
        "status": "ASSESSED",
        "score": 85,
        "feedback": "Các câu ngắn và tập trung vào phần việc.",
        "evidence": ["Lưu dữ liệu trong PostgreSQL; quản lý mã nguồn bằng Git."]
      },
      {
        "code": "ACHIEVEMENT_CLARITY",
        "status": "ASSESSED",
        "score": 80,
        "feedback": "Nêu sản phẩm cụ thể; cần xác nhận thêm kết quả kiểm thử nếu có dữ kiện.",
        "evidence": ["Xây dựng REST API CRUD sách và độc giả"]
      }
    ],
    "limitations": ["Đánh giá văn bản và cấu trúc, không kiểm tra bố cục PDF."]
  }
}
```

Các field cũ `summary`, `strengths_json`, `issues_json` và `suggestions` vẫn được dùng. Gợi ý sửa có thể gồm `category`, `severity`, `original_content`, `suggested_content`, `explanation`; người dùng review trước khi áp dụng. Phân tích không tự sửa CV.

Khi `AI_ENABLED=false`, báo cáo `RULE_BASED` vẫn chỉ kiểm tra sự hiện diện của các mục CV như trước, với `ats_score: null`; **điểm cách viết không khả dụng**, không gán 0 hoặc tự sinh điểm AI. Báo cáo cũ chưa có rubric cũng hiển thị chưa có đánh giá cách viết; cần tạo lần phân tích mới để có dữ liệu mới. Không dùng điểm baseline để so trực tiếp với điểm AI có công thức 80/20.

## Hai bộ dữ liệu Postman cùng dữ kiện

Hai mẫu là thông tin giả lập, cùng học vấn, kỹ năng và dự án; chỉ khác cách diễn đạt và trình bày. Dùng `POST /resumes` thủ công, **không dùng `/resumes/build`**, để model không viết lại mẫu trước khi đánh giá. `structured_data: {}` giữ bài kiểm tra ở văn bản `raw_content`; không dùng dấu phẩy, dấu ngoặc của JSON làm bằng chứng lỗi dấu câu trong CV.

### Mẫu A — diễn đạt rõ, bullet và dấu câu thống nhất

**POST `{{baseUrl}}/resumes`**, Body → raw → JSON:

```json
{
  "title": "CV kiểm thử cách viết",
  "language": "vi",
  "raw_content": "NGUYỄN MINH AN\nEmail: an.cv@example.test\n\nĐỊNH HƯỚNG\nSinh viên Công nghệ thông tin, định hướng ứng tuyển Fresher Java Backend.\n\nHỌC VẤN\nCử nhân Công nghệ thông tin — Trường Đại học Demo, 2022–2026.\n\nDỰ ÁN HỌC TẬP\nQuản lý thư viện — Backend, 01/2026–05/2026\n- Xây dựng REST API CRUD sách và độc giả bằng Java, Spring Boot.\n- Lưu dữ liệu trong PostgreSQL; quản lý mã nguồn bằng Git.\n- Thực hiện dự án trong nhóm học phần; ứng dụng chạy local.\n\nKỸ NĂNG\nJava, Spring Boot, PostgreSQL, Git.\n\nKINH NGHIỆM VÀ CHỨNG CHỈ\nChưa có kinh nghiệm làm việc toàn thời gian hoặc chứng chỉ.",
  "structured_data": {}
}
```

### Mẫu B — dài dòng, ngôn từ và dấu câu thiếu thống nhất

Gửi một request tạo CV riêng với JSON sau:

```json
{
  "title": "CV kiểm thử cách viết",
  "language": "vi",
  "raw_content": "NGUYỄN MINH AN\nEmail: an.cv@example.test\n\nĐỊNH HƯỚNG\nmình là sv công nghệ thông tin á!!! mình muốn apply fresher java backend, nói chung là muốn apply fresher java backend\n\nHỌC VẤN\ncử nhân công nghệ thông tin — trường đại học demo 2022–2026\n\nDỰ ÁN HỌC TẬP\nQuản lý thư viện — Backend, 01/2026–05/2026\n- mình làm rest api crud sách và độc giả bằng Java với Spring Boot!!!\n* dữ liệu thì lưu PostgreSQL còn source thì dùng Git\nrồi làm với nhóm học phần, app chạy local.. nói chung là app chạy local\n\nKỸ NĂNG\nJava, Spring Boot, PostgreSQL, Git\n\nKINH NGHIỆM VÀ CHỨNG CHỈ\nchưa có kinh nghiệm làm việc toàn thời gian hoặc chứng chỉ!!!",
  "structured_data": {}
}
```

Với từng mẫu, lưu ID từ response tạo CV bằng script **Post-response**:

```javascript
pm.test("Tạo CV thử nghiệm", () => pm.response.to.have.status(200));
if (pm.response.code === 200) {
  pm.environment.set("writingResumeId", pm.response.json().id);
}
```

Chạy **confirm → analyses → đọc lại** theo bảng thứ tự trên rồi mới chuyển sang mẫu còn lại. Ghi riêng ID và kết quả của A/B trước khi ghi đè `writingResumeId`. Không cần chọn primary hoặc tạo embedding cho bài kiểm tra này.

Sau request phân tích, dùng script kiểm tra hợp đồng:

```javascript
pm.test("Có đủ điểm cách viết và công thức 80/20", () => {
  pm.response.to.have.status(200);
  const r = pm.response.json();
  pm.expect(r.analysis_type).to.eql("AI_QUALITY");
  const q = r.writing_quality;
  pm.expect(q).to.be.an("object");
  pm.expect(q.rubric_version).to.eql("cv-writing-v1");
  pm.expect(q.content_score).to.be.a("number").and.within(0, 100);
  const codes = [
    "BULLET_STRUCTURE", "PUNCTUATION_CONSISTENCY", "PROFESSIONAL_WORDING",
    "CONCISENESS", "ACHIEVEMENT_CLARITY"
  ];
  pm.expect(q.criteria).to.be.an("array").with.lengthOf(5);
  pm.expect(q.criteria.map(c => c.code).sort()).to.eql([...codes].sort());
  q.criteria.forEach(c => {
    pm.expect(c.status, c.code).to.be.oneOf([
      "ASSESSED", "NOT_APPLICABLE", "INSUFFICIENT_EVIDENCE"
    ]);
    if (c.status === "ASSESSED") {
      pm.expect(c.score, c.code).to.be.a("number").and.within(0, 100);
    } else {
      pm.expect(c.score, c.code).to.eql(null);
    }
    pm.expect(c.feedback, c.code).to.be.a("string");
    pm.expect(c.evidence, c.code).to.be.an("array");
    c.evidence.forEach(text => pm.expect(text).to.be.a("string"));
  });
  pm.expect(q.limitations).to.be.an("array");
  const assessed = q.criteria.filter(c => c.status === "ASSESSED");
  pm.expect(q.assessed_criteria).to.eql(assessed.length);
  if (assessed.length) {
    pm.expect(q.content_weight).to.eql(0.8);
    pm.expect(q.writing_weight).to.eql(0.2);
    const average = assessed.reduce((sum, c) => sum + c.score, 0) / assessed.length;
    pm.expect(q.score).to.be.a("number").and.within(0, 100);
    pm.expect(q.score).to.be.closeTo(average, 0.011);
  } else {
    pm.expect(q.score).to.eql(null);
    pm.expect(q.content_weight).to.eql(1);
    pm.expect(q.writing_weight).to.eql(0);
  }
  const overall = Math.round((
    q.content_score * q.content_weight + (q.score ?? 0) * q.writing_weight
  ) * 100) / 100;
  pm.expect(r.overall_score).to.be.closeTo(overall, 0.011);
  pm.environment.set("writingAnalysisId", r.id);
});
```

Đọc lại báo cáo bằng GET và đối chiếu toàn bộ `writing_quality` với response POST, không yêu cầu GET chạy model lần nữa. Sửa nội dung CV thì phải review/confirm lại và tạo báo cáo mới; báo cáo cũ là lịch sử, không tự cập nhật theo nội dung mới.

## Cách đánh giá kết quả thử

Kỳ vọng định tính: mẫu A được nhận xét dễ đọc và nhất quán hơn; mẫu B có nhận xét cụ thể về từ đệm, lặp ý, trộn ký hiệu bullet, viết hoa và chuỗi dấu `!!!`/`..`. Không đặt trước một điểm cố định hoặc khẳng định mọi lần chạy đều xếp A cao hơn B. Nếu kết quả không hợp lý, lưu lại output để phân tích lỗi rubric/model.

Review thủ công các điểm sau:

- Bằng chứng có xuất hiện trong CV đúng mẫu, không phải trong dữ liệu CV khác hoặc ví dụ của prompt.
- Tiêu chí chưa quan sát được có trạng thái và giới hạn phù hợp; không dùng điểm 0 hoặc điểm mặc định thay cho thiếu bằng chứng.
- Model không phê bình bullet thiếu dấu chấm khi người dùng dùng nhất quán các cụm từ không dấu chấm.
- Model không yêu cầu bullet cho họ tên, email, tiêu đề hoặc mọi tên kỹ năng.
- Gợi ý vẫn giữ dự án học tập chạy local; không biến thành kinh nghiệm công ty, hệ thống production hoặc tự thêm số liệu thành tích.
- Điểm cách viết phản ánh diễn đạt; không thay thế đánh giá kỹ năng thật, độ phù hợp công việc hoặc kiểm thử bố cục PDF.

Ghi thời điểm, model hiệu lực, ID CV/báo cáo, điểm từng tiêu chí và lỗi nhận xét phát hiện được. Hai fixture này là ca so sánh thủ công, chưa phải dataset độc lập hay số đo độ chính xác model. Không đưa token hoặc CV thật vào log công khai.

## Kiểm chứng

Các ví dụ và assertion ở trên mô tả dữ liệu và kết quả cần kiểm tra. Chất lượng nhận xét thực tế cần được xác nhận qua output Ollama và review dữ kiện; kiểm tra JSON/công thức thành công chưa chứng minh model chấm đúng.

Kết quả chạy ngày **22/10/2026**, giờ Việt Nam:

| Kiểm tra | Kết quả | Bằng chứng |
| --- | --- | --- |
| Backend `mvn -B verify`, bật phép thử Ollama local | **BUILD SUCCESS** lúc 22:04; 659 test được ghi nhận, **606 đạt**, 0 failure/error, **53 bỏ qua**; thời gian Maven 2 phút 27 giây | [Log verify](../target/cv-writing-verify.log), [JUnit XML](../target/surefire-reports) |
| Rubric và API candidate mới | **17 kiểm tra utility và 7 integration đạt**, gồm tính điểm, thiếu bằng chứng, dữ liệu lỗi, trích dẫn, lưu/đọc lại, quyền sở hữu và chế độ tắt AI | [Log kiểm tra phần mới](../target/cv-writing-targeted-tests.log) |
| API consultant trên DB tạm riêng | **3 kiểm tra đạt, không bỏ qua**: tạo báo cáo, từ chối phản hồi lỗi và đọc báo cáo theo quyền truy cập | [Log consultant](../target/cv-writing-consultant-integration.log) |
| Ollama đang cài trên máy, CV giả lập | Model trả JSON đủ rubric và trích dẫn vượt qua kiểm tra khớp nguồn; đã dùng JSON Schema native như luồng ứng dụng | [Output gốc](../target/cv-writing-ollama-synthetic-output.json), [kết quả chuẩn hóa](../target/cv-writing-ollama-synthetic-normalized.json) |
| Frontend | TypeScript/Vite build thành công; **17 kiểm tra adapter/render mới** và **11 kiểm tra hồi quy CV** đạt; lint các file liên quan đạt. Build còn cảnh báo bundle lớn | [Build](../../../FE/FA26SE119-FE/resume-writing-build.log), [kiểm tra mới](../../../FE/FA26SE119-FE/resume-writing-contract-tests.log), [hồi quy CV](../../../FE/FA26SE119-FE/resume-writing-candidate-regression.log), [lint](../../../FE/FA26SE119-FE/resume-writing-lint.log) |
| Docker backend local | Đã cập nhật container; health `UP`, hash JAR chạy trong container khớp JAR đã verify | [Log cập nhật](../target/cv-writing-docker-update.log), [metadata runtime](../target/cv-writing-app-runtime.json) |
| DB ứng dụng | Flyway **V13**, 0 migration lỗi; giữ nguyên 2 CV và 2 báo cáo cũ, các báo cáo cũ có `writing_quality_json = null` | [Metadata DB](../target/cv-writing-app-runtime.json) |

Đã [sao lưu PostgreSQL trước migration](../target/pre-cv-writing-20261022-214342.dump) và kiểm tra đọc được danh mục archive; [metadata backup](../target/cv-writing-app-backup.json) lưu hash và trạng thái DB V12. Chưa thử phục hồi archive lên DB riêng.

53 test bỏ qua ở lượt verify đầy đủ bao gồm các suite yêu cầu cấu hình DB riêng; ba kiểm tra consultant ở trên chạy riêng trên container pgvector tạm, không dùng DB ứng dụng. Không cộng hai lượt chạy thành số test duy nhất hoặc ghi toàn bộ 659 test đều đạt.

Phép thử model dùng một CV tổng hợp, chỉ xác nhận phản hồi có cấu trúc hợp lệ và các trích dẫn khớp nguồn. Chưa hiệu chuẩn thang điểm hoặc đo độ đúng của từng nhận xét trên bộ CV gán nhãn; nhận xét ngôn từ/dấu câu vẫn cần review. Chưa kiểm thử thao tác bằng trình duyệt hoặc gọi API phân tích bằng tài khoản thật trên container; các API có xác thực được kiểm tra bằng MockMvc với DB thật và AI mock. Sau cập nhật, các endpoint công khai trả `200`, request phân tích không có token trả `401`.

Có thể chạy lại phép thử model đã cài tại `127.0.0.1:11434` từ thư mục backend; script không tải weights hoặc gọi dịch vụ AI thương mại:

```powershell
$env:RUN_OLLAMA_WRITING_SMOKE = 'true'
mvn -B '-Dtest=CvWritingOllamaSmokeTests' test
$env:RUN_OLLAMA_WRITING_SMOKE = $null
```
