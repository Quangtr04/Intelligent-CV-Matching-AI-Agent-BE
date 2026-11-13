> Tài liệu lịch sử v1/v2. Xem [catalog v3 và quy tắc hiện hành](skill-catalog-roadmap.vi.md) để biết nguồn dữ liệu, scoring và reprocess mới.

# Bóc tách yêu cầu tuyển dụng và Bloom — 13/11/2026

## A. Nguyên nhân đã xác nhận trong repository

Luồng cũ: JobCollectionScheduler/JobCrawlService → JobCrawlSourceAccess → JobPostingParser →
JobNormalizer → JobService/JobRepository → market_job_posts + market_job_skills → MatchingRepository →
MatchingService → QuickJobMatcher hoặc JobSkillOverlap hoặc vector + JobMatchRerank.

JobPostingParser ưu tiên JSON-LD JobPosting, fallback sang HTML ITviec/TopCV. Bộ nhận diện cũ chỉ dò
một danh sách công nghệ trong Java. Không có đơn vị yêu cầu, quan hệ OR, câu chưa nhận diện, hoặc Bloom.
Những tên không nằm trong danh sách biến mất khỏi dữ liệu kỹ năng. QuickJobMatcher chia số kỹ năng
bắt buộc khớp cho số kỹ năng bắt buộc nhận diện được. Vì vậy SQL có thể là yêu cầu duy nhất trong mẫu số.

CV được lấy từ structured_data.skills cùng summary, experience và projects; profile có bảng kỹ năng riêng.
ResumeSkillMatcher đã có quy tắc phủ định, đang học và mong muốn học. Quy tắc này cần được giữ khi mở rộng
vốn từ. Chỉ có tên kỹ năng không chứng minh trình độ thực hành hoặc mức tư duy Bloom.

Nội dung gốc HTML ở original_content, bản text ở raw_content, yêu cầu ở parsed_requirements và trách nhiệm
ở parsed_responsibilities. SqlStore đã hỗ trợ JSONB và Java record qua Jackson, nên không cần thêm hạ tầng NLP.

## B. Kiến trúc được triển khai

1. Giữ nguyên crawler và việc lấy nội dung nguồn.
2. JobRequirementExtractor làm sạch HTML, phân vùng, giữ ranh giới dòng/bullet và tách câu.
3. Mỗi câu giữ text gốc, section, importance, relation, types, skills, status và bloom.
4. Từ điển resources/jobs/skill-taxonomy.txt cung cấp tên chuẩn, loại và alias; regex được biên dịch khi nạp.
5. Tên chưa biết không trở thành kỹ năng tùy tiện: câu gốc được giữ và đánh dấu REVIEW_REQUIRED.
6. Snapshot được lưu JSONB; danh sách market_job_skills vẫn tồn tại để tương thích.
7. RequirementCoverage đối chiếu dữ liệu yêu cầu; dữ liệu này đi vào kết quả matching và đầu vào AI rerank.

Không có lời gọi AI trong extraction/Bloom/reprocess. AI embedding, rerank và các tính năng AI khác vẫn hoạt động.
Không thêm dependency. Extraction giới hạn 100.000 ký tự mỗi nguồn và 300 câu; không phải mô hình hiểu ngôn ngữ tổng quát.

## C. Phân vùng và logic yêu cầu

Ưu tiên parsed_requirements, sau đó nội dung gốc có tiêu đề requirements/qualifications/Your Skills and Experience/
yêu cầu ứng viên; đọc cả responsibilities/job description/mô tả công việc khi có ngữ cảnh phù hợp.
Navigation, script, footer, form bị loại. Phần benefits/Why You'll Love Working Here/quyền lợi bị bỏ qua.
Nội dung không có vùng xác định chỉ được lấy khi có dấu hiệu yêu cầu như required, knowledge, experience.

importance gồm MANDATORY, PREFERRED, FLEXIBLE, RESPONSIBILITY, CONDITIONAL, UNSPECIFIED.
Preferred/nice to have/ưu tiên đánh dấu ưu tiên; willingness to learn/sẵn sàng học là linh hoạt.
Not required/không bắt buộc không biến thành điều kiện bắt buộc.

