# Skill Catalog và tích hợp roadmap — 13/11/2026

## A. Kiến trúc và nguyên nhân

Luồng được kiểm tra: crawler → JobPostingParser → JobNormalizer → JobService → market_job_posts /
market_job_skills → MatchingService / QuickJobMatcher / JobSkillOverlap → skill gap → LearningService.
CV đi qua ResumeService/ResumeSkillMatcher, còn khai báo profile nằm ở candidate_profile_skills.

Bản cũ có ba điểm yếu: từ điển text nhỏ thiếu ID/provenance/quan hệ; CV và learning dùng các bộ alias khác nhau;
reprocess chỉ sửa JSONB nên bảng job–skill có thể vẫn thiếu. Một lỗi phân vùng text xen HTML đã được sửa ở v2,
nhưng thêm vài từ riêng lẻ không giải quyết vấn đề cấu trúc danh mục. Việc đếm tên kỹ năng còn dễ bị hiểu nhầm
thành mức phù hợp toàn bộ công việc.

Bản này dùng SkillCatalog dùng chung, nạp một lần trên JVM khi được dùng lần đầu. Hai JSON resource được đọc,
kiểm tra ID/alias/nguồn/tham chiếu/chu trình rồi tạo trie và regex đã biên dịch. Mỗi JD tra cứu nội bộ, không tải
roadmap.sh và không gọi LLM. Các kỹ năng liên quan không được tự mở rộng thành yêu cầu hoặc bằng chứng CV.

JobRequirementExtractor vẫn giữ phân vùng, câu/bullet gốc, loại yêu cầu, AND/OR, câu chưa rõ và Bloom.
Snapshot dùng version requirements-v3 và catalogVersion gồm version resource cùng SHA-256 nội dung.
Mỗi kỹ năng có thêm id, type, recognition (ALIAS/CONTEXTUAL), sourceReferences; các trường name/category cũ vẫn còn.

## B. Nguồn roadmap, quyền sử dụng và dữ liệu thực tế

