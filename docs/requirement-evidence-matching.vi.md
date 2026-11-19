# Đối chiếu yêu cầu JD với bằng chứng CV

Phiên bản triển khai: `requirement-evidence-v1`.

## Luồng chính

Khi cả chat và embedding provider được cấu hình, `POST /matching` dùng chung
`RequirementEvidenceService` cho QUICK và DETAILED. Hai tên mode vẫn được chấp nhận
để giữ tương thích API; cả hai không còn có hai cách chấm điểm trong nhánh mới.
Luồng không gọi `JOB_MATCH_RERANK` để tự sinh điểm tổng.

1. Chỉ dùng CV READY của đúng tài khoản. SELECTED_CV dùng đúng CV đã chọn;
   ALL_CVS giữ nguồn riêng của từng CV; CAREER_PROFILE chỉ dùng thông tin thực tế
   đã khai báo, không biến mục tiêu nghề nghiệp thành kinh nghiệm.
2. Tách dữ liệu CV đã xác nhận thành đoạn summary, skills, experience, projects,
   education, certifications. Không lấy tên tệp, thông tin liên hệ hoặc raw text cũ
   để thay nội dung đã xác nhận. Mỗi đoạn có ID, đường dẫn nguồn và offset.
   Đoạn dài được chia tiếp, không cắt bỏ đuôi; trên 1.000 đoạn thì từ chối với lỗi rõ ràng.
3. Dùng JobRequirementExtractor lấy câu yêu cầu, mức bắt buộc/ưu tiên, AND/OR và
   kỹ năng liên quan. Tag kỹ năng thủ công chỉ được bổ sung nếu chưa có câu tương ứng.
   Nhiệm vụ được đánh giá để giải thích, không tự biến thành điều kiện bắt buộc.
4. Yêu cầu chỉ là tên kỹ năng có thể được giải quyết bằng tên chuẩn/alias trong mục
   kỹ năng, ngay trong cùng bảng kết quả. Không suy từ tên kỹ năng ra số năm, mức
   thành thạo, việc đã thực hiện hoặc chứng chỉ.
5. Các yêu cầu còn lại: embedding từng câu có ngữ cảnh chức danh và từng đoạn CV,
   dùng cùng provider/model 1024 chiều. Cosine chọn ba đoạn gần nhất; thêm tối đa
   ba đoạn có kỹ năng tương ứng để giảm bỏ sót. Không có ngưỡng cosine giả định là
   ngưỡng đáp ứng. Retrieval có thể bỏ sót; chưa tìm thấy không chứng minh ứng viên
   không có năng lực.
6. `JOB_REQUIREMENT_EVIDENCE` đánh giá theo nhóm tối đa sáu yêu cầu. Mỗi kết luận
   phải có đúng requirement ID; MET/PARTIAL/NOT_MET cần trích dẫn nguyên văn từ đúng
   đoạn CV được cung cấp cho yêu cầu đó. Server từ chối thiếu kết quả, ID lạ, trùng
   ID hoặc trích dẫn không có trong nguồn. Ngày đánh giá được cung cấp cho điều kiện
   thời gian/chứng chỉ. Kiểm tra trích dẫn xác nhận nguồn, không chứng minh mọi suy luận
   ngữ nghĩa của model đều chính xác.

## Điểm và gap

Server tính một điểm duy nhất theo yêu cầu bắt buộc:

`coverage = tổng credit của yêu cầu bắt buộc / số yêu cầu bắt buộc`.

- MET: 1; toàn bộ điều kiện trong câu có căn cứ.
- PARTIAL: 0,5; câu có nhiều điều kiện, một phần có căn cứ. Đây là chính sách chấm
  phiên bản đầu, không phải xác suất hay confidence đã được hiệu chỉnh.
- NOT_MET: 0; có thông tin thể hiện không đáp ứng điều kiện.
- MISSING_EVIDENCE: 0; chưa có thông tin phù hợp trong phần CV tìm được.
- UNVERIFIED: 0; không thể kết luận. Kết quả phải thể hiện còn yêu cầu chưa rõ.

Một câu AND/OR là một đơn vị, không cộng thêm điểm lần nữa từ tag kỹ năng.
Điểm ưu tiên được tính riêng, chỉ dùng để phân thứ tự khi điểm bắt buộc bằng nhau.
Chưa có yêu cầu bắt buộc, nguồn bị cắt/thiếu hoặc phạm vi điều kiện chưa rõ:
`requirement_coverage_available=false`, giao diện không hiện một % hoàn chỉnh.
Điểm này là độ bao phủ bằng chứng tự khai, không phải xác suất được tuyển.

## Tích hợp giao diện và roadmap