AND giữ nhiều kỹ năng; OR giữ một nhóm lựa chọn được chấp nhận. Phép tính không đòi tất cả phần tử OR.
Câu có hỗn hợp AND/OR phức tạp hoặc điều kiện if/neu được giữ và yêu cầu kiểm tra. Không xây dựng cây logic
cho mọi câu tự nhiên. Câu có liên từ và vế chưa nhận diện cũng cần kiểm tra.
Trách nhiệm và yêu cầu ưu tiên/linh hoạt không bị tự động đưa vào mẫu số bắt buộc.

## D. Chuẩn hóa và NLP thực tế

Các kỹ thuật được dùng: Jsoup, phân đoạn block/dòng/câu, Unicode NFD, chuẩn hóa dấu/hoa thường, regex ranh giới
token, alias, chọn mention dài nhất khi chồng lấn, và quy tắc ngữ cảnh. Không dùng POS tagger, dependency parser
hoặc mô hình ngôn ngữ. Danh mục ban đầu bao gồm công nghệ và một số năng lực dữ liệu, giao tiếp, kế toán,
báo cáo tài chính, dịch vụ khách hàng, quản lý dự án, tiếng Anh; chưa phải ontology đầy đủ mọi ngành.

Postgres/PostgreSQL là alias; SQL/PostgreSQL là kỹ năng liên quan và không được tự động coi tương đương.
SQL Server không tự sinh thêm SQL từ phần tên chồng lấn. Không suy luận quan hệ cha-con thành bằng chứng sở hữu.
CV tái sử dụng mention từ danh mục mới, nhưng vẫn qua các quy tắc phủ định, mong muốn học và suffix cũ.
Kỹ năng khai báo rõ nhưng chưa có alias vẫn được giữ trong evidence của QuickJobMatcher.

Mở rộng từ vựng bằng cách thêm dòng name|category|alias1|alias2 vào skill-taxonomy.txt, thêm test và tăng VERSION.
Câu lạ vẫn được bảo toàn để người vận hành xem xét; chưa có giao diện duyệt taxonomy trong thay đổi này.

## E. Revised Bloom

Sáu mức: Remember, Understand, Apply, Analyze, Evaluate, Create.
Mỗi rule yêu cầu hành động và object/context: ví dụ recall + definitions; explain + concepts;
use + đối tượng + to/procedure/process; analyze + causes/patterns; evaluate + criteria/evidence;
design/create + new/original + solution/system/workflow. Có quy tắc tiếng Việt tương ứng.

Bloom lưu nguyên câu chứng cứ và pattern giải thích. Nhiều pattern trong một câu có thể trả nhiều mức.
Không đủ chứng cứ trả UNDETERMINED. Ví dụ Design dashboards chưa đủ để mặc định Create;
Design a new workflow solution có căn cứ cho Create. Senior hoặc 10 years không quyết định Bloom.

Đây là phân loại thận trọng theo rule, chưa giải được mọi đối tượng ngầm hoặc mọi tác vụ trong câu ghép.
Bloom không phải proficiency, số năm, độ quan trọng, hoặc bằng chứng ứng viên đã thực hiện được tác vụ.
Không lấy chênh lệch Bloom để trừ điểm tùy ý. Dữ liệu đã sẵn trong snapshot/kết quả để hỗ trợ assessment tương lai;
chưa nối Bloom thành tiêu chí chấm bài assessment tự động.

## F. Matching và cách đọc điểm

Quick/fallback dùng RequirementCoverage thay cho chỉ đếm giao tên kỹ năng:

- Một kỹ năng AND là một đơn vị đối chiếu; một nhóm OR là một đơn vị.
- Tên/nhóm lặp không được đếm lại.
- Một câu bắt buộc chưa diễn giải rõ thêm một đơn vị chưa xác minh.
- Yêu cầu ưu tiên, linh hoạt, và trách nhiệm không bị tính thành thiếu kỹ năng bắt buộc.
- Kỹ năng thủ công/legacy không có trong câu nguồn vẫn được đối chiếu, không bị xóa.
- score = số đơn vị có kỹ năng chứng cứ / tổng đơn vị đối chiếu, bao gồm đơn vị chưa xác minh.
- skill_coverage = số đơn vị khớp / số đơn vị kỹ năng có thể đối chiếu; loại phần chưa xác minh khỏi mẫu số này.

