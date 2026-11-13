# Kiểm tra mất kỹ năng trong Learning Roadmap — 13/11/2026

## Bằng chứng trước khi sửa

Roadmap `1e424186-ca1d-4f9c-8bc6-613f5c3987ac`, job `58e757d3-8b23-443a-986b-4a4dabfa291f`:

| Điểm kiểm tra | Kết quả |
|---|---:|
| market_job_skills | 22 |
| is_required=true | 6 |
| Kỹ năng Career Profile | 1 |
| Assessment dùng trong gap | Không có |
| missing_skills trong snapshot trước AI | 6 |
| roadmap_nodes trong DB | 6 |
| Output token lần tạo thành công | 597 |

Sáu kỹ năng cũ: FastAPI, PostgreSQL, MongoDB, Data handling, Natural language processing, Computer vision.
Một lần gọi khác trả HTTP 503 là lỗi nhà cung cấp, không phải nguyên nhân của roadmap thành công này.

Nguyên nhân trực tiếp nằm trước AI:

1. `JobRequirementExtractor.Extraction.legacySkills()` chỉ xuất required=true nếu MANDATORY và AND.
   Boolean false đồng thời đại diện preferred, responsibility, OR, ambiguous và flexible, làm mất ý nghĩa.
2. `MissingSkillAnalysis.find()` bỏ tất cả required=false; không còn danh sách tổng thể để người dùng xem lại.
   “Hiểu rõ API design (RESTful/gRPC)” và “Thành thạo Git/GitFlow” bị AMBIGUOUS rồi bị loại.
3. Một đoạn dài gồm nhiều mục phân cách bằng ` + ` chứa chữ “Ưu tiên” bị phân loại toàn đoạn PREFERRED,
   làm ẩn Docker/Kubernetes. Ngược lại “một trong ... PostgreSQL, MongoDB” trước đó bị coi là AND.
4. CV/profile chỉ cần khai tên kỹ năng là bị loại khỏi whitelist; không có trạng thái riêng phân biệt
   bằng chứng khai báo với kết quả đánh giá. Không có bằng chứng về việc AI tự cắt 22 còn 6 trong ca này.

## Truy vết toàn luồng

- Crawl: `JobPostingParser` → `JobRequirementExtractor` → JSONB market_job_posts.requirement_extraction;
  `JobRequirementReprocessor`/job ingestion xuất bảng liên kết market_job_skills tương thích cũ.
- Chuẩn hóa: `SkillNames.normalize()` → `SkillCatalog.comparisonKey()`, chỉ canonical/alias tương đương.
  Python/FastAPI/Django/API design/Docker/Kubernetes vẫn là các identity riêng, không mở rộng related skills.
- `LearningRepository`: lấy toàn bộ kỹ năng job, profile, CV và assessment cùng context; không phân trang
  danh sách kỹ năng. LIMIT 1 dùng chọn assessment gần nhất, không dùng cắt số skill.
- `LearningService.prepareRoadmap()`: xác thực chủ sở hữu/context, phục hồi snapshot bài đánh giá,
  xác định gap trên server trước AI. Assessment từ job/CV khác không được tái sử dụng tùy tiện.
- `MissingSkillAnalysis`: ngưỡng 70 chỉ áp dụng trên kỹ năng thực sự có score hợp lệ. Không lấy điểm tổng
  gán cho kỹ năng chưa được hỏi. Dedup chỉ dùng identity tương đương, không gộp quan hệ công nghệ.
- `ROADMAP_GENERATE`: whitelist server → native schema LearningResponseSchema → AiService → parse JSON →
  constrainGenerated kiểm tra đủ từng kỹ năng, không lặp/không ngoài phạm vi.
- Publish: gap_analyses/context_snapshot, gap_analysis_skills, roadmaps/learning_recommendations_snapshot,
  roadmap_nodes và bảng prerequisite/resource hiện có. Toàn bộ publish trong transaction.
- Read: LearningRepository.listRoadmapNodesByRoadmapId ORDER BY sequence_order, không LIMIT;
  LearningService.roadmap() trả nodes, missing_skills, skill_states và unverified_requirements.
- FE: skillPathService.toLearningPath() map toàn bộ arrays; SkillAssessmentPage render toàn bộ nodes
  và danh sách đối chiếu đầy đủ. Không có slice(0,6) hay pagination làm mất skill.

## Thay đổi ít ảnh hưởng API

`MissingSkillAnalysis.analyze()` bổ sung ledger đầy đủ, giữ field gap_status cũ cho API/DB:

| status mới | Ý nghĩa | Xử lý |
|---|---|---|
| SATISFIED | Assessment cùng context đạt ngưỡng 70 | Không cần node luyện thêm; không chứng nhận Bloom |
| NEEDS_IMPROVEMENT | Assessment cùng context dưới 70 | Node luyện tập; gap_status=NEEDS_PRACTICE |
| MISSING | Bắt buộc độc lập, chưa có bằng chứng khai báo/đánh giá | Node học/kiểm tra; gap_status=MISSING_EVIDENCE; không kết luận thiếu năng lực đã xác minh |
| UNVERIFIED | Kỹ năng chỉ khai báo, hoặc yêu cầu lựa chọn/điều kiện/chưa rõ | Giữ trong ledger, yêu cầu xác minh; không tự gọi là thành thạo |