Đã xem [repository](https://github.com/nilbuild/developer-roadmap),
[giấy phép](https://github.com/nilbuild/developer-roadmap/blob/master/license) và
[điều khoản website](https://roadmap.sh/terms). URL repository cũ người dùng đưa chuyển tới nilbuild/developer-roadmap
ở thời điểm kiểm tra. Giấy phép giới hạn việc sử dụng nội dung và đưa nội dung ra ngoài repository; do chưa có
quyền nhập nội dung cho ứng dụng, không tự sao chép hàng loạt dữ liệu vào EduNexus.

README nguồn mô tả public topic content dạng roadmaps/<slug>/content/<topic-slug>@<node-id>.md. File Markdown
chứa chủ đề; tên file giữ node ID. Không có căn cứ coi thứ tự file hoặc đường nối bất kỳ là prerequisite.
Không khẳng định public Markdown chứa đầy đủ graph học tập. Không scrape HTML hoặc bypass hạn chế nguồn.

Dữ liệu runtime thực tế:

- 328 mục trong skills/catalog.json: từ vựng của dự án cộng curation độc lập, không phải 328 node tải từ roadmap.sh.
- Các miền frontend, backend, DevOps, data, security, QA, mobile, product, business và general.
- 29 learning topic do EduNexus tự biên soạn; không khai báo prerequisite giả định cho các topic này.
- skills/imported-catalog.json hiện rỗng: 0 mục roadmap.sh được nhập trực tiếp.
- Ví dụ importer có 2 skill/2 topic synthetic trong target và test resource; không nạp chúng vào catalog production.

Importer offline scripts/dev/import-skill-catalog.mjs nhận JSON nodes/edges (kể cả label trong data.label)
hoặc thư mục Markdown theo mẫu tên trên. Manifest review phải có source URL, revision, license, permissionKind,
permissionEvidence, catalogVersion và danh sách node được duyệt rõ ràng. Nguồn roadmap.sh/developer-roadmap
đòi manifest WRITTEN_PERMISSION; khai báo MIT không đủ để bỏ qua điều kiện này. Importer không tự xác minh
một văn bản cấp quyền là thật: người vận hành chịu trách nhiệm cung cấp bằng chứng sử dụng phù hợp.

Mặc định node không được duyệt bị bỏ; title/group/link/category không trở thành job skill. Label nguồn phải được
review làm tên chuẩn hoặc alias. Stable ID do reviewer chỉ định, không sinh theo vị trí đồ thị. Alias trùng/conflict,
ID trùng, tham chiếu sai, thay đổi identity hiện có hoặc cycle prerequisite bị từ chối. Cùng ID hiện có chỉ được
bổ sung khi tên chuẩn/type/context tương thích. Source revision chứa node ID và SHA-256 input.

Chỉ edge được nguồn gắn type prerequisite (hoặc data.relationship=prerequisite) và được reviewer chọn mới được
nhập. Đường nối không có ngữ nghĩa, thứ tự bố trí và quan hệ related không biến thành prerequisite.

Chạy ví dụ tự biên soạn, không có nội dung roadmap.sh:

```powershell
node scripts/dev/import-skill-catalog.mjs scripts/dev/catalog-import-example/source.json scripts/dev/catalog-import-example/review.json target/my-reviewed-example.json
```

Output không ghi đè file đã có. Với dữ liệu được cấp quyền, xuất staging, review/chạy test, rồi thay resource
skills/imported-catalog.json và build lại. Không cần migration hoặc thêm bảng alias/topic/Bloom.

## C. Nhận diện yêu cầu và kỹ năng

Lớp 1: trie tìm canonical name/alias theo ranh giới token, giữ tên có dấu +/# và chọn mention dài nhất khi chồng lấn.
C, C++, C#, SQL và PostgreSQL vẫn khác nhau. Alias Postgres/PostgreSQL cùng identity; quan hệ liên quan SQL–PostgreSQL
không làm ứng viên tự có SQL. Khoảng trắng giữa các từ của alias được chuẩn hóa mà vẫn giữ offset.
Tên ngắn/từ đa nghĩa Go, R, C, React, Spring và Communication có điều kiện ngữ cảnh; tên khai báo riêng lẻ vẫn
có thể chuẩn hóa. Alias mơ hồ không được tự chọn một identity bất kỳ.

Lớp 2: pattern hành động–đối tượng tái sử dụng, Việt/Anh, gồm một số biến thể quá khứ: gather/elicit requirements,
optimize queries, clean datasets, coordinate stakeholders, prepare financial reports, assess risks, automate tests…
Pattern hữu hạn, có giới hạn khoảng cách từ và được biên dịch một lần. Đây không phải semantic parser tổng quát.

Lớp 3: câu có ngữ cảnh yêu cầu nhưng chưa map được được lưu nguyên văn và đánh dấu REVIEW_REQUIRED.
Không tạo một skill verified từ noun phrase chưa biết. Câu có vế AND/OR/danh sách chưa nhận diện, điều kiện,
yêu cầu trình độ hoặc kinh nghiệm/học vấn chưa xác minh được giữ lại. Phần công ty, phúc lợi, quảng cáo và
navigation bị loại theo phân vùng. Nguồn bị giới hạn kích thước/số câu có trạng thái TRUNCATED.

MANDATORY/PREFERRED/FLEXIBLE/CONDITIONAL/RESPONSIBILITY/UNSPECIFIED được giữ riêng. OR đơn giản là một nhóm.
AND/OR hỗn hợp, slash hoặc nhóm ngoặc không diễn giải chắc chắn được đánh dấu MIXED/AMBIGUOUS và giữ câu gốc,
không chuyển mọi tên thành các kỹ năng bắt buộc độc lập. Cách này ưu tiên precision, có thể cần review thêm.

## D. Bloom

Từ `requirements-v6`, `JobRequirementExtractor` giữ hợp đồng snapshot/skill hiện có và gọi
`BloomRuleEngine.classifyRequirement` để phân loại Bloom. Engine tách các mệnh đề hành động Việt/Anh,
lưu chứng cứ theo mệnh đề và chọn rule có độ ưu tiên cao nhất trong mỗi mệnh đề. Thay đổi này cần
chạy lại công cụ reprocess để cập nhật snapshot job crawl cũ.

Giữ sáu mức Revised Bloom và UNDETERMINED trong requirement_extraction JSONB. Rule xét hành động và object/context/
outcome, lưu câu chứng cứ cùng giải thích pattern. Ví dụ design a new workflow solution có căn cứ Create;
chỉ nói design dashboards chưa đủ. Số năm, seniority và vị trí của node trong roadmap không gán mức Bloom.
Các pattern dùng chung và cache sau lần biên dịch đầu, không tạo model hoặc bảng Bloom mới.

Catalog không chứa mức Bloom cố định cho skill. Một skill xuất hiện trong CV không được tự có mức Bloom đã xác minh.
Phân loại hiện có vẫn là tập rule thận trọng; tác vụ phức tạp hoặc đối tượng ngầm có thể UNDETERMINED.

## E. CV, matching và skill gap

JobNormalizer, ResumeSkillMatcher, SkillNames, RequirementCoverage và MissingSkillAnalysis dùng chung canonical
name/alias. Bộ alias riêng của ResumeSkillMatcher được thay bằng catalog nhưng giữ các hàng rào phủ định,
đang học và mong muốn học. Contextual evidence của CV cũng qua các hàng rào đó. Không ghi đè CV hay profile đã lưu.

QUICK vẫn không gọi AI. DETAILED vẫn dùng embedding/rerank và nhận các yêu cầu có cấu trúc. Kết quả tách:

- skill_coverage: mức phủ các đơn vị kỹ năng có thể so sánh;
- requirement_coverage: tỷ lệ bằng chứng trên các đơn vị yêu cầu, gồm câu thật sự chưa xác minh;
- requirement_coverage_available: có đủ nguồn để báo tỷ lệ yêu cầu hay không;
- score_kind, extraction_status, unverified_requirements, coverage_complete và catalog_version;
- kiểm tra kinh nghiệm vẫn riêng, không coi thiếu số năm là 0 năm.

Không tạo một yêu cầu giả với trọng số 1 chỉ để kéo điểm xuống khi toàn bộ source chưa phân tích được.
Trong trường hợp MISSING_SOURCE/TRUNCATED, requirement_coverage không có giá trị và FE hiển thị Chưa đủ dữ liệu.
Điểm legacy numeric dùng xếp hạng theo tag vẫn được giữ để tương thích, nhưng được ghi rõ DECLARED_SKILL_COVERAGE_ONLY,
không trình bày như mức phù hợp toàn JD. Câu chưa hiểu không được tự xem là thỏa mãn hoặc xác nhận ứng viên thiếu kỹ năng.
Bloom không trở thành hệ số phạt. Không có trần điểm 90/95/99 tùy ý; FE trình bày phần trăm với một chữ số thập phân
bằng truncation để không làm tròn số dưới 100 thành 100. Đây là quy tắc hiển thị, không đổi điểm nguồn.

Spring Boot và một yêu cầu Spring được khai báo riêng không bị mặc định coi là một alias. Các test kỳ vọng cũ
1.0 ở tình huống này được sửa thành 2/3 độ phủ, giữ nguyên kết luận kinh nghiệm không đủ.

Cache matching thêm digest catalog vào request identity; fingerprint database vốn bao gồm snapshot và skills.
Vì thế reprocess hoặc cập nhật resource làm request cũ mất hiệu lực. Không xóa lịch sử matching hoặc dữ liệu billing.

## F. Learning Roadmap

LearningService chỉ lấy catalog learning topics của whitelist missing skills đã được xác định. Input AI bổ sung
catalog_learning_context, catalog_version và chứng cứ JD/Bloom liên quan, có giới hạn kích thước. Không gửi cả
roadmap vào danh sách thiếu kỹ năng. Câu chưa xác minh/OR chưa thỏa mãn được ghi riêng. Nếu không có gap chắc chắn
nhưng còn các yêu cầu đó, API yêu cầu review thay vì khẳng định không cần học gì.

Mỗi learning topic có nguồn và prerequisite rõ ràng. Quan hệ chỉ được dùng khi các chủ đề thuộc phạm vi gap
được chọn. Sau khi AI sinh nội dung, server chỉ lưu prerequisite có liên kết hỗ trợ trong catalog nguồn;
AI không được tự tạo dependency graph không có căn cứ. Catalog seed hiện không có cạnh prerequisite;
khả năng dùng cạnh đã được kiểm thử bằng dữ liệu synthetic được cấp quyền của dự án.

AI vẫn tạo hướng dẫn cá nhân hóa; resource URLs và các kiểm tra whitelist hiện có được giữ. Context JSONB và hash
của roadmap có catalog version để không dùng lại nội dung với vocabulary/topic khác. Skill/requirement gaps vẫn
phân biệt MISSING_EVIDENCE và NEEDS_PRACTICE; không tự suy ra trình độ đã được chứng minh.

## G. Database và reprocess

Số bảng public BASE TABLE trước thay đổi: 96. Không có migration/schema/table mới trong lần sửa này.
Tái sử dụng skills, market_job_skills, resume_skills, candidate_profile_skills và requirement_extraction JSONB;
roadmap context/snapshot cũng dùng JSONB hiện có. Local catalog ID không phải UUID của skills: liên kết database
ưu tiên record cùng tên canonical/alias đã nhận diện; nếu không có record phù hợp thì skill_id giữ null như cơ chế cũ.
Không tạo hàng loạt database skill records từ mọi topic của catalog.

JobRequirementReprocessor chọn job CRAWL/SCHEDULED_CRAWL chưa được duyệt, chưa khóa verified và khác extraction/catalog
version. Transaction + FOR UPDATE SKIP LOCKED, batch có giới hạn. Cập nhật snapshot và thay các association tự sinh;
giữ nguyên job ID, source text/hash, URL, trạng thái, job thủ công, thông tin đã review, CV và profile. Chạy lại khi
không đổi version trả 0. Không re-crawl, không enqueue thêm embedding, không sửa learning/matching history.
Vector embedding cũ không bị dựng lại tự động để tránh thêm chi phí; semantic retrieval vẫn có giới hạn của vector cũ.

Công cụ offline không khởi động Spring application hoặc các AI worker:

```powershell
mvn compile
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/maintenance/reprocess-job-catalog.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/maintenance/reprocess-job-catalog.ps1 -Apply
```

Mặc định preview. Apply sao lưu snapshot + association cũ vào outputs/backups trước khi cập nhật. Dùng JDBC tới
localhost từ .env; JOB_REPROCESS_DB_URL/USER/PASSWORD cho phép chỉ định môi trường khác. Script không in mật khẩu,
kiểm tra resource đã compile và dùng Java argument file để tránh giới hạn command line Windows. Khi vận hành,
tạm dừng backend trong thời gian apply để tránh thay đổi nguồn đồng thời; sau đó khởi động lại và làm mới cache matching.

## H. Kiểm chứng và giới hạn

Code chính: SkillCatalog.java; JobRequirementExtractor.java; JobNormalizer.java; ResumeSkillMatcher.java;
SkillNames.java; RequirementCoverage.java; MissingSkillAnalysis.java; LearningService.java; MatchingService.java;
AiResponseData.java; JobRequirementReprocessor.java. Resource: skills/catalog.json và skills/imported-catalog.json.
Tool: importer MJS + manifest example, ReprocessJobRequirements.java và wrapper PowerShell.
FE: domain types, candidateMappers, JobsPage, JobDetailsPage. Không thêm package dependency.

Đã thực thi importer bằng fixture tự biên soạn: 2 skill, 2 topic, bỏ 1 node, bỏ 1 edge không có ngữ nghĩa.
7 test importer qua. Java regression kiểm tra load, identity, alias, context, false positive, unresolved, logic,
Bloom, CV, QUICK, roadmap/skill-gap, cache và reprocess. PostgreSQL Testcontainers xác minh association được thay,
ID catalog database được map, manual/reviewed không bị sửa, chạy lại idempotent và catalog change invalidation.
API learning đầy đủ được chạy trong Spring Boot với PostgreSQL cô lập và AiService mock, không gọi provider thật.
Kết quả: 219 test backend thuộc 14 lớp đã chạy, không failure/error/skip; frontend build thành công;
58 test matching frontend và 3 test thông báo lỗi qua. mvn package -DskipTests và formatter:validate qua.

Bộ nhãn regression tự biên soạn gồm 8 câu đa miền: TP=26, FP=0, FN=0 trên chính 8 câu đó. Không phải dataset chuyên gia
độc lập hoặc thống kê accuracy trên toàn bộ thị trường việc làm. Có fixture HTML ITviec/TopCV và nhiều ca Việt/Anh,
trong đó có cấu trúc text xen HTML đã từng lỗi. Unknown và điều kiện phức tạp vẫn cần review.

Benchmark cuối trên fixture ngắn 5 yêu cầu, warm-up 20 lần, đo 100 lần với catalog 328 mục: p50=2,049 ms,
p95=15,186 ms ở lần chạy có các kiểm thử khác và Docker trong môi trường này. Các lần đo trước dao động;
không suy rộng thành SLA hoặc hiệu năng JD dài/batch production. Không có request AI hoặc tải roadmap trong phép đo.

Giới hạn còn lại: curation nội bộ chưa phải ontology đầy đủ; quyền nhập roadmap.sh chưa được cấp; learning topic seed
ít hơn số skill và chưa có prerequisite ngoài dữ liệu được duyệt; pattern NLP/Bloom không hiểu ngôn ngữ tổng quát;
không xác minh tự động mọi điều kiện học vấn/proficiency; cần bộ JD có nhãn độc lập để đo precision/recall thực tế.

### Kết quả áp dụng trên dữ liệu local

Đã chạy offline apply cho 56 job CRAWL đủ điều kiện; lần preview kế tiếp trả 0. Có 855 association job–skill tự sinh
sau cập nhật. Không crawl lại và không gọi AI trong thao tác này. Cả 56 snapshot là requirements-v3 và REVIEW_REQUIRED:
mỗi job còn ít nhất một câu/điều kiện chưa thể xác minh hoặc diễn giải hoàn toàn bằng các rule hiện tại.
Không dùng trạng thái này làm phần trăm confidence hoặc kết luận ứng viên thiếu toàn bộ kỹ năng.

Đối chiếu fingerprint trước/sau ở 11 phạm vi đều khớp: users, resumes, resume_skills, candidate_profiles,
candidate_profile_skills, skills, toàn bộ 66 job MANUAL và association của chúng, source fields/ID/hash của tất cả
job, embeddings và danh sách 96 bảng public. Chỉ snapshot và association tự sinh của job crawl được cập nhật.

Backup dữ liệu dẫn xuất trước apply:
outputs/backups/job-catalog-before-reprocess-20261113-121155.json (đường dẫn tính từ workspace Project).
Báo cáo fingerprint: target/catalog-protected-before.csv và target/catalog-protected-after.csv trong backend.
Các bản ghi matching/roadmap/assessment cũ được giữ làm lịch sử; yêu cầu matching mới dùng catalog/hash mới.

Backend đã build và khởi động lại sau cập nhật; /actuator/health trả HTTP 200, status UP.
JOB_COLLECTION_SCHEDULER_ENABLED=false được xác minh trong container. Cache matching cũ đã được xóa theo prefix riêng.