Đây là tỷ lệ đối chiếu, không phải xác suất được tuyển. Các đơn vị bắt buộc có trọng số bằng nhau;
importance điều khiển việc đưa vào mẫu số, không tạo hệ số quan trọng giả định.
Kinh nghiệm/cấp bậc vẫn có kiểm tra riêng MATCHED/INSUFFICIENT/UNVERIFIED; không bịa số năm.
Học vấn chưa được đối chiếu tự động từ CV: giữ câu và đánh dấu chưa xác minh. Câu kinh nghiệm cũng có thể
vẫn cần review dù bộ kiểm tra số năm riêng đã kết luận; chủ đích thận trọng, chưa hợp nhất hai tầng bằng chứng này.

Chế độ semantic giữ điểm ước lượng AI của nó, đồng thời gửi nhóm yêu cầu có cấu trúc vào rerank và trả assessment.
Không đánh đồng semantic score với verified coverage. Đầu vào rerank giới hạn 20 câu, mỗi câu 500 ký tự.
Không thay AI score bằng một hình phạt Bloom.

Frontend dùng final_score để hiển thị điểm recommend nhanh, với nhãn Yêu cầu đã xác minh;
không lấy skill_coverage riêng làm phần trăm chính nữa. Còn yêu cầu chưa rõ/thiếu nguồn thì hiển thị ghi chú.
Điểm phân số dưới 1 không được làm tròn lên 100%.

Nguồn thiếu chỉ có tag thủ công vẫn có thể khớp toàn bộ tag, nhưng status MISSING_SOURCE và ghi chú nêu rõ
không xác nhận toàn bộ JD. PARSED chỉ nghĩa là các câu đã được diễn giải theo rule; không bảo đảm recall tuyệt đối.
Một câu nhận diện một phần mà không có cấu trúc liên từ rõ vẫn có thể bị bỏ sót ý; đây là giới hạn cần đánh giá tiếp.

## G. Database, compatibility và vận hành

V36 thêm vào market_job_posts:

- requirement_extraction JSONB: snapshot version/status/câu/skills/Bloom/evidence.
- requirement_extraction_verified boolean: khóa bảo vệ bản được duyệt thủ công.

Không thêm bảng, không sửa/xóa nguồn hoặc match history. Import mới lưu snapshot. Job detail trả thêm hai trường;
các trường cũ vẫn giữ. Kết quả match_reason_json.assessment thêm requirements, extraction_status,
unverified_requirements, score_kind. Snapshot hiện tại/được duyệt được đọc khi matching; job cũ chưa có snapshot
được phân tích từ nguồn lưu sẵn. Bản được khóa thủ công không bị batch ghi đè.

JobRequirementReprocessor chạy batch tối đa 500 bản ghi, transaction và FOR UPDATE SKIP LOCKED,
chỉ chọn version khác hiện tại và chưa được khóa. Chạy lại không thay thêm dữ liệu. Không sửa market_job_skills
thủ công, hash nguồn, embedding hay lịch sử. parsed_responsibilities được đưa lại vào phân tích.

Có runner bảo trì opt-in: khởi động backend với --jobs.requirements.reprocess.enabled=true.
Runner dùng batch 200, commit theo batch, dừng khi hết việc. Mặc định tắt. Sau khi chạy xong nên bỏ cờ này.
Cần áp dụng Flyway V36 trước khi dùng code mới. Phiên làm việc này chỉ áp dụng migration/reprocess trên
PostgreSQL Testcontainers; chưa chạy trên database ứng dụng đang dùng.

Khóa response cache đổi v1 → v2; request hash thêm extraction VERSION; catalog fingerprint thêm snapshot và
cờ duyệt. Job cũ được tính lại khi version/nguồn/snapshot đổi. AI prompt-cache input thay vì có dữ liệu yêu cầu mới.
Vector embedding cũ chưa được dựng lại: semantic retrieval vẫn có giới hạn recall của embedding cũ.
contentHash hiện có cả legacy skills, còn fingerprint liên nguồn không có skills. Không đổi thuật toán dedup trong
lần sửa này; crawl lại cùng trang với skill list mới có thể tạo phiên bản nguồn mới theo logic hiện hữu.
Batch reprocess là cách cập nhật snapshot mà không tác động identity/dedup.

## H. File thay đổi và kiểm chứng

Main Java:

