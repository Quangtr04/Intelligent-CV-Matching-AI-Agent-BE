# Tạo bài đánh giá theo Skill và Bloom của JD

- Job có sẵn: đọc requirement_extraction hợp lệ hoặc đã được duyệt qua JobRequirementExtractor.forJob;
  snapshot cũ/thiếu được bóc tách lại cục bộ, không gọi AI phân loại JD.
- JD tự dán: cùng bộ bóc tách cục bộ nhận diện kỹ năng, hành động và Bloom. Không ghi JD riêng vào danh sách job.
- AI nhận jd_skills, jd_text và jd_bloom_requirements (kỹ năng, bằng chứng, mức Bloom, trạng thái, AND/OR,
  mức bắt buộc). Chỉ các kỹ năng trong phạm vi bài đánh giá được gửi trong mapping.
- CV là tùy chọn cho cả hai nguồn. Nếu có, phải thuộc người dùng và đã READY; dùng để cá nhân hóa tình huống,
  không xác nhận năng lực Bloom hoặc hạ mức JD yêu cầu. Không đủ bằng chứng Bloom thì dùng câu nền tảng
  Understand/Apply và không gọi đó là mức nhà tuyển dụng đã yêu cầu.
- Context snapshot/hash bao gồm mapping, phiên bản extractor và catalog. Bài cũ vẫn xem được; khi cần tạo
  lộ trình từ snapshot cũ khác phiên bản đầu vào, hệ thống có thể yêu cầu làm bài mới.
- Giao diện /candidate/skill-assessment có lựa chọn Dùng JD tự nhập, tên vị trí và nội dung 30–50.000 ký tự.
  Bài JD tự nhập được lưu trên server theo tài khoản; phiên đang hiển thị trong giao diện dùng cache bộ nhớ.
  Lộ trình của JD tự nhập chỉ tạo sau khi hoàn thành bài, dùng assessmentId.

Đây là thay đổi dữ liệu đầu vào và chỉ dẫn tạo câu hỏi. Kiểm tra đầu ra hiện có tiếp tục kiểm tra kỹ năng,
bằng chứng JD/CV, cấu trúc, đáp án và câu trùng. Chưa có bộ chấm độc lập xác nhận AI tuân thủ Bloom về ngữ nghĩa.
Điểm bài vẫn là tỷ lệ đúng theo kỹ năng, chưa phải chứng nhận năng lực từng bậc Bloom.
Trắc nghiệm Create chỉ khảo sát lập luận thiết kế; cần bài thực hành để xác minh khả năng tạo sản phẩm.
Không thêm bảng hay migration, không gọi AI thật trong kiểm thử.