Kỹ năng khai báo không bắt buộc học lại tự động, nhưng vẫn hiện để xác minh. PREFERRED có priority OPTIONAL,
không bị gọi là bắt buộc hoặc âm thầm biến mất. Người dùng xem chúng theo mục tiêu; lần sửa này chưa thêm
chế độ tự chọn kỹ năng optional vào node học. Yêu cầu OR không bắt học tất cả lựa chọn. Câu chưa nhận diện
kỹ năng cũng giữ riêng trong unverified_requirements. Không có node học chưa đồng nghĩa đáp ứng toàn bộ JD.

Extractor requirements-v4 tách mục ` + ` có khoảng trắng, giữ nguyên C++, nhận diện “một trong/ít nhất một”.
Đối với snapshot có cấu trúc, quan hệ phong phú được ưu tiên hơn boolean legacy khi xác định mandatory độc lập.

Không còn chặn 50 kỹ năng gap, 50 node hay 100 gap; không còn cắt 50 đoạn bằng chứng roadmap.
Sinh nội dung theo batch 12 để giới hạn kích thước từng response, không giới hạn tổng gap. Mọi batch phải
đủ kỹ năng trước publish; chỉ số prerequisite được điều chỉnh khi ghép. Nếu bất kỳ batch thiếu skill/lỗi,
không lưu roadmap một phần. Batch không suy diễn prerequisite mới giữa các nhóm.

AiService từ chối response ROADMAP_GENERATE có finish_reason=length ngay cả khi JSON parse được.
JSON hỏng hoặc node thiếu bị báo lỗi, không bị parser bỏ qua rồi lưu thành roadmap ngắn. Input/output size
safety bounds hiện có vẫn báo lỗi rõ ràng; không cắt danh sách gap để lách giới hạn nhà cung cấp.
Giới hạn token cấu hình mặc định của learning là 4000; metadata lịch sử không lưu finish_reason nên không
thể suy ra toàn bộ quá khứ chỉ từ token count. Ca 6 mục có whitelist 6 được lưu trước AI là bằng chứng trực tiếp.

Không thêm bảng/migration. Ledger và các trạng thái dùng JSONB snapshot hiện có. Roadmap lịch sử được giữ
nguyên, trả gap_analysis_version=LEGACY nếu thiếu ledger; FE yêu cầu tạo lại để thấy đối chiếu đầy đủ.
Việc tạo lại là thao tác người dùng gọi AI; đợt kiểm tra không tự phát sinh lời gọi AI thật.

## Đối chiếu lại dữ liệu thật, chỉ đọc

Code mới đọc đúng job trên: CURRENT_LEDGER=22, CURRENT_LEARNING=6. Con số node vẫn có thể là 6 hợp lệ,
nhưng danh sách đã khác: Artificial intelligence, Docker, Kubernetes, Data handling, NLP, Computer vision.
FastAPI chuyển sang review vì xuất hiện trong nhóm microservices có lựa chọn; PostgreSQL/MongoDB là OR.
Python/Go/C++ giữ ưu tiên; các yêu cầu chưa chắc chắn giữ trong ledger/review, không bị tính thành satisfied.
Không có căn cứ ép toàn bộ 22 keyword thành 22 yêu cầu bắt buộc độc lập.
Bản dry-run: target/roadmap-gap-live-dry-run.txt. Không sửa CV/profile/job nguồn hoặc roadmap cũ.

## Kiểm thử

- JD 20 kỹ năng bắt buộc, profile khai 2: 18 node học, 20 trạng thái (2 UNVERIFIED), không skill nào bị gộp.
- Qua API → hai batch AI giả lập → publish DB → GET: đủ 18 node và 20 trạng thái.
- Batch trả thiếu skill dù JSON hợp lệ: HTTP 502, không có roadmap lưu dở dang.
- 51 skill gap/node không bị chặn 50; alias dedup và các kỹ năng công nghệ riêng biệt được bảo toàn.
- Danh sách + không làm lan PREFERRED; PostgreSQL/MongoDB là lựa chọn OR.
- Token-limit response bị từ chối, không tự retry; FE giữ đủ 20 trạng thái/18 node và đọc được API cũ.

Kết quả kiểm chứng cuối: 225 test backend thuộc 12 lớp và 8 test frontend đạt, không failure/error;
build BE/FE và formatter validation thành công. Không gọi AI thật trong các test.

## Chính sách mới theo yêu cầu người dùng: jd-skills-v3

Thay thế quy tắc UNVERIFIED chặn node ở phần trên. Mọi kỹ năng đã nhận diện trong JD, gồm optional, alternative và responsibility, được đề xuất học nếu chưa có khai báo tương ứng hoặc assessment đạt ngưỡng. Profile/CV khai kỹ năng thì trạng thái DECLARED, không tự gọi SATISFIED; assessment dưới 70 vẫn cần luyện thêm. Priority REQUIRED/OPTIONAL/JD_RELATED giữ khác biệt phạm vi, không khẳng định mọi lựa chọn đều bắt buộc. Không dùng UNVERIFIED trong skill_states mới.

Job f54992c2-fa5a-44eb-a3cb-c29ff4b5b402, profile chỉ Spring Boot: dry-run chỉ đọc cho CURRENT_LEDGER=10 và CURRENT_LEARNING=10. Không gọi AI thật để tạo lại lịch sử; người dùng bấm Tạo lại lộ trình. Roadmap policy cũ hiển thị thông báo tạo lại để cập nhật.