- util/shared/jobs/JobRequirementExtractor.java — engine, dữ liệu, normalization, Bloom.
- util/shared/jobs/JobPostingParser.java — dùng engine cho legacy skills.
- util/shared/resume/ResumeSkillMatcher.java — mention chung, giữ bộ lọc evidence CV.
- util/candidate/jobs/RequirementCoverage.java — đối chiếu nhóm/câu chưa rõ.
- util/candidate/jobs/QuickJobMatcher.java, JobSkillOverlap.java — tích hợp điểm mới.
- util/candidate/jobs/JobMatchRerank.java — dữ liệu yêu cầu có giới hạn cho AI.
- service/shared/jobs/JobService.java — snapshot khi import.
- service/shared/jobs/JobRequirementReprocessor.java, JobRequirementReprocessingRunner.java — xử lý lại opt-in.
- repository/shared/jobs/JobRepository.java, repository/candidate/jobs/MatchingRepository.java — projection/cache.
- service/candidate/jobs/MatchingService.java — version, metadata các mode, dữ liệu rerank.

Resource: jobs/skill-taxonomy.txt và db/migration/V36__job_requirement_extraction.sql.
Test Java: JobRequirementExtractorTests mới; cập nhật MatchingFullResponseCacheTests,
JobMatchRerankTests, MatchingCatalogCacheIntegrationTests.
Frontend: src/pages/candidate/JobsPage.tsx, src/services/candidateMappers.ts;
tests/matching-jobs-page.test.mjs, tests/matching-service.test.mjs.

Đã chạy:

- Maven compile (thực hiện trong lifecycle test).
- mvn formatter:format và formatter:validate.
- 114 test trong 10 lớp extraction/parser/CV/quick matching/rerank/recommend modes/cache: qua.
- MatchingCatalogCacheIntegrationTests trên PostgreSQL 17 Testcontainers: migration, cache invalidation,
  reprocess idempotent và khóa thủ công: 12 test qua, không skip.
- npm.cmd run build: TypeScript + Vite thành công, có cảnh báo bundle lớn.
- npm.cmd run test:matching: các bài kiểm thử matching UI/service/error, gồm score khác skill coverage,
  nguồn chưa rõ và làm tròn điểm: 56 test qua.

Test bao gồm fixture HTML ITviec/TopCV có sẵn; yêu cầu internship do người dùng cung cấp được mô phỏng;
các trường hợp Việt/Anh về công nghệ, kế toán, khách hàng, tài chính, ngôn ngữ; OR, optional, flexible,
conditional, phủ định, tên lạ, kỹ năng liên quan, 6 mức Bloom và trường hợp chưa xác định.
Đây là regression suite với nhãn kỳ vọng được viết trong test, chưa phải bộ JD thực tế đa ngành được chuyên gia
đánh nhãn độc lập. Không công bố precision/recall hoặc phần trăm accuracy ngoài phạm vi test này.

Hướng tiếp theo: tập JD có nhãn độc lập để đo precision/recall theo ngành; từ điển theo dữ liệu review;
parser logic lồng nhau; bằng chứng học vấn và trình độ từ CV; assessment kiểm tra cognitive task thực tế;
kiểm soát nguồn chưa phân đoạn và chiến lược refresh embedding khi taxonomy thay đổi.

## Sửa hồi quy từ JD Product Owner có text xen HTML

Job ec0f2ee8-4aaf-44ba-9338-ad8f7da847c9 có JD đầy đủ, nhưng tiêu đề Your Skills and Experience
là text node nằm ngoài các thẻ heading. Bản v1 bắt đầu ở EXCLUDED rồi bỏ qua text node đó, dẫn đến snapshot
MISSING_SOURCE/rỗng. Legacy skill English bị dùng làm toàn bộ mẫu số. Đây là lỗi triển khai, không phải
job thực tế chỉ yêu cầu tiếng Anh.

Bản requirements-v2 chuyển các dòng tiêu đề nhận diện được trong text node thành heading trước khi duyệt
block, giữ đúng thứ tự DOM, nhận Key Responsibilities/The Job và loại benefits. Danh mục bổ sung các năng lực
Product, Agile/Scrum, stakeholder management. Có nguồn nhưng extraction rỗng sẽ tạo một đơn vị chưa xác minh,
không cho một tag khớp thành full coverage. Một số test dùng nội dung giả không có yêu cầu rõ nay có lower bound
1/(1 kỹ năng + 1 nguồn chưa xác minh), thay vì 100%.

Trang chi tiết job chỉ hiển thị độ bao phủ kỹ năng nhận diện khi backend có số liệu này. Bốn dòng kỹ năng,
kinh nghiệm, mức lương, lĩnh vực trước đây dùng bản sao của final_score đã được bỏ khỏi trang này;
backend không cung cấp bốn điểm độc lập đó. Điểm thấp không còn gắn nhãn Tiềm năng tốt.