- Danh sách việc làm mặc định gọi DETAILED và xếp hạng bằng độ bao phủ yêu cầu.
  Đường dẫn chi tiết giữ CV hoặc CAREER_PROFILE đã dùng ở danh sách.
- `POST /matching` nhận thêm `jobId` tùy chọn. Có jobId thì chỉ tải/đánh giá job đó;
  không chạy lại tìm top job. Job vẫn phải active, chưa hết hạn. Bộ lọc lương chỉ
  loại job trong gợi ý, không chặn phân tích job mà người dùng đã chủ động chọn.
- `POST /jobs/{jobId}/ats` giữ cấu trúc response cũ và bổ sung `evidence_report`.
  Trong nhánh mới, `ats_score` là coverage × 100 để tương thích client; xem
  `evidence_report` để xác định đúng loại điểm. Không chạy thêm JOB_ATS để tạo điểm
  thứ hai. Giao diện không gọi matching bổ sung khi đã có báo cáo bằng chứng.
- Giao diện hiển thị từng yêu cầu, trạng thái, lý do và đoạn CV trích dẫn. Không
  trình bày cosine thành phần trăm hoặc bịa các điểm thành phần từ điểm tổng.
- Roadmap sử dụng cùng báo cáo và kết quả assessment đúng ngữ cảnh. Assessment
  dưới 70 vẫn dẫn đến luyện tập dù CV có khai báo. OR chưa được giải quyết không
  tạo tất cả lựa chọn thành môn bắt buộc; điều kiện chưa rõ được giữ để xem xét.
  Điều kiện bằng cấp hoặc số năm không được tự chuyển thành một khóa học.
- Khi provider chưa được cấu hình, luồng đối chiếu kỹ năng cũ vẫn là fallback có
  method SKILL_OVERLAP, không được coi là đã phân tích bằng embedding.

## Lưu trữ và vận hành

Migration V41 thêm `matching_evidence_vectors` và `matching_evidence_reports`.
Mỗi bản ghi được giới hạn theo user_id, xóa cùng tài khoản. Vector được cache theo
hash nội dung, version, provider/model; report/verdict cache 24 giờ theo CV, JD,
chức danh, model, prompt và ngày đánh giá. Trong một request, vector CV được dùng
lại cho các job; cùng một yêu cầu và ngữ cảnh được dùng lại giữa các job.
Các cache không thay thế kiểm tra quyền truy cập và trạng thái nguồn.

Nếu embedding hoặc lượt kiểm tra bằng chứng trả 502/503/504, request vẫn giữ các
kết quả đã xác minh hoặc đối chiếu được bằng khai báo rõ ràng. Các yêu cầu còn lại
được đánh dấu UNVERIFIED/DEFERRED, không biến thành kỹ năng thiếu hoặc khóa học bắt
buộc. Trong phần còn lại của request, không tiếp tục gọi provider đang lỗi; cache
đã xác minh vẫn được đọc. Không có yêu cầu bắt buộc nào đã đối chiếu được thì giao
diện không hiện 0% như một kết luận phù hợp. Kết quả một phần không được cache 1 giờ
hoặc 24 giờ; cập nhật sau có thể thử xác minh lại. Lỗi quyền truy cập và quota vẫn
được trả về, không bị che thành kết quả thành công.

Không cần đổi `.env` nếu cấu hình Gemini hiện tại đã hoạt động. Khởi động backend
với Flyway để áp dụng V41; build frontend tương ứng. Không trộn vector của các model.

Lần phân tích chưa có cache cần nhiều lượt embedding/đánh giá hơn một yêu cầu Gemini
đơn lẻ. Mỗi request mặc định chỉ mở tối đa 64 lượt embedding và 4 lượt kiểm tra bằng
chứng, trong khoảng ngân sách 45 giây; lượt provider đã bắt đầu vẫn dùng timeout
provider hiện có. Cấu hình qua `app.matching.evidence.max-embedding-calls`,
`max-verification-calls`, `request-budget`. Cache không tiêu thụ ngân sách gọi mới.
Job có nhiều yêu cầu đã xác nhận bằng khai báo được xử lý trước; đây chỉ là thứ tự
xác minh, không tạo thêm điểm hoặc thay công thức coverage. Khi hết ngân sách, các
job còn lại vẫn dùng cache và đối chiếu rõ ràng, phần còn lại đánh dấu DEFERRED;
không gán thành lỗi Gemini hoặc kỹ năng thiếu. Các verdict thành công được dùng lại
khi cập nhật tiếp. Client chờ tối đa 10 phút và không tự retry matching. Chưa có benchmark tải lớn hoặc
ngưỡng cosine được hiệu chỉnh; cần đo trên dữ liệu thật trước khi mở rộng catalog lớn.
Các test tự động dùng AI giả lập, không gọi Gemini thật hoặc tiêu thụ quota sản xuất.
