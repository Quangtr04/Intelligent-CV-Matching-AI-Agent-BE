# Giải thích code trong từng service của backend EduNexus

Đối chiếu mã nguồn ngày **20/10/2026** tại repository `INTELLIGENT_CV_MATCHING_AI_AGENT`. Tài liệu giải thích dependency, API, dữ liệu, quyền, transaction và trạng thái theo code hiện tại.

Phần AI runtime được cập nhật ngày **04/11/2026** theo adapter chat giao thức OpenAI và adapter embedding native để gọi Gemini: chat `gemini-2.5-flash-lite`,
embedding `gemini-embedding-001` 1024 chiều và cần GEMINI_API_KEY. Thiết lập Docker/model
ở [gemini.md](gemini.md); cách giữ vector legacy 1536 của V1 ở [schema.md](schema.md).
Các kết quả kiểm chứng mang mốc cũ chỉ áp dụng cho lượt chạy được ghi nhận.

Phạm vi: **35 file Java trong thư mục service**. Lần refactor helper tác động **17 service** và thêm **17 util**. Không thống kê số method vì overload, callback và delegate dễ khiến con số nhanh lỗi thời.

Các bảng/SQL được diễn giải từ repository mà service thực sự gọi; không suy hoạt động chỉ từ tên lớp hoặc package. Liên kết nguồn mở đúng file Java để bạn đối chiếu khi đọc. Đây là giải thích logic hiện có, không phải bản sao toàn bộ source.

## Mục lục theo service

| STT | Service | Package dưới service/ | Nội dung |
| --- | --- | --- | --- |
| 1 | [AuthFlowService](#svc-authflowservice) | shared/identity | Điều phối auth và throttle |
| 2 | [AuthService](#svc-authservice) | shared/identity | Đăng ký, OTP, login |
| 3 | [TokenService](#svc-tokenservice) | shared/identity | JWT, session, rotation |
| 4 | [GoogleLoginService](#svc-googleloginservice) | shared/identity | Google ID token |
| 5 | [ProfileService](#svc-profileservice) | shared/identity | Tài khoản/career profile |
| 6 | [GuestSessionService](#svc-guestsessionservice) | guest/identity | Phiên và dữ liệu demo |
| 7 | [GuestAccess](#svc-guestaccess) | guest/identity | Quyền và token budget guest |
| 8 | [GuestTrialService](#svc-guesttrialservice) | guest/ai | Builder/recommendation thử |
| 9 | [AiService](#svc-aiservice) | shared/ai | Model, schema và usage/quota |
| 10 | [AiUsageRecorder](#svc-aiusagerecorder) | shared/ai | Persist AI telemetry |
| 11 | [ResumeService](#svc-resumeservice) | candidate/resume | Vòng đời CV, extraction và xác nhận |
| 12 | [MatchingService](#svc-matchingservice) | candidate/jobs | Ranking, vectors và cache |
| 13 | [CareerToolsService](#svc-careertoolsservice) | candidate/jobs | ATS, draft, apply và salary |
| 14 | [CareerAssistantService](#svc-careerassistantservice) | candidate/ai | Conversation, RAG và chat |
| 15 | [AssessmentService](#svc-assessmentservice) | candidate/learning | Question/answer/submit assessment |
| 16 | [LearningService](#svc-learningservice) | candidate/learning | Gap, assessment và roadmap |
| 17 | [BillingService](#svc-billingservice) | shared/billing | Order/webhook và activation |
| 18 | [BillingQueryService](#svc-billingqueryservice) | shared/billing | Billing HTTP facade và ownership |
| 19 | [EntitlementService](#svc-entitlementservice) | shared/billing | Tier, quota và reservation |
| 20 | [SubscriptionMaintenance](#svc-subscriptionmaintenance) | shared/billing | Subscription expiry và notifications |
| 21 | [ConsultationService](#svc-consultationservice) | shared/consultation | Threads, messages, assignment và SSE |
| 22 | [ConsultationAnalysisService](#svc-consultationanalysisservice) | consultant/consultation | Báo cáo candidate trong consultation |
| 23 | [JobService](#svc-jobservice) | shared/jobs | Catalog, import, dedup và trạng thái |
| 24 | [JobCrawlService](#svc-jobcrawlservice) | admin/jobs | Discovery/fetch/parse/import |
| 25 | [JobCollectionScheduler](#svc-jobcollectionscheduler) | admin/jobs | Collection/expiry có lease |
| 26 | [JobEmbeddingService](#svc-jobembeddingservice) | shared/jobs | Queue, retry và vector publishing |
| 27 | [RagDocumentService](#svc-ragdocumentservice) | admin/rag | Tài liệu tham khảo và chunk indexing |
| 28 | [AdminService](#svc-adminservice) | admin/management | User/tier/AI/prompt/monitoring |
| 29 | [AuditService](#svc-auditservice) | admin/monitoring | Audit và admin actions |
| 30 | [AnnouncementService](#svc-announcementservice) | shared/announcement | Thông báo/audience/notifications |
| 31 | [SupportService](#svc-supportservice) | shared/support | Support ticket và trao đổi |
| 32 | [LearningReferenceService](#svc-learningreferenceservice) | shared/learning | Nguồn học từ catalog |
| 33 | [ConsultantEvaluationService](#svc-consultantevaluationservice) | consultant/consultation | Consultant phân tích CV và ATS |
| 34 | [CrawlSettingsService](#svc-crawlsettingsservice) | admin/jobs | Cấu hình crawl trong DB |
| 35 | [RagCourseService](#svc-ragcourseservice) | admin/rag | Duyệt khóa học cho catalog và RAG |

Các phần đọc chung:

- [Ranh giới service/util và các hàm đã tách](#doc-utils).
- [Cách đọc tài liệu và lần theo code](#doc-reading).
- [Các cú pháp/thành phần dùng chung](#doc-language).
- [Transaction, quyền và lỗi](#doc-boundaries).
- [Luồng liên kết giữa các service](#doc-flows).
- [Cách chạy và kiểm tra chức năng](#doc-usage).
- [Các giới hạn cần hiểu đúng](#doc-limits).

<a id="doc-utils"></a>

## Ranh giới service/util và các hàm đã tách

Service giữ **luồng nghiệp vụ**: quyền/ownership/assignment, repository, trạng thái, transaction/lock, AI/provider, Redis, mail, file I/O, audit, scheduling và retry. Util giữ **kiểm tra và biến đổi dữ liệu**: kiểu/độ dài/schema, chuỗi/UUID, map/list/DTO, hash/nội dung, chia chunk và thuật toán so kỹ năng. Không chuyển một hàm chỉ vì nó là private hoặc nằm cuối file.

Các util mới là lớp final với constructor private và hàm static. Chúng không tự đọc repository hoặc phiên người dùng. Service truyền dữ liệu cần xử lý vào util rồi quyết định hành động tiếp theo. `ResumeContent.canonicalText/contentHash` nhận thêm `ObjectMapper`; `TokenSupport.encodeAccessToken` nhận `JwtEncoder` và `decodeAccessToken` nhận `JwtDecoder`. Service vẫn tạo/giữ các dependency này. Một số util dùng giờ hiện tại hoặc sinh số ngẫu nhiên nên không hoàn toàn là hàm thuần.

Thư mục `util` còn có tiện ích kỹ thuật với I/O như `ResumeFileExtractor` để đọc tệp và OCR.
Khi lần theo luồng, đọc trách nhiệm của từng lớp; phần refactor này giữ truy cập database và ranh giới transaction ở service/repository.

`replaceSections`, `invalidate`, `owned`, `verifyContext`, `assignedCandidate`, cache và callback dọn file vẫn nằm trong service. `AuditService.record` giữ nguyên vì nó ghi DB. Package ở cột util dưới đây tính từ `com.example.intelligent_cv_matching_ai_agent.util`; mỗi liên kết mở file nguồn.

| Service | Util hiện tại | Hàm/nhóm logic |
| --- | --- | --- |
| [AuthService](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/shared/identity/AuthService.java) | [`shared.identity.AuthValidation`](../src/main/java/com/example/intelligent_cv_matching_ai_agent/util/shared/identity/AuthValidation.java) | email, passwordLength |
| [TokenService](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/shared/identity/TokenService.java) | [`shared.identity.TokenSupport`](../src/main/java/com/example/intelligent_cv_matching_ai_agent/util/shared/identity/TokenSupport.java) | signingKey, random/hash, encodeAccessToken/decodeAccessToken |
| [AiService](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/shared/ai/AiService.java) | [`shared.ai.AiResponseData`](../src/main/java/com/example/intelligent_cv_matching_ai_agent/util/shared/ai/AiResponseData.java) | schema, requestedLanguage, validate; array/string/score nội bộ |
| [ResumeService](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/candidate/resume/ResumeService.java) | [`candidate.resume.ResumeContent`](../src/main/java/com/example/intelligent_cv_matching_ai_agent/util/candidate/resume/ResumeContent.java)<br>[`candidate.resume.ResumeInput`](../src/main/java/com/example/intelligent_cv_matching_ai_agent/util/candidate/resume/ResumeInput.java) | canonicalText, contentHash, baseline; title/language/document/UUID/skillSlug |
| [MatchingService](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/candidate/jobs/MatchingService.java) | [`candidate.jobs.JobSkillOverlap`](../src/main/java/com/example/intelligent_cv_matching_ai_agent/util/candidate/jobs/JobSkillOverlap.java) | overlap |
| [CareerToolsService](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/candidate/jobs/CareerToolsService.java) | [`candidate.jobs.CareerToolsSupport`](../src/main/java/com/example/intelligent_cv_matching_ai_agent/util/candidate/jobs/CareerToolsSupport.java) | analysisResponse, requiredAi, languageOf, salary interval/currency |
| [CareerAssistantService](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/candidate/ai/CareerAssistantService.java) | [`candidate.ai.CareerAssistantData`](../src/main/java/com/example/intelligent_cv_matching_ai_agent/util/candidate/ai/CareerAssistantData.java) | message, feedbackRating/feedbackHelpful, citation, contextUuid |
| [AssessmentService](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/candidate/learning/AssessmentService.java) | [`candidate.learning.AssessmentValidator`](../src/main/java/com/example/intelligent_cv_matching_ai_agent/util/candidate/learning/AssessmentValidator.java) | validateQuestions |
| [LearningService](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/candidate/learning/LearningService.java) | [`candidate.learning.RoadmapValidator`](../src/main/java/com/example/intelligent_cv_matching_ai_agent/util/candidate/learning/RoadmapValidator.java) | validateNodes, validateGaps; optionalText nội bộ |
| [LearningReferenceService](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/shared/learning/LearningReferenceService.java) | [`shared.learning.LearningReferenceData`](../src/main/java/com/example/intelligent_cv_matching_ai_agent/util/shared/learning/LearningReferenceData.java) | focusSkills, assessmentFocus, skillPattern, trustedResources |
| [ConsultantEvaluationService](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/consultant/consultation/ConsultantEvaluationService.java) | [`consultant.consultation.ConsultationEvaluationData`](../src/main/java/com/example/intelligent_cv_matching_ai_agent/util/consultant/consultation/ConsultationEvaluationData.java) | suggestion, aiArray/aiStrings/aiText, languageOf, atsResponse |
| [JobService](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/shared/jobs/JobService.java) | [`shared.jobs.JobEmbeddingSupport`](../src/main/java/com/example/intelligent_cv_matching_ai_agent/util/shared/jobs/JobEmbeddingSupport.java) | embeddingText |
| [JobEmbeddingService](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/shared/jobs/JobEmbeddingService.java) | [`shared.jobs.JobEmbeddingSupport`](../src/main/java/com/example/intelligent_cv_matching_ai_agent/util/shared/jobs/JobEmbeddingSupport.java) | embeddingText, usable, safeError |
| [CrawlSettingsService](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/admin/jobs/CrawlSettingsService.java) | [`admin.jobs.CrawlSettingsSupport`](../src/main/java/com/example/intelligent_cv_matching_ai_agent/util/admin/jobs/CrawlSettingsSupport.java) | fromEnvironment/fromDatabase, fixedDelay, normalize; bounded/instant nội bộ |
| [JobCollectionScheduler](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/admin/jobs/JobCollectionScheduler.java) | [`admin.jobs.JobCollectionSupport`](../src/main/java/com/example/intelligent_cv_matching_ai_agent/util/admin/jobs/JobCollectionSupport.java) | leaseName |
| [RagDocumentService](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/admin/rag/RagDocumentService.java) | [`admin.rag.RagDocumentSupport`](../src/main/java/com/example/intelligent_cv_matching_ai_agent/util/admin/rag/RagDocumentSupport.java) | split, safeError |
| [RagCourseService](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/admin/rag/RagCourseService.java) | [`admin.rag.RagCourseSupport`](../src/main/java/com/example/intelligent_cv_matching_ai_agent/util/admin/rag/RagCourseSupport.java) | content, skills, text |

**Delegate giữ tương thích:** `AuthService.email`; `TokenService.random/hash`; `ResumeService.canonicalText`; `MatchingService.overlap`; `AssessmentService.validateQuestions`; `LearningService.validateNodes/validateGaps`; `JobService.embeddingText`; `RagDocumentService.split`. `JobCollectionScheduler.leaseName` giữ delegate phạm vi package. Logic thực nằm trong util; caller/test cũ vẫn gọi được entry trước đây. Helper private đã chuyển không còn thuộc service.

Ví dụ: `ResumeService.create` gọi `ResumeInput.requiredTitle/language/optionalUuid`, chuẩn hóa qua `ResumeData.normalize`, tính `ResumeContent.contentHash(mapper, title, language, raw, data)` rồi mới ghi repository.

Giữ nguyên khác biệt validation: `ResumeInput.language` dùng vi khi giá trị null, nhận vi/en/VI/EN và từ chối giá trị khác; `CareerToolsSupport.languageOf` mặc định vi khi giá trị không phù hợp. Không gộp hai quy tắc này thành một hàm chung.

<a id="doc-reading"></a>

## Cách đọc tài liệu và lần theo code

Nếu mới đọc backend, đi theo thứ tự: AuthFlowService → AuthService → TokenService → ProfileService → ResumeService → AiService/EntitlementService → MatchingService/CareerToolsService. Sau đó đọc Learning/Assessment, consultation/billing, jobs/crawl/worker và admin.

Mỗi phần service có:

1. **Vai trò/đường gọi:** controller hoặc service nào gọi, ai được dùng.
2. **Constructor/dependency:** field nào là repository, service, helper hay cấu hình.
3. **Phương thức:** input/output, luồng thành công, các nhánh khác và lỗi.
4. **Dữ liệu/trạng thái:** ghi bảng nào, thay đổi trạng thái nào và có tác dụng phụ gì.
5. **Cách sử dụng:** route hoặc lịch chạy, mẫu body/điều kiện cần có.

Khi đọc một phương thức:

~~~text
Controller route + request DTO
    → method service
    → util kiểm tra/biến đổi dữ liệu hoặc service chuyên trách
    → repository method và SQL
    → constraint/migration
    → response hoặc exception
~~~

Đừng dừng ở tên repository như update...ById2: nhiều hàm tên gần giống nhưng SQL/điều kiện khác nhau. Mở câu SQL để biết có owner/status/hash/lock và số row kỳ vọng hay không.

Cách tìm nhanh trong IDE hoặc terminal:

~~~powershell
rg -n 'importPrepared|enqueue|indexNow' src/main/java
rg -n 'findJobForUpdate|claimNext' src/main/java
rg -n 'market_job_sources' src/main/resources/db/migration
~~~

Ví dụ cùng `index(id)` nhưng JobService chuyển sang queue worker, còn RagDocumentService chạy synchronous từng chunk. Tên hàm giống nhau không cho biết transaction, retry hay thời điểm gọi AI.

<a id="doc-language"></a>

## Các cú pháp và thành phần code dùng chung

| Cú pháp/thành phần | Cách hiểu trong dự án |
| --- | --- |
| `@Service` | Đăng ký class thành Spring bean; controller/service khác được inject bean này |
| Constructor và `private final` | Dependency truyền từ ngoài vào và không đổi tham chiếu sau khởi tạo |
| `@Autowired` | Chọn constructor để Spring tạo bean khi lớp có nhiều constructor |
| `@Value` | Lấy property của ứng dụng, có fallback ở annotation; application.yaml/env có thể cung cấp giá trị khác fallback |
| `@Valid` | Validate DTO/đối tượng lồng khi framework xử lý request; gọi service trực tiếp không tự chạy mọi validation controller |
| `@Scheduled` | Spring gọi entry theo lịch; cần scheduling được bật; không cần request HTTP |
| `Map<String,Object>` | Một object JSON/SQL row có field khác kiểu; thường phải kiểm/cast trước dùng |
| `List<Map<String,Object>>` | Một danh sách SQL rows hoặc các object trong response |
| `Map.of` | Map không sửa được và không cho Java null; phù hợp các key/value chắc có giá trị |
| `LinkedHashMap` | Map sửa được/cho null, giữ thứ tự insert; dùng fields, snapshot và response bổ sung |
| `List.of/List.copyOf` | List không sửa được; copyOf tạo snapshot danh sách trước xử lý |
| `Optional` | Kết quả có thể thiếu; map/filter/orElse/ifPresent thể hiện các nhánh không cần null thủ công |
| `stream().map/filter/distinct/sorted/toList` | Biến đổi/lọc/loại trùng/sắp tập dữ liệu; không mặc nhiên chạy async |
| `var` | Java suy kiểu local variable từ biểu thức, không phải biến dynamic JavaScript |
| `record` | Kiểu dữ liệu bất biến gọn cho request/snapshot/metadata; accessor được Java sinh |
| `UUID` | ID tài nguyên/actor; client gửi đúng UUID trong path/body |
| `OffsetDateTime/Instant/Timestamp` | Thời điểm ứng dụng/JDBC; cần chuyển cùng hệ quy chiếu để so deadline |
| `BigDecimal` | Tiền/giá/quota liên quan precision; không cộng các currency khác nhau như một loại tiền |
| `ObjectProvider` | Tìm bean model tùy chọn; cho phép ứng dụng chạy khi provider không được cấu hình |
| Jackson `ObjectMapper/JsonMapper` | Serialize/parse JSON; kết quả AI còn phải validate nghiệp vụ sau parse |
| Jsoup | Parse HTML thành text/DOM và HTTP GET nguồn job |
| `PasswordEncoder` | Encode/matches password hash, không dùng plaintext để so DB |
| `Arrays.toString(float[])` | Dạng textual vector; repository cast sang pgvector |
| SLF4J Logger | Log vận hành; khác các bảng audit/usage hoặc state task |
| `ApiException` | Lỗi nghiệp vụ chứa HTTP status, được advice chuyển thành response |

Một số tên trường đầu vào dùng camelCase, nhưng nhiều SQL row/response dùng snake_case. Ví dụ DTO `employmentType` trở thành cột `employment_type`. Với CV JSON cần giữ đúng `structured_data` và schema bên trong, không đổi tên tùy ý.

Các helper thường gặp ngoài thư mục service:

| Code | Vai trò |
| --- | --- |
| [CurrentUser.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/security/shared/CurrentUser.java) | UUID/role/ownership của Authentication hiện tại |
| [SqlStore.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/repository/shared/SqlStore.java) | Bind parameter SQL, map JDBC rows/JSON và insert động với identifiers do server kiểm soát |
| [Values.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/util/shared/Values.java) | text/uuid/required/fields/pageSize/offset dùng lại |
| [FlowSupport.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/util/shared/FlowSupport.java) | Hash/vector và hỗ trợ luồng chung |
| [ResumeData.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/util/shared/resume/ResumeData.java) | Chuẩn hóa/validate CV canonical JSON |
| [ResumeFileExtractor.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/util/shared/resume/ResumeFileExtractor.java) | MIME/signature, PDF/DOCX/TXT/image và OCR khi bật |
| [WebhookVerifier.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/util/shared/billing/WebhookVerifier.java) | Xác minh signature/timestamp webhook trước xử lý payment |
| [GoogleTokenVerifier.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/security/shared/GoogleTokenVerifier.java) | Xác minh Google ID token |
| [JobNormalizer.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/util/shared/jobs/JobNormalizer.java) | Chuẩn hóa job/hash/fingerprint |
| [JobPostingParser.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/util/shared/jobs/JobPostingParser.java) | JobPosting JSON-LD/HTML/skills/metadata |
| [JobUrlDiscovery.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/util/shared/jobs/JobUrlDiscovery.java) | Search URL, detail links và tracking canonicalization |

<a id="doc-boundaries"></a>

## Transaction, quyền và lỗi

### Transaction

| Cơ chế | Hành vi cần hiểu |
| --- | --- |
| `@Transactional` mặc định | Gọi qua Spring proxy; tham gia transaction đang có hoặc mở transaction mới; runtime exception thường rollback |
| `REQUIRES_NEW` | Tạm dừng transaction ngoài, commit/rollback riêng; rollback nghiệp vụ không mặc nhiên rollback quota/usage đã commit riêng |
| `NOT_SUPPORTED` | Không giữ transaction ngoài khi xử lý; worker dùng các transaction ngắn có chủ đích |
| `TransactionTemplate.execute` | Chạy callback trong transaction và trả giá trị |
| `executeWithoutResult` | Transaction callback chỉ có tác dụng ghi/check, không cần giá trị trả |
| `FOR UPDATE` | Khóa row để các lượt ghi cùng đối tượng không chồng nhau |
| `FOR SHARE` | Khóa đọc dùng ở luồng cần giữ điều kiện quyền/assignment trong transaction |
| Advisory lock | Khóa theo khóa nghiệp vụ như membership/fingerprint/URL/config/feature |
| `SKIP LOCKED` | Worker bỏ row instance khác đang khóa, claim công việc khác |
| Lease token/expiry | Quyền tạm xử lý task; token mới ngăn worker cũ publish/completion sai |
| `ON CONFLICT` | Upsert/idempotency dựa trên unique key; cần đọc đúng nhánh update/ignore |

Public method gọi qua bean inject mới đi qua transaction interceptor. Tự gọi method khác cùng class không tự mở transaction mới chỉ vì method đó có annotation. Một số service dùng template rõ ràng để xử lý trường hợp này.

Không phải tất cả lỗi đều cùng rollback: AI call/usage, file system, SMTP hoặc SSE nằm ngoài database theo cách khác nhau. Phần service cụ thể giải thích log nào vẫn giữ sau lỗi, file nào được dọn bằng callback và event nào phát sau commit.

### Quyền và owner

Request HTTP qua [SecurityConfig.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/config/shared/SecurityConfig.java) và bearer filter trước controller. DTO @Valid rồi service kiểm role/owner/trạng thái theo chức năng.

- `/admin/**` cần ADMIN ở SecurityConfig, service vẫn kiểm lại.
- Public GET gồm skill/job/template/tier; không đồng nghĩa mọi method trong module đó public.
- Guest route public ở lớp security nhưng thường cần X-Guest-Session hợp lệ.
- Phần lớn tài nguyên cá nhân lấy owner từ CurrentUser, không tin userId body.
- Package consultant/shared/candidate không tự quyết định quyền; đọc access/owns/requireRole thật.
- Background job dùng actor đã lưu/cấu hình và kiểm identity trong DB thay vì giả Authentication HTTP.

### Lỗi HTTP

[ApiExceptionHandler.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/exception/shared/ApiExceptionHandler.java) chuyển ApiException, validation và lỗi DB đã biết sang ProblemDetail.

| Status | Ý nghĩa thường gặp trong service |
| --- | --- |
| 400 | Input/UUID/JSON/enum/size không hợp lệ, dữ liệu thiếu, deadline/options sai |
| 401 | Thiếu/hết hạn token hoặc guest session, refresh không còn dùng được |
| 403 | Role/trạng thái account/feature không cho phép |
| 404 | Row không có hoặc bị ẩn do ownership/assignment/status |
| 409 | State/prerequisite/duplicate/concurrent operation xung đột |
| 413 | File vượt multipart limit |
| 429 | OTP/IP/attempt/token quota limit |
| 502 | Provider/output AI không đáp ứng contract |
| 503 | Provider/mail/database/cấu hình phụ thuộc chưa sẵn sàng |

Một lỗi RuntimeException chưa được advice map có thể vẫn là 500. Không nên suy mọi lỗi service đều được đổi thành 400/409.

Ví dụ service owner thường trả404 khi tài nguyên của người khác, thay vì xác nhận tài nguyên tồn tại rồi trả403. Với async/index/crawl, HTTP200 có thể chứa FAILED/PARTIAL/QUEUED, cần đọc state trong body.

<a id="svc-authflowservice"></a>

## AuthFlowService

Nguồn: [AuthFlowService.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/shared/identity/AuthFlowService.java).

### Vai trò, nơi gọi và các phụ thuộc

`AuthController` gọi service này cho các API `/api/v1/auth`.
Lớp điều phối thứ tự kiểm tra chống spam rồi chuyển dữ liệu đến lớp xử lý nghiệp vụ.
Nó không trực tiếp truy cập bảng nào và không có `@Transactional`.
Transaction của đăng ký, xác thực, đăng nhập hoặc refresh nằm ở service được gọi.

Constructor nhận:

| Phụ thuộc | Dùng để |
| --- | --- |
| [AuthService](#svc-authservice) | Đăng ký, OTP, đăng nhập email/password. |
| [TokenService](#svc-tokenservice) | Xoay refresh token và thu hồi token. |
| `CurrentUser` | Lấy ID người đang đăng nhập khi logout. |
| `AuthThrottle` | Đếm số lần thao tác theo IP hoặc email. |
| [GoogleLoginService](#svc-googleloginservice) | Xác minh và đăng nhập bằng Google ID token. |

`remoteAddress` lấy từ `HttpServletRequest.getRemoteAddr()` ở controller.
Service không tự đọc `X-Forwarded-For`.
`AuthThrottle` lưu cửa sổ đếm trong `ConcurrentHashMap` của một instance.
Nếu chạy nhiều replica, bộ đếm hiện tại không tự chia sẻ giữa các replica.

### register(RegistrationRequest body, String remoteAddress)

Đầu vào là DTO đăng ký đã qua `@Valid`; đầu ra là map hướng dẫn xác minh email.

1. Gọi `throttle.check("register:" + remoteAddress, 10, 3600)`.
2. Chuyển `email`, `password`, `fullName`, `preferredLanguage` sang `AuthService.register`.
3. Trả nguyên kết quả của service nghiệp vụ.

Vượt 10 lần/IP trong 3.600 giây gây HTTP 429.
Bộ đếm tăng trước nghiệp vụ; đăng ký thất bại vẫn tính một lượt chống spam.
Thành công chưa trả access token: tài khoản email mới còn `PENDING`.

### verify(VerifyEmailRequest body, String remoteAddress)

Nhận email và mã sáu chữ số; trả map thông báo xác minh thành công.

1. Giới hạn key `verify:<IP>` ở 30 lượt/600 giây.
2. Gọi `AuthService.verify(email, code)`.
3. Chuyển kết quả hoặc lỗi lên controller.

Đây là giới hạn IP; số lần thử một mã còn bị giới hạn riêng trong database ở `AuthService`.

### resend(EmailRequest body, String remoteAddress)

Nhận email và trả thông báo gửi mã, hoặc thông báo trung lập nếu tài khoản không phù hợp.

1. Giới hạn key `resend:<IP>` ở 10 lượt/3.600 giây.
2. Gọi `AuthService.resend(email)`.
3. Trả kết quả; không tự kiểm tra tài khoản hoặc tự gửi SMTP.

### login(LoginRequest body, String remoteAddress)

Nhận email/password và trả cặp access/refresh token khi xác thực đúng.

1. Giới hạn IP theo `login-ip:<IP>`: 100 lượt/900 giây.
2. Giới hạn email theo `login-email:<email chuẩn hóa>`: 15 lượt/900 giây.
3. Gọi `AuthService.login`.

Hai giới hạn kết hợp chống thử mật khẩu tập trung vào một tài khoản.
`AuthService.email` làm cho các cách viết hoa/thường của cùng email dùng chung key.
Lượt thử được đếm cả khi password sai hoặc tài khoản chưa active.

### refresh(RefreshTokenRequest body, String remoteAddress)

Nhận `refreshToken`, trả cặp token mới.

1. Giới hạn key `refresh:<IP>` ở 100 lượt/900 giây.
2. Gọi `TokenService.rotate(body.refreshToken())`.
3. Trả kết quả rotation.

Không xác thực refresh bằng JWT: token refresh là chuỗi ngẫu nhiên có tiền tố `r_` và được tra bằng hash.

### logout()

Nhận người dùng qua security context; trả `{"message":"All sessions logged out"}`.

1. Lấy `current.id()`; thiếu xác thực gây HTTP 401.
2. Gọi `TokenService.revokeAll`.
3. Trả thông báo.

Logout thu hồi tất cả bản ghi token chưa thu hồi của người dùng.
Các API protected kiểm tra lại session database nên access token đã cấp cũng ngừng dùng được.

### google(GoogleLoginRequest body, String remoteAddress)

Nhận `idToken` Google; trả cặp token ứng dụng hoặc yêu cầu xác minh email.

1. Giới hạn key `google:<IP>` ở 30 lượt/900 giây.
2. Gọi `GoogleLoginService.login(idToken)`.
3. Trả kết quả.

Google ID token dùng làm bằng chứng danh tính tại bước đăng nhập.
Sau đó client dùng token riêng do ứng dụng phát hành.

### Sử dụng các API xác thực

| API | DTO/body |
| --- | --- |
| `POST /api/v1/auth/register` | `RegistrationRequest`. |
| `POST /api/v1/auth/verify-email` | `VerifyEmailRequest`. |
| `POST /api/v1/auth/resend-code` | `EmailRequest`. |
| `POST /api/v1/auth/login` | `LoginRequest`. |
| `POST /api/v1/auth/refresh` | `RefreshTokenRequest`. |
| `POST /api/v1/auth/logout` | Không cần body; cần Bearer token. |
| `POST /api/v1/auth/google` | `GoogleLoginRequest`. |

Ví dụ đăng ký:

```json
{
  "email": "reader@example.com",
  "password": "ExamplePass123!",
  "fullName": "Nguyễn An",
  "preferredLanguage": "vi"
}
```

Password đăng ký tối thiểu 10 ký tự, tối đa 72 ký tự ở DTO.
Service còn kiểm tra tối đa 72 byte UTF-8.
`preferredLanguage` nhận `vi` hoặc `en`; có thể bỏ để dùng mặc định `vi`.

Ví dụ xác minh:

```json
{
  "email": "reader@example.com",
  "code": "123456"
}
```

Sau đó đăng nhập:

```json
{
  "email": "reader@example.com",
  "password": "ExamplePass123!"
}
```

Refresh gửi `{"refreshToken":"{{refreshToken}}"}`.
Google login gửi `{"idToken":"{{googleIdToken}}"}`.
Các DTO chứa password/token/OTP override `toString()` bằng chuỗi đã che dữ liệu.

<a id="svc-authservice"></a>

## AuthService

Nguồn: [AuthService.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/shared/identity/AuthService.java).

Util đã tách: [AuthValidation.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/util/shared/identity/AuthValidation.java). Xem [ranh giới service/util](#doc-utils).

### Vai trò, phụ thuộc và dữ liệu

[AuthFlowService](#svc-authflowservice) gọi lớp này cho tài khoản email/password.
[GoogleLoginService](#svc-googleloginservice) cũng dùng `resend` khi Google không có thẩm quyền xác nhận địa chỉ email.

Constructor nhận `AuthRepository`, `PasswordEncoder`, [TokenService](#svc-tokenservice),
`ObjectProvider<JavaMailSender>` và ba cấu hình mail/development.
`SecurityConfig` tạo `BCryptPasswordEncoder(12)`, dùng cho password và hash OTP.

Các bảng được đụng tới:

| Bảng | Nội dung |
| --- | --- |
| `users` | Tạo tài khoản, kiểm tra trạng thái, xác minh email, cập nhật lần đăng nhập. |
| `email_verification_codes` | Hash OTP, hạn dùng, số lần thử, thời điểm đã xác minh. |
| `candidate_profiles` | Khởi tạo hồ sơ nghề nghiệp sau xác minh email. |
| `refresh_tokens` | Được ghi gián tiếp qua `TokenService.issue` khi đăng nhập. |

Constructor tạo `dummyHash = encoder.encode(TokenService.random(""))` một lần.
Hash này dùng khi email không tồn tại để login vẫn thực hiện BCrypt.
Nhờ đó nhánh email lạ không bỏ qua công việc kiểm tra password.

### `AuthService.email(String email)` → `AuthValidation.email(String email)`

Method public static của service là delegate. Logic nằm ở `com.example.intelligent_cv_matching_ai_agent.util.shared.identity.AuthValidation.email`.

Đầu vào là email; đầu ra là `trim().toLowerCase(Locale.ROOT)`.

Các lớp auth dùng chung helper để đồng nhất lookup database và key giới hạn email.
`Locale.ROOT` tránh việc chuyển chữ phụ thuộc locale của máy chạy.
Helper không tự xác minh cú pháp email hoặc xử lý `null`; DTO chịu trách nhiệm đầu vào HTTP.

Ví dụ `" Reader@Example.com "` trở thành `"reader@example.com"`.
Nó không thay đổi dấu chấm hoặc phần `+tag` của địa chỉ email.

### `AuthValidation.passwordLength(String password)` — util public static

Hàm thuộc `com.example.intelligent_cv_matching_ai_agent.util.shared.identity.AuthValidation`; register/login gọi trực tiếp.

Nhận password; không trả dữ liệu.

1. Mã hóa password thành byte UTF-8.
2. So sánh chiều dài với 72.
3. Ném HTTP 400 nếu dài hơn giới hạn.

Giới hạn là byte vì BCrypt có giới hạn đầu vào.
Một password gồm ký tự tiếng Việt có thể có số byte lớn hơn số ký tự.
DTO `@Size` và helper này kiểm tra hai điều khác nhau.

### register(String email, String password, String fullName, String language)

Phương thức có `@Transactional`.
Đầu ra là kết quả `issueCode`, không phải session đăng nhập.

1. Kiểm tra số byte password.
2. Chuẩn hóa email.
3. Tra `users`; nếu email đã có bản ghi, ném HTTP 409.
4. Tạo user với password BCrypt, `auth_provider=EMAIL`, `role=CANDIDATE`, `status=PENDING`.
5. Trim họ tên; mặc định ngôn ngữ `vi` nếu `language == null`.
6. Gọi `issueCode` cho user vừa tạo.
7. Trả thông báo và thời gian sống OTP.

Insert user và OTP cùng transaction.
Nếu gửi SMTP thất bại hoặc mail chưa cấu hình ở chế độ cần mail, `ApiException` làm rollback cả hai.
Việc SMTP đã gửi thành công nằm ngoài tính nguyên tử của database transaction;
SMTP không tự rollback nếu một lỗi database xuất hiện sau thao tác gửi.

### resend(String email)

Có `@Transactional`.
Đầu vào là email; đầu ra là thông báo trung lập hoặc thông tin OTP mới.

1. Chuẩn hóa email.
2. Repository chỉ tìm user có `status=PENDING`.
3. Nếu không có, trả cùng thông báo trung lập, không tiết lộ email có tồn tại hay không.
4. Nếu có, gọi `issueCode`.

Tài khoản `ACTIVE`, `SUSPENDED` hoặc đã xóa không đi vào nhánh gửi mã.
Cooldown 60 giây được thực hiện trong `issueCode`.

### issueCode(UUID user, String email) — helper private

Đây là phần tạo, lưu và gửi OTP.
Được gọi bên trong transaction của `register` hoặc `resend`.

1. Tìm bất kỳ OTP nào của user được tạo trong 60 giây gần nhất.
2. Nếu có, ném HTTP 429.
3. Cho hết hạn các OTP chưa xác minh trước đó.
4. Sinh mã từ `SecureRandom.nextInt(1000000)` và định dạng thành sáu chữ số.
5. Lưu BCrypt hash của mã, hạn 10 phút và thời gian tạo.
6. Nếu không bật chế độ trả OTP phát triển, kiểm tra mail và gửi `SimpleMailMessage`.
7. Trả `message`, `expiresIn=600`; có thêm `developmentCode` khi cấu hình development bật.

Đoạn tạo mã trong code:

```java
String code = String.format("%06d", new SecureRandom().nextInt(1000000));
```

Số 42 được biểu diễn thành `"000042"`.
Database giữ `code_hash`, không lưu mã OTP thô.

Các nhánh gửi mail:

| Điều kiện | Hành vi |
| --- | --- |
| `app.auth.expose-development-code=true` | Không gửi SMTP; trả mã trong `developmentCode`. |
| Development code tắt và `app.mail.enabled=false` | HTTP 503 yêu cầu cấu hình mail. |
| Mail bật nhưng không có `JavaMailSender` | HTTP 503 mail chưa cấu hình. |
| Sender ném `RuntimeException` | HTTP 503 gửi thất bại; không đưa lỗi SMTP chi tiết vào response. |
| Mail gửi được | Trả thông báo và `expiresIn`, không trả OTP. |

`ObjectProvider` giúp service được tạo ngay cả khi bean mail không tồn tại.
Mailtrap được dùng thông qua cấu hình SMTP/`JavaMailSender`; service không gọi Mailtrap API trực tiếp.
`mailFrom` lấy từ `app.auth.mail-from`, được `application.yaml` ánh xạ từ `MAIL_FROM`.
Giá trị mặc định ở YAML là `noreply@example.invalid`;
`noreply@edunexus.local` trong annotation chỉ là fallback khi property không được khai báo.

### verify(String email, String code)

Có `@Transactional(noRollbackFor = ApiException.class)`.
Đầu ra là `{"message":"Email verified; you can log in"}`.

1. Chuẩn hóa email.
2. Tìm mã mới nhất chưa xác minh, chưa hết hạn của user `PENDING`.
3. Khóa bản ghi mã bằng `FOR UPDATE OF c`.
4. Nếu `attempt_count >= 5`, trả HTTP 429.
5. Tăng `attempt_count` trước khi so khớp.
6. So mã với BCrypt hash; sai gây HTTP 400.
7. Đánh dấu mã đã xác minh.
8. Chuyển user `PENDING → ACTIVE`, ghi `email_verified_at`.
9. Insert `candidate_profiles` theo kiểu `ON CONFLICT DO NOTHING`.
10. Trả thông báo để client đăng nhập riêng.

`noRollbackFor` giữ lần thử đã tăng khi OTP sai gây `ApiException`.
Nếu dùng rollback mặc định, nhập sai sẽ rollback bộ đếm và giới hạn năm lần mất tác dụng.
Khóa mã giữ các lần xác minh đồng thời không cùng đọc một số lần thử cũ.

Mã đã dùng, hết hạn hoặc không có đều nhận thông báo `Invalid or expired verification code`.
Service không trả token ngay sau xác minh.

### login(String email, String password)

Có `@Transactional`.
Đầu ra là map `accessToken`, `refreshToken`, `tokenType`, `expiresIn`.

1. Kiểm tra chiều dài password theo byte.
2. Chuẩn hóa email.
3. Repository tìm user có provider `EMAIL`, chưa bị soft delete và khóa `FOR NO KEY UPDATE`.
4. Lấy password hash thật; dùng `dummyHash` nếu không tìm thấy.
5. Thực hiện `encoder.matches`.
6. Email không có hoặc password sai: HTTP 401 với cùng thông báo.
7. User chưa `ACTIVE`: HTTP 403.
8. Cập nhật `last_login_at` và `updated_at`.
9. Gọi `TokenService.issue(userId)`.

Tài khoản Google không đăng nhập qua password tại nhánh này.
Khóa account phối hợp với đổi password, suspend và cấp token để giảm race giữa trạng thái tài khoản và session mới.

### Cấu hình và trình tự đọc

| Property | Biến môi trường trong application.yaml | Tác dụng |
| --- | --- | --- |
| `app.auth.expose-development-code` | `AUTH_EXPOSE_DEV_OTP` | Cho đọc OTP trực tiếp khi phát triển; mặc định false. |
| `app.mail.enabled` | `MAIL_ENABLED` | Bật nhánh gửi mail; mặc định false. |
| `app.auth.mail-from` | `MAIL_FROM` | Địa chỉ From của OTP. |

Để test email đăng ký, cấu hình SMTP và bật mail rồi thực hiện chuỗi
`register → verify-email → login`.
Để test local không dùng SMTP, có thể dùng chế độ development code đã nêu;
response lúc đó có mã đọc để gửi vào API verify.
Chi tiết API/body nằm ở [AuthFlowService](#svc-authflowservice).

<a id="svc-tokenservice"></a>

## TokenService

Nguồn: [TokenService.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/shared/identity/TokenService.java).

Util đã tách: [TokenSupport.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/util/shared/identity/TokenSupport.java). Xem [ranh giới service/util](#doc-utils).

### Vai trò, phụ thuộc và mô hình token

Các service auth gọi lớp này để cấp hoặc thu hồi session.
Bộ lọc Bearer xác thực gọi `authenticate`.
[ProfileService](#svc-profileservice) gọi `revokeAll` khi đổi mật khẩu hoặc xóa tài khoản.

Constructor Spring nhận `TokenRepository` và `app.security.jwt-secret`.
Cấu hình runtime bắt buộc ánh xạ secret từ `APP_JWT_SECRET`, không có fallback.
Test cũng phải truyền secret tường minh; không còn constructor chỉ nhận repository.
Constructor từ chối secret trống, dưới 32 byte UTF-8 hoặc placeholder đã biết.
Sinh secret từ ít nhất 32 byte ngẫu nhiên mật mã theo [README](../README.md); độ dài không chứng minh độ ngẫu nhiên.
Secret được dùng nguyên dưới dạng UTF-8, không giải mã Base64, và phải giống nhau giữa các instance.
Lớp sử dụng `NimbusJwtEncoder` và `NimbusJwtDecoder` của Spring Security cho JWT HS256.

Lớp dùng hai bảng:

| Bảng | Mục đích |
| --- | --- |
| `users` | Khóa account, kiểm tra ACTIVE/chưa xóa, đọc role và tên. |
| `refresh_tokens` | Lưu hash và hạn dùng cho cả access token lẫn refresh token. |

Tên bảng `refresh_tokens` có thể gây hiểu nhầm khi đọc:
`save` được dùng cho cả hai loại token.
Access là JWT HS256; refresh là chuỗi ngẫu nhiên `r_...`.
Cả hai cần bản ghi session database còn hiệu lực.

Các method `TokenSupport` dưới đây thuộc `com.example.intelligent_cv_matching_ai_agent.util.shared.identity.TokenSupport`; chỉ random/hash giữ entry tương thích trong service.

### `TokenSupport.signingKey(String secret)` — util

Kiểm secret bắt buộc, không trống, có ít nhất 32 byte UTF-8 và không trùng placeholder đã biết.
Trả `SecretKeySpec` dùng cho HS256; cấu hình không hợp lệ gây lỗi khi khởi tạo service.
Helper kiểm độ dài/cấu hình, không xác minh độ ngẫu nhiên của secret.

### `TokenService.random(String prefix)` → `TokenSupport.random(String prefix)`

Service giữ delegate public static.

Đầu vào là tiền tố; đầu ra là tiền tố nối chuỗi Base64 URL-safe của 32 byte ngẫu nhiên.

1. Tạo mảng 32 byte.
2. Dùng `SecureRandom` static sinh byte.
3. Encode URL-safe không padding.
4. Nối `prefix`.

Phần ngẫu nhiên có 43 ký tự.
Tiền tố `r_` hoặc `g_` làm chuỗi có tổng độ dài 45.
[GuestSessionService](#svc-guestsessionservice) tái sử dụng helper để sinh guest key.

### `TokenService.hash(String value)` → `TokenSupport.hash(String value)`

Service giữ delegate public static.

Nhận chuỗi token/key; trả SHA-256 dạng hexadecimal.

1. Chuyển chuỗi thành UTF-8.
2. Tính digest `MessageDigest.getInstance("SHA-256")`.
3. `HexFormat` đổi 32 byte thành 64 ký tự hex.
4. Nếu thuật toán/digest gặp lỗi bất thường, ném `IllegalStateException`.

Hash cho phép tra token mà không lưu chuỗi token thô.
Hash OTP/password khác với helper này: chúng dùng BCrypt.

### issue(UUID userId)

Có `@Transactional`.
Đầu ra: `accessToken`, `refreshToken`, `tokenType="Bearer"`, `expiresIn=900`.

1. `requireActive(userId)` khóa và xác nhận user còn active.
2. Lấy `issuedAt` có độ chính xác giây, tạo JWT access với `exp = issuedAt + 900 giây`.
3. Tạo refresh qua `random("r_")`.
4. Lưu hash SHA-256 access với `expires_at` đúng bằng `exp` của JWT, hạn 15 phút.
5. Lưu hash SHA-256 refresh, hạn 30 ngày.
6. Trả hai chuỗi raw cho client.

Hai lần save chạy trong cùng transaction.
`jti` của JWT dùng `UUID.randomUUID()`:
nhiều token cấp cho cùng user trong cùng một giây vẫn khác nhau.
Nếu chỉ dùng `sub/iat/exp`, token có thể giống nhau và đụng unique hash.

### save(UUID user, String raw, OffsetDateTime expires) — private

Nhận user, chuỗi token và hạn dùng; không trả dữ liệu.

Insert vào `refresh_tokens`:

- `user_id` là chủ session.
- `token_hash` bằng `hash(raw)`.
- `expires_at` là hạn của loại token.
- `created_at` là thời điểm hiện tại.

Helper không tự phân loại access/refresh bằng một cột riêng.
Loại token được nhận biết ở phía input/hình thức và hàm xử lý.

### requireActive(UUID user) — private

Nhận ID user; không trả giá trị.

Repository tìm `users` với `status=ACTIVE`, `deleted_at IS NULL`,
đồng thời khóa `FOR NO KEY UPDATE`.
Không tìm thấy gây HTTP 401 `Account unavailable`.

Lock account được lấy trước lock refresh token trong rotation.
Thứ tự này đồng bộ với các luồng thay đổi account/thu hồi token để tránh khóa chéo.

### authenticate(String access)

Không có `@Transactional` riêng.
Đầu ra là map user chứa `id`, `role`, `full_name` để tạo authentication context.

1. Từ chối `null` và chuỗi bắt đầu `r_` bằng HTTP 401.
2. Gọi `decodeAccessToken` để kiểm chữ ký HS256, claims, thời hạn và lấy UUID trong `sub`.
3. Tra SHA-256 access trong `refresh_tokens`.
4. SQL chỉ cho session chưa revoked, chưa hết hạn và user `ACTIVE`, chưa xóa.
5. Kiểm `sub` JWT trùng `id` user tìm từ session.
6. Trả map session/user.

Do tra database ở mỗi lần xác thực, logout/suspend/delete có hiệu lực với access token hiện tại.
JWT hợp lệ về chữ ký nhưng không có session còn hoạt động vẫn bị từ chối.
Access token không tồn tại trong bảng hoặc account bị vô hiệu nhận HTTP 401.

### rotate(String refresh)

Có `@Transactional`.
Nhận refresh token cũ; trả cặp token mới.

1. Yêu cầu không `null`, có tiền tố `r_`, dài đúng 45 ký tự.
2. Tra hash để lấy `user_id` của token chưa revoked/chưa hết hạn.
3. Khóa account qua `requireActive`.
4. Đọc lại token với `FOR UPDATE OF t`, điều kiện account active và đối chiếu `user_id` với account đã khóa.
5. Thu hồi token refresh cũ bằng UPDATE có điều kiện chưa revoked/chưa hết hạn và đúng chủ sở hữu;
   yêu cầu số bản ghi cập nhật bằng 1, nếu không trả HTTP 401.
6. Gọi `issue` cấp cặp mới.
7. Trả cặp mới.

Tra lại dưới lock xử lý hai request cùng dùng một refresh:
một request thu hồi token; request đến sau không còn thấy token hợp lệ.
Kiểm tra số bản ghi cập nhật bảo vệ việc sử dụng một lần ngay cả khi trạng thái token thay đổi.
UPDATE kiểm `expires_at > clock_timestamp()` tại thời điểm sử dụng, nên token hết hạn trong lúc chờ lock bị từ chối.
Thu hồi refresh và lưu cặp mới nằm trong cùng transaction; lỗi cấp token mới rollback việc thu hồi.
Refresh cũ chỉ dùng một lần.
Rotation không trực tiếp thu hồi access token cũ; nó vẫn theo hạn/revocation hiện có.
`revokeAll` là luồng thu hồi tất cả session.

### revokeAll(UUID user)

Có `@Transactional`; không trả dữ liệu.

1. Khóa account `FOR NO KEY UPDATE`, không yêu cầu account còn ACTIVE.
2. Đánh dấu `revoked_at` cho mọi token của user còn chưa revoked.

Điểm này cho phép `deleteAccount` thu hồi token sau khi đã chuyển user sang `DELETED`.
Lock vẫn tồn tại đến cuối transaction gọi bên ngoài nếu cùng tham gia transaction.

### `TokenSupport.encodeAccessToken(JwtEncoder encoder, UUID userId, Instant issuedAt, Instant expiresAt)`

Hàm public static nhận encoder từ service.

Nhận ID user, thời điểm phát hành và deadline; trả chuỗi JWT ba phần.

1. Tạo `JwsHeader` với thuật toán `HS256` và type `JWT`.
2. Tạo `JwtClaimsSet` chứa `sub` là UUID user, `jti` UUID ngẫu nhiên, `iat` và `exp`.
3. `NimbusJwtEncoder` serialize JSON, encode và ký token bằng secret đã kiểm tra trong constructor.

`issue` dùng thời điểm có độ chính xác giây cho JWT và hạn access trong database.

Claims được server tạo; client không điều khiển payload tại bước phát hành.
Role không nằm trong payload; role được lấy từ database khi authenticate.

### `TokenSupport.decodeAccessToken(JwtDecoder decoder, String token)`

Hàm public static nhận decoder đã cấu hình từ service.

Đầu ra là UUID user hoặc HTTP 401.

1. `NimbusJwtDecoder` parse JWT và xác minh chữ ký; chỉ chấp nhận `HS256`.
2. Yêu cầu `sub` parse được UUID, có `exp`/`iat`, và `jti` không trống.
3. Kiểm token chưa hết hạn, `nbf` nếu có, `iat` không nằm trong tương lai và `iat < exp`.
4. Trả UUID từ `sub`; JWT hoặc claims không hợp lệ gây HTTP 401.

Validator thời gian dùng clock skew bằng 0; token hết hạn không có khoảng gia hạn.
Thư viện xử lý cấu trúc JSON và chữ ký; lớp không tự ghép JWT hoặc parse claims bằng regex.
Session còn phải vượt qua các điều kiện database trong `authenticate`.

### Cách dùng và quan sát session

Client lấy token từ login/Google/refresh rồi gửi `Authorization: Bearer {{accessToken}}`.
`POST /api/v1/auth/refresh` nhận `{"refreshToken":"{{refreshToken}}"}`.
`POST /api/v1/auth/logout` thu hồi toàn bộ các bản ghi token đang hoạt động.
API quản lý từng bản ghi session nằm ở [ProfileService](#svc-profileservice).

Không dùng token Google làm Bearer cho các API ứng dụng.
Không dùng `r_` refresh hoặc `g_` guest key thay access JWT.

<a id="svc-googleloginservice"></a>

## GoogleLoginService

Nguồn: [GoogleLoginService.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/shared/identity/GoogleLoginService.java).

### Vai trò và các phụ thuộc

[AuthFlowService](#svc-authflowservice) gọi `login` sau khi giới hạn request.
Constructor nhận:

| Phụ thuộc | Vai trò |
| --- | --- |
| `GoogleTokenVerifier` | Decode/xác minh ID token Google và đánh giá quyền xác nhận email. |
| `GoogleLoginRepository` | Tìm/tạo user Google, tạo candidate profile, cập nhật login. |
| [TokenService](#svc-tokenservice) | Cấp token ứng dụng. |
| [AuthService](#svc-authservice) | Gửi OTP nếu email còn phải xác minh. |

Bảng trực tiếp: `users`, `candidate_profiles`.
Bảng gián tiếp: `email_verification_codes` và `refresh_tokens`.

### login(String idToken)

Có `@Transactional`.
Đầu ra là cặp token ứng dụng, thông tin OTP hoặc thông báo cần xác minh email.

1. Gọi `verifier.verify(idToken)`.
2. Chuẩn hóa email trong claim.
3. Tìm tài khoản `auth_provider=GOOGLE` theo `google_sub`.
4. Nếu chưa có user theo subject, kiểm xem email có tài khoản khác chưa.
5. Nếu email đã có tài khoản, HTTP 409; không tự nối hai tài khoản theo email.
6. Nếu là tài khoản mới, tính `authoritativeEmail`.
7. Lấy tên từ claim `name`; fallback email; cắt tối đa 255 ký tự.
8. Insert user Google, role `CANDIDATE`, trạng thái `ACTIVE` hoặc `PENDING`.
9. Với authoritative email, ghi `email_verified_at`.
10. Insert candidate profile.
11. Nếu user mới còn `PENDING`, gọi `auth.resend(email)` và trả ngay.
12. Với user cũ `PENDING`, trả `emailVerificationRequired=true`.
13. Với user cũ khác `ACTIVE`, HTTP 403.
14. Cập nhật login và gọi `sessions.issue(id)` khi active.

Điều kiện xác minh do `GoogleTokenVerifier` thực hiện trước nghiệp vụ:

- Cấu hình `app.auth.google-client-id` phải có; thiếu trả HTTP 503.
- Kiểm chữ ký theo JWK Google, issuer `https://accounts.google.com` và thời hạn.
- Audience phải chứa client ID của ứng dụng.
- Subject không rỗng, tối đa 255 ký tự.
- `email_verified` phải bằng true và email hợp lệ.
- Nếu nhiều audience, `azp` phải trùng client ID.

`authoritativeEmail` true khi email kết thúc `@gmail.com` hoặc có claim `hd`.
Email ngoài các trường hợp đó vẫn cần OTP ứng dụng dù claim `email_verified` là true.
Đây là phân biệt mức tin cậy email trong code hiện hành.

### Sử dụng

`POST /api/v1/auth/google`:

```json
{
  "idToken": "{{googleIdToken}}"
}
```

DTO yêu cầu token không blank, tối đa 10.000 ký tự.
Cấu hình client ID bằng `GOOGLE_CLIENT_ID`.
Client cần lấy Google **ID token** cho đúng client ID.
Response `emailVerificationRequired` cần được xử lý bằng resend/verify-email rồi đăng nhập lại Google.

<a id="svc-profileservice"></a>

## ProfileService

Nguồn: [ProfileService.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/shared/identity/ProfileService.java).

### Vai trò, constructor và bảng

`ProfileController` gọi service qua `/api/v1/me`.
Constructor nhận `ProfileRepository`, `CurrentUser`, [TokenService](#svc-tokenservice), `PasswordEncoder`.
Mọi thao tác chọn chủ thể bằng `user.id()`; không nhận một user ID tùy ý từ body.

| Bảng | Cách dùng |
| --- | --- |
| `users` | Đọc/sửa hồ sơ cá nhân, đổi password, soft delete. |
| `candidate_profiles` | Upsert hồ sơ nghề nghiệp. |
| `candidate_profile_skills`, `skills` | Lưu kỹ năng nghề nghiệp và kiểm skill active. |
| `career_profile_embeddings` | Đánh dấu vector cũ không còn current khi đổi hồ sơ. |
| `roadmaps` | Đánh dấu lộ trình cũ `is_outdated`. |
| `refresh_tokens` | Liệt kê/thu hồi session; revoke toàn bộ khi đổi password/xóa. |

Repository đọc profile cá nhân bằng `UserJpaRepository` rồi map sang DTO response.
Các cập nhật/hồ sơ nghề nghiệp dùng SQL qua `SqlStore`.

### me()

Đầu vào lấy từ security context; đầu ra `PersonalProfileResponse`.

1. Lấy ID người đang đăng nhập.
2. Repository tìm entity `User` theo ID.
3. Không tìm thấy: HTTP 404.
4. `PersonalProfileResponse.from` chuyển entity sang DTO dữ liệu cá nhân.
5. Trả DTO.

Response DTO tránh trả toàn bộ entity, nhất là password hash.
Không có transaction annotation riêng trong method này.

### update(PersonalProfileRequest body)

Đầu ra là profile cá nhân sau cập nhật.

1. Trim `fullName`.
2. Cập nhật `avatarUrl`, `preferredLanguage`, `githubUrl` cho user hiện tại.
3. SQL dùng `COALESCE` để giữ ngôn ngữ cũ nếu input null.
4. Ghi `updated_at`.
5. Gọi `me` và trả DTO.

`avatarUrl` và `githubUrl` null có thể thay giá trị hiện tại thành null.
Vì vậy đây không phải cơ chế PATCH tự bỏ qua mọi field null.
Method không có `@Transactional` riêng; update SQL và đọc sau update là hai thao tác service.

### career()

Đầu ra là map hồ sơ nghề nghiệp kèm `skills`.

1. Lấy `candidate_profiles` của user hiện tại.
2. Nếu chưa có, dùng map tối thiểu `{"user_id":<id>}`.
3. Sao chép vào `LinkedHashMap`.
4. Truy vấn skill nối danh mục `skills`.
5. Gắn `skills` vào response.

Không tạo profile mới ở nhánh đọc.
Các skill trả kèm `skill_id`, tên, slug, trình độ và số năm kinh nghiệm.

### careerUpdate(CareerProfileRequest body)

Có `@Transactional`.
Đầu ra là `career()` sau cập nhật.

1. DTO kiểm một `expectedSalary` tùy chọn, lớn hơn 0 và tối đa 12 chữ số nguyên/2 thập phân; đơn vị theo tháng.
2. Vi phạm hoặc còn gửi trường lương min/max cũ trả HTTP 400.
3. Upsert bản ghi profile tối thiểu theo user ID.
4. Cập nhật job hiện tại, mục tiêu, kinh nghiệm, lương, địa điểm, workplace/employment type và summary.
5. Serialize `preferences` thành JSON để ghi `preferences_json`.
6. Nếu `skills != null`, kiểm không có `skillId` trùng.
7. Xóa toàn bộ skill nghề nghiệp trước đó.
8. Với mỗi skill mới, kiểm ID có trong danh mục đang active.
9. Insert từng `candidate_profile_skills`.
10. Đặt tất cả career embedding của user `is_current=false`.
11. Đặt các roadmap của user `is_outdated=true`.
12. Trả hồ sơ và skill mới.

`skills=null` giữ danh sách skill cũ.
`skills=[]` xóa danh sách skill cũ.
Nếu một skill không hợp lệ, transaction rollback cả profile, việc xóa skill và các cờ invalidation.

Đoạn kiểm trùng trong code:

```java
if (body.skills().stream().map(CareerSkillRequest::skillId).distinct().count()
        != body.skills().size())
    throw ApiException.badRequest("Duplicate skill IDs");
```

`stream → map → distinct → count` so số ID khác nhau với số phần tử.
Không tạo vector mới trực tiếp ở method này; nó chỉ làm vector cũ mất hiệu lực.
Matching/roadmap phải sử dụng dữ liệu mới hoặc xử lý dấu outdated tương ứng.

### deleteAccount()

Có `@Transactional`.
Đầu ra `{"message":"Account closed"}`.

1. Lấy PostgreSQL transaction advisory lock `ACCOUNT_ADMIN_MEMBERSHIP`.
2. Đọc role hiện tại từ database.
3. Nếu user là admin, khóa danh sách admin active.
4. Kiểm còn admin active khác với chính user.
5. Nếu đây là admin active cuối cùng, HTTP 409.
6. Soft delete user: `status=DELETED`, `deleted_at=now()`.
7. Gọi `tokens.revokeAll(user.id())`.
8. Trả thông báo.

Role lấy từ database ở bước bảo vệ admin cuối thay vì chỉ tin role trong security context.
Advisory lock phối hợp với các luồng thay đổi membership admin.
Đây là soft delete; method không tự xóa vật lý tất cả CV/payment/history.

### password(PasswordChangeRequest body)

Có `@Transactional`.
Đầu ra `{"message":"Password changed; log in again"}`.

1. Kiểm cả password cũ/mới không vượt 72 byte UTF-8.
2. Khóa user `ACTIVE` bằng `FOR UPDATE`.
3. Yêu cầu `auth_provider=EMAIL` và có password hash.
4. Nếu dùng Google/provider khác, HTTP 400.
5. So password cũ với BCrypt hash.
6. Sai password cũ: HTTP 401.
7. Encode và ghi password mới.
8. Thu hồi mọi token của user.
9. Trả thông báo đăng nhập lại.

DTO yêu cầu password mới dài 10–72 ký tự.
Sau thành công, access token đang gọi API này cũng đã bị revoke qua bảng session.
Không có bước tự cấp session mới.

### sessions()

Đầu ra là danh sách bản ghi token hiện còn hiệu lực của user.

Repository chọn `id`, `created_at`, `expires_at`,
lọc chưa revoked/chưa hết hạn, sort mới nhất và giới hạn 100.
Không trả hash hoặc token raw.
Vì bảng chứa cả access và refresh, danh sách này có thể có hai bản ghi từ một lần login.

### revokeSession(UUID id)

Nhận ID **bản ghi** `refresh_tokens`; trả thông báo thu hồi.

1. Repository đọc bản ghi có đúng `id` và `user_id` hiện tại.
2. Không tồn tại/không thuộc user: HTTP 404.
3. Ghi `revoked_at` cho đúng bản ghi.
4. Trả `Session revoked`.

Method chỉ thu hồi một row.
Nếu muốn logout tất cả access/refresh còn hiệu lực, dùng `/auth/logout`.

### API và ví dụ

| API | Method |
| --- | --- |
| `GET /api/v1/me` | `me`. |
| `PUT /api/v1/me` | `update`. |
| `GET /api/v1/me/career-profile` | `career`. |
| `PUT /api/v1/me/career-profile` | `careerUpdate`. |
| `DELETE /api/v1/me` | `deleteAccount`. |
| `PUT /api/v1/me/password` | `password`. |
| `GET /api/v1/me/sessions` | `sessions`. |
| `DELETE /api/v1/me/sessions/{id}` | `revokeSession`. |

Sửa hồ sơ cá nhân:

```json
{
  "fullName": "Nguyễn An",
  "avatarUrl": null,
  "preferredLanguage": "vi",
  "githubUrl": "https://github.com/example"
}
```

Sửa nghề nghiệp, dùng ID skill đã có trong danh mục:

```json
{
  "targetJobTitle": "Java Backend Developer",
  "yearsExperience": 1.5,
  "expectedSalary": 7500000,
  "salaryCurrency": "VND",
  "preferredLocation": "Hồ Chí Minh",
  "workplacePreference": "HYBRID",
  "employmentType": "FULL_TIME",
  "profileSummary": "Có kinh nghiệm Java và Spring Boot.",
  "preferences": {"industries": ["IT"]},
  "skills": [
    {
      "skillId": "{{javaSkillId}}",
      "proficiencyLevel": "INTERMEDIATE",
      "yearsExperience": 1.5
    }
  ]
}
```

Các trường nghề nghiệp phần lớn optional; giới hạn nằm ở `CareerProfileRequest`.
Số năm 0–99,9; salary không âm; currency nhận `VND|USD|EUR`.
Body đổi password dùng `currentPassword` và `newPassword`.

<a id="svc-guestsessionservice"></a>

## GuestSessionService

Nguồn: [GuestSessionService.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/guest/identity/GuestSessionService.java).

### Vai trò và các phụ thuộc

`GuestController` gọi service này để tạo session thử nghiệm và đọc dữ liệu demo.
Constructor nhận `GuestSessionRepository`, [GuestAccess](#svc-guestaccess), `AuthThrottle`
và `app.guest.token-limit`.
Cấu hình ánh xạ `GUEST_TOKEN_LIMIT`, mặc định 5.000.

Bảng `guest_sessions` được ghi khi tạo session.
`resumes` và `resume_templates` được đọc ở API demo/template.
Các method của lớp không có `@Transactional` riêng.

### create(String remoteAddress)

Đầu vào là IP được controller cung cấp; đầu ra gồm ID, session key raw, quota và thời hạn.

1. Giới hạn `guest:<IP>` ở 3 lượt/86.400 giây.
2. Sinh key qua `TokenService.random("g_")`.
3. Đặt hạn session sau 24 giờ.
4. Insert `guest_sessions` với hash của key, `token_limit`, `token_used=0`.
5. Trả `id`, `sessionKey` raw, `tokenLimit`, `tokenUsed`, `expiresAt`.

Chỉ lúc tạo session service trả key raw.
Database giữ SHA-256 trong cột `session_key`.
Lượt tạo thất bại sau throttle vẫn đã được đếm vào giới hạn IP.

### me(String key)

Đầu vào là guest key; đầu ra là row session đã bỏ hash key.

1. Gọi `guest.require(key)` để kiểm định dạng, hạn và trạng thái.
2. Sao chép row vào `LinkedHashMap`.
3. Xóa trường `session_key`.
4. Trả map còn lại, gồm quota/expiry.

Không tạo session mới và không trừ quota ở API đọc này.

### demos(String key)

Đầu ra danh sách CV demo.

1. Xác nhận guest session hợp lệ.
2. Query `resumes` với `is_demo=true`, `status=READY`.
3. Chọn `id`, `title`, `language`, `structured_data`.
4. Sort theo title, tối đa 20.

Method dùng dữ liệu demo của hệ thống.
Đọc danh sách không thực hiện AI và không trừ trial token.

### templates(String key)

Đầu ra danh sách template cho trial.

1. Xác nhận session hợp lệ.
2. Query template `is_active=true` và `is_trial_enabled=true`.
3. Sort theo tên.
4. Trả danh sách.

Template active nhưng trial chưa bật không xuất hiện.
Điều kiện tương tự được kiểm lại khi `GuestTrialService.build` nhận một ID template.

### Sử dụng

`POST /api/v1/guest/sessions` không cần body.
Lưu `sessionKey` vào biến Postman `guestSession`.
Các API sau gửi header `X-Guest-Session: {{guestSession}}`:

| API | Method |
| --- | --- |
| `GET /api/v1/guest/sessions/me` | `me`. |
| `GET /api/v1/guest/demo-resumes` | `demos`. |
| `GET /api/v1/guest/templates` | `templates`. |

Guest key khác với access token.
Có key hợp lệ chưa đồng nghĩa còn đủ quota cho AI; kiểm quota nằm ở [GuestAccess](#svc-guestaccess).

<a id="svc-guestaccess"></a>

## GuestAccess

Nguồn: [GuestAccess.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/guest/identity/GuestAccess.java).

### Vai trò và dữ liệu

Constructor nhận `GuestAccessRepository`.
[GuestSessionService](#svc-guestsessionservice), [GuestTrialService](#svc-guesttrialservice),
`AiService` và các luồng guest dùng lớp này để kiểm session và hạch toán quota.

| Bảng | Cách dùng |
| --- | --- |
| `guest_sessions` | Kiểm key/expiry/converted, tăng hoặc hoàn token đã dùng. |
| `trial_usage_events` | Lưu feature, ngân sách/chi phí, trạng thái PROCESSING/COMPLETED/FAILED. |

`reserve`, `consume`, `settle` dùng `Propagation.REQUIRES_NEW`.
Mỗi thao tác quota có transaction độc lập với công việc đang gọi.
Điều này giúp quota đã reserve/settle không mất chỉ vì transaction lưu kết quả nghiệp vụ rollback.

### require(String key)

Đầu ra là row guest session.

1. Yêu cầu key không null, bắt đầu `g_`, dài đúng 45 ký tự.
2. Sai hình thức: HTTP 401 `X-Guest-Session header required`.
3. Tính hash key bằng `TokenService.hash`.
4. Tra session có `converted_user_id IS NULL` và `expires_at > now()`.
5. Không có session hợp lệ: HTTP 401 `Guest session expired`.
6. Trả row session.

Session đã được chuyển thành tài khoản không dùng tiếp như guest ở lookup này.
Method không kiểm quota còn bao nhiêu vì các API đọc không cần bị chặn bởi quota AI.

### id(String key)

Nhận guest key; trả UUID của session.
Chỉ là `return (UUID) require(key).get("id")`.
Mọi caller của `id` được hưởng cùng kiểm định dạng/expiry/converted của `require`.

### consume(String key, String feature, int tokens, UUID demoResume)

Có `REQUIRES_NEW`; không trả dữ liệu.

1. Gọi `id(key)` để xác thực session.
2. Gọi `charge(..., "COMPLETED")`.
3. Ghi chi phí và event hoàn tất ngay.

Dùng khi biết chi phí cố định và không cần hai bước reserve/settle.
Lớp không tự suy ra token theo feature; caller cung cấp số lượng.

### reserve(UUID guestId, String feature, int tokens, UUID demoResume)

Có `REQUIRES_NEW`.
Đầu ra là UUID `trial_usage_events` cần dùng để settle.

1. Gọi `charge` với `status=PROCESSING`.
2. Tăng `token_used` ngay nếu còn đủ quota.
3. Trả event ID.

Reserve giữ trước một ngân sách tối đa.
Quota đủ lúc đọc session không đảm bảo còn đủ lúc reserve;
SQL update nguyên tử xử lý những request chạy đồng thời.

### charge(UUID guestId, String feature, int tokens, UUID demoResume, String status) — private

Đầu ra là event ID.

1. Từ chối số token âm bằng HTTP 400.
2. Cập nhật `token_used=token_used+tokens`.
3. Điều kiện SQL yêu cầu chưa hết hạn, chưa converted và tổng dùng không vượt token limit.
4. Nếu số row update khác 1, HTTP 429 `Trial limit reached`.
5. Tạo event có guest ID, feature code, token dùng, status và thời gian.
6. Thêm `demo_resume_id` khi caller cung cấp ID.
7. Insert `trial_usage_events`.
8. Trả ID event.

Câu SQL thể hiện điều kiện quota:

```sql
UPDATE guest_sessions
SET token_used = token_used + ?
WHERE id = ?
  AND token_used + ? <= token_limit
  AND expires_at > now()
  AND converted_user_id IS NULL
```

Đây là kiểm và ghi trong một câu update.
Hai request không thể cùng vượt quota chỉ vì cả hai từng đọc một số dư cũ.
Nếu insert event lỗi, transaction của caller quota rollback cả update.

### settle(UUID eventId, int actualTokens, boolean success)

Có `REQUIRES_NEW`; không trả dữ liệu.

1. Khóa event bằng `FOR UPDATE`.
2. Tính trạng thái cuối: thành công `COMPLETED`, thất bại `FAILED`.
3. Nếu event đã final, trả ngay khi trạng thái và token thực tế trùng yêu cầu.
4. Nếu event đã final nhưng khác yêu cầu, HTTP 409.
5. Yêu cầu `0 <= actualTokens <= reserved`; sai trả HTTP 400.
6. Tính `refund = reserved - actualTokens`.
7. Giảm `guest_sessions.token_used` đi refund; SQL yêu cầu còn đủ số token để hoàn.
8. Nếu không update đúng một row, HTTP 409 về accounting.
9. Cập nhật event token thực tế và trạng thái cuối.

Tính idempotent cho phép lặp đúng cùng kết quả settle mà không hoàn token hai lần.
`success=false` không mặc nhiên miễn phí: caller vẫn có thể settle một chi phí >0 nếu provider đã trả response.
`AiService` quyết định cách tính tiêu thụ từ usage/provider response.

### Ví dụ ngân sách

Nếu đã dùng 200/5.000 và reserve 1.000, `token_used` trở thành 1.200.
Nếu thực tế dùng 300, settle hoàn 700, quota dùng còn 500.
Nếu provider chưa trả response và caller settle 0/false, hoàn toàn bộ 1.000.
Nếu provider đã trả nhưng không cung cấp usage, `AiService` có thể giữ chi phí ngân sách đã reserve.

Không có API HTTP riêng cho `reserve/settle`.
Client chỉ quan sát `GET /api/v1/guest/sessions/me` trước/sau thao tác trial.

<a id="svc-guesttrialservice"></a>

## GuestTrialService

Nguồn: [GuestTrialService.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/guest/ai/GuestTrialService.java).

### Vai trò, constructor và dữ liệu

`GuestTrialController` gọi lớp này ở `/api/v1/guest/trials`.
Constructor nhận `GuestTrialRepository`, [GuestAccess](#svc-guestaccess), `AiService`, `ResumeService`.

Bảng trực tiếp:

| Bảng | Vai trò |
| --- | --- |
| `resume_templates` | Template active và được bật trial. |
| `resumes` | CV demo READY, không có user owner ở bước lấy demo. |
| `market_job_posts`, `market_job_skills` | Danh sách job để demo recommendation. |
| `job_matching_runs` | Ghi lần gợi ý thử nghiệm. |
| `matching_run_resumes` | Liên kết run với CV demo. |
| `job_match_results` | Lưu top kết quả và giải thích. |

Quota/event guest được ghi gián tiếp qua `GuestAccess`.
Usage model được ghi qua `AiService`/`AiUsageRecorder` trong nhánh build.

### build(String key, GuestBuildRequest request)

Không có `@Transactional` riêng.
Đầu ra là object chứa `trial=true`, `template_id`, `structured_data` và lời mời đăng ký.

1. Xác nhận guest session và lấy UUID.
2. Lấy template theo `template_id`; query chỉ cho active và trial-enabled.
3. Gọi `demo` cho `demo_resume_id`.
4. Tạo input gồm template, structured data của demo, `trial=true` và ngôn ngữ template.
5. Gọi `ai.generateForGuest("CV_BUILD", input, guestId)`.
6. Chuẩn hóa output bằng `ResumeData.normalize`.
7. Trả CV thử nghiệm và lời mời tạo/lưu CV cá nhân sau khi đăng ký.

Input sử dụng facts CV demo và template được phép.
DTO `@JsonIgnoreProperties(ignoreUnknown=true)` bỏ field không biết;
gửi thêm CV cá nhân vào body không làm luồng này dùng CV đó.
Không insert một CV cá nhân của guest vào `resumes` ở method này.

Build cần chat provider thật:
AI tắt hoặc chưa có provider gây HTTP 503.
`AiService` reserve guest quota theo ngân sách prompt/output, gọi model, validate JSON rồi settle.
Quota đã settle chạy transaction độc lập với caller.
Lỗi provider hoặc output không hợp lệ được `AiService` chuyển thành HTTP 502.

### recommend(String key, GuestRecommendationRequest request)

Có `@Transactional`.
Đầu ra chứa `matching_run_id`, `mode=SKILL_OVERLAP`, `trial=true`, tối đa ba results và lời mời đăng ký.

1. Xác nhận guest key và lấy guest UUID.
2. Lấy demo CV theo `demo_resume_id`.
3. Reserve chi phí cố định 100 trial credit cho `JOB_RECOMMENDATION`.
4. Query tối đa 500 job `status=ACTIVE`, mới nhất theo collected time, kèm skill.
5. Gọi `resumes.canonicalText(demo)` để lấy văn bản chuẩn.
6. Với mỗi job, gọi `MatchingService.overlap(text, job)`.
7. Gắn comparison và score vào bản sao map job.
8. Sort score giảm dần; hòa điểm sort theo ID.
9. Insert run `COMPLETED`, provider `RULE_BASED`, model `skill-overlap-v1`.
10. Lưu snapshot mode/demo ID và liên kết run-resume.
11. Lấy tối đa ba job đầu.
12. Insert từng result với điểm, rank, reason, strengths và skill gaps.
13. Settle 100 token, success true.
14. Trả response.

Nếu có `RuntimeException` trong khối tính/lưu, settle 0/false rồi ném lại lỗi.
Run/results nằm trong transaction nghiệp vụ và rollback khi lỗi.
Reservation/refund nằm ở `REQUIRES_NEW` của `GuestAccess` và vẫn được giữ.

Nhánh này là rule-based, không gọi LLM hoặc embedding.
Nó có thể chạy khi chat provider tắt nếu đủ quota và có demo/job hợp lệ.
Query hiện tại của repository lọc `status=ACTIVE`; không có điều kiện `valid_through` riêng.
Việc xử lý hết hạn ở pipeline job cần chuyển status đúng để preview này loại job cũ.

Thuật toán xếp hạng trong code:

```java
ranked.sort(
    Comparator.<Map<String, Object>>comparingDouble(
        row -> ((Number) row.get("score")).doubleValue()
    ).reversed().thenComparing(row -> row.get("id").toString())
);
```

`thenComparing` tạo thứ tự ổn định khi nhiều job cùng điểm.
Top 3 cố định trong service; request không có trường tùy chỉnh limit.

### demo(UUID id) — helper private

Nhận ID resume; trả row CV demo.

Repository yêu cầu đồng thời:

- `is_demo=true`.
- `user_id IS NULL`.
- `status=READY`.

CV cá nhân của user hoặc CV chưa ready không đi qua helper này.
Không tìm được row phù hợp sẽ gây lỗi not-found từ lớp lưu trữ.

### API và ví dụ

Cả hai API gửi `X-Guest-Session: {{guestSession}}`.

`POST /api/v1/guest/trials/build`:

```json
{
  "template_id": "{{trialTemplateId}}",
  "demo_resume_id": "{{demoResumeId}}"
}
```

`POST /api/v1/guest/trials/recommendations`:

```json
{
  "demo_resume_id": "{{demoResumeId}}"
}
```

Tên field của hai DTO này là **snake_case** do `@JsonProperty`.
Lấy ID từ `GET /guest/templates` và `GET /guest/demo-resumes`.
Đọc lại `GET /guest/sessions/me` để quan sát quota thay đổi.

<a id="svc-announcementservice"></a>

## AnnouncementService

Nguồn: [AnnouncementService.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/shared/announcement/AnnouncementService.java).

### Vai trò, các phụ thuộc và trạng thái

`AdminAnnouncementController` gọi các thao tác quản trị.
`AnnouncementController` gọi đọc thông báo và notification của người hiện tại.
Constructor nhận `AnnouncementRepository`, `CurrentUser`, `AuditService`.

| Bảng | Cách dùng |
| --- | --- |
| `announcements` | Nội dung, trạng thái DRAFT/PUBLISHED/ARCHIVED, publish/expiry. |
| `announcement_targets` | Audience ALL, ROLE hoặc USER. |
| `users` | Kiểm người nhận active, lấy role hiện tại. |
| `notifications` | Notification tạo khi publish và cờ đọc. |
| `audit_logs` | Ghi gián tiếp qua AuditService cho thao tác admin. |

Luồng chính: `create DRAFT → edit khi DRAFT → publish PUBLISHED → archive ARCHIVED`.
Không có luồng sửa nội dung announcement đã publish ở service này.

### visible()

Đầu ra là danh sách announcement người hiện tại được xem.

Repository lọc:

1. `status=PUBLISHED` và `publish_at <= now()`.
2. Chưa hết hạn, hoặc không có hạn.
3. Audience `ALL`, đúng role hiện tại hoặc target đúng user ID.
4. `DISTINCT` tránh một announcement xuất hiện nhiều lần vì nhiều target.
5. Sort publish time giảm dần, tối đa 100.

Method lấy `user.id()` nên cần đăng nhập.
Audience role được xét theo database user tại thời điểm đọc.

### all()

Đầu ra danh sách announcement quản trị, tối đa 100 mới nhất.

1. `user.requireRole("ADMIN")`.
2. Repository trả tất cả trạng thái, sort theo created time.

Thiếu xác thực: HTTP 401; sai role: HTTP 403.
Danh sách không gắn targets như response `detail` nội bộ.

### create(AnnouncementRequest body)

Có `@Transactional`.
Đầu ra là announcement mới có `targets`.

1. Require admin.
2. Tạo map fields gồm creator ID, title/content, expiry và timestamps.
3. Đặt status `DRAFT`.
4. Insert announcement.
5. Gọi `targets(id, body.targets())`.
6. Đọc lại bằng `detail`.
7. Ghi audit `CREATE_ANNOUNCEMENT`.
8. Trả response.

Thông báo draft chưa tạo notification người nhận.
Nếu target không hợp lệ hoặc audit lỗi, transaction rollback thao tác tạo.

### edit(UUID id, AnnouncementRequest body)

Có `@Transactional`.
Đầu ra announcement/targets sau sửa.

1. Require admin.
2. Khóa announcement `FOR UPDATE`.
3. Chỉ chấp nhận `DRAFT`; trạng thái khác gây HTTP 409.
4. Update title/content/expiry.
5. Xóa toàn bộ target cũ.
6. Insert target mới qua helper `targets`.
7. Đọc lại detail.
8. Ghi audit `UPDATE_ANNOUNCEMENT` với before/after.
9. Trả dữ liệu mới.

Đây là thay thế toàn bộ danh sách target.
Việc xóa target và insert lại cùng transaction tránh trạng thái thiếu audience khi một target lỗi.

### targets(UUID id, List<AnnouncementTargetRequest> targets) — private

Không trả giá trị.

Với mỗi target:

1. Nếu `type=ROLE` mà thiếu `role`, HTTP 400.
2. Nếu `type=USER` mà thiếu `userId`, HTTP 400.
3. Tạo fields `announcement_id` và `target_type`.
4. Với ROLE, ghi `target_role`.
5. Với USER, kiểm user ID thuộc account active rồi ghi `target_user_id`.
6. Insert target.

DTO giới hạn type `ALL|ROLE|USER` và role `CANDIDATE|CAREER_CONSULTANT|ADMIN`.
Body phải có ít nhất một target, tối đa 100.
Type ALL không cần role/userId.
Helper không sử dụng role/userId thừa của type khác.

### detail(UUID id) — private

Đầu ra là map announcement có trường `targets`.

1. Đọc announcement.
2. Sao chép vào `LinkedHashMap`.
3. Query targets theo announcement ID.
4. Gắn vào response.

Helper không expose API detail riêng và không tự kiểm role.
Các caller admin đã kiểm role trước khi dùng.

### publish(UUID id)

Có `@Transactional`.
Đầu ra là announcement/targets sau publish.

1. Require admin.
2. Khóa announcement, query thêm cờ `is_expired`.
3. Yêu cầu status DRAFT; sai gây HTTP 409.
4. Nếu đã hết hạn, HTTP 400 yêu cầu sửa expiry.
5. Nếu không có target, HTTP 400 yêu cầu chọn audience.
6. Update status `PUBLISHED`, `publish_at=now()`.
7. Insert notifications cho các user active phù hợp với audience.
8. Đọc detail và ghi audit `PUBLISH_ANNOUNCEMENT`.
9. Trả dữ liệu.

Query insert dùng `SELECT DISTINCT u.id`, tránh một user nhận hai row do trùng audience.
Notification được tạo trong database, không gửi email.
Method gọi `updateNotificationsById` nhưng repository thực chất dùng `INSERT ... SELECT`.

### archive(UUID id)

Có `@Transactional`.
Đầu ra `{"message":"Announcement archived"}`.

1. Require admin.
2. Đọc before.
3. Update status `ARCHIVED` và updated time.
4. Ghi audit `ARCHIVE_ANNOUNCEMENT`.
5. Trả thông báo.

Method không đặt điều kiện chỉ archive từ một trạng thái cụ thể.
Announcement archived không còn trong `visible`.
Notification đã tạo trước đó không được xóa ở method này.

### notifications(boolean unreadOnly)

Đầu ra danh sách notification của user hiện tại, tối đa 100 mới nhất.

SQL luôn lọc `user_id=user.id()`.
Nếu `unreadOnly=true`, lọc thêm `is_read=false`.
Nếu false, trả cả đã đọc/chưa đọc.
Danh sách có thể chứa ANNOUNCEMENT và các loại notification do service khác tạo.

### read(UUID id)

Nhận ID notification; trả `{"message":"Notification read"}`.

1. Kiểm row notification thuộc user hiện tại.
2. Không thuộc/không có: HTTP 404.
3. Set `is_read=true`.
4. Ghi `read_at=COALESCE(read_at,now())`.
5. Trả thông báo.

Đọc lại cùng notification giữ thời gian đọc đầu tiên.
Method không có transaction annotation riêng; kiểm ownership vẫn được lặp trong SQL update.

### readAll()

Đầu ra `{"message":"Notifications read"}`.

Update notification của user hiện tại đang chưa đọc:
`is_read=true`, `read_at=now()`.
Không ảnh hưởng notification của user khác.
Không require admin.

### API và ví dụ

| API | Method |
| --- | --- |
| `GET /api/v1/admin/announcements` | `all`. |
| `POST /api/v1/admin/announcements` | `create`. |
| `PUT /api/v1/admin/announcements/{id}` | `edit`. |
| `POST /api/v1/admin/announcements/{id}/publish` | `publish`. |
| `POST /api/v1/admin/announcements/{id}/archive` | `archive`. |
| `GET /api/v1/announcements` | `visible`. |
| `GET /api/v1/notifications?unreadOnly=true` | `notifications`. |
| `PATCH /api/v1/notifications/{id}/read` | `read`. |
| `POST /api/v1/notifications/read-all` | `readAll`. |

Body tạo/sửa:

```json
{
  "title": "Lịch bảo trì",
  "content": "Hệ thống bảo trì vào tối thứ Bảy.",
  "expiresAt": null,
  "targets": [
    {"type": "ROLE", "role": "CANDIDATE"}
  ]
}
```

`expiresAt` có thể null; nếu cung cấp phải ở tương lai theo DTO `@Future`.
Tạo xong gọi API publish để người dùng thấy và nhận notification.
Để nhắm một người, dùng `{"type":"USER","userId":"{{targetUserId}}"}`.
Để nhắm toàn bộ, dùng `{"type":"ALL"}`.

<a id="svc-supportservice"></a>

## SupportService

Nguồn: [SupportService.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/shared/support/SupportService.java).

### Vai trò, phụ thuộc và dữ liệu

`SupportController` gọi các API list/create/detail/message.
`AdminSupportController` gọi API update.
Cả hai controller dùng base path `/api/v1/support/tickets`.
Chữ `Admin` trong tên controller không có nghĩa route bắt đầu `/admin`.

Constructor nhận `SupportRepository`, `CurrentUser`, `AuditService`.

| Bảng | Vai trò |
| --- | --- |
| `support_tickets` | Owner, subject/category/description, status/priority/admin phụ trách. |
| `support_ticket_messages` | Lịch sử tin nhắn với sender. |
| `users` | Kiểm admin được assign, đọc tên sender. |
| `notifications` | Thông báo SUPPORT_REPLY cho chủ ticket. |
| `audit_logs` | Audit cập nhật ticket của admin. |

Status body nhận `OPEN|IN_PROGRESS|RESOLVED|CLOSED`.
Priority nhận `LOW|NORMAL|HIGH|URGENT`.
Ticket mới bắt đầu `OPEN`, `NORMAL`.

### list()

Đầu ra danh sách ticket, tối đa 100 mới nhất.

1. Nếu user có role ADMIN, query tất cả ticket.
2. Nếu không, query ticket có `user_id` bằng user hiện tại.
3. Trả list.

Service áp dụng ownership cho người dùng thường.
Không thêm role check CANDIDATE trong method; security route và người đăng nhập quyết định access chung.

### create(SupportTicketRequest body)

Không có `@Transactional` riêng.
Đầu ra ticket mới có `messages`.

1. Lấy user ID hiện tại.
2. Insert subject, category, description.
3. Đặt status OPEN, priority NORMAL và timestamps.
4. Gọi `detail(id)`.
5. Trả ticket với danh sách messages ban đầu rỗng.

Body không nhận user owner hoặc assigned admin.
Caller không thể tạo ticket dưới danh tính user khác bằng cách thêm field owner.
Method không ghi audit hoặc gửi mail khi mở ticket.

### owned(UUID id) — private overload

Nhận ticket ID; trả row sau kiểm ownership.
Ủy quyền sang `owned(id, false)`.
Dùng ở các thao tác đọc không cần khóa row.

### owned(UUID id, boolean lock) — private overload

1. Nếu `lock=true`, repository đọc ticket `FOR UPDATE`.
2. Nếu false, đọc ticket thông thường.
3. Không có ticket: HTTP 404 từ persistence.
4. Nếu user không phải admin, gọi `user.owns(ticket, "user_id")`.
5. Không đúng owner: HTTP 404.
6. Trả ticket.

Trả 404 cho ticket của người khác giúp không tiết lộ tài nguyên tồn tại.
Admin bỏ qua ownership để hỗ trợ người dùng.

### detail(UUID id)

Đầu ra ticket kèm messages.

1. Gọi `owned(id)`.
2. Sao chép row sang `LinkedHashMap`.
3. Query messages theo ticket ID, nối `users` để lấy `full_name` sender.
4. Sort created time tăng dần, tối đa 500.
5. Gắn `messages` và trả response.

Query messages chỉ chạy sau khi ownership/admin access đã được kiểm.

### message(UUID id, SupportMessageRequest body)

Có `@Transactional`.
Đầu ra row message vừa insert.

1. Gọi `owned(id, true)` để khóa và kiểm quyền.
2. Nếu status CLOSED, HTTP 409.
3. Insert message với ticket ID, sender user ID, content và thời gian.
4. Update `support_tickets.updated_at`.
5. Nếu sender là admin và khác owner, insert notification cho owner.
6. Notification có `type=SUPPORT_REPLY` và reference đến ticket.
7. Đọc message vừa tạo và trả response.

Status RESOLVED chưa bị chặn ở method này; chỉ CLOSED bị chặn.
Notification là dữ liệu in-app, không gửi SMTP.
Khóa ticket phối hợp với `update` để tránh chèn message song song với một lần đóng ticket đã lấy lock.

### update(UUID id, SupportTicketUpdateRequest body)

Có `@Transactional`.
Đầu ra detail sau cập nhật.

1. Require ADMIN.
2. Khóa ticket qua `owned(id, true)`.
3. Nếu có `assignedAdminId`, kiểm user đó là admin ACTIVE.
4. Cập nhật status, priority, assigned admin.
5. SQL đặt `resolved_at=now()` khi status RESOLVED hoặc CLOSED.
6. Các status còn lại đặt `resolved_at=NULL`.
7. Ghi updated time.
8. Đọc after và audit `UPDATE_SUPPORT_TICKET`.
9. Trả detail.

Service chấp nhận các giá trị status do DTO cho phép; không áp một bảng transition chặt hơn.
Đưa `assignedAdminId=null` xóa assignment hiện tại.
Sửa từ RESOLVED sang IN_PROGRESS xóa resolved timestamp theo SQL.

### API và ví dụ

| API | Method/quyền |
| --- | --- |
| `GET /api/v1/support/tickets` | `list`; admin thấy tất cả, user thấy ticket mình. |
| `POST /api/v1/support/tickets` | `create`; user đăng nhập. |
| `GET /api/v1/support/tickets/{id}` | `detail`; owner hoặc admin. |
| `POST /api/v1/support/tickets/{id}/messages` | `message`; owner hoặc admin, ticket chưa CLOSED. |
| `PATCH /api/v1/support/tickets/{id}` | `update`; ADMIN. |

Mở ticket:

```json
{
  "subject": "Không tạo được CV",
  "category": "TECHNICAL",
  "description": "Sau khi chọn template, thao tác tạo CV báo lỗi."
}
```

Gửi message:

```json
{
  "content": "Mình đã thử lại và vẫn gặp lỗi."
}
```

Admin cập nhật:

```json
{
  "status": "IN_PROGRESS",
  "priority": "HIGH",
  "assignedAdminId": "{{adminId}}"
}
```

`subject` tối đa 255 ký tự, `category` 50 ký tự, description/content 10.000 ký tự.
Category hiện tại là chuỗi có giới hạn độ dài, không có enum TECHNICAL bắt buộc trong DTO.



<a id="svc-resumeservice"></a>

## ResumeService

Nguồn: [ResumeService.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/candidate/resume/ResumeService.java).

Util đã tách: [ResumeContent.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/util/candidate/resume/ResumeContent.java), [ResumeInput.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/util/candidate/resume/ResumeInput.java). Xem [ranh giới service/util](#doc-utils).

### Vai trò, nơi gọi và dữ liệu

Service quản lý vòng đời CV của người đăng nhập: tạo, upload, sửa, xác nhận, chọn CV chính,
phân tích, tạo embedding và tải xuống. `ResumeController` gọi các phương thức HTTP;
[MatchingService](#svc-matchingservice), [CareerToolsService](#svc-careertoolsservice),
[LearningService](#svc-learningservice) và [CareerAssistantService](#svc-careerassistantservice)
dùng `owned`/`canonicalText` để đọc CV theo cùng quy tắc.

Constructor `ResumeService(...)` nhận `ResumeRepository`, `CurrentUser`, [AiService](#svc-aiservice),
`ResumeFileExtractor`, Jackson `ObjectMapper` và `app.storage.path`.
`Path.of(...).toAbsolutePath().normalize()` chuyển nơi lưu file thành đường dẫn tuyệt đối trước khi sử dụng.
Trong annotation có fallback `./storage/resumes`; với application.yaml hiện tại, mặc định hiệu lực là `./uploads` qua UPLOAD_DIR.

| Nhóm dữ liệu | Bảng sử dụng | Ý nghĩa |
| --- | --- | --- |
| CV chính | `resumes`, `resume_templates` | Metadata, dữ liệu JSON, trạng thái, file, hash, template |
| Dữ liệu từng mục | `resume_experiences`, `resume_educations`, `resume_projects`, `resume_certifications`, `resume_skills`, `skills` | Các mục đã được ứng viên xác nhận |
| Parsing và phân tích | `resume_parse_results`, `resume_analyses`, `resume_analysis_suggestions` | Kết quả trích xuất, điểm, đề xuất |
| Vector và phụ thuộc | `resume_embeddings`, `vector_indexing_jobs`, `roadmaps`, `gap_analyses` | Vector hiện hành và roadmap bị ảnh hưởng khi CV thay đổi |
| Khóa thao tác | `users` | `FOR UPDATE` để tránh hai yêu cầu cùng chọn hai CV chính |

### Trạng thái cần hiểu trước khi đọc

`MANUAL`, `UPLOAD` và `AI_BUILDER` là giá trị của `source`; chúng mô tả cách tạo CV.
`DRAFT`, `REVIEW_REQUIRED`, `READY` và `ARCHIVED` là giá trị của `status`.
Tạo thủ công đưa CV vào `DRAFT`; upload hoặc tạo bằng AI đưa vào `REVIEW_REQUIRED`.
Chỉ `confirm` mới đưa CV sang `READY` sau khi kiểm tra nội dung.
Sửa nội dung làm hash thay đổi đưa CV về `REVIEW_REQUIRED` và bỏ cờ CV chính.
`ARCHIVED` không được sửa/xác nhận và không xuất hiện trong danh sách CV thông thường.

`structured_data` là JSON ứng viên đã xem lại. Các bảng `resume_*` chứa dữ liệu từng mục được
đồng bộ ở bước xác nhận; `update` chưa tự xây lại những bảng này.
Điều này giải thích vì sao chức năng matching/ATS/roadmap yêu cầu CV `READY`.

### `templates()`, `list()`, `owned(UUID id)`, `get(UUID id)`

- `templates` không có input; trả danh sách template `is_active=true`, sắp theo tên.
- `list` lấy `current.id()`, trả metadata của CV thuộc người hiện tại, loại `ARCHIVED`,
  sắp CV chính trước rồi mới theo `updated_at` giảm dần.
- `owned` lấy CV theo ID, gọi `current.owns(row,"user_id")`; tài nguyên của người khác được
  trả như không tìm thấy. Đây là kiểm tra quyền dùng chung, không chỉ một câu SQL đọc dữ liệu.
- `get` tạo `new LinkedHashMap<>(owned(id))` để bổ sung `experiences`, `educations`,
  `projects`, `certifications`, `skills` và `parse_results`. Output là một map chi tiết CV.

`get` đọc được CV đã archive nếu đúng chủ sở hữu; việc loại CV archive áp dụng ở `list`,
không phải mọi phương thức đọc.

### `upload(MultipartFile file, String title, String language)`

HTTP: `POST /api/v1/resumes/upload`, `multipart/form-data` với `file`, `title` tùy chọn và
`language` mặc định `vi`. Phương thức có `@Transactional`.

1. Xác định user, kiểm tra ngôn ngữ và title tối đa 255 ký tự.
2. Gọi `extractor.extract(file)` để xác thực và trích xuất text.
   Extractor hỗ trợ PDF, DOCX, TXT, PNG/JPEG; giới hạn 10 MiB, PDF tối đa 50 trang,
   text tối đa 200.000 ký tự. Nó kiểm tra MIME/signature; ảnh cần bật OCR/Tesseract.
3. Nếu thiếu title, dùng tên file gốc đã làm sạch. Tạo file key bằng UUID cộng đuôi file.
   Tên người dùng gửi không trực tiếp trở thành đường dẫn lưu trữ.
4. `write` lưu bytes. Chuẩn bị structured data rỗng qua `ResumeData.normalize(Map.of())`.
5. Nếu chat AI bật và text không rỗng, gọi `CV_PARSE` rồi chuẩn hóa output.
   `ApiException` tại bước AI được bắt: lưu parse `FAILED` và thông báo lỗi, nhưng CV vẫn
   được tạo để người dùng sửa thủ công.
6. Insert `resumes` với `source=UPLOAD`, `status=REVIEW_REQUIRED`, raw text, JSON chuẩn hóa
   và content hash. Insert `resume_parse_results` chứa parser, OCR, raw/extracted/normalized data.
7. Trả `get(id)` để client xem lại.

Nếu AI tắt, parser file vẫn chạy và parse status mặc định `COMPLETED`.
Nếu text rỗng, parse record có thông báo cần OCR/nhập thủ công; đó không đồng nghĩa CV đã sẵn sàng.
File được xóa khi transaction database rollback bằng synchronization trong `write`.

### `create(Map<String,Object> input, boolean generated)`

HTTP: `POST /api/v1/resumes` truyền `generated=false`;
`POST /api/v1/resumes/build` truyền `generated=true`. Cả hai dùng `ResumeWriteRequest.toInputMap()`.
Input có `title` bắt buộc, `language`, `raw_content`, `template_id` và `structured_data`.
Phương thức có transaction.

- Title phải không rỗng, tối đa 255; language chỉ `vi/en`; raw text tối đa 200.000.
- Nếu có template ID, repository yêu cầu template đang active.
- Khi `generated=false`, `document(input)` yêu cầu `structured_data` là object;
  `ResumeData.normalize` chỉ giữ schema CV được hỗ trợ.
- Khi `generated=true`, copy input vào map mới, bổ sung `career_profile` thuộc user hiện tại,
  gọi `ai.generate("CV_BUILD",safe)` và normalize. Các dữ kiện mở rộng trong input có thể
  được gửi cho AI; schema lưu cuối cùng vẫn là schema CV.
- Lưu source `MANUAL`/`AI_BUILDER`, status `DRAFT`/`REVIEW_REQUIRED` rồi trả CV chi tiết.

`ResumeData` chuẩn hóa năm section thành array, mỗi section tối đa 100 mục;
skills có thể là chuỗi hoặc object. Nó kiểm tra ISO date, thứ tự ngày, boolean `is_current`,
years experience 0..99 và UUID skill. Không nên gửi trường lạ rồi kỳ vọng trường đó được lưu nguyên trạng.

Ví dụ tạo thủ công:

```http
POST /api/v1/resumes
Authorization: Bearer {{candidateToken}}
Content-Type: application/json
```

```json
{
  "title": "CV Backend Java",
  "language": "vi",
  "structured_data": {
    "full_name": "Nguyen Van A",
    "summary": "Sinh vien CNTT co du an Spring Boot.",
    "experience": [],
    "education": [],
    "projects": [
      {
        "project_name": "CV Matching",
        "description": "Xay dung REST API va xu ly du lieu.",
        "technologies": ["Java", "Spring Boot", "PostgreSQL"]
      }
    ],
    "certifications": [],
    "skills": ["Java", "Spring Boot", "PostgreSQL"]
  }
}
```

### `update(UUID id, Map<String,Object> input)`

HTTP: `PATCH /api/v1/resumes/{id}`; transaction bảo vệ cập nhật và invalidation.
Đầu tiên kiểm tra sở hữu và từ chối `ARCHIVED` bằng 409.
Mỗi trường chỉ được thay khi `input.containsKey(field)`; trường không gửi giữ giá trị cũ.
Đây là điểm cần phân biệt giữa không gửi field với gửi field bằng `null`.
DTO giữ thông tin field có mặt, còn service/helper quyết định `null` có hợp lệ không.

Service chuẩn hóa structured data, lấy raw/title/language mới hoặc cũ rồi tính `newHash`.
`changed` là kết quả so sánh hash cũ và mới.
Nếu đổi, SQL đặt `REVIEW_REQUIRED`, bỏ `is_primary` và `invalidate(id)`;
nếu không đổi, giữ trạng thái hiện tại. Output là CV mới qua `get`.

### `confirm(UUID id, Map<String,Object> input)`

HTTP: `POST /api/v1/resumes/{id}/confirm`; body có thể bỏ qua.
Transaction khóa row `users` của người hiện tại trước khi xác nhận.

1. Kiểm tra sở hữu, từ chối archived.
2. Lấy structured data/raw text từ input nếu có, nếu không lấy bản đang lưu; normalize.
3. `hasContent` yêu cầu raw text, summary hoặc ít nhất một section có nội dung.
   Chỉ full name/email không đủ để xác nhận CV.
4. Tính hash mới; `replaceSections` xây lại các bảng dữ liệu từng mục từ structured data.
5. Nếu hash thay đổi thì invalidate embedding/roadmap.
6. `is_primary` được giữ nếu trước đó đã true hoặc input yêu cầu true.
   Nếu true, bỏ cờ primary của các CV khác trong cùng user.
7. SQL cập nhật JSON/raw/hash, đặt `READY` rồi trả chi tiết.

Body tối thiểu `{}` dùng dữ liệu đang lưu. Có thể gửi `{"is_primary":true}`.
Khóa user bảo đảm việc thay CV chính diễn ra tuần tự trong các thao tác của service.

### `primary(UUID id)` và `archive(UUID id)`

`primary` có transaction, khóa user, kiểm tra sở hữu và status `READY`.
Sau đó bỏ cờ primary của toàn bộ CV chính hiện tại, đặt CV được chọn làm primary và trả detail.
HTTP: `POST /api/v1/resumes/{id}/primary`.

`archive` có transaction, kiểm tra sở hữu, đặt `ARCHIVED` và `is_primary=false`,
đồng thời invalidate embedding/roadmap. HTTP `DELETE /api/v1/resumes/{id}` trả 204.
Nó không xóa vật lý file và không xóa toàn bộ lịch sử phân tích.

### `analyze(UUID id)`

HTTP: `POST /api/v1/resumes/{id}/analyses`. Chỉ CV `READY` được phân tích; transaction
lưu analysis và suggestions cùng nhau.

Sau khi kiểm tra owner/READY, `analyze` tính hash toàn bộ đầu vào CV/ngôn ngữ, mode AI/rule,
model/prompt và phiên bản rubric. Key `edunexus:resume-analysis:response:v1:{userId}:{resumeId}:{inputHash}`
lưu JSON envelope `schema_version/identity/response` tối đa 24 giờ. Cache hit hợp lệ trả analysis và
suggestions trực tiếp với `cached=true`, không đọc các dòng kết quả trong DB. Redis miss/lỗi tạo
phân tích mới; luồng này không có DB cache lookup theo input hash. Kết quả mới vẫn được lưu lịch sử
và chỉ ghi Redis sau commit; rollback không publish cache.

Nếu `ai.enabled()`, gọi `CV_ANALYZE` với row CV và `canonicalText`, lưu `AI_QUALITY` và model.
Nếu chat AI tắt, gọi `baseline`, lưu `RULE_BASED` và `rules-v1`.
Output luôn là analysis chi tiết, nhưng rule mode chỉ kiểm tra độ đầy đủ section:
`ats_score=null`, không có điểm ATS thực.

Result AI/rule được ghi vào `resume_analyses`; các phần tử map trong `suggestions` được insert
sang `resume_analysis_suggestions` với các cột được hỗ trợ.
Map/result không được lưu như một khối tùy ý vào tất cả cột database.

### `analyses(UUID id)` và `analysis(UUID analysisId, UUID resumeId)`

`analyses` xác thực chủ CV rồi trả lịch sử phân tích.
`analysis` xác thực chủ CV, truy vấn analysis theo cả analysis ID lẫn resume ID,
bổ sung array `suggestions`. Ghép cả hai ID ngăn đọc analysis của CV khác bằng ID đoán được.
HTTP lần lượt là `GET /api/v1/resumes/{id}/analyses` và
`GET /api/v1/resumes/{id}/analyses/{analysisId}`.

### `embedding(UUID id)`

HTTP: `POST /api/v1/resumes/{id}/embedding`.
Annotation là `@Transactional(noRollbackFor = ApiException.class)` để giữ task lỗi khi một
`ApiException` được ném; các runtime exception khác vẫn theo quy tắc rollback mặc định.

1. Xác thực chủ CV, yêu cầu `READY` và `ai.embeddingsEnabled()`.
2. Tìm vector current theo CV ID + provider + embedding model + content hash.
   Có rồi thì trả metadata vector, không gọi provider lại.
3. Insert `vector_indexing_jobs` với `RESUME/EMBED/PROCESSING` và attempt 1.
4. Gọi `ai.embed(canonicalText(resume))`.
5. Đọc lại CV bằng `FOR UPDATE` sau network call; nếu status/hash thay đổi thì báo 409,
   ghi task `FAILED`. Đây là kiểm tra bản chụp chống publish vector của dữ liệu cũ.
6. Chuyển các embedding cũ `is_current=false`, upsert vector mới theo hash/model/provider,
   đặt task `COMPLETED` và trả metadata embedding.

`Arrays.toString(float[])` tạo literal dạng `[0.1, ...]` để SQL cast sang pgvector.
Vector 1024 chiều và giá trị hữu hạn được [AiService](#svc-aiservice) kiểm tra qua `FlowSupport.vector`.
Output repository không trả toàn bộ vector; chỉ ID/model/hash/current/timestamp.

### `ResumeService.canonicalText(Map<String,Object> resume)` → `ResumeContent.canonicalText(ObjectMapper mapper, Map<String,Object> resume)`

Service giữ API public cũ và truyền mapper vào `com.example.intelligent_cv_matching_ai_agent.util.candidate.resume.ResumeContent.canonicalText`.

Đây là nguồn text chung cho phân tích, matching, ATS, chat context và embedding.
Nếu JSON có summary hoặc có dữ liệu trong experience/education/projects/certifications/skills,
service serialize JSON đó; nếu chưa có nội dung có cấu trúc, dùng `raw_content`.
Cuối cùng ghép `title + "\n" + text`.

Như vậy nội dung ứng viên sửa/xác nhận được ưu tiên so với text trích xuất cũ.
Việc thay đổi title cũng thay canonical text và content hash.

### `download(UUID id, boolean text)`

HTTP: `GET /api/v1/resumes/{id}/download?text=false`.
Kiểm tra sở hữu trước khi đọc file.

Nếu `text=false` và CV có `file_url`, đọc bytes bằng `safeFile` rồi trả
`ResumeDownloadResponse(content, filename, mimeType)`. Không đọc được file trả 404.
Các CV không có file, hoặc `text=true`, được xuất thành canonical text UTF-8,
filename là title đã thay ký tự không phù hợp thành `_` cộng `.txt`.
Controller đặt Content-Disposition attachment và `X-Content-Type-Options: nosniff`.

### Hàm điều phối của service và helper trong util

| Phương thức | Input/output | Giải thích code và tác động |
| --- | --- | --- |
| `replaceSections(UUID,Map)` | JSON chuẩn hóa → ghi nhiều bảng | Xóa rồi insert lại experience/education/projects/certifications; đổi cột ngày thành `LocalDate`, technologies thành JSON. Xây lại resume skills, kiểm tra skill ID active hoặc tạo/đọc skill theo slug, dùng `Set<UUID> seen` loại trùng, gắn source `REVIEWED`. |
| `invalidate(UUID)` | CV ID → void | Tắt `is_current` của resume embeddings; đặt `roadmaps.is_outdated=true` đối với gap analysis dùng CV này. |
| `ResumeContent.baseline(Map)` | CV → map analysis | Duyệt summary/experience/education/skills/projects; mỗi mục hiện diện +20, tổng tối đa 100. Ghi strengths/issues/suggestions tương ứng; ATS để null. |
| `write(String,byte[])` | Key/bytes → file | Tạo thư mục, kiểm tra safe path, ghi `CREATE_NEW` để không ghi đè; đăng ký transaction callback xóa file khi DB rollback. Storage lỗi trả 503. |
| `afterCompletion(int status)` | Trạng thái transaction → void | Callback public trong anonymous `TransactionSynchronization` được tạo ở `write`; chỉ khi `STATUS_ROLLED_BACK` thì `Files.deleteIfExists(file)`, bỏ qua IOException khi dọn file. Commit thành công giữ file. |
| `safeFile(String)` | Key → `Path` | Chỉ nhận UUID-like key với đuôi pdf/docx/txt/png/jpg/jpeg; dùng root real path, resolve/normalize, yêu cầu nằm trong root, từ chối symbolic link. |
| `ResumeContent.hash(String) (private util)` | Text → hex SHA-256 | Băm UTF-8 qua `MessageDigest`; lỗi tạo digest chuyển thành `IllegalStateException`. |
| `ResumeContent.contentHash(ObjectMapper,String,String,String,Map)` | title/language/raw/data → hash | Serialize list bốn thành phần, ổn định key JSON bằng `stable`, rồi SHA-256. |
| `ResumeContent.stable(Object) (private util)` | JSON bất kỳ → JSON có key ổn định | Map được chuyển thành `TreeMap` và xử lý đệ quy; list giữ thứ tự nhưng normalize từng item bằng stream. Đổi thứ tự key object không làm hash thay đổi; đổi thứ tự list vẫn có thể làm đổi hash. |
| `ResumeInput.optionalUuid(Object)` | null/text → null/UUID | Null nghĩa không có ID; UUID sai trả 400. |
| `ResumeInput.requiredTitle(Map)` | input → title | Dùng `ResumeData.text` giới hạn 255 rồi yêu cầu không rỗng. |
| `ResumeInput.language(Object)` | giá trị → vi/en | Null mặc định vi; nhận vi/en/VI/EN rồi lower case; giá trị khác trả 400. |
| `ResumeInput.skillSlug(String)` | Tên skill → slug | Chuẩn hóa chữ thường, thay ký tự ngoài chữ/số bằng gạch và bỏ gạch đầu/cuối; trống trả 400. Việc đọc/tạo skill vẫn ở service. |
| `ResumeInput.document(Map)` | input → structured map | Yêu cầu `structured_data` là object, khác loại trả 400. |
| `Values.object(Object)` | object → map | Pattern check/cast map; thiếu hoặc khác loại trả `Map.of()`. |

Đoạn tiêu biểu cho invalidation:

```java
boolean changed = !Objects.equals(before.get("content_hash"), newHash);
if (changed)
    invalidate(id);
```

`Objects.equals` so sánh an toàn khi một phía null; `changed` đi cùng SQL đổi status/primary.
Nếu chỉ sửa field không tham gia hash, service không xem đó là sửa nội dung CV.
Các field hash hiện có là title, language, raw content và structured data.

<a id="svc-matchingservice"></a>

## MatchingService

Nguồn: [MatchingService.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/candidate/jobs/MatchingService.java).

Util đã tách: [JobSkillOverlap.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/util/candidate/jobs/JobSkillOverlap.java). Xem [ranh giới service/util](#doc-utils).

### Vai trò, phụ thuộc và dữ liệu

Service xếp hạng job cho ứng viên, lưu một lần chạy và các kết quả.
[CareerToolsService](#svc-careertoolsservice) làm lớp nhận DTO cho controller rồi gọi service này.
Constructor nhận `MatchingRepository`, `CurrentUser`, [AiService](#svc-aiservice),
[ResumeService](#svc-resumeservice) và `ObjectProvider<StringRedisTemplate>`.

Đọc `candidate_profiles`, `candidate_profile_skills`, `skills`, `resumes`,
`market_job_posts`, `market_job_skills`, `market_job_embeddings` và `market_job_sources`;
ghi `job_matching_runs`, `matching_run_resumes`, `job_match_results`.
Redis là cache tùy chọn; thiếu Redis hoặc lỗi kết nối vẫn dùng database.

### `result(UUID runId)`

Đọc run theo cả ID và `user.id()`, bổ sung `results` theo rank.
Không đọc run của người khác. Job result chứa metadata và nguồn apply hiện còn active.
Repository giữ `original_source_url`, đồng thời chiếu `source_url`/`source_site` từ
`market_job_sources` để job dedup đa nguồn có thể chuyển sang link nguồn còn sống.
Nếu deadline đã qua nhưng status chưa cập nhật, result chiếu `job_status=EXPIRED`.

### `match(String basis, List<UUID> selected, int limit)`

HTTP thông qua `POST /api/v1/matching` với `MatchRequest`; method có transaction.
Yêu cầu role `CANDIDATE`, limit 1..100, basis thuộc ba giá trị sau.

| Basis | Cách tạo candidate text | Điều kiện |
| --- | --- | --- |
| `CAREER_PROFILE` | Profile cộng danh sách profile skills | Phải có profile |
| `SELECTED_CV` | `resumes.canonicalText` của một CV | `resumeIds` đúng một ID, thuộc user, `READY` |
| `ALL_CVS` | Ghép canonical text của các CV | SQL chỉ lấy CV `READY` và không demo, cần ít nhất một, tối đa 100 |

SQL lấy tối đa 101 CV cho `ALL_CVS` để service phát hiện vượt 100.
`StringBuilder` ghép text theo basis, tránh lặp phép tạo chuỗi trong vòng lặp.

Service đọc tối đa 5.000 job active chưa quá `valid_through` với skills có cấu trúc.
Không có job thì trả 409. Mode là `SEMANTIC_LLM` khi chat AI bật, hoặc `SKILL_OVERLAP` khi tắt.
Nhánh semantic còn cần embedding provider hoạt động; chỉ bật chat mà embedding tắt sẽ lỗi 503
ở `ai.embed`, không tự chuyển sang rule mode.

### Cache nằm trong `match`

Hash cache gồm basis, danh sách resume ID đã sort, candidate text, catalog fingerprint,
vector fingerprint khi AI bật, mode, provider/chat model/embedding model/prompt, limit và chính sách lương/tỷ giá.
Catalog fingerprint băm các job active/chưa hết hạn với raw hash, metadata hiển thị, deadline và URL/site nguồn active; vector fingerprint băm
các vector current đúng provider/model và đúng hash job.
Do đó đổi CV/profile, import job mới, job hết hạn hoặc re-index model có thể làm cache miss.

1. Redis key `edunexus:matching:response:v1:{userId}:{hash}` lưu JSON toàn bộ response trong envelope `schema_version/identity/response`.
2. Nếu identity/payload/tuổi run/deadline hợp lệ, trả trực tiếp `cached=true/cache_source=REDIS`, không gọi `result(runId)` hoặc đọc run/results.
3. Nếu Redis miss, tìm run `COMPLETED` cùng request hash trong 1 giờ ở database.
4. Có DB cache thì trả `cached=true/cache_source=DATABASE` và nhớ lại Redis.
5. Cache miss mới tạo run `PROCESSING` và liên kết những CV được sử dụng.

Cache là bản chụp response của kết quả đã persist; việc ghi Redis chỉ diễn ra sau commit.
Kiểm tra owner, CV/profile, catalog/vector và chính sách lương vẫn chạy trước lookup Redis.
TTL tối đa một giờ, không vượt thời điểm run hoàn tất + một giờ hoặc deadline gần nhất của job trong response.
Key ID cũ không được đọc bằng namespace response mới; JSON sai hoặc Redis lỗi được coi là miss.

### Nhánh semantic trong `match`

```text
candidate text → ai.embed → pgvector top K
               → JOB_MATCH_RERANK → final_score → lưu top limit
```

- `FlowSupport.vector` chuyển float array thành vector literal.
- Repository dùng `e.embedding <=> queryVector`, tức khoảng cách cosine của pgvector.
  Điểm được xuất bằng `1 - distance`; semantic retrieval không đồng nghĩa điểm LLM.
- Chỉ vector current, đúng provider, đúng embedding model, đúng content hash và job active
  chưa hết hạn được tham gia.
- K là `min(100,max(limit,20))`; kể cả limit nhỏ vẫn có ít nhất 20 ứng viên cho rerank.
- Không tìm được vector phù hợp trả 409 yêu cầu index job theo model đang cấu hình.
- Gọi AI feature `JOB_MATCH_RERANK` với candidate và danh sách job vừa lấy.
- Chuyển kết quả AI thành `Map<UUID,Map>`; duplicate ID trả 502, score ngoài 0..1 trả lỗi.
- Mỗi job retrieval phải có phần giải thích tương ứng; AI bỏ sót một job trả 502.
  Kết quả bổ sung ID ngoài danh sách không được dùng để tạo job mới.

### Nhánh rule và lưu kết quả trong `match`

Khi chat AI tắt, copy danh sách job vào `ArrayList` rồi gọi `overlap` cho từng job.
Cả hai nhánh sort giảm dần theo `final_score`; hòa điểm thì sort theo ID để kết quả ổn định.
Lưu tối đa `limit` result với rank bắt đầu từ 1; vector score, LLM score là các cột riêng.
Nhánh rule có `llm_rerank_score=null` và reason method `SKILL_OVERLAP`.

Run được cập nhật `COMPLETED`, lấy `result(runId)` với `cached=false` và chuẩn bị cache response;
chỉ publish Redis sau commit, với TTL giới hạn theo tuổi run/deadline job.
Nếu exception làm transaction rollback, các row run/result vừa tạo rollback cùng nhau.
Quota/model usage đã được [AiService](#svc-aiservice) xử lý riêng, không mặc nhiên được hoàn
chỉ vì transaction matching sau đó thất bại.

Ví dụ input DTO:

```json
{
  "basis": "SELECTED_CV",
  "resumeIds": ["{{resumeId}}"],
  "limit": 10
}
```

`{{resumeId}}` phải thay bằng UUID của CV `READY`. Với profile có thể gửi
`{"basis":"CAREER_PROFILE","resumeIds":[],"limit":10}`.

### `MatchingService.overlap(String candidate, Map<String,Object> job)` → `JobSkillOverlap.overlap`

Service giữ delegate public static; thuật toán thuộc `com.example.intelligent_cv_matching_ai_agent.util.candidate.jobs.JobSkillOverlap.overlap(String,Map)`.

Public static helper dùng được trực tiếp trong test.
Đổi candidate và skill name sang lowercase `Locale.ROOT`.
Mỗi skill được tìm bằng regex ranh giới chữ/số:
`Pattern.quote(name)` làm tên như `C++` hoặc `.NET` không bị hiểu là cú pháp regex.

`required` mặc định true, chỉ `Boolean.FALSE` mới là optional.
Skill tìm thấy được đưa vào strengths; skill required chưa tìm thấy đưa vào gaps.
Điểm bằng `matchedRequired / requiredCount`; optional skill không tăng mẫu số.
Nếu job không có required skill thì score 0 và reason nói thiếu structured skills.
Đây là so khớp từ khóa, không có kiến thức ngữ nghĩa hoặc đánh giá kinh nghiệm làm việc.

Ví dụ job cần Java/PostgreSQL, ứng viên chỉ có Java: score `1/2=0.5`,
strengths `["java"]` và gaps `["postgresql"]`.

### Helper cache của MatchingService

| Phương thức | Input/output | Cách xử lý |
| --- | --- | --- |
| `AiResponseCache.read(key, identity)` | Key/identity → response tùy chọn | Kiểm schema/identity; JSON hoặc Redis lỗi trả empty. |
| `validCachedResult(result, basis, cacheHash)` | Response → boolean | Kiểm run/user/basis/hash, danh sách result, tuổi run và deadline. |
| `remember(key, identity, result)` | Response → void | Tính TTL còn lại rồi đăng ký ghi toàn bộ response sau commit. |
| `cacheLifetime(result)` | Response → Duration | Giới hạn bởi completed_at + một giờ và deadline job gần nhất. |

Đoạn sort cần hiểu:

```java
ranked.sort(
    Comparator.<Map<String, Object>>comparingDouble(j -> number(j.get("final_score"), 0))
        .reversed()
        .thenComparing(j -> j.get("id").toString())
);
```

Lambda lấy điểm từng map; `reversed()` đặt điểm lớn trước;
`thenComparing` giải quyết hòa điểm. Cast kiểu generic tường minh giúp Java suy luận `j`
là map thay vì object.

<a id="svc-careertoolsservice"></a>

## CareerToolsService

Nguồn: [CareerToolsService.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/candidate/jobs/CareerToolsService.java).

Util đã tách: [CareerToolsSupport.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/util/candidate/jobs/CareerToolsSupport.java). Xem [ranh giới service/util](#doc-utils).

### Vai trò, constructor và dữ liệu

Service cung cấp các công cụ quanh việc tìm và ứng tuyển job:
matching façade, phản hồi, phân tích ATS, CV theo job, email/cover letter, link apply và ước lượng lương.
`CareerToolsController` gọi service dưới prefix `/api/v1`.

Constructor `CareerToolsService(...)` nhận `CareerToolsRepository`, `CurrentUser`,
[MatchingService](#svc-matchingservice), [ResumeService](#svc-resumeservice),
[AiService](#svc-aiservice) và Redis provider tùy chọn.
Các bảng chính: `job_matching_runs`, `job_match_results`, `job_match_feedback`,
`job_resume_analyses`, `application_drafts`, `external_apply_events`,
`salary_estimations`, `ai_usage_logs`, `candidate_profiles`,
`market_job_posts`, `market_job_skills`, `market_job_sources`.

### `match(MatchRequest request)` và `run(UUID id)`

`match` truyền thẳng `basis/resumeIds/limit` cho `MatchingService.match`.
`run` truyền ID cho `MatchingService.result`. Không có thuật toán matching thứ hai trong lớp này.
HTTP lần lượt `POST /api/v1/matching` và `GET /api/v1/matching/{id}`.
Xem [MatchingService](#svc-matchingservice) để hiểu cache/vector/rerank/quyền sở hữu.

### `history(int limit)` và `aiUsage(int limit, int offset)`

`history` liệt kê run của user hiện tại theo `started_at` giảm dần, limit 1..100.
HTTP `GET /api/v1/matching?limit=20`.

`aiUsage` yêu cầu role candidate, limit 1..100, offset 0..100.000,
đọc lịch sử model usage của đúng user: feature, model, tokens, latency, success và error.
HTTP `GET /api/v1/ai/usage?limit=20&offset=0`.
Nó đọc log thực tế, không tự tính lại số dư quota.

### `feedback(UUID id, MatchingFeedbackRequest feedback)`

HTTP `PUT /api/v1/matching/results/{id}/feedback`; transaction.
`id` là ID một `job_match_results`, không phải job ID hay matching run ID.
Repository join run để xác nhận result thuộc user hiện tại.

Upsert theo cặp `(user_id,match_result_id)`:
gửi lần hai sẽ thay feedback cũ, không tạo thêm một phản hồi cho cùng cặp.
DTO nhận `feedbackType=LIKE/DISLIKE`, rating tùy chọn 1..5, comment tối đa 2.000.
Output là row feedback sau upsert.

### `ats(UUID jobId, CvRequest request)`

HTTP `POST /api/v1/jobs/{jobId}/ats` với `{"resumeId":"{{resumeId}}"}`.
Transaction yêu cầu candidate, CV của user ở `READY` và job active/chưa qua deadline.

Tính `inputHash` từ toàn bộ input gửi `JOB_ATS` (structured CV, canonical text, job hiện hành,
ngôn ngữ), provider/chat model và fingerprint prompt active.
Key Redis gồm user + CV + job + hash; vì vậy cùng UUID nhưng nội dung đổi sẽ không dùng cache cũ.

1. Key `edunexus:ats:response:v1:{userId}:{resumeId}:{jobId}:{inputHash}` chứa JSON envelope `schema_version/identity/response`.
2. Redis response hợp lệ trả trực tiếp `cached=true`, không đọc lại analysis từ DB; kiểm tra owner/READY/job và hash đã chạy trước lookup.
3. Redis miss/JSON sai/lỗi kết nối thì tìm DB theo user/CV/job/hash; có kết quả thì `analysisResponse(row,true)` và ghi lại response Redis sau commit, không gọi AI.
4. Miss mới gọi `JOB_ATS` với structured CV, canonical text, job và ngôn ngữ CV.
5. Kiểm tra score 0..100, lưu strengths/missing skills và suggestions.
6. Suggestions JSON được lưu dưới object `{"input_hash":...,"items":[...]}`.
   API trả lại items array, không lộ cấu trúc cache nội bộ.
7. Trả response `cached=false` và chỉ ghi toàn bộ response Redis sau commit, TTL tối đa 24 giờ.

DB lookup ATS không giới hạn thời gian theo `created_at`. TTL 24 giờ chỉ áp dụng Redis;
nếu input hash không đổi, DB vẫn có thể tái sử dụng analysis lâu hơn 24 giờ.
Redis fail không làm ATS fail.

### `CareerToolsSupport.analysisResponse(Map<String,Object> row, boolean cached)`

Hàm static thuộc `com.example.intelligent_cv_matching_ai_agent.util.candidate.jobs.CareerToolsSupport`, không còn là method của service; nó sửa và trả map đầu vào.

Private helper bóc `improvement_suggestions_json.items` ra array,
thay field response bằng array đó và thêm cờ `cached`.
Method sửa map row đang nhận; không thực hiện UPDATE database.
`Values.object/array` giúp giá trị thiếu/sai shape được biểu diễn thành object/list rỗng.

### `targetedResume(UUID jobId, Map input)`

HTTP `POST /api/v1/jobs/{jobId}/resume-draft`.
Service kiểm tra candidate và active job, copy input vào `LinkedHashMap`.
Title mặc định `CV - {job_title}` nếu input không có title;
bổ sung target job, structured job requirements và language.
Thêm `accuracy_requirement` yêu cầu chỉ viết lại dựa trên sự thật ứng viên đã cung cấp,
không tự thêm kinh nghiệm/chứng chỉ thiếu.

Gọi `resumes.create(request,true)` nên CV tạo ra có source `AI_BUILDER`,
status `REVIEW_REQUIRED`, cần xác nhận trước các chức năng yêu cầu CV ready.
Map input này linh hoạt nhưng validation lưu CV vẫn do ResumeService/ResumeData thực hiện.

### `createDraft(UUID jobId, DraftRequest request)`

HTTP `POST /api/v1/jobs/{jobId}/application-drafts`.
Input `resumeId` bắt buộc và language `vi/en` tùy chọn.
Transaction: candidate → confirmed CV → active job → `APPLICATION_EMAIL`.

AI phải trả `email`, `cover_letter` không rỗng, mỗi phần tối đa 30.000 ký tự.
Tạo hai row `application_drafts`:

- `APPLICATION_EMAIL`: content ghép subject, hai newline và email.
- `COVER_LETTER`: content là thư xin việc.

Cả hai gắn user/job/resume/model; output là list hai row vừa tạo.
Đây là tạo nội dung bản nháp; service không gửi email hoặc gửi hồ sơ đến nhà tuyển dụng.

### `drafts(UUID jobId)` và `editDraft(UUID id, ApplicationDraftUpdateRequest input)`

`drafts` liệt kê draft của user, tối đa 100 theo updated time.
Có job ID thì thêm filter; null thì đọc tất cả draft của user.
HTTP `GET /api/v1/application-drafts?jobId={{jobId}}`.

`editDraft` kiểm tra ID thuộc user, update content/timestamp rồi trả row mới.
DTO yêu cầu content không rỗng tối đa 30.000.
HTTP `PATCH /api/v1/application-drafts/{id}`.
Nó không gọi lại AI và không cập nhật CV gốc.

### `apply(UUID id, CvRequest request)`

HTTP `POST /api/v1/jobs/{id}/external-apply`; body có thể bỏ qua.
Yêu cầu candidate; lấy job active chưa hết hạn cùng nguồn active được projection chọn.
Nếu không có nguồn khả dụng thì 409.

`JobSourceValidator.validateExternalUrl` kiểm tra link HTTPS thuộc các domain được cho phép.
Nếu body có resume ID thì CV phải thuộc user và `READY`.
Insert `external_apply_events` với event type `REDIRECT`, target URL và thời gian;
trả `{"event_id":...,"target_url":...}` cho client.

Service trả URL dưới JSON; nó không tự trả HTTP 302, không submit CV lên TopCV/ITviec.
`source_url` có thể là một alias còn sống nếu nguồn gốc đã expired,
còn `original_source_url` giữ thông tin nguồn đầu tiên trong row job.

### `salary(UUID id)`

HTTP `POST /api/v1/jobs/{id}/salary-estimation`.
Yêu cầu candidate, profile hiện tại và active job; gọi `SALARY_ESTIMATE`.
Điểm min/max được kiểm tra hữu hạn, không âm, tối đa `999999999999`;
max phải lớn hơn hoặc bằng min. Currency output chỉ `VND/USD/EUR`.
Interval hoặc currency sai trả 502.

Lưu estimate với user, job, target job title, min/max/currency/reasoning/model.
Output là row estimate, phục vụ tham khảo từ AI theo input; code không query một bảng
mức lương thống kê để xác nhận mức lương thị trường.

### `salaryForCareer(SalaryEstimateRequest request)`

HTTP `POST /api/v1/salary-estimations`.
Dùng profile cùng career position gồm `targetJobTitle`, `location`, `currency`,
thay vì bắt buộc có market job ID.
Kiểm tra interval/currency output và lưu estimate tương tự `salary`,
nhưng row không gắn `market_job_id`.

DTO yêu cầu target title không rỗng tối đa 160, location tối đa 120,
currency input tối đa 80 và language `vi/en`.
Whitelist currency kiểm tra trên output AI; DTO không hạn chế currency input bằng whitelist.

Ví dụ:

```json
{
  "targetJobTitle": "Java Backend Developer",
  "location": "Ho Chi Minh",
  "currency": "VND",
  "language": "vi"
}
```

### Helper điều phối và util của CareerToolsService

| Phương thức | Input/output | Vai trò và lỗi |
| --- | --- | --- |
| `confirmed(UUID)` | CV ID → row CV | Gọi `resumes.owned` rồi yêu cầu status READY; chưa confirm trả 409. |
| `activeJob(UUID)` | Job ID → row job | Repository lọc ACTIVE và deadline; chiếu nguồn active. Không tìm thấy trả 404 qua SqlStore. |
| `CareerToolsSupport.requiredAi(Map,String)` | AI result/key → text | Giá trị trống hoặc dài hơn 30.000 trả 502; dùng cho email/cover letter. |
| `CareerToolsSupport.languageOf(Object)` | giá trị → vi/en | Lowercase với Locale.ROOT; giá trị ngoài vi/en hoặc null mặc định vi. |

`CareerToolsSupport.validateSalaryInterval(min,max)` kiểm max ≥ min; `salaryCurrency(Object)` kiểm VND/USD/EUR. Service vẫn dùng `FlowSupport.score` cho từng mức lương trước khi gọi util và lưu DB.

Điểm đọc code: `putIfAbsent` chỉ thêm mặc định nếu chưa có giá trị hữu dụng theo quy tắc Map,
trong khi `put` luôn ghi đè bằng dữ liệu job được server chọn.
Dữ kiện `target_job` và `job_requirements` vì vậy được service bổ sung từ DB.

<a id="svc-careerassistantservice"></a>

## CareerAssistantService

Nguồn: [CareerAssistantService.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/candidate/ai/CareerAssistantService.java).

Util đã tách: [CareerAssistantData.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/util/candidate/ai/CareerAssistantData.java). Xem [ranh giới service/util](#doc-utils).

### Vai trò, dependency và dữ liệu

Service dùng chung cho chat nghề nghiệp có tài khoản và guest.
`CareerAssistantController` dùng `/api/v1/assistant`;
`GuestAssistantController` dùng `/api/v1/guest/assistant` và header `X-Guest-Session`.
Constructor nhận `CareerAssistantRepository`, `CurrentUser`,
[GuestAccess](#svc-guestaccess), [AiService](#svc-aiservice), [ResumeService](#svc-resumeservice).

Bảng chính: `career_assistant_conversations`, `career_assistant_messages`,
`career_assistant_message_sources`, `career_assistant_feedback`.
Context đọc profile/CV/job/gap analysis/roadmap; retrieval đọc
`rag_documents`, `rag_chunks`, `rag_chunk_embeddings` và `users`.

### `list()`

Trả conversation theo `user_id` hiện tại, updated time giảm dần.
Repository không filter status nên có cả thread archived.
Guest không gọi method này; guest chỉ đọc thread với guest session phù hợp.

### `create(Map body)` và `createGuest(String key)`

`create(body)` gọi private overload `create(body,null)` cho tài khoản.
`createGuest(key)` xác thực guest key bằng `guests.id` rồi gọi private overload với title dùng thử.
Hai entry point trả row conversation mới.

Private `create(Map body, UUID guest)`:

1. Nếu không có guest, yêu cầu tài khoản xác thực.
2. Gắn `user_id` hoặc `guest_session_id`, title tối đa 255,
   context type tối đa 50, status `ACTIVE`.
3. Với user, đọc những context ID được gửi rồi gọi `verifyContext` trước khi lưu.
4. Với guest, không nhận các liên kết context riêng của user.
5. Insert và trả thread.

`uuid` kiểm tra ID; `verifyContext` đảm bảo CV/gap/roadmap thuộc user, job đang active.
DTO tạo conversation dùng các JSON field snake_case như `related_resume_id`.

Ví dụ `POST /api/v1/assistant/conversations`:

```json
{
  "title": "Tu van huong Backend",
  "context_type": "resume",
  "related_resume_id": "{{resumeId}}"
}
```

### `get(UUID id)`, `getGuest(UUID id,String key)` và `detail(Map thread)`

`get` gọi `owned(id,null)` rồi `detail`.
`getGuest` giải key thành guest UUID và gọi `owned` theo guest.
`detail` copy thread vào map, thêm toàn bộ `messages` theo thời gian/ID
và `sources` của các message trong thread.
Output chi tiết này không phải history giới hạn đưa vào prompt AI;
history prompt được query riêng tối đa 20 message.

### `send(UUID id,Map body)` và `sendGuest(UUID id,Map body,String key)`

Hai method public đều có `@Transactional` và ủy quyền cho
private `send(UUID id,Map body,UUID guestId)`.
Guest key được xác thực trước khi dùng. Cùng một thuật toán gửi, nhưng quyền context/retrieval
và nơi trừ quota khác nhau.

### Private `send(UUID,Map,UUID)`: dựng input

1. `owned` xác nhận người/guest có quyền đọc thread; thread phải `ACTIVE`.
2. Message bắt buộc không rỗng, tối đa 8.000; nếu có `retrieve` thì phải boolean.
3. Input gồm message, tối đa 20 message gần nhất sắp lại theo thời gian tăng,
   cùng `context(thread,guestId)`.
4. Nếu là tài khoản và `retrieve=true`, tạo embedding message rồi query RAG.
   Guest không chạy nhánh retrieval dù gửi `retrieve=true`.
5. Bổ sung nguồn vừa query và `citation_rule` chỉ được viện dẫn nguồn thực sự được cung cấp.
6. Gọi `CAREER_CHAT` qua `ai.generate` với user hoặc `ai.generateForGuest` với guest.

Retrieval RAG chỉ lấy tối đa 5 chunk, cosine similarity tối thiểu 0.55,
đúng provider/model, document `embedding_status=COMPLETED`, không disabled.
Document phải do user này upload hoặc uploader là ADMIN.
Do đó retrieval không lấy tài liệu riêng của người khác/consultant chỉ vì vector gần nhau.

### Private `send(UUID,Map,UUID)`: ghi kết quả

Sau network call, service đọc thread `FOR UPDATE` và kiểm tra lại ACTIVE.
Nếu thread bị archive trong lúc AI sinh câu trả lời, trả 409 và không append message.

Ghi một message USER tại `sentAt` và một message ASSISTANT tại `sentAt+1 ms`
để thứ tự hai message ổn định.
Assistant row lưu answer, model, input/output tokens và retrieval flag.
Các nguồn retrieval được ghi sang bảng sources với chunk/document ID, label và similarity.
Thread cập nhật last-message/updated time; output là message ASSISTANT kèm `sources`.

Service lưu tất cả nguồn đã cấp cho model, không parse answer để kết luận model đã trích dẫn
đúng từng câu trong đó.
Nếu transaction chat rollback sau provider call, usage/quota AI vẫn được ghi/xử lý độc lập.

Ví dụ gửi:

```http
POST /api/v1/assistant/conversations/{{conversationId}}/messages
```

```json
{
  "message": "Tu CV nay, toi nen hoc them ky nang gi cho vi tri Java Backend?",
  "retrieve": true
}
```

### `archive(UUID id)`

Kiểm tra thread thuộc user, đặt `ARCHIVED` và updated time.
HTTP `DELETE /api/v1/assistant/conversations/{id}` trả 204.
Không xóa messages; gửi tiếp vào thread archived bị 409.
Guest controller hiện không cung cấp thao tác archive.

### `feedback(UUID message,Map body)`

HTTP `POST /api/v1/assistant/messages/{id}/feedback`.
Repository chỉ đọc message ASSISTANT, join thread để kiểm tra user sở hữu.
Rating nếu có phải số nguyên 1..5; `is_helpful` nếu có phải boolean;
`feedback_text` nếu có tối đa 4.000.

Insert row feedback mới và trả row.
Code dùng insert, không dùng upsert như feedback matching;
không nên coi hai API có cùng cơ chế ghi đè.

### Helper quyền sở hữu và dữ liệu context

| Phương thức | Input/output | Chi tiết |
| --- | --- | --- |
| `owned(UUID,UUID)` | Thread ID/guest ID → row | User dùng `current.owns(...,"user_id")`; guest UUID phải bằng guest_session_id. Không khớp trả 404. |
| `verifyContext(String,UUID)` | Tên field/ID → void | Switch cố định: CV thuộc user, job active, gap/roadmap thuộc user; field ngoài whitelist trả 400. |
| `context(Map,UUID)` | Thread/guest → map context | Guest chỉ `{"trial":true}`. User lấy profile và những resume/job/gap/roadmap được liên kết; roadmap thêm nodes. |
| `CareerAssistantData.contextUuid(Object)` | null/text → null/UUID | ID không hợp lệ trả 400; null nghĩa chưa liên kết. |

`com.example.intelligent_cv_matching_ai_agent.util.candidate.ai.CareerAssistantData` còn chứa `message(Map)`, `feedbackRating(Object)`, `feedbackHelpful(Object)` và `citation(UUID,Map)`. Service giữ kiểm quyền/context, retrieval/AI và ghi message/citation; util chỉ kiểm dữ liệu hoặc dựng map gồm chunk/document, nhãn, similarity, URL và provider nguồn.

Trong `context`, CV dùng canonical text nhưng không kiểm tra `READY` trong method này.
Job context được đọc theo ID để giữ context hội thoại, không kiểm tra lại deadline ở query đó.
Quy tắc yêu cầu CV READY của ATS/matching vì vậy không tự áp dụng vào chat.

Đoạn kiểm tra sau gọi AI:

```java
Map<String, Object> locked = db.getCareerAssistantConversationsByIdForUpdate(id);
if (!"ACTIVE".equals(locked.get("status")))
    throw ApiException.conflict("Conversation was closed while the response was being generated");
```

Kiểm tra trước call chặn thread vốn đã đóng; kiểm tra sau call chặn thay đổi đồng thời.
Row lock bảo vệ giai đoạn ghi kết quả và phối hợp với UPDATE archive.

<a id="svc-learningservice"></a>

## LearningService

Nguồn: [LearningService.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/candidate/learning/LearningService.java).

Util đã tách: [RoadmapValidator.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/util/candidate/learning/RoadmapValidator.java). Xem [ranh giới service/util](#doc-utils).

### Vai trò, constructor và dữ liệu

Service làm façade cho assessment và trực tiếp xây roadmap từ job/profile/CV/assessment.
`LearningController` gọi service dưới prefix `/api/v1`.
Constructor nhận `LearningRepository`, `CurrentUser`, [AiService](#svc-aiservice),
[AssessmentService](#svc-assessmentservice), [ResumeService](#svc-resumeservice), [LearningReferenceService](#svc-learningreferenceservice).

Đọc profile/skills/job/CV/assessment và catalog `learning_resources`;
ghi `gap_analyses`, `gap_analysis_skills`, `roadmaps`, `roadmap_nodes`,
`roadmap_node_resources`, `roadmap_node_prerequisites`.
Row `users` được khóa khi cấp version gap analysis.

### Những method ủy quyền assessment

| Method | HTTP/input | Output và nơi xử lý chính |
| --- | --- | --- |
| `assess(AssessmentRequest request)` | POST `/api/v1/assessments`; targetJobTitle/jobId | Gọi `assessments.create` tạo đề và session |
| `assessments()` | GET `/api/v1/assessments` | Query tối đa 100 session của user, mới nhất trước |
| `assessment(UUID id)` | GET `/api/v1/assessments/{id}` | Gọi `assessments.get`, áp dụng quy tắc ẩn đáp án |
| `answer(UUID id,AssessmentAnswerRequest answer)` | PUT `/api/v1/assessments/{id}/answers` | Gọi `assessments.answer` với questionId/selectedOption |
| `submit(UUID id)` | POST `/api/v1/assessments/{id}/submit` | Gọi `assessments.submit` chấm bài và hoàn thành |

Logic đề thi nằm ở [AssessmentService](#svc-assessmentservice).
Lớp này giữ controller gọn và tập trung nhóm chức năng học tập vào một façade.

### `createRoadmap(RoadmapRequest request)`: chọn dữ liệu đầu vào

HTTP `POST /api/v1/roadmaps`; transaction; yêu cầu role candidate.
Input gồm `targetJobTitle`, `jobId`, `resumeId`, `assessmentId`, đều có thể tùy chọn
nhưng cuối cùng phải xác định được target nghề nghiệp.

1. Đọc profile nếu có; job ID nếu có phải là job ACTIVE theo query hiện tại.
2. Nếu có CV, kiểm tra sở hữu và `READY`; CV chưa confirm trả 409.
3. Target ưu tiên request → job title → profile target; không có thì 400.
4. Nếu chỉ rõ assessment ID, lấy assessment đã `completed` của user và yêu cầu target khớp,
   so sánh target không phân biệt hoa thường.
5. Nếu không chỉ rõ assessment, lấy assessment completed/current mới nhất theo user + target.
6. Đọc profile skills và job required skills nếu có.
7. Gọi `ROADMAP_GENERATE` với toàn bộ dữ kiện và quy ước prerequisite đánh số 1-based,
   chỉ tham chiếu node trước đó, không tạo URL tài liệu chưa xác minh.

Query job trong LearningRepository hiện kiểm tra `status=ACTIVE`;
không thêm predicate deadline như MatchingRepository/CareerToolsRepository.
Tài liệu phản ánh đúng code tại thời điểm viết, không gán thêm điều kiện chưa có.

### `createRoadmap`: kiểm tra và lưu gap analysis

Output AI phải có nodes, title và skill gaps hợp lệ.
`validateNodes` kiểm tra cấu trúc node/prerequisite; title bắt buộc tối đa 255;
`skill_gaps` phải là list rồi `validateGaps` kiểm tra từng gap.

Sau khi AI đã trả output hợp lệ, khóa row user bằng `FOR UPDATE`.
SQL lấy `max(version)+1` theo user/target, bỏ `is_latest` của gap analysis cũ,
insert gap analysis mới với `COMPLETED` và completed time.

`input_source` ưu tiên theo dữ liệu có mặt:
`ASSESSMENT` → `RESUME` → `JOB` → `PROFILE`.
Đây là nhãn nguồn chính của gap analysis; prompt vẫn có thể chứa nhiều nguồn cùng lúc.

Mỗi gap ghi skill name/status/current level/target level/urgency/reasoning sang
`gap_analysis_skills`. Dữ liệu thô AI không được biến thành SQL column tùy ý.

### `createRoadmap`: lưu nodes, tài nguyên và prerequisite

Tổng giờ được tính từ `estimated_hours` của từng node bằng `stream().mapToInt(...).sum()`,
thay vì tin trường `estimated_total_hours` do AI trả.
Roadmap lưu `status=ready`, liên kết gap analysis, target/job/title và `learning_recommendations_snapshot` từ `references.recommend`.

Vòng lặp đầu tạo node theo thứ tự 1..N:

- Tìm skill ID active bằng tên không phân biệt hoa thường; không tìm thấy vẫn lưu skill name,
  skill ID null.
- Insert node với description/giờ/thứ tự.
- Lưu node UUID vào `nodeIds` để vòng sau chuyển sequence sang UUID.
- Gọi `references.resourcesForSkill(skill)`, bỏ ID thiếu/trùng rồi gắn tài nguyên có sẵn trong catalog; không tạo resource URL từ AI.

Vòng lặp sau đọc prerequisite của mỗi node, đổi số thứ tự `sequence-1` thành node UUID,
insert quan hệ và đánh dấu node được tham chiếu `is_prerequisite=true`.
Trả detail qua `roadmap(roadmapId)`.

Ví dụ request:

```json
{
  "targetJobTitle": "Java Backend Developer",
  "jobId": "{{jobId}}",
  "resumeId": "{{resumeId}}",
  "assessmentId": "{{assessmentId}}"
}
```

Các biến ID phải là UUID hợp lệ. Có thể bỏ các ID không dùng;
assessment chỉ rõ phải đã hoàn thành và tương ứng target.

### `LearningService.validateNodes(List<?> nodes)` → `RoadmapValidator.validateNodes(List<?> nodes)`

Service giữ delegate public static; thuật toán nằm ở `com.example.intelligent_cv_matching_ai_agent.util.candidate.learning.RoadmapValidator`.

Public static validator, thường được gọi trong unit test.
Yêu cầu 1..50 nodes.
Mỗi node phải có skill name chuỗi không rỗng tối đa 150,
hours là số nguyên hữu hạn 1..1.000; description nếu có là text tối đa 30.000.
Prerequisites nếu có phải list.

Với node tại index `i`, prerequisite chỉ nhận số nguyên từ 1 đến `i` và không lặp.
Index Java bắt đầu 0, sequence API bắt đầu 1.
Node thứ nhất có `i=0` nên không có prerequisite hợp lệ.
Chỉ tham chiếu node trước đó làm quan hệ không có chu kỳ.

Ví dụ hợp lệ của output AI:

```json
[
  {"skill_name":"Java","estimated_hours":20,"prerequisites":[]},
  {"skill_name":"Spring Boot","estimated_hours":30,"prerequisites":[1]},
  {"skill_name":"REST API","estimated_hours":15,"prerequisites":[1,2]}
]
```

Đây là hình dạng nodes AI trả, không phải body `RoadmapRequest`.
Prerequisite `[2]` ở node thứ hai bị từ chối vì tự tham chiếu.

### `RoadmapValidator.validateGaps(List<?> gaps)` và `RoadmapValidator.optionalText(Map,String,int)`

Service giữ delegate validateGaps; optionalText là private static trong util.

`validateGaps` giới hạn 100 gap.
Mỗi gap có skill name không rỗng tối đa 150 và gap status không rỗng tối đa 20.
Current/target level nếu có tối đa 20, reasoning nếu có tối đa 30.000.
`FlowSupport.score` kiểm tra urgency hữu hạn trong 0..100.
Code cast urgency sang int khi lưu, nên giá trị phần thập phân có thể bị bỏ;
validator không bắt urgency phải số nguyên.

Private static `optionalText` chỉ kiểm tra field hiện diện khác null có phải String
và nằm trong chiều dài cho phép không; field thiếu/null được chấp nhận.
Các output AI sai shape/giới hạn trả 502.

### `roadmaps()` và `roadmap(UUID id)`

List bỏ snapshot nội bộ khỏi từng row. Detail trả snapshot dưới key `learning_recommendations`; dữ liệu cũ thiếu snapshot được bổ sung qua `references.recommend` từ target và skill nodes. `withAssessmentRecommendations` bổ sung gợi ý học khi lấy/chấm assessment.

`roadmaps` đọc tối đa 100 roadmap của user theo thời gian tạo mới nhất.
HTTP `GET /api/v1/roadmaps`.

`roadmap` đọc roadmap theo ID/user, lấy nodes theo sequence;
với mỗi node thêm prerequisite IDs và resources active,
sau đó thêm gap skills của gap analysis vào roadmap.
HTTP `GET /api/v1/roadmaps/{id}`.
Output gồm cả `is_outdated` nếu dữ liệu CV/assessment sau đó đã làm roadmap cũ.

### `node(UUID id, UUID nodeId, RoadmapNodeStatusRequest input)`

HTTP `PATCH /api/v1/roadmaps/{id}/nodes/{nodeId}` với
`{"status":"not_started|in_progress|completed"}`. DTO kiểm tra whitelist.
Transaction khóa roadmap theo ID/user, xác nhận node thuộc roadmap.

- Nếu muốn status khác `not_started`, mọi prerequisite phải `completed`,
  nếu chưa thì 409.
- Nếu muốn reset về `not_started`, không được có dependent node đã bắt đầu.
- Nếu muốn status khác `completed`, cũng không được có dependent node đã bắt đầu.
  Điều này chặn chuyển prerequisite đã hoàn thành về in_progress trong khi node sau đang học.
- Update status và completed time: chỉ `completed` có timestamp.
- SQL tính `floor(100 * completedCount / totalCount)`; tất cả nodes completed thì roadmap
  `completed`, nếu còn node chưa xong thì `ready`.
- Trả roadmap detail mới.

Đoạn chuyển số thứ tự prerequisite thành ID:

```java
int sequence = ((Number) prerequisite).intValue();
db.updateRoadmapNodePrerequisites(nodeIds.get(i), nodeIds.get(sequence - 1));
```

`sequence-1` là bước chuyển từ numbering 1-based của AI sang index 0-based Java.
Validator chạy trước nên index tham chiếu luôn tồn tại và nằm trước node hiện tại.

<a id="svc-learningreferenceservice"></a>

## LearningReferenceService

Nguồn: [LearningReferenceService.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/shared/learning/LearningReferenceService.java). Util: [LearningReferenceData.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/util/shared/learning/LearningReferenceData.java).

Service được LearningService gọi khi trả assessment hoặc tạo/đọc roadmap. Constructor nhận LearningRepository và RoadmapReferenceCatalog. Không có endpoint riêng; không gọi website hoặc AI để tạo gợi ý. Nó đọc catalog đã có và chọn link hợp lệ.

| Method | Luồng và dữ liệu trả về |
| --- | --- |
| `recommend(String target, Collection<String> skills)` | Lọc tối đa 20 focus skills; chọn roadmap từ catalog; gom resource khóa học đã duyệt, resource theo URL roadmap và từng skill; trả provider, source_catalog_url, focus_skills, roadmaps và tối đa 15 resources. |
| `forAssessment(Map session)` | Session chưa completed trả map rỗng. Session hoàn thành lấy skill score hữu hạn trong 0..<70, ưu tiên điểm thấp, bỏ General rồi recommend theo target. |
| `resourcesForSkill(String skill)` | Null/trống trả list rỗng; tìm resource theo slug/pattern và roadmap URL rồi lọc còn tối đa 8 resources. |
| `skillSlugs(String)` (private service) | Hợp nhất alias catalog và slug fallback từ CourseLink; vẫn ở service vì cần catalog. |

`com.example.intelligent_cv_matching_ai_agent.util.shared.learning.LearningReferenceData` chứa `focusSkills`, `assessmentFocus`, `skillPattern` và `trustedResources`. Pattern escape các ký tự đặc biệt của LIKE. Resource được ưu tiên khi metadata có course_id, cần ID và URL HTTPS có host, không user-info; URL trùng chỉ giữ một. Việc đã qua bộ lọc URL không đồng nghĩa service đã fetch hoặc xác nhận nội dung hiện tại của trang.

Luồng liên quan: [RagCourseService](#svc-ragcourseservice) approve khóa học → learning_resources → recommend/resourcesForSkill → gợi ý học trong assessment/roadmap.

<a id="svc-assessmentservice"></a>

## AssessmentService

Nguồn: [AssessmentService.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/candidate/learning/AssessmentService.java).

Util đã tách: [AssessmentValidator.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/util/candidate/learning/AssessmentValidator.java). Xem [ranh giới service/util](#doc-utils).

### Vai trò, dependency và trạng thái

[LearningService](#svc-learningservice) gọi service này để tạo đề, lưu đáp án và chấm bài.
Constructor nhận `AssessmentRepository`, `CurrentUser`, [AiService](#svc-aiservice).
Bảng chính: `assessment_sessions`, `assessment_questions`, `assessment_answers`;
đọc candidate profile/job và đặt roadmap cùng target `is_outdated` khi có kết quả mới.

Status session dùng chữ thường: `in_progress` và `completed`.
Không dùng `READY/COMPLETED` của CV/job task để so sánh status assessment.

### `get(UUID id)`

Lấy session theo ID và user; thêm questions và answers.
Repository chỉ trả cột public của câu hỏi khi chưa completed:
ID/part/question/options/related skill, không có correct option hoặc explanation.
Khi completed mới trả đầy đủ question row.
Answers trả selected option/time; không cung cấp `is_correct` qua query answers.
Người dùng xem score ở session và đáp án question sau submit.

### `create(String target, UUID jobId)`

Transaction; yêu cầu role candidate.
Nếu có job ID, lấy job ACTIVE. Target ưu tiên target input, rồi job title,
rồi target profile; cần không rỗng và tối đa 255.

Gọi `ASSESSMENT_GENERATE` với target/job/profile và đề nghị 5 foundation + 5 role-specific.
Code không bắt buộc đúng 10: `validateQuestions` nhận 2..30 với cả hai phần.
Sau khi output hợp lệ, bỏ cờ `is_current` của session cũ cùng user/target,
tính số câu part 1 và part 2, insert session rồi insert từng question với sequence 1..N.
Session mới dựa vào default database cho trạng thái bắt đầu/current.

Trả `get(id)` nên correct answers vẫn bị ẩn ngay khi tạo đề.
AI generation lỗi làm transaction không tạo một bộ câu hỏi chưa đầy đủ.

### `AssessmentService.validateQuestions(List<?> questions)` → `AssessmentValidator.validateQuestions`

Service giữ delegate public static; logic nằm ở `com.example.intelligent_cv_matching_ai_agent.util.candidate.learning.AssessmentValidator.validateQuestions(List<?>)`.

Public static validator kiểm tra output provider trước khi ghi DB:

- 2..30 questions; phải có ít nhất một part 1 và một part 2.
- Part chỉ 1 hoặc 2.
- Question text String không rỗng tối đa 5.000.
- Related skill nếu có là text tối đa 150; explanation nếu có tối đa 10.000.
- Options map phải có chính xác bốn key `A/B/C/D`.
- Mỗi option là String không rỗng tối đa 2.000.
- Correct option phải là String trùng một key trong options.

Giới hạn này ngăn model trả câu hỏi thiếu lựa chọn, đáp án `E` hoặc một phần thi duy nhất.
Lỗi output là 502 vì input API có thể hợp lệ nhưng provider trả dữ liệu không đúng hợp đồng.

### `answer(UUID sessionId, UUID questionId, String selected)`

HTTP qua `PUT /api/v1/assessments/{id}/answers`.
Transaction khóa session `FOR UPDATE` theo ID/user, yêu cầu `in_progress`.
Selected option phải A/B/C/D; question ID phải thuộc session.

Upsert `(session_id,question_id)` cho phép ứng viên đổi lựa chọn trước submit.
Output chỉ question ID/selected option, chưa chấm đúng sai.
Session completed trả 409, không cho thay đáp án.

Ví dụ:

```json
{
  "questionId": "{{questionId}}",
  "selectedOption": "B"
}
```

### `submit(UUID id)`

Transaction khóa session và kiểm tra sở hữu.
Nếu đã completed, trả lại `get(id)`; submit lặp không chấm/lưu một session mới.
Trạng thái khác in_progress/completed trả 409.

SQL đếm câu chưa có answer; còn câu trống trả 400.
Sau đó cập nhật `is_correct=(selected_option=correct_option)` bằng join câu hỏi.
Group score theo skill: total, correct và `round(100*correct/total,2)`;
câu không có skill dùng `General`.
Lưu scores JSON vào session, đặt `completed`/submitted time;
roadmap cùng user/target được đặt outdated vì có assessment mới.
Output lúc này đã được phép xem correct option/explanation của câu hỏi.

Không có lời gọi AI để chấm lựa chọn: đáp án đã lưu lúc tạo đề là chuẩn so sánh.
Khóa session phối hợp answer/submit để tránh ghi đáp án trong khi bài đang hoàn tất.

<a id="svc-aiservice"></a>

## AiService

Nguồn: [AiService.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/shared/ai/AiService.java).

Util đã tách: [AiResponseData.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/util/shared/ai/AiResponseData.java). Xem [ranh giới service/util](#doc-utils).

### Vai trò và constructor

Đây là ranh giới tập trung gọi provider model thật của dự án.
Resume, matching, ATS, assessment, roadmap, salary, chat, guest trial,
consultation analysis, RAG indexing và job embedding sử dụng service này.
Controller không gọi trực tiếp SDK provider.

Constructor `AiService(...)` nhận:

| Dependency/cấu hình | Công dụng |
| --- | --- |
| `ObjectProvider<ChatModel>` | Lấy chat model nếu bean được cấu hình; cho phép ứng dụng chạy khi AI tắt |
| `ObjectProvider<EmbeddingModel>` | Embedding provider riêng; khả năng chat/embedding không đồng nhất |
| Jackson `ObjectMapper` | Serialize input và parse JSON output |
| `AiRepository` | Đọc chat model config/prompt template active mới nhất |
| `CurrentUser` | Người dùng sở hữu quota/log trong request có tài khoản |
| [EntitlementService](#svc-entitlementservice) | Reserve/settle quota user |
| [GuestAccess](#svc-guestaccess) | Reserve/settle quota guest |
| [AiUsageRecorder](#svc-aiusagerecorder) | Ghi model usage trong transaction riêng |
| `app.ai.enabled`, `app.ai.provider` | Bật khả năng gọi và tên provider |
| `spring.ai.openai.chat.model`, `app.ai.embedding-model` | Model chat mặc định và model embedding đã cấu hình |
| `app.ai.embedding-dimensions` | Bắt buộc 1024 chiều |
| `app.ai.max-output-tokens` | Giới hạn output; clamp vào 256..16.000 |

Các giá trị mặc định đọc từ annotation trong code là provider `gemini`, chat `gemini-2.5-flash-lite`,
embedding `gemini-embedding-001` và max output 2.000. Constructor từ chối provider khác `gemini`
hoặc embedding dimension khác 1024 để khớp schema V10. Model server lấy địa chỉ từ
`spring.ai.openai.base-url`; cần `GEMINI_API_KEY`. Xem [cấu hình Gemini](gemini.md).

Service đọc `ai_configurations` và `ai_prompt_templates`.
Quota đi qua service tương ứng; log ghi `ai_usage_logs`.
Không có `@Transactional` bao quanh toàn bộ provider call trong lớp này;
reservation/log được quản lý bởi những service được inject.

### `enabled()` và `embeddingsEnabled()`

`enabled` trả `configured && chatModels.getIfAvailable()!=null`.
`embeddingsEnabled` trả `configured && embeddingModels.getIfAvailable()!=null`.
Đây là kiểm tra cấu hình/bean, không gửi request thử để chứng minh Gemini/model đang nhận inference.

Có thể chat bật nhưng embedding không có hoặc ngược lại.
Các service khác phải kiểm tra khả năng thích hợp: analyze CV dùng chat,
embedding CV/job dùng embedding.

### `provider()`, `model()` và `embeddingModel()`

`provider` trả tên provider đã inject.
`model` tìm chat configuration active cùng provider, version cao nhất;
lấy `model_name` khác rỗng nếu có, nếu không dùng default.
Chuỗi method `Optional.map(...).filter(...).orElse(...)` xử lý trường hợp thiếu row/model.

`embeddingModel` trả cấu hình embedding đã inject.
Code không đọc `ai_configurations` để đổi embedding model động bằng method này.
Mọi cache/vector lookup phải sử dụng đúng model do getter tương ứng trả về.

### `generate(String feature, Map<String,Object> input)`

Entry point cho user, gọi `generateForGuest(feature,input,null)`.
Feature là mã nghiệp vụ như `CV_BUILD`, `JOB_ATS` hay `ROADMAP_GENERATE`.
Input là JSON-compatible map do service nghiệp vụ dựng.
Output là map đã parse, validate shape và bổ sung `_usage`/`_model`.

Đây không phải HTTP endpoint. Caller cần tự kiểm tra quyền sở hữu CV/job/context
trước khi đưa dữ liệu vào map.

### `generateForGuest(String feature, Map input, UUID guestId)`: dựng prompt

Nếu chat AI không enabled, trả 503 trước khi reserve.
`schema(feature)` từ chối mã feature không được hỗ trợ.
Lấy effective model và bắt đầu đồng hồ latency bằng `System.nanoTime()`.

Prompt system yêu cầu một JSON object, không markdown, đúng schema của feature;
coi CV/job/tài liệu/history là dữ liệu không đáng tin để làm instruction;
không tự tạo kinh nghiệm, chứng chỉ, nhà tuyển dụng, assessment result,
course URL đã xác minh hoặc source citation.
Ngôn ngữ lấy từ `requestedLanguage`, mặc định tiếng Việt.

Nếu có prompt template active mới nhất, bổ sung `system_prompt` và
`user_prompt_template` thành task guidance/output guidance.
Code nối những chuỗi này vào system prompt; không thực hiện phép thay placeholder
của một template engine.

Jackson serialize toàn bộ input thành user JSON.
Input JSON dài hơn 250.000 ký tự trả 400.
Hai message system/user được tách qua Spring AI `SystemMessage` và `UserMessage`;
`OpenAiChatOptions` chọn effective model, `maxTokens(maxOutputTokens)` và response format `JSON_OBJECT`. Schema được đặt trong system prompt và backend tiếp tục validate kết quả.

### `generateForGuest`: reservation và provider call

Budget reserve được ước lượng:

```text
UTF8 byte length(system)
+ UTF8 byte length(user JSON)
+ 512
+ maxOutputTokens
```

Đây là cận dự trù theo byte, không phải số token thực do tokenizer provider tính.
Guest null → `entitlement.reserve(current.id(),feature,budget)`.
Guest có UUID → `guests.reserve(guestId,feature,budget,null)`.

Sau reserve gọi `chatModels.getObject().call(new Prompt(...))`.
Theo dõi `responseReceived` để phân biệt provider chưa trả phản hồi với phản hồi không dùng được.
Nếu metadata usage có mặt, lấy prompt/completion tokens không âm.
Yêu cầu response/result/output tồn tại và output không quá 250.000 ký tự.

Parse output thành `Map.class` rồi `validate(feature,result)`.
Không có bước bóc code fence; model trả markdown thay vì JSON sẽ bị parse lỗi.
Copy result sang `LinkedHashMap`, thêm:

```json
{
  "_usage": {"input_tokens": 100, "output_tokens": 40, "total_tokens": 140},
  "_model": "effective-model-name"
}
```

Các số minh họa trên là metadata provider, không phải ước tính budget.

### `generateForGuest`: kết thúc và lỗi

Thành công: gọi `settle(...,success=true)`,
xóa biến reservation/budget để tránh settle lặp, ghi log success rồi trả output.

`ApiException` được giữ nguyên status/message; nếu đã reserve thì settle trước khi ném lại.
Những lỗi khác như network, output trống, JSON sai hoặc validate thất bại:
settle, ghi log failure với thông báo tổng quát, trả 502.

Không thể coi mọi 502 là “không mất quota”:
provider đã trả response thì tokens thực hoặc budget vẫn được tính theo `settle`.
Quota bị từ chối trước khi reserve thành công không tạo model call.
Các lỗi `ApiException` đi qua catch riêng không luôn tạo ai_usage log failure trong method này.

### `embed(String text)` và `embedForUser(UUID actor,String text)`

`embed` gọi `embedForActor(null,text)` và dùng user hiện tại.
`embedForUser` yêu cầu actor không null rồi gọi helper với actor chỉ định.
Entry point này phục vụ job indexing worker khi không có HTTP SecurityContext.

`embedForUser` không tự kiểm tra role ADMIN trong method;
caller nội bộ phải xác thực chủ usage/job task và attribution trước khi gọi.
Không có API public cho client tùy ý chọn một user khác để bị trừ quota.

Output là `float[]` có 1024 phần tử, không phải row embedding database.
Caller chịu trách nhiệm kiểm tra hash/status mới nhất trước khi publish và lưu vector.

### `embedForActor(UUID requestedActor,String text)`

Private helper dùng chung cho hai entry point:

1. Embedding phải enabled; text bắt buộc không rỗng, tối đa 200.000.
2. Actor là requested actor hoặc `current.id()`.
3. Reserve quota `EMBEDDING` với `UTF8 bytes(text)+128`.
4. Gọi `EmbeddingModel.call(new EmbeddingRequest(...))` với adapter `GeminiEmbeddingModel` native: model đã cấu hình và `outputDimensionality=1024`.
5. Lấy total tokens từ metadata nếu có; yêu cầu response/result/output tồn tại.
6. `FlowSupport.vector` yêu cầu vector đúng 1024 chiều, mọi giá trị `Float.isFinite`.
7. Settle và ghi usage success với actor/model embedding; trả vector.

Exception trong provider/result/validation: settle, ghi failure với actor,
trả 502 nếu Gemini lỗi hoặc vector không tương thích `vector(1024)`.
Reserve thực hiện trước khối try provider, nên lỗi entitlement được trả theo lỗi gốc.
Embedding API không lưu job/CV vector; nó chỉ tạo vector và quản lý usage.

Đoạn kiểm tra:

```java
float[] vector = response.getResult().getOutput();
FlowSupport.vector(vector);
```

Chỉ chiều vector bằng 1024 chưa đủ: NaN/Infinity vẫn bị chặn trước SQL cast.
`Float.isFinite` là kiểm tra từng phần tử, không phải một điều kiện cho cả mảng.

### `settle(UUID reservation, UUID guestReservation, int budget, int actual, boolean responded, boolean success)`

Helper tính lượng quota tiêu thụ:

| Provider đã trả response? | Có usage thực dương? | Consumed |
| --- | --- | --- |
| Không | Bất kỳ | 0, user reservation bị cancel |
| Có | Có | `min(actual,budget)` |
| Có | Không/0 | Toàn budget |

User response nhận được → `entitlement.complete`; chưa response → `entitlement.cancel`.
Guest dùng `guests.settle` với consumed và success.
`success=false` không tự làm consumed bằng 0; JSON sai sau một response vẫn là một call
provider đã thực hiện.

Khi provider không báo usage, log token có thể bằng 0 nhưng quota tiêu thụ là budget.
Số token log và số quota đã charge vì vậy cần đọc theo đúng ý nghĩa của từng trường.

### `log(...)` và `logForUser(...)`

`log` giải user ID từ CurrentUser nếu không phải guest; nếu không lấy được user thì giữ null.
Sau đó gọi `logForUser`.

`logForUser` dựng map với user/guest, feature, provider, model, input/output/total tokens,
latency milliseconds, success và error rồi gọi `usageRecorder.record`.
Latency lấy chênh lệch `System.nanoTime` chia 1.000.000 để đổi nanosecond sang millisecond.
Không ghi raw CV, toàn prompt hay API key trong map log này.

Job worker gọi logForUser với actor rõ ràng nên model usage có người sở hữu
ngay cả khi không có tài khoản trong SecurityContext của thread nền.

### `AiResponseData.schema(String feature)` — util

Public static switch ở `com.example.intelligent_cv_matching_ai_agent.util.shared.ai.AiResponseData` trả chuỗi mô tả schema prompt, không phải Java record hay JSON Schema validator.
Các feature được hỗ trợ:

| Feature | Dữ liệu chính yêu cầu AI trả |
| --- | --- |
| `CV_BUILD`, `CV_PARSE` | Full name/summary và experience/education/projects/certifications/skills |
| `CV_ANALYZE` | Overall score/ATS score 0..100, summary, strengths/issues/suggestions |
| `JOB_MATCH_RERANK` | Results gồm job UUID, score 0..1, reason, strengths/gaps |
| `JOB_ATS` | ATS score 0..100, strengths/missing skills/improvement suggestions |
| `ASSESSMENT_GENERATE` | Questions có part, A/B/C/D, correct option, related skill/explanation |
| `ROADMAP_GENERATE` | Title/hours/summary/gaps/nodes/prerequisites |
| `APPLICATION_EMAIL` | Subject/email/cover letter |
| `CAREER_CHAT` | Answer |
| `SALARY_ESTIMATE` | Estimated min/max/currency/reasoning |

Feature không có trong switch trả 400.
Các service nghiệp vụ dùng validator riêng để kiểm tra sâu theo domain
như prerequisite roadmap, số chiều vector hoặc job IDs trong rerank.

### `AiResponseData.requestedLanguage(Map<String,Object> input)` — util

Public static helper trong util: null input/field → vi;
toString/lowercase với Locale.ROOT; chỉ en/vi được dùng, giá trị khác → vi.
Service caller không thêm language thì prompt mặc định tiếng Việt.
Ví dụ MatchingService/AssessmentService hiện không đưa language vào map AI.

### `AiResponseData.validate(String feature, Map<String,Object> result)` — util

Service vẫn quản lý provider call, catch, reservation và usage; util chỉ kiểm output đã parse.

Từ chối result null/rỗng.
Switch kiểm tra những field cơ bản theo feature:

- CV build/parse: năm section phải array.
- CV analyze: hai score 0..100, summary nonblank và strengths/issues/suggestions array.
- Job rerank: results array; chi tiết job ID/score do MatchingService kiểm tra tiếp.
- ATS: score 0..100 và ba array.
- Assessment: questions array; nội dung từng câu do AssessmentService kiểm tra.
- Roadmap: title nonblank, nodes/skill gaps array; DAG/gap do LearningService kiểm tra.
- Email: subject/email/cover letter nonblank.
- Chat: answer nonblank.
- Salary: min/max số hữu hạn 0..`1e15`, currency String;
  CareerToolsService còn kiểm interval, mức tối đa và whitelist currency.

Các helper validation ném `IllegalArgumentException`;
generate catch chuyển thành 502 và xử lý reservation/log.
Việc kiểm shape tại AiService không thay thế việc kiểm nghiệp vụ ở caller.

### Helper private của `AiResponseData`: `array(Map,String)`, `string(Map,String)`, `score(Map,String,double)`

Ba helper thuộc util, không còn là method của AiService.

| Helper | Điều kiện hợp lệ | Điểm đọc code |
| --- | --- | --- |
| `array` | Field là List, size ≤200 | Đây là validator, trả void; khác `Values.array(Object)` chuyển missing value thành list rỗng |
| `string` | Field là String và không blank | Không có một giới hạn chiều dài chung riêng từng field tại helper này |
| `score` | Field là Number, finite, trong 0..max | `instanceof Number n` pattern matching lấy biến n chỉ khi đúng kiểu |

Ví dụ gọi trong code nghiệp vụ:

```java
Map<String, Object> result = ai.generate(
    "JOB_ATS",
    Map.of("resume_text", confirmedText, "job", activeJob, "language", "vi")
);
```

Input object phải do service dựng sau kiểm tra quyền; output được đọc bằng key theo schema.
`Map.of` không nhận null, nên code có dữ liệu tùy chọn thường dùng `LinkedHashMap`
hoặc helper `Values.fields` để giữ các giá trị null hợp lệ với database.

<a id="svc-aiusagerecorder"></a>

## AiUsageRecorder

Nguồn: [AiUsageRecorder.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/shared/ai/AiUsageRecorder.java).

### Vai trò và constructor

Service rất nhỏ nhưng có vai trò quan trọng: giữ lịch sử call model dù nghiệp vụ gọi AI
về sau bị rollback. Constructor `AiUsageRecorder(AiUsageRecorderRepository db)`
nhận repository ghi bảng `ai_usage_logs`.
[AiService](#svc-aiservice) là nơi dựng các field và gọi recorder.

### `record(Map<String,Object> fields)`

Input là map log chứa user/guest, feature/provider/model, tokens, latency, success/error.
Output void; repository insert row log.

```java
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void record(Map<String, Object> fields) {
    db.insertAiUsageLogs(fields);
}
```

`REQUIRES_NEW` tạm dừng transaction bên ngoài nếu có, mở transaction riêng cho log.
Ví dụ provider đã sinh roadmap nhưng validation/SQL roadmap sau đó thất bại:
row roadmap có thể rollback, log call đã commit vẫn tồn tại.
AiService gọi recorder qua bean inject nên transaction proxy có cơ hội áp dụng.

Method này không reserve/complete quota và không đếm tokens từ prompt.
Nó chỉ persist số liệu do AiService đưa vào.
Service không tự bỏ qua lỗi insert: lỗi database vẫn có thể truyền về caller.
Phân biệt recorder với [EntitlementService](#svc-entitlementservice):
recorder lưu lịch sử, entitlement quản lý quyền và số dư quota.

<a id="svc-billingservice"></a>

## BillingService

[Nguồn BillingService.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/shared/billing/BillingService.java)

### Vai trò và đường gọi

Đây là lớp ghi nghiệp vụ payment: tạo đơn và xử lý event đã được xác minh. Nó được gọi bởi [BillingQueryService](#svc-billingqueryservice), không nhận HTTP trực tiếp.
Controller tương ứng là BillingController tại /api/v1. Service không mở checkout, tạo QR hay truy vấn giao dịch ngân hàng.
MANUAL_GATEWAY hiện là tên provider cho hợp đồng webhook generic.

Constructor nhận BillingRepository và property app.payment.provider. PAYMENT_PROVIDER mặc định MANUAL_GATEWAY được ánh xạ tại application.yaml.
Service giữ provider trong field final, không đọc lại biến môi trường từng request.

Các bảng chính:

| Bảng | Đọc/ghi |
| --- | --- |
| subscription_tiers | Đọc tier active và giá/currency hiện hành |
| payment_orders | Tạo PENDING, khóa và finalization |
| payment_webhook_events | Receipt chống replay theo provider/event_id |
| users | Khóa account khi đổi gói |
| user_subscriptions | Hủy gói ACTIVE cũ, tạo gói mới |
| invoices | Tạo hóa đơn PAID cho order |
| notifications | Thông báo PAYMENT_SUCCESS trong ứng dụng |

Cả order() và webhook() có @Transactional. Exception nghiệp vụ RuntimeException khiến thay đổi DB của lượt bị rollback.
activate() chạy bên trong transaction webhook, không phải transaction ngoài riêng.

### order(UUID user, UUID tierId, int months)

**Đầu vào:** UUID chủ đơn, UUID tier và số tháng.
**Đầu ra:** Map row payment_orders vừa lưu; có id, amount, currency, status.
**HTTP caller:** POST /api/v1/payments/orders.
**Quyền:** caller HTTP authenticated; UUID user do BillingQueryService lấy từ CurrentUser.

Luồng code:

1. Kiểm months trong 1–12; ngoài khoảng trả400.
2. Repository tìm tier theo id và is_active=true; không có trả404.
3. Chuyển price_monthly thành BigDecimal.
4. Giá <=0 trả400: FREE không cần mua đơn.
5. amount = priceMonthly × months.
6. Tổng vượt99999999.99 trả400.
7. Sinh UUID order.
8. Insert user_id/tier_id/duration_months/amount/currency/payment_provider.
9. provider_order_id hiện dùng chuỗi UUID order.
10. status PENDING, created_at là thời điểm server.
11. Đọc lại row và trả về.

BigDecimal được dùng để tính tiền thay vì số thực double. Giá/currency lấy từ DB, không lấy amount do frontend gửi.
Core method nhận user như tham số; bản thân nó không dùng CurrentUser để kiểm account. API an toàn nhờ caller truyền ID từ bearer, không cho client chỉ định owner.
Chưa có thao tác charge bên ngoài transaction này.

~~~json
{
  "tierId": "{{paidTierId}}",
  "durationMonths": 1
}
~~~

Lưu json.id thành paymentOrderId. Lưu json.provider_order_id, json.amount và json.currency để đối chiếu webhook.
Đơn PENDING không làm thay đổi entitlement.

### webhook(PaymentWebhookRequest event)

**Đầu vào:** event DTO đã được BillingQueryService xác minh HMAC và parse.
**Đầu ra:** accepted, duplicate và orderId.
**HTTP caller:** POST /api/v1/payments/webhook, không dùng bearer.
**Trust boundary:** method này không tự kiểm signature; không gọi trực tiếp từ đường HTTP mới mà bỏ verifier.

Validation thực hiện trong service vì DTO webhook không có Bean Validation annotations:

| Điều kiện | Kết quả lỗi |
| --- | --- |
| orderId/providerOrderId/eventId/amount/currency null | 400 |
| eventId blank hoặc >255 | 400 |
| providerOrderId >255 | 400 |
| status không COMPLETED/FAILED/CANCELLED | 400 |
| Không tìm được order | 404 |
| Provider hoặc provider reference không khớp | 400 |
| Số tiền/currency không khớp | 400 |
| Event ID đã gắn order khác | 409 |
| Order đã final sang trạng thái khác | 409 |

Luồng sau validation:

1. SELECT payment_orders ... FOR UPDATE.
2. So configured provider với payment_provider của đơn.
3. So event.providerOrderId với provider_order_id.
4. So amount bằng BigDecimal.compareTo(), không yêu cầu cùng scale.
5. Trim currency trong row rồi so exact với currency event.
6. Insert receipt với ON CONFLICT(provider,event_id) DO NOTHING.
7. Nếu receipt đã có, đọc payment_order_id cũ.
8. Cùng order trả duplicate=true; khác order trả409.
9. Nếu receipt mới nhưng order không PENDING, so terminal status.
10. Terminal status giống event trả duplicate=true, không chạy activate lần nữa.
11. Terminal status khác event trả409 và rollback receipt mới.
12. PENDING + COMPLETED gọi activate(order).
13. PENDING + FAILED/CANCELLED chỉ cập nhật status đơn.
14. Trả accepted=true, duplicate=false cho lượt xử lý mới.

Đoạn code quyết định khả năng replay:

~~~java
int received = db.updatePaymentWebhookEvents(provider, event.eventId(), event.orderId());
if (received == 0) {
    Map<String, Object> previous = db.getPaymentWebhookEventsByProvider(provider, event.eventId());
    if (!event.orderId().equals(previous.get("payment_order_id")))
        throw ApiException.conflict("Event ID reused for another order");

    return Map.of("accepted", true, "duplicate", true, "orderId", event.orderId());
}
~~~

Unique receipt là đảm bảo trong PostgreSQL, không phải set trong RAM. Khóa order khiến hai callback cùng đơn được xử lý theo thứ tự.
Receipt lưu identity event/order; nó không lưu bản hash toàn bộ payload. Event cùng ID/cùng order được nhận như duplicate sau khi provider/amount/currency vẫn khớp.
Một event ID mới nhưng terminal status giống đơn cũng được xem là duplicate về kết quả nghiệp vụ. Không phát hành gói/hóa đơn lần hai trong hai nhánh này.

~~~json
{
  "orderId": "{{paymentOrderId}}",
  "providerOrderId": "{{providerOrderId}}",
  "eventId": "{{paymentEventId}}",
  "status": "COMPLETED",
  "amount": 99000,
  "currency": "VND"
}
~~~

Amount99000 chỉ minh họa BASIC một tháng của seed; dùng số tiền thực của order. Header/chữ ký được mô tả tại BillingQueryService.webhook().
HTTP200 accepted không xác nhận thanh toán ngân hàng đã diễn ra; adapter gửi event chịu trách nhiệm xác minh giao dịch bên ngoài.

### activate(Map<String,Object> order) — private

Helper ghi kết quả COMPLETED; không trả dữ liệu riêng. Chỉ được gọi từ webhook() khi event mới và đơn còn PENDING.

Các bước:

1. Lấy user_id từ row order.
2. Khóa users bằng FOR UPDATE.
3. Chuyển mọi user_subscription ACTIVE cũ của account thành CANCELLED.
4. Lấy duration_months từ order.
5. Tạo user_subscription mới ACTIVE bắt đầu now.
6. expires_at = now.plusMonths(months), auto_renew=false.
7. Finalize order COMPLETED, completed_at và subscription_id.
8. Insert invoices với invoice_number EN-<order UUID>.
9. subtotal=amount, tax_amount=0, total_amount=amount.
10. Invoice PAID, issued_at/paid_at cùng now.
11. Insert notification PAYMENT_SUCCESS tham chiếu PAYMENT_ORDER.

Khóa account bảo vệ concurrent activation của nhiều đơn cùng user. Mỗi đơn mới có thể thay gói; gói ACTIVE cũ bị hủy ngay.
Thời gian còn dư không được cộng vào gói mới. Không refund gói cũ, không tự auto-renew charge.
Invoice/subscription/notification cùng transaction với đơn và receipt. Nếu insert invoice hoặc constraint lỗi, toàn bộ kết quả event rollback.
PAYMENT_SUCCESS là notification DB, không gọi SMTP.

Luồng đọc quyền sau đó đi qua [EntitlementService.effectiveTier()](#svc-entitlementservice). Scheduler hết hạn nằm tại [SubscriptionMaintenance](#svc-subscriptionmaintenance).

<a id="svc-billingqueryservice"></a>

## BillingQueryService

[Nguồn BillingQueryService.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/shared/billing/BillingQueryService.java)

### Vai trò, dependency và constructor

Lớp này nối BillingController với core payment/quota và SQL đọc theo owner. Mọi endpoint BillingController dùng service này.
Nó có năm dependency constructor:

| Dependency | Mục đích |
| --- | --- |
| BillingQueryRepository | Query owner-only, reports, invoices và cancel |
| CurrentUser | UUID account của request |
| BillingService | Tạo order/xử lý event |
| EntitlementService | Summary quyền hiện hành |
| WebhookVerifier | HMAC/time window trước parse JSON |

Field mapper là JsonMapper tạo một lần bằng builder. Service trả Map/List vì đa số projection là row SQL.
Các trường SQL như user_id/provider_order_id thường snake_case. Quota list public có inner JSON featureCode/quotaValue/quotaPeriod do SQL tạo.

Bảng được dùng: subscription_tiers, subscription_quotas, user_subscriptions, payment_orders, payment_reports và invoices.
Các bảng side effect webhook thuộc BillingService.

Không có @Transactional trên lớp hoặc các method của BillingQueryService. Tạo đơn/webhook nhận transaction từ BillingService.
cancel()/report() là các bước repository riêng, không nên mô tả như một transaction service chung.

### tiers()

**Input:** không có.
**Output:** List<Map> active tiers.
**API:** GET /api/v1/subscriptions/tiers, PUBLIC.

Gọi db.listSubscriptionQuotasByTierId() không truyền user. SQL chọn st.is_active, sort price_monthly.
Mỗi tier kèm quotas array aggregate theo tier_id. Tier không có quota trả array rỗng nhờ COALESCE.
Không khai báo page/limit/offset query cho API này.

Đây là giá cấu hình hiện hành; không hardcode seed BASIC/PRO vào client. Method không khẳng định tier nào thích hợp với nhu cầu user.

### subscription()

**Input:** không có.
**Output:** Map tier/quotas/tokensUsedThisMonth.
**API:** GET /api/v1/subscriptions/me, USER.

Lấy CurrentUser.id(). Delegate entitlement.summary(id).
Không đọc client-supplied owner ID. Không tự tạo FREE subscription row khi user chưa mua gói.
Fallback FREE là kết quả effectiveTier(), không cần insert gói mặc định.

### history()

**Input:** không có.
**Output:** List user_subscriptions của account.
**API:** GET /api/v1/subscriptions/history, USER.

Repository nhận user.id(). JOIN tier để bổ sung display_name.
Sort created_at giảm dần, LIMIT100. Không lọc chỉ ACTIVE; có cả lịch sử CANCELLED/EXPIRED.
ID trong row là subscription UUID, khác tier_id.

### cancel(UUID id)

**Input:** UUID subscription cần hủy.
**Output:** message Subscription cancelled.
**API:** POST /api/v1/subscriptions/{id}/cancel, USER, body none.

1. Query id + user_id để xác nhận owner.
2. Không có row hoặc thuộc user khác trả404.
3. Update status CANCELLED, auto_renew=false.
4. Set cancelled_at/updated_at=now.
5. Trả message.

Không kiểm phải đang ACTIVE trước update. Có thể hủy lại một row thuộc user và vẫn nhận message.
Quyền paid dừng khi effectiveTier không còn tìm thấy ACTIVE. Không tự refund hoặc gọi provider.
Các bước get/update không nằm trong @Transactional method này.

### order(PaymentOrderRequest body)

**Input:** DTO tierId/durationMonths.
**Output:** payment order Map.
**API:** POST /api/v1/payments/orders, USER.

Lấy owner từ CurrentUser.id(). Delegate billing.order(owner,body.tierId(),body.durationMonths()).
Controller @Valid yêu cầu tierId nonnull và durationMonths1–12. Thiếu primitive durationMonths thành0 rồi bị validation.
Body không có ownerId, amount hay payment status làm nguồn quyết định. Chi tiết transaction/giá nằm tại [BillingService.order()](#svc-billingservice).

### orders()

**Input:** không có.
**Output:** List payment_orders.
**API:** GET /api/v1/payments/orders, USER.

Query chỉ user_id=current user. Sort created_at DESC, LIMIT100.
Không query pagination hay status filter ở controller. PENDING trong danh sách không phải paid subscription.

### order(UUID id) — overload đọc

**Input:** UUID order.
**Output:** Map order thuộc account.
**API:** GET /api/v1/payments/orders/{id}, USER.

db.getPaymentOrdersById(id,user.id()) đặt owner condition ngay trong SQL. Không đọc order của account khác rồi trả một phần.
Thiếu/không owner trả404. Order COMPLETED có subscription_id để liên kết history.
Method không query lại gateway.

### report(UUID id, PaymentReportRequest body)

**Input:** UUID order và subject/description.
**Output:** payment_reports row vừa tạo.
**API:** POST /api/v1/payments/orders/{id}/reports, USER.

1. Gọi order(id) để xác nhận owner.
2. Insert payment_order_id/id user/subject/description.
3. Status OPEN, created_at now.
4. Đọc lại report theo UUID server tạo.
5. Trả row.

DTO subject nonblank/max255, description nonblank/max10000. Report có thể tạo cho order ở trạng thái khác nhau; method không yêu cầu COMPLETED.
Không sửa status payment hoặc hoàn tiền. Admin resolve report là workflow service admin riêng.
Đường đọc lại by report ID an toàn vì ID vừa tạo trong method, không từ input tùy ý.

### reports()

**Input:** không có.
**Output:** List reports thuộc current user.
**API:** GET /api/v1/payments/reports, USER.

WHERE user_id trong repository. ORDER BY created_at DESC LIMIT100.
Không có query status/page/offset. Có thể đọc resolution đã được admin cập nhật nếu row có dữ liệu đó.

### invoices()

**Input:** không có.
**Output:** List invoices owner-only.
**API:** GET /api/v1/invoices, USER.

Repository WHERE user_id. ORDER BY issued_at DESC LIMIT100.
Đơn chưa COMPLETED thường chưa có invoice. Array rỗng là kết quả hợp lệ.
Không render hoặc tải PDF hóa đơn.

### invoice(UUID id)

**Input:** UUID invoice, không phải UUID order.
**Output:** Map invoice.
**API:** GET /api/v1/invoices/{id}, USER.

SQL id + user_id. Không có/không owner trả404.
Trả amount/status/issued_at theo dữ liệu đã lưu. Không phát hành invoice mới, không SMTP.

### webhook(String timestamp, String signature, String body)

**Input:** raw HTTP body và hai header.
**Output:** Map accepted/duplicate/orderId từ core service.
**API:** POST /api/v1/payments/webhook.
**Authorization:** route PUBLIC nhưng bắt buộc signed webhook.

Luồng:

1. body.length()>65536 →400.
2. verifier.verify(timestamp,signature,body).
3. Parse nguyên body sang PaymentWebhookRequest.
4. Parse lỗi RuntimeException →400 Invalid webhook JSON.
5. Delegate billing.webhook(event).

Body limit là Java char count, không phải byte size. Chữ ký được kiểm trước parse; không serialize JSON rồi mới verify.
WebhookVerifier đọc app.payment.webhook-secret/PAYMENT_WEBHOOK_SECRET. Secret cần ít nhất32 byte UTF-8, thiếu trả503.
Timestamp phải Unix seconds, trong ±300 giây; invalid/expired trả401. Signature phải64 ký tự hex, HMAC-SHA256:
UTF8(timestamp + "." + rawBody). So byte chữ ký bằng MessageDigest.isEqual.

~~~http
POST /api/v1/payments/webhook
Content-Type: application/json
X-Payment-Timestamp: <Unix-seconds>
X-Payment-Signature: <64-hex-HMAC>
~~~

Giữ chính xác body sau thay biến, không pretty-print lại sau ký. Postman input/script đầy đủ nằm trong postman-api-guide.md.
Service không chứa checkout/provider SDK thực. Đừng dùng JWT secret hoặc user token thay payment webhook secret.

<a id="svc-entitlementservice"></a>

## EntitlementService

[Nguồn EntitlementService.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/shared/billing/EntitlementService.java)

### Vai trò, caller và constructor

Constructor chỉ nhận EntitlementRepository. Service xử lý effective tier và accounting quota AI cho user đăng nhập.
Caller chính là [AiService](#svc-aiservice), bao gồm gọi embedding attributed actor. [BillingQueryService](#svc-billingqueryservice) dùng summary() để hiển thị quota.
Guest quota được [GuestAccess](#svc-guestaccess) xử lý riêng.

Bảng chính: users, user_subscriptions, subscription_tiers, subscription_quotas và ai_token_reservations.
ai_usage_logs là telemetry provider, không phải nguồn tính quota ở đây.

Core trạng thái reservation:

~~~text
reserve -> RESERVED
provider có phản hồi -> complete -> COMPLETED
không nhận phản hồi -> cancel -> CANCELLED
~~~

reserve()/complete()/cancel() có propagation REQUIRES_NEW. Accounting có transaction riêng với nghiệp vụ gọi AI.
Lỗi lưu kết quả CV/chat sau provider không tự rollback lượng token đã tính. Không có endpoint public để client tự reserve hoặc tự báo actual usage.

### reserve(UUID user, String feature, int tokenBudget)

**Input:** owner UUID, feature code và budget dự kiến.
**Output:** UUID ai_token_reservations mới.
**Caller:** AiService trước gọi chat/embedding provider.

Luồng:

1. Budget ngoài1–200000 →400.
2. Khóa users ACTIVE bằng FOR NO KEY UPDATE.
3. Tìm effective tier.
4. Tìm quota feature AI_TOTAL_TOKENS.
5. Thiếu global quota →503.
6. Tìm optional quota riêng của feature.
7. Quota riêng bằng0 →403 Feature unavailable.
8. Tính usage global trong quota period.
9. used + budget vượt global quota →429.
10. Nếu có quota riêng, tính usage feature đúng period riêng.
11. Vượt quota feature →429.
12. Insert RESERVED, reserved_tokens=budget, consumed_tokens=0.
13. created_at now; trả UUID.

Không lấy user ID tùy ý từ body HTTP. Caller đang authenticated hoặc worker có actor đã được xác minh.
Repository không tìm được ACTIVE user trả404 qua SqlStore.one. Method không giới hạn chỉ role CANDIDATE; actor admin cũng dùng accounting user.

Lock user là điểm serialize kiểm budget. Hai request cùng user không cùng nhìn usage cũ rồi cùng reserve vượt quota.
Reservation chưa xử lý vẫn tính full reserved_tokens.

Đoạn quyết định quota riêng:

~~~java
var specific = db.findSubscriptionQuotasByTierId2(tier.get("id"), feature);
if (specific.isPresent() && ((Number) specific.get().get("quota_value")).longValue() == 0)
    throw ApiException.forbidden("Feature unavailable in this subscription");
~~~

Không có quota riêng không đồng nghĩa feature bị tắt; global quota vẫn áp dụng. Service còn kiểm quota>=0 trước so ngân sách.
Đó là xử lý value nội bộ âm như unlimited, không phải API admin cho phép nhập âm. SubscriptionQuotaRequest của admin có @Min(0).

### period(Object raw) — private

**Input:** quota_period raw hoặc null.
**Output:** string dùng chọn SQL boundary.

| Giá trị đầu vào | Kết quả |
| --- | --- |
| DAILY | day |
| YEARLY | year |
| LIFETIME | all |
| MONTHLY/null/khác | month |

Uppercase dùng Locale.ROOT để không phụ thuộc locale process. Null mặc định MONTHLY.
Repository chỉ dùng boundary day/month/year/all do service tạo. Không đưa query period tự do từ client vào string SQL.

### usage(UUID user, String feature, String period) — private

Delegate db.tokensUsed(user,feature,period), trả long. feature=null tính tổng; feature có giá trị chỉ tính feature đó.
SQL COUNT accounting gồm RESERVED và COMPLETED. RESERVED dùng reserved_tokens, COMPLETED dùng consumed_tokens.
CANCELLED không tính. day/month/year dùng created_at>=date_trunc(period,now()).
all không có time boundary. Ranh giới này theo clock/timezone PostgreSQL, không ngày client tự chọn.

### effectiveTier(UUID user)

**Input:** UUID user.
**Output:** Map subscription_tiers effective.
**Caller:** reserve()/summary(); có thể được service khác dùng nội bộ.

Repository tìm user_subscription ACTIVE, tier is_active=true. expires_at phải null hoặc >now().
Chọn started_at mới nhất. Không tìm paid/active tier hợp lệ → tìm tier_code FREE/is_active=true.
Thiếu FREE →503 Free subscription tier is not configured.

Hết hạn quyền có hiệu lực ngay ở query. Không đợi [SubscriptionMaintenance](#svc-subscriptionmaintenance) đổi status sang EXPIRED.
Không tự insert subscription FREE. Nếu admin tắt paid tier, tier đó không còn effective theo query.
Không cộng quota còn dư của gói cũ khi fallback.

### complete(UUID reservation, int actualTokens)

**Input:** reservation UUID và usage được caller tính.
**Output:** void; cập nhật DB.
**Transaction:** REQUIRES_NEW.

1. SELECT reservation FOR UPDATE.
2. Không có row →404.
3. Status khác RESERVED →409 Reservation already finalized.
4. actual<0 hoặc actual>reserved →400.
5. Update consumed_tokens=actual và status=COMPLETED.

Remaining budget không được giữ như usage khi actual thấp hơn reserved. Đây là cập nhật accounting, không thực hiện refund tiền payment.
complete không idempotent cho lần gọi thứ hai; lần sau trả409. Row lock ngăn hai settlement cùng đổi reservation.
Service không tự đọc metadata provider; caller chịu trách nhiệm tính actual.

AiService.settle() là cầu nối quan trọng: responded=true, actual>0 →min(actual,budget).
responded=true nhưng không có usage đáng tin →budget. responded=false →cancel reservation.
Provider đã trả nhưng JSON/nghiệp vụ sau đó thất bại vẫn có thể được complete. Xem [AiService](#svc-aiservice) để biết xử lý respond/success trong từng feature.

### cancel(UUID reservation)

**Input:** UUID reservation.
**Output:** void.
**Transaction:** REQUIRES_NEW.

SQL chỉ đổi status CANCELLED khi hiện RESERVED. Không lock/đọc row trước bằng service.
Không raise409 nếu row đã COMPLETED/CANCELLED hoặc không tồn tại; update có thể0. Không thể dùng cancel để xóa accounting của một COMPLETE đã tính.
Cancel không xóa row để vẫn giữ lịch sử accounting.

### summary(UUID user)

**Input:** UUID account.
**Output:**

~~~json
{
  "tier": {"id": "<tier UUID>", "tier_code": "FREE"},
  "quotas": [],
  "tokensUsedThisMonth": 0
}
~~~

1. effectiveTier(user).
2. Query tất cả quota của tier, sorted feature_code.
3. usage(user,null,"month").
4. Trả Map.of tier/quotas/tokensUsedThisMonth.

tokensUsedThisMonth luôn boundary tháng để hiển thị. Nếu một quota là DAILY/YEARLY, field này không phải remaining quota period đó.
Summary không reserve, không gọi AI. Không nhất thiết là snapshot transaction chung của cả ba query.

<a id="svc-subscriptionmaintenance"></a>

## SubscriptionMaintenance

[Nguồn SubscriptionMaintenance.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/shared/billing/SubscriptionMaintenance.java)

### Vai trò và constructor

Scheduled service giữ status subscription và tạo notification trong ứng dụng. Không gọi SMTP, gateway hoặc AI.
Dependency duy nhất là SubscriptionMaintenanceRepository. Bảng: user_subscriptions, subscription_renewal_notifications, notifications.

Constructor nhận:

| Property | Default | Hành vi |
| --- | --- | --- |
| app.billing.maintenance-enabled | true | false bỏ toàn bộ DB work |
| app.billing.maintenance-batch-size | 500 | Clamp1–1000 |
| app.billing.maintenance-cron | 0 0 8 * * * | @Scheduled, mặc định08:00 |
| app.billing.maintenance-zone | Asia/Ho_Chi_Minh | Múi giờ lịch |

Cron/zone được dùng trong annotation, không phải constructor field. BILLING_MAINTENANCE_ENABLED ánh xạ enabled trong application.yaml.
Cron/batch có thể override bằng Spring properties. Có @EnableScheduling trong config của dự án.

### maintain()

**Input/output:** không tham số, void.
**Caller:** Spring scheduler.
**Transaction:** @Transactional cho toàn lượt.

1. enabled=false →return ngay.
2. Query batch subscriptions ACTIVE đã expires_at<=now().
3. SQL khóa due rows FOR UPDATE SKIP LOCKED.
4. Update EXPIRED/auto_renew=false/updated_at.
5. Với mỗi row, notifyOnce(...,"EXPIRED",...).
6. Query batch ACTIVE còn >1day và <=7days.
7. Loại rows đã có receipt RENEWAL_7D.
8. notifyOnce(...,"RENEWAL_7D",...).
9. Query batch ACTIVE còn >now và <=1day.
10. Loại rows đã có receipt RENEWAL_1D.
11. notifyOnce(...,"RENEWAL_1D",...).

Batch applies riêng cho ba nhóm, không chỉ500 tổng. Có thể xử lý tới ba nhóm batch trong một lượt.
SQL sort expires_at để xử lý gần nhất trước. SKIP LOCKED cho phép replica khác xử lý row còn lại thay vì chờ cùng row.
Unique receipt tiếp tục bảo vệ nếu hai lượt thử cùng notification type.

“Nhắc7 ngày” là window >1day..7days, không đúng một thời điểm duy nhất trước7 ngày. Một gói đã gần hết hạn<=1day sẽ vào nhóm1D thay vì7D.
Nếu scheduler bỏ lỡ window thì không tạo bù7D khi đã expired. Entitlement đã fallback FREE trước khi maintain chạy.
Notification receipt và insert notification cùng transaction.

### notifyOnce(Map subscription, String type, String title, String content)

**Visibility:** package-private, có test gọi trực tiếp.
**Output:** boolean true nếu tạo notification mới, false nếu receipt đã có.

1. Insert subscription_renewal_notifications(user,subscription,type,sent_at).
2. ON CONFLICT(subscription_id,notification_type) DO NOTHING.
3. inserted=0 →false.
4. Insert notifications user/type/title/content.
5. reference_type SUBSCRIPTION, reference_id subscription UUID.
6. created_at now, return true.

Không dùng Set hay memory flag để chống trùng. Unique constraint bảo vệ qua restart và replica.
Được gọi trong maintain transaction nên nếu insert notification thất bại, receipt rollback. Helper tự nó không có @Transactional riêng khi gọi trực tiếp ngoài scheduler.
Không cần MAIL_ENABLED để tạo notification. Auto_renew là dữ liệu cờ; job không charge hoặc kéo dài subscription.

<a id="svc-consultationservice"></a>

## ConsultationService

[Nguồn ConsultationService.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/shared/consultation/ConsultationService.java)

### Vai trò, caller và dữ liệu

Constructor nhận ConsultationRepository và CurrentUser. Bốn controller gọi các phần của service:
CandidateConsultationController, ConsultationController, AdminConsultationController và ConsultantConsultationController.

Đây là trao đổi người thật qua thread/messages/SSE. Không gọi AI chat hoặc quota token.
Bảng chính consultation_threads/consultation_messages. candidateProfile đọc users/candidate_profiles/candidate_profile_skills/skills/resumes.
assign ghi audit_logs. Service không tạo notification DB cho mỗi consultation message.

Subscriber là record private gồm UUID userId và SseEmitter. subscribers là ConcurrentHashMap<UUID,CopyOnWriteArrayList<Subscriber>>.
Map và emitter sống trong process, không nằm trong PostgreSQL. Messages có lịch sử DB; danh sách connection SSE không bền qua restart.

Quyền theo method, không theo package controller:

| Operation | Quyền thực |
| --- | --- |
| create | CANDIDATE |
| assign | ADMIN |
| list/detail/messages/read/close/events/profile | Owner candidate, assigned consultant hoặc ADMIN |

ConsultantConsultationController không tự gate mọi request bằng role consultant. Người candidate owner và ADMIN cũng có thể đọc candidate profile qua cùng path.
Bearer filter vẫn yêu cầu user ACTIVE trước gọi các API này.

### access(UUID id) — private

**Input:** thread UUID.
**Output:** toàn row thread nếu được phép.

1. Repository tìm thread; không có →404.
2. Nếu caller ADMIN thì cho phép.
3. Nếu current user bằng candidate_user_id thì cho phép.
4. Nếu bằng consultant_user_id thì cho phép.
5. Còn lại trả404 Consultation not found.

404 giúp không xác nhận một thread tồn tại với người không có quyền. Method không yêu cầu thread OPEN.
Closed thread vẫn có thể đọc. Không cho consultant đọc thread chỉ vì cùng role nếu chưa được assign.
Helper được dùng làm cổng chung cho các method đọc/gửi.

### create(CreateConsultationRequest input)

**Output:** row thread tạo mới.
**API:** POST /api/v1/consultations.
**Role:** current.requireRole("CANDIDATE").

DTO:

| Field | Validation |
| --- | --- |
| subject | Nonblank/max255 |
| requestType | Optional/max50, không enum trong DTO |
| description | Nonblank/max10000 |

1. Gate role candidate.
2. Insert candidate_user_id từ CurrentUser.
3. Ghi subject/request_type/request_description.
4. priority NORMAL.
5. Database default status OPEN và created_at.
6. Trả access(id).

Không nhận consultantId trong DTO tạo thread. Không tự assign consultant.
Không reserve AI tokens. Method không có @Transactional riêng; insert là một statement.
Không dùng candidate UUID do client gửi.

~~~json
{
  "subject": "Nhờ tư vấn CV backend",
  "requestType": "CV_REVIEW",
  "description": "Tôi muốn được tư vấn về CV Java và kế hoạch tìm việc."
}
~~~

Lưu json.id thành threadId. Admin assign trước khi consultant cụ thể có quyền.

### list()

**Input:** không có.
**Output:** List<Map> thread.
**API:** GET /api/v1/consultations.

ADMIN →db.listConsultationThreads(), tối đa100 mới nhất. User khác →where candidate_user_id=current OR consultant_user_id=current.
Current UUID được truyền cả hai vị trí SQL. Không query limit/offset/status tại endpoint list này.
Không chỉ OPEN; CLOSED cũng xuất hiện. Một consultant chưa assign có thể nhận array rỗng.

### thread(UUID id)

**Output:** Map row thread.
**API:** GET /api/v1/consultations/{id}.

Chỉ delegate access(id). Không tự append messages.
Không query profile hoặc gọi report service. Không có/không quyền404.
OPEN/CLOSED đều đọc được.

### candidateProfile(UUID id, UUID resumeId)

**Input:** thread UUID và CV optional.
**Output:** Map có candidate/career_profile/resume.
**API:** GET /api/v1/consultations/{id}/candidate-profile?resumeId=<UUID>.

1. access(id) kiểm thread.
2. Owner là candidate_user_id của thread, không current user tự chọn.
3. Đọc candidate_profile; thiếu thì LinkedHashMap rỗng.
4. Thêm skills join names/proficiency/experience.
5. Đọc candidate id/full_name/preferred_language.
6. Candidate DELETED bị query loại, trả404.
7. resumeId null →tìm primary READY CV của candidate.
8. Không có primary →resume là Map rỗng.
9. Có resumeId →query id + owner + READY, sai trả404.
10. Trả fields(candidate,career_profile,resume).

Projection CV chỉ id/title/language/structured_data/updated_at. Không trả file system path, token hoặc password hash.
Không chọn CV đầu tiên tùy ý nếu thiếu primary. Không cho consultant truyền resumeId của candidate khác để đọc.
Method không có transaction snapshot chung giữa các query. Không gọi AiService để phân tích CV.

### assign(UUID id, AssignConsultantRequest request)

**Input:** thread UUID, consultantId nonnull.
**Output:** row thread sau assign.
**API:** PUT /api/v1/consultations/{id}/consultant.
**Role:** ADMIN.
**Transaction:** @Transactional.

1. requireRole ADMIN.
2. Lock thread WHERE status OPEN FOR UPDATE.
3. Closed hoặc không có thread →404 từ repository query.
4. Tìm user consultantId role CAREER_CONSULTANT/status ACTIVE.
5. Không hợp lệ →404.
6. Update consultant_user_id.
7. Insert audit action CONSULTATION_ASSIGN.
8. new_value JSON chứa consultant_id.
9. Register afterCommit disconnect(id).
10. Trả access(id) với assignment mới.

~~~json
{"consultantId":"{{consultantId}}"}
~~~

Có thể reassign OPEN thread. Không giữ quyền consultant cũ chỉ vì họ từng gửi message.
Disconnect diễn ra sau commit, để connection phải nối lại theo assignment mới. Tất cả subscriber của thread bị disconnect, không chỉ consultant cũ.
Không gửi SMTP hoặc automatic notification trong method này.

### messages(UUID id, int limit, int offset)

**Output:** List message rows join full_name/role.
**API:** GET /api/v1/consultations/{id}/messages.
**Defaults controller:** limit50, offset0.

1. access(id).
2. pageSize(limit) chấp nhận1–100, sai400.
3. offset(offset) chấp nhận0–100000, sai400.
4. SQL sort sent_at,id tăng dần rồi LIMIT/OFFSET.
5. Trả history.

Không chỉ lấy message sau thời điểm connect. Client dùng pagination để bù history khi SSE reconnect.
CLOSED thread vẫn đọc history được. Không gọi AI và không consume quota.

### send(UUID id, ConsultationMessageRequest input)

**Input:** thread UUID và content nonblank/max10000.
**Output:** message row vừa insert.
**API:** POST /api/v1/consultations/{id}/messages.
**Transaction:** @Transactional.

1. Lock thread FOR UPDATE, không filter OPEN ở câu lock.
2. access(id) kiểm assignment/owner hiện tại.
3. Thread không OPEN →409 Consultation is closed.
4. Insert thread_id/sender_user_id/content/sent_at now.
5. message_type nhận default TEXT trong DB.
6. Đọc row message.
7. Register afterCommit publish(id,message).
8. Trả row.

~~~json
{"content":"Tôi đã xem CV và có vài góp ý về phần dự án."}
~~~

Khóa thread serialize send với close/assign. Nếu close commit trước, send thấy CLOSED và bị409.
Nếu send commit trước, message đã được lưu trước close. Sender ID từ token; DTO không cho giả sender.
Không gửi SSE khi transaction insert chưa commit.

### read(UUID id, UUID messageId)

**Output:** {id:messageId,read:true}.
**API:** PATCH /api/v1/consultations/{id}/messages/{messageId}/read.
**Body:** none.

1. access thread.
2. Xác nhận messageId thuộc threadId.
3. Update read_at=COALESCE(read_at,now()) khi sender_user_id<>current user.
4. Trả read=true.

COALESCE giữ timestamp đọc đầu tiên. Người gửi không tự đánh dấu message của mình bằng update này.
Response vẫn read=true nếu update0 do caller chính là sender. Method không đọc row lần nữa để chứng minh read_at đã đổi.
Không có receipt riêng cho từng người nhận; mỗi message chỉ một read_at. Không có @Transactional method riêng và không phát SSE read event.

### close(UUID id)

**Output:** thread row CLOSED.
**API:** POST /api/v1/consultations/{id}/close, body none.
**Transaction:** @Transactional.

1. Lock thread FOR UPDATE.
2. access kiểm quyền.
3. Update CLOSED.
4. closed_at=COALESCE(closed_at,now()), giữ lần đóng đầu.
5. Register afterCommit disconnect.
6. Trả row.

Owner candidate, assigned consultant hoặc ADMIN đều đóng được. Không cần riêng role ADMIN để close.
Close lại row CLOSED vẫn được update và giữ closed_at đầu. Không xóa history.
Không có reopen API trong service này.

### events(UUID id)

**Input:** UUID thread.
**Output:** SseEmitter.
**API:** GET /api/v1/consultations/{id}/events.
**Header:** Authorization Bearer, Accept:text/event-stream.

1. access(id).
2. Tạo SseEmitter(300000L): timeout5 phút.
3. Tạo Subscriber(user.id(),emitter).
4. computeIfAbsent thread list.
5. Add subscriber.
6. Đăng ký cleanup cho completion/timeout/error.
7. Gửi event connected với data {thread_id:id}.
8. Nếu IOException lúc gửi connected, complete emitter.
9. Trả emitter cho controller.

Đoạn quản lý connection:

~~~java
SseEmitter emitter = new SseEmitter(300_000L);
Subscriber subscriber = new Subscriber(user.id(), emitter);
var list = subscribers.computeIfAbsent(id, key -> new CopyOnWriteArrayList<>());
list.add(subscriber);
~~~

ConcurrentHashMap bảo vệ các thread keys khi nhiều request. CopyOnWriteArrayList cho iteration publish trong lúc connection add/remove.
Cleanup xóa subscriber; list rỗng thì xóa map entry. Method không filter status OPEN nên có thể connect CLOSED thread nếu access hợp lệ.
Close/reassign sẽ complete các emitter đã đăng ký tại thời điểm afterCommit. Không replay message theo Last-Event-ID.
Client reconnect và GET messages để lấy phần lịch sử còn thiếu.

### publish(UUID id, Map message) — private

**Caller:** callback sau send commit.
**Output:** void; phát SSE tới subscribers của process.

1. Lấy subscriber list của thread, thiếu thì list rỗng.
2. Với mỗi subscriber, query quyền hiện tại trong DB.
3. Query yêu cầu user ACTIVE và ADMIN/owner/current assigned consultant.
4. Nếu có quyền, gửi event name message.
5. Event id là message.id, data toàn row message.
6. Mất quyền →complete emitter.
7. IOException/IllegalStateException →complete emitter.

Đây là recheck theo subscriber.userId, không dựa trên CurrentUser của request send. Có thể loại consultant cũ hoặc account vừa inactive khỏi notification tiếp theo.
Không kiểm token expiry cho connection đã mở; kiểm trạng thái account/assignment bằng DB. DB query chạy ngoài try send; lỗi DB có thể làm callback thất bại.
Message đã commit vẫn tồn tại để client lấy qua GET history.

Map process-local nghĩa là message ở instance A không tự fan-out tới emitter ở B. Database history dùng chung không đồng nghĩa SSE bus dùng chung.
Không có Redis Pub/Sub, RabbitMQ hay WebSocket fan-out trong service này.

### disconnect(UUID id) — private

Remove thread list khỏi subscribers. Nếu có list, gọi complete() từng emitter.
Nếu không có thì không làm gì. Được dùng sau assign/close commit.
Không xóa messages trong DB. Không gửi một payload event “closed”; đóng connection là side effect.
Reconnect phải kiểm quyền và thread state lại qua API.

### afterCommit(Runnable action) — private static

Helper đăng ký TransactionSynchronization với manager hiện tại. Caller là assign/send/close đều có @Transactional.
Không chạy action ngay lúc đăng ký.

~~~java
TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
    @Override
    public void afterCommit() {
        action.run();
    }
});
~~~

Anonymous override afterCommit() là method thực được Spring gọi sau commit. Transaction rollback không chạy publish/disconnect.
Tách hiệu ứng network/in-memory khỏi trạng thái DB chưa commit. Nếu callback lỗi sau commit, message/assignment/close đã được lưu; không rollback được DB.
Helper cần active synchronization; không dùng tùy ý ngoài transaction. Nó không biến SSE và DB thành một distributed transaction.

### Workflow sử dụng đúng quyền

1. Candidate tạo consultation.
2. ADMIN assign consultant ACTIVE.
3. Candidate/consultant đọc thread và profile theo assignment.
4. GET events mở connection, POST messages từ request khác.
5. Nhận event message hoặc đọc history khi reconnect.
6. read dùng token người nhận.
7. Candidate/consultant/admin close khi hoàn tất.
8. Report đã lưu được đọc qua [ConsultationAnalysisService](#svc-consultationanalysisservice).

<a id="svc-consultationanalysisservice"></a>

## ConsultationAnalysisService

[Nguồn ConsultationAnalysisService.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/consultant/consultation/ConsultationAnalysisService.java)

### Vai trò và constructor

Service đọc bảy nhóm báo cáo đã có của candidate trong thread. Caller duy nhất là ConsultantConsultationController.candidateAnalyses().
Không sinh báo cáo AI mới, không embedding, không reserve quota.

Constructor nhận:

| Dependency | Vai trò |
| --- | --- |
| ConsultationAnalysisRepository | Xác định candidate theo thread, query bảy nhóm |
| ConsultationRepository | Xác minh resume READY thuộc candidate |
| CurrentUser | ID viewer hiện tại |

Bảng đọc gồm consultation_threads/users; resumes/resume_analyses/resume_analysis_suggestions;
job_resume_analyses/market_job_posts; job_matching_runs/matching_run_resumes/job_match_results;
salary_estimations; assessment_sessions;
gap_analyses/gap_analysis_skills; roadmaps/roadmap_nodes/roadmap_node_prerequisites/roadmap_node_resources/learning_resources.

### candidateAnalyses(UUID consultationId, ConsultationAnalysisRequest request)

**API:** GET /api/v1/consultations/{id}/candidate-analyses.
**Input:** thread UUID và query DTO.
**Output:** ConsultationAnalysesResponse.
**Transaction:** @Transactional để giữ share lock assignment trong cả lượt đọc.

DTO:

| Query | Default/validation |
| --- | --- |
| resumeId | UUID optional |
| jobId | UUID optional |
| limit | null ->20, 1–100 |
| offset | null ->0, >=0 |

Controller dùng @Valid @ModelAttribute @ParameterObject. Đây là query, không JSON body.
Offset DTO không có Max100000 như messages(); không áp đặt giới hạn không có trong code.

Luồng method:

1. repository.candidateIdForThread(consultationId,currentUser.id()).
2. SQL JOIN viewer và candidate.
3. Viewer phải ACTIVE; candidate không DELETED.
4. Viewer ADMIN hoặc candidate owner hoặc assigned consultant.
5. SELECT FOR SHARE OF t giữ assignment ổn định.
6. Không đủ quyền/thread không có →404.
7. Lấy resumeId/jobId/limit/offset từ request.
8. Nếu resumeId có, ConsultationRepository xác nhận owner + READY.
9. Sai CV/không READY →404.
10. Query bảy list với candidateId và filters.
11. Tạo typed response gồm metadata và bảy list.
12. Transaction kết thúc rồi thả lock.

FOR SHARE ngăn assign/close update cùng row trong lúc ghép báo cáo. Không giữ quyền chỉ dựa trên thread ID client hoặc role consultant.
CLOSED thread vẫn được đọc, vì query không filter OPEN. Service không gate “chỉ consultant”; owner candidate và ADMIN được phép theo SQL.
jobId chỉ làm filter; service không query để khẳng định job phải ACTIVE hay có tồn tại. jobId không match có thể trả list rỗng.

### Bảy nhóm và điều kiện SQL

| Output key | Repository query/điều kiện quan trọng |
| --- | --- |
| resume_analyses | CV owner/READY; optional resumeId; kèm suggestions aggregate |
| ats_analyses | Owner, CV READY, job; match_result liên quan phải run owner/COMPLETED nếu có |
| matching_results | Run user owner và COMPLETED; filter job/CV; kèm các READY resume_ids |
| salary_estimations | Owner, optional job filter, LEFT JOIN job |
| assessments | Session owner/status lowercase completed; kiểm ownership reused_from_session nếu có |
| gap_analyses | Owner/status uppercase COMPLETED; kiểm resume/assessment liên quan cùng owner |
| roadmaps | Owner/status ready hoặc completed; gap cùng owner, prerequisite/resource theo node |

Mỗi nhóm có LIMIT/OFFSET riêng. limit20 không nghĩa tổng response chỉ20 items.
Một lượt có thể tới20 items cho mỗi trong bảy nhóm. Thứ tự thường created_at DESC,id DESC ổn định.
Các list không là trang chung cross-module.

Báo cáo vẫn có thể mô tả job cũ inactive, kèm job_status. Không yêu cầu chỉ dữ liệu mới nhất: gap is_latest/roadmap is_outdated có trong projection.
Client đọc flags thay vì coi mọi report còn phù hợp với profile/CV hiện tại. Roadmap output có nodes, prerequisite_ids và resources active.
Assessment output là metadata/skill scores đã lưu, không tự trả toàn bộ câu hỏi/đáp án. ATS improvement_suggestions_json normalize array hoặc object.items; kiểu khác thành[].
Dữ liệu liên kết sai owner bị SQL loại, không chỉ dựa vào filter top-level.

### Response và cách gọi

~~~http
GET /api/v1/consultations/{{threadId}}/candidate-analyses?resumeId={{resumeId}}&jobId={{jobId}}&limit=20&offset=0
Authorization: Bearer <candidate-or-assigned-consultant-or-admin-token>
~~~

Bỏ resumeId/jobId khi không có UUID thực, không gửi "". resumeId phải READY của candidate trong thread, không CV của consultant.
Query valid vẫn có thể trả các nhóm rỗng nếu chưa tạo reports.

Response keys:

~~~json
{
  "candidate_id": "<candidate UUID>",
  "resume_id": null,
  "job_id": null,
  "limit": 20,
  "offset": 0,
  "resume_analyses": [],
  "ats_analyses": [],
  "matching_results": [],
  "salary_estimations": [],
  "assessments": [],
  "gap_analyses": [],
  "roadmaps": []
}
~~~

Typed response dùng @JsonProperty cho snake_case IDs/group keys. Null filter không đổi owner; owner luôn lấy từ thread được xác minh.
Không trả password/token/email verification hash. Projection báo cáo có nội dung AI đã lưu, không chứng minh chất lượng AI ngoài các nguồn đó.
Nếu assignment đổi sau response hoàn tất, request tiếp theo phải qua SQL access lại.



<a id="svc-consultantevaluationservice"></a>

## ConsultantEvaluationService

Nguồn: [ConsultantEvaluationService.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/consultant/consultation/ConsultantEvaluationService.java). Controller: [ConsultantEvaluationController.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/controller/consultant/consultation/ConsultantEvaluationController.java). Util: [ConsultationEvaluationData.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/util/consultant/consultation/ConsultationEvaluationData.java).

Cho CAREER_CONSULTANT xem CV READY và tạo báo cáo cho candidate được gán trong consultation. Constructor nhận ConsultationEvaluationRepository, CurrentUser, AiService và ResumeService. Cả ba entry public có `@Transactional`. Candidate sở hữu báo cáo; người gọi consultant là actor của AI usage và audit.

| Method/API dưới `/api/v1/consultations` | Luồng |
| --- | --- |
| `candidateResumes(id)`; GET `/{id}/candidate-resumes` | assignedCandidate kiểm role/assignment; trả CV READY của candidate. Entry này không bắt consultation còn OPEN. |
| `analyzeResume(id,resumeId)`; POST `/{id}/resumes/{resumeId}/analyze` | Yêu cầu consultation OPEN và CV READY đúng candidate; requireAi; gọi CV_ANALYZE bằng structured/canonical text, kiểm score/text/arrays/suggestion, ghi resume analysis/suggestions và audit. |
| `ats(id,jobId,request)`; POST `/{id}/jobs/{jobId}/ats` | Kiểm consultation OPEN, CV READY và job active; lấy skills, tính hash gồm version, canonical text/language, job/skills/provider/model; đọc cache DB trước khi requireAi. Cache miss gọi JOB_ATS, validate, ghi báo cáo và audit. Body chứa resumeId. |

Provider tắt khiến lượt phân tích mới trả 503; cache ATS phù hợp vẫn có thể trả trước guard này. Không dùng rule fallback cho lượt đánh giá mới. General CV analysis không suy độ phù hợp với một JD; ATS sử dụng yêu cầu rõ ràng của job và coi điểm là tư vấn.

`com.example.intelligent_cv_matching_ai_agent.util.consultant.consultation.ConsultationEvaluationData` kiểm `aiArray/aiStrings/aiText/suggestion` và dựng `languageOf/atsResponse`. Output AI sai trả 502. `assignedCandidate`, `requireAi`, `modelOf` và `audit` vẫn trong service vì liên quan quyền, provider hoặc lưu dữ liệu. atsResponse copy row và thêm cached; cơ chế này khác CareerToolsSupport.analysisResponse.

Report được lưu cho candidate nhưng quyền tạo bắt buộc consultant đang được gán; không dùng ResumeService.owned vì CV không thuộc người gọi.

<a id="svc-jobservice"></a>

## JobService

**Code:** [JobService.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/shared/jobs/JobService.java).

Util đã tách: [JobEmbeddingSupport.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/util/shared/jobs/JobEmbeddingSupport.java). Xem [ranh giới service/util](#doc-utils).

Lớp này là cửa vào đọc và nhập tin tuyển dụng. Public controller dùng nó để đọc job/skill; admin controller dùng để import, đổi trạng thái và thao tác indexing. Crawl cũng đi qua cùng pipeline import nên dữ liệu được chuẩn hóa và dedup theo cùng một cách.

### Dependency, biến và thư viện

| Thành phần | Ý nghĩa trong lớp |
| --- | --- |
| `JobRepository db` | Đọc/ghi jobs, skills, aliases, embeddings, indexing jobs và audit; SQL nằm ở repository |
| `CurrentUser user` | Lấy UUID người đang gọi và kiểm role ADMIN |
| `AiService ai` | Provider/model và embedding; nhánh Spring thông thường gọi qua worker |
| `JobEmbeddingService embeddings` | Enqueue/index ngay qua queue bền vững |
| `JobNormalizer` | Chuẩn hóa metadata, canonical URL, hash và fingerprint |
| `Jsoup.parse(...).text()` | Chuyển HTML description thành plain text |
| `Values.fields(...)` | Tạo map tên cột → giá trị, cho phép giá trị null |
| `UUID / OffsetDateTime` | Định danh job/actor và deadline theo thời điểm |

Constructor bốn tham số có `@Autowired` và được Spring dùng. Constructor ba tham số truyền `embeddings=null` để tương thích cách khởi tạo trực tiếp trong test cũ. Do đó nhánh fallback trong `index` không phải worker chuẩn của ứng dụng khi chạy bằng Spring.

Bảng chính: `market_job_posts`, `market_job_sources`, `market_job_skills`, `market_job_embeddings`, `vector_indexing_jobs`, `skills`, `audit_logs`.

### Các hàm đọc: skills, jobs, job

| Hàm | Input → output | Code thực hiện |
| --- | --- | --- |
| `skills()` | Không input → danh sách skill đang active | Gọi repository; giới hạn 1.000, sắp theo tên; không tự kiểm login |
| `jobs(q, location, limit, offset)` | Hai chuỗi tìm kiếm/phân trang → danh sách job | Từ chối q/location dài hơn255, bọc thành `%...%` cho ILIKE; `pageSize` kiểm1–100 và `offset` kiểm0–100000 |
| `job(id)` | UUID canonical job → map chi tiết | Lấy job ACTIVE/chưa quá deadline, thêm `skills` và `sources`; không có job phù hợp trả404 |

Public projection chọn nguồn còn ACTIVE/chưa quá deadline, ưu tiên nguồn chính nếu vẫn hợp lệ; giữ `original_source_url` và `original_source_site` để tra nguồn gốc. Vì vậy `source_url` trả frontend có thể là alias TOPCV của canonical job ban đầu nhập từ ITVIEC.

### importJob(input): nhập từ HTTP admin

Input là `ImportJobRequest` gồm sourceSite/sourceUrl/title/company/location/salary/category/content/skills và metadata tùy chọn employmentType, requirements, responsibilities, benefits, postedAt, validThrough. Output là job đã lưu với `deduplicated`.

1. `user.requireRole("ADMIN")` kiểm quyền.
2. Lấy actor bằng `user.id()`.
3. Gọi `importPrepared(input, actor)`.
4. `@Transactional` bao phủ insert/update nguồn, job, skills, enqueue và audit. Lỗi trong phần này rollback các thay đổi cùng transaction.

Hàm này không fetch website. URL hợp lệ trong body chỉ là định danh nguồn; admin tự cung cấp nội dung.

### importJobAs(input, actor): nhập từ scheduler

Hợp đồng dữ liệu giống `importJob`, nhưng không lấy người dùng từ SecurityContext HTTP. Repository kiểm actor tồn tại, role ADMIN, status ACTIVE và chưa bị soft delete rồi mới gọi `importPrepared`. Actor cũng là chủ quota cho task embedding.

Đây là phương thức nội bộ được [JobCrawlService](#svc-jobcrawlservice) gọi khi scheduled=true; không có endpoint để client tùy ý truyền actor và giả danh admin.

### importPrepared(raw, actor): pipeline nhập dữ liệu

**Bước1 — normalize và kiểm dữ liệu:**

- `JobNormalizer.normalize(raw)` chuẩn hóa site/URL/tên, aliases địa điểm, employment type và skill.
- Deadline `<= now()` bị từ chối400.
- Description được strip HTML và phải còn tối thiểu30 ký tự.
- `contentHash` xác định nội dung/version ở một nguồn. Hash bao gồm metadata, skills và dates.
- `fingerprint` nhận diện cùng nội dung ở nguồn khác; yêu cầu company và cùng title/location/description/requirements chuẩn hóa.

**Bước2 — khóa trước khi tìm duplicate:**

~~~java
if (fingerprint != null)
    db.readDatabaseValue("FINGERPRINT:" + fingerprint);
db.readDatabaseValue(input.sourceUrl());
~~~

`readDatabaseValue` gọi PostgreSQL advisory lock gắn với transaction. Cả hai importer dùng cùng thứ tự fingerprint → URL, hạn chế hai request cùng tạo bản tin tương đương. Sau đó đọc mapping URL hiện tại và tìm job cùng source/hash hoặc raw hash.

**Bước3 — chọn một trong ba nhánh:**

| Nhánh | Điều kiện | Tác dụng |
| --- | --- | --- |
| Cùng version | Tìm được job cùng hash | Job phải ACTIVE; khóa canonical cũ/mới, refresh alias/deadline, retire mapping cũ nếu đã chuyển, enqueue/reuse task, trả `deduplicated=true` |
| Cùng nội dung ở nguồn khác | Không cùng hash nhưng tìm được fingerprint ACTIVE ở source khác | Gắn URL mới vào canonical đã có; không tạo job thứ hai; ghi audit `JOB_SOURCE_LINK` |
| Tin mới/version mới | Không match hai nhánh trên | Insert job/skills/source, retire bản cũ không còn nguồn active, invalidate vectors cũ, enqueue, ghi `JOB_IMPORT`, trả `deduplicated=false` |

Trong nhánh cùng hash, job inactive trả409 thay vì tự hồi sinh. Khi URL quay lại nội dung version cũ vẫn ACTIVE nhờ alias khác, `retirePrevious` giúp bản version vừa bị thay thế không còn ACTIVE nếu đã mất nguồn cuối cùng.

**Bước4 — giải quyết skill và lưu tin mới:**

- Có skillId: kiểm skill đó còn active.
- Chỉ có name: thử lookup tên/slug trong catalog; không match thì vẫn lưu skill text với skillId=null.
- `normalized_skill` dùng tên lowercase và lưu `is_required`.
- Nội dung gốc giữ ở `original_content`; plain text ở `raw_content/parsed_description`.
- Requirements/responsibilities/benefits, employment type, postedAt, validThrough được lưu riêng.
- Queue được ghi cùng transaction import; provider chưa bật vẫn có thể để task chờ.

### retirePrevious(previous, current): dọn canonical cũ

`previous` là Optional mapping URL trước khi đổi; `current` là UUID canonical sau import.

`map` lấy market_job_id cũ; `filter` bỏ trường hợp không đổi canonical; `ifPresent` mới thực hiện cập nhật. Repository đánh REMOVED chỉ nếu job cũ không còn alias ACTIVE, invalidate vectors tương ứng và tính lại deadline. Không xóa bản tin lịch sử.

### lockSourceJobs(previous, target): thứ tự row lock

Tạo list gồm target và canonical cũ nếu khác nhau, rồi repository dùng `ORDER BY id FOR UPDATE` để khóa theo thứ tự nhất quán. Job row được khóa trước khi sửa source row; expiration maintenance cũng dùng thứ tự job → source.

### enqueue(id, hash, actor): wrapper gọi queue

Nếu dependency embeddings tồn tại, gọi `embeddings.enqueue`. Đây là enqueue database, chưa gọi provider. Check null phục vụ constructor ba tham số trong test.

### index(id): index/retry admin

Kiểm ADMIN. Khi chạy Spring, gọi `JobEmbeddingService.indexNow(id, user.id())` và trả trạng thái task hiện tại. HTTP200 có thể đi cùng QUEUED/PROCESSING/FAILED; phải đọc body.

Fallback khi embeddings=null: đọc job, gọi `ai.embed`, invalidate vector cũ, upsert vector mới rồi insert indexing job COMPLETED. Nhánh này không dùng cơ chế lease/queue chuẩn và được giữ cho khởi tạo trực tiếp cũ.

`index` không có transaction dài bao quanh provider; đường worker tự chia transaction ngắn trước/sau network.

### `JobService.embeddingText(Map job)` → `JobEmbeddingSupport.embeddingText(Map job)`

Service giữ delegate public static; thuật toán dùng chung với worker nằm ở `com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.JobEmbeddingSupport`.

Hàm static ghép title, raw_content, parsed_requirements, parsed_responsibilities, employment_type bằng newline. `Values.text` đổi null thành chuỗi rỗng nên không có chữ "null" giả trong embedding input.

Hàm không ghép salary, source URL, benefits hoặc bảng skills riêng. Muốn hiểu vector đang đại diện cho gì cần đọc hàm này, không chỉ xem job có metadata nào.

### indexStatus(id): đọc monitoring riêng một job

Kiểm ADMIN và sự tồn tại của job kể cả inactive. Output:

~~~json
{"job_id":"<UUID job>","indexing_jobs":[]}
~~~

Repository trả tối đa20 task mới nhất với provider/model/hash/status/attempt/error/timestamps. Hàm không gọi AI, không enqueue và không tiêu quota.

### status(id, input): đổi trạng thái canonical job

Input map có `status` thuộc ACTIVE/EXPIRED/REMOVED/INVALID; trạng thái khác hoặc thiếu trả400.

1. Đọc bản tin hiện tại.
2. Chuyển deadline JDBC thành Instant: hỗ trợ OffsetDateTime, Timestamp, Instant hoặc chuỗi ISO.
3. Không cho chuyển ACTIVE nếu deadline đã qua.
4. Cập nhật job và trạng thái các mapping nguồn.
5. Trạng thái khác ACTIVE làm vectors noncurrent; ACTIVE enqueue/reuse task.
6. Ghi audit JOB_STATUS và trả bản ghi mới.

Được bao trong `@Transactional`. Đây là thao tác rõ ràng của admin, khác cơ chế tự đánh expired khi mọi source đều không còn hiệu lực.

### contentHash(sourceSite, sourceUrl, input, normalizedContent): wrapper tương thích

Hàm package-private static vẫn nhận bốn tham số theo hợp đồng cũ, nhưng hiện chỉ dùng input và delegate `JobNormalizer.normalize/contentHash`. Ba tham số phụ không quyết định hash riêng; muốn sửa định nghĩa hash phải sửa normalizer, không sửa wrapper này.

### Cách sử dụng

| Nhu cầu | API |
| --- | --- |
| Catalog skill | `GET /api/v1/skills` |
| Tìm job | `GET /api/v1/jobs?q=Java&location=Ho%20Chi%20Minh&limit=20&offset=0` |
| Chi tiết canonical | `GET /api/v1/jobs/{id}` |
| Nhập nội dung | `POST /api/v1/admin/jobs` |
| Index/retry / đọc queue | `POST /api/v1/admin/jobs/{id}/index` / `GET` cùng path |
| Đổi status | `PATCH /api/v1/admin/jobs/{id}/status` |

Ví dụ body import; dùng URL thật thuộc nguồn hỗ trợ:

~~~json
{
  "sourceSite": "ITVIEC",
  "sourceUrl": "https://itviec.com/it-jobs/java-example-123456",
  "jobTitle": "Java Backend Developer",
  "companyName": "Example Company",
  "location": "HCM",
  "roleCategory": "Backend",
  "content": "Build reliable Java backend services and maintain production applications.",
  "skills": [{"name": "Java", "required": true}],
  "employmentType": "FULL_TIME",
  "requirements": "Experience with Java and SQL.",
  "validThrough": "2099-12-31T23:59:59+07:00"
}
~~~

Import → lấy job UUID → đọc indexStatus → khi COMPLETED với đúng hash/provider/model mới chạy semantic matching.

<a id="svc-jobembeddingservice"></a>

## JobEmbeddingService

**Code:** [JobEmbeddingService.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/shared/jobs/JobEmbeddingService.java).

Util đã tách: [JobEmbeddingSupport.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/util/shared/jobs/JobEmbeddingSupport.java). Xem [ranh giới service/util](#doc-utils).

Đây là worker tạo vector cho job đã import và đường index thủ công dùng cùng queue. Nó giữ task trong PostgreSQL, phối hợp giữa nhiều instance và tránh ghi vector từ nội dung/lease đã cũ.

### Constructor, dependency và cấu hình

`JobEmbeddingRepository` làm claim/upsert/retry; [AiService](#svc-aiservice) tạo vector theo actor; `PlatformTransactionManager` tạo TransactionTemplate. Record nội bộ `Work(jobId, actor, hash, job)` giữ snapshot đã được kiểm tra trước network.

| Tham số | Default / clamp trong constructor |
| --- | --- |
| enabled | true; chỉ gate scheduled worker |
| batchSize | 10; ép vào1–100 |
| leaseSeconds | 300; ép vào30–3600 |
| retrySeconds | 30; ép vào1–3600 |

Task ở `vector_indexing_jobs` với entity_type=JOB/operation=INDEX; vector ở `market_job_embeddings`. Không cần RabbitMQ cho queue này.

### enqueue(jobId, contentHash, requestedBy)

Input: UUID job, SHA-256 lowercase64 ký tự, UUID admin. Output: UUID task được tạo hoặc reuse.

1. Kiểm null/hash format →400; kiểm admin ACTIVE/chưa delete →403.
2. Khóa job FOR UPDATE; không có →404.
3. `usable` kiểm ACTIVE/hash/deadline. Không hợp lệ tạo/cập nhật trạng thái CANCELLED.
4. Đã có current embedding đúng job/hash/provider/model: COMPLETED, không gọi lại model.
5. Còn lại QUEUED.
6. Upsert queue theo unique job/hash/provider/model; task QUEUED/PROCESSING đang chạy được giữ, task terminal có thể requeue khi cần.

`@Transactional` thường tham gia transaction import đang có, nên không xuất hiện task của job mà import đã rollback. Enqueue không yêu cầu model đang bật.

### processPending(): worker theo lịch

Có `@Scheduled`: fixedDelay mặc định30000ms, initialDelay10000ms. `@Transactional(NOT_SUPPORTED)` tránh kéo theo transaction của caller.

- Nếu worker tắt hoặc embedding provider chưa sẵn sàng: return ngay.
- Lặp tối đa batchSize.
- Mỗi lượt dùng transaction ngắn claim một task cùng provider/model hiện tại, token lease mới và TTL.
- Repository dùng FOR UPDATE SKIP LOCKED; instance khác đang giữ row sẽ được bỏ qua.
- Không có task: kết thúc lượt.
- Gọi `process`; lỗi runtime bất ngờ được log loại lỗi, task có lease để reclaim về sau.

Không kiểm `ai.enabled()` của chat; embedding provider có thể chạy khi chat provider không có.

### indexNow(jobId, requestedBy): xử lý có chủ đích

Provider embedding chưa bật →503. Dùng transaction ngắn đọc/khóa job, kiểm usable, enqueue hoặc reuse task. Sau đó claim đúng task bằng `claimById` và xử lý khi claim được.

Nếu instance khác giữ task, trả trạng thái PROCESSING. Nếu task trong backoff chưa đến hạn, có thể trả QUEUED. Output lấy từ row queue sau khi xử lý; không giả định mọi POST index đều COMPLETED.

Phương thức cũng `NOT_SUPPORTED`; việc gọi trực tiếp `enqueue` trong cùng lớp không đi qua proxy annotation riêng, nhưng ở đây enqueue đã nằm bên trong `transactions.execute`.

### prepare(task): kiểm tra trước khi gọi model

Khóa job rồi khóa/kiểm lease queue theo cùng thứ tự với enqueue/publish.

| Trường hợp | Kết quả |
| --- | --- |
| Lease không còn thuộc token hoặc hết hạn | Return null; worker cũ không xử lý tiếp |
| Job mất, inactive, quá deadline hoặc hash khác | CANCELLED rồi return null |
| requested_by mất quyền ADMIN/ACTIVE | CANCELLED rồi return null |
| Vector đúng nội dung đã có | COMPLETED rồi return null |
| Đủ điều kiện | Trả Work để gọi provider ngoài transaction |

Không cần SecurityContext HTTP: actor nằm trong task và được kiểm lại trong database.

### process(task): gọi AI rồi publish có điều kiện

Chỉ nhận task PROCESSING. Nếu `prepare` không trả Work thì dừng.

~~~java
float[] vector = ai.embedForUser(work.actor(), JobService.embeddingText(work.job()));
~~~

Lệnh này chạy ngoài transaction job/queue, nhưng [AiService](#svc-aiservice) tự quản lý reservation và usage. Admin đã yêu cầu là chủ quota, không phải thread scheduler.

Sau provider, mở transaction mới:

1. Đọc/khóa job hiện tại.
2. Kiểm token lease vẫn hợp lệ; lease đã đổi thì không publish.
3. Kiểm usable với hash snapshot; job thay đổi/hết hạn thì CANCELLED.
4. Vô hiệu vector cũ, upsert current vector mới cùng provider/model/hash.
5. Chuyển task COMPLETED, xóa lease/error trong cùng transaction.

Lỗi runtime trong provider/publication đi qua retryOrFail. Khoảng nghỉ:

~~~java
int delay = Math.min(3600,
    retrySeconds * (1 << Math.min(2, Math.max(0, attempt - 1))));
~~~

Toán tử bit shift `1 << n` cho hệ số1/2/4, nên default backoff là30/60/120 giây; repository dừng sau tối đa3 attempt. Mỗi update gắn lease token nên worker cũ không ghi đè task đã được instance khác reclaim.

### `JobEmbeddingSupport.usable(Map<String,Object> job, String hash)` — util

Trả boolean; không ghi DB. Phải ACTIVE và raw_content_hash khớp hash. Không có deadline coi là còn hợp lệ; nếu có, chuyển Instant/OffsetDateTime/Timestamp/chuỗi về Instant và so với now.

Việc hỗ trợ Timestamp cần thiết vì SQL map đọc từ JDBC có thể không trả OffsetDateTime.

### `JobEmbeddingSupport.safeError(RuntimeException ex)` — util

Cả hai method public static thuộc `com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.JobEmbeddingSupport`.

Giữ message của ApiException để admin thấy lỗi quota/config hợp lệ; lỗi runtime khác dùng thông điệp chung. Message null có fallback và bị cắt tối đa1000 ký tự, tránh đưa lỗi provider nội bộ dài vào queue.

### State và cách dùng

~~~mermaid
stateDiagram-v2
    [*] --> QUEUED: import/enqueue
    QUEUED --> PROCESSING: claim
    PROCESSING --> COMPLETED: vector published
    PROCESSING --> QUEUED: retry still allowed
    PROCESSING --> FAILED: attempt limit
    PROCESSING --> CANCELLED: stale job or invalid actor
    PROCESSING --> PROCESSING: lease reclaimed
~~~

Cấu hình `AI_ENABLED=true`, `AI_PROVIDER=gemini`, `AI_EMBEDDING_PROVIDER=gemini` và `GEMINI_BASE_URL`;
đặt `GEMINI_API_KEY` và dùng `gemini-embedding-001` với `dimensions=1024`.
Các biến `JOB_EMBEDDING_ENABLED/BATCH_SIZE/FIXED_DELAY_MS/INITIAL_DELAY_MS/LEASE_SECONDS/RETRY_SECONDS`
ánh xạ ở application.yaml; lệnh chuẩn bị model ở [gemini.md](gemini.md).

Dùng `GET /api/v1/admin/jobs/{jobId}/index` để xem queue. Sau khi sửa provider/quota, `POST` cùng path retry/reuse task. Không dùng task UUID thay job UUID.

<a id="svc-jobcrawlservice"></a>

## JobCrawlService

**Code:** [JobCrawlService.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/admin/jobs/JobCrawlService.java).

Điều phối discovery URL, fetch HTML, parse và import từng job. Parser/normalizer thuộc util; service quản lý thứ tự, counter, transaction và lỗi từng trang.

### Constructor, dependency và dữ liệu

`JobCrawlRepository` ghi market_crawl_runs/items/metadata/expiry/audit; `CurrentUser` kiểm admin HTTP; [JobService](#svc-jobservice) xử lý import/dedup/queue. Constructor tạo `TransactionTemplate` với `PROPAGATION_REQUIRES_NEW` để run/item/log có transaction độc lập.

`Jsoup Connection` dùng HTTP GET, không chạy JavaScript. `MAX_BODY_BYTES` là2MiB. `JobUrlDiscovery/JobPostingParser/JobNormalizer/JobSourceValidator` xử lý URL/parsing/chuẩn hóa/whitelist.

### crawl(input) và crawlAs(input, actor)

| Hàm | Người gọi | Quyền và delegate |
| --- | --- | --- |
| `crawl(CrawlRequest)` | POST admin crawl | CurrentUser.requireRole ADMIN → collect(input,user.id(),false) |
| `crawlAs(CrawlRequest,UUID)` | Scheduler | DB kiểm actor ADMIN/ACTIVE/chưa delete → collect(input,actor,true) |

Flag scheduled quyết định importJobAs hay importJob. Scheduler không dựng SecurityContext giả.

### collect(input, actor, scheduled): pipeline chính

**Input:** sourceSite, keyword, roleCategory, location, urls, limit, maxPages. Output: map run gồm counters/status/timestamps và items.

**1. Kiểm đầu vào trước tạo run/network**

- limit1–20, maxPages1–5, default20/3 từ effectiveLimit/effectiveMaxPages.
- source strip/uppercase; searchUrl(site,keyword,1) xác nhận nguồn/keyword.
- urls null thành list rỗng; canonicalize/whitelist mọi URL, distinct; tối đa20 URL khác nhau.
- requested_limit bằng số explicit URL hoặc limit discovery.
- Insert RUNNING/requested_by/started_at trong transaction riêng.

**2. Discovery nếu urls rỗng**

1. LinkedHashSet giữ thứ tự và loại trùng.
2. Lặp search pages đến maxPages hoặc đủ limit.
3. Fetch; HTTP ngoài2xx là lỗi.
4. discoverLinks lấy detail URL phù hợp và loại tracking.
5. Ghi SEARCH_FETCHED với số URL mới.
6. Không có link mới thì dừng.
7. IOException/ApiException ghi SEARCH_FETCH_FAILED, tăng rejected, dừng discovery; các URL đã có vẫn được import.

Keyword là search thật ở chế độ discovery; ở explicit mode chủ yếu là nhãn run. roleCategory là nhãn admin chọn, không phải AI classification.

**3. Detail processing tuần tự**

~~~text
fetch
 ├─ HTTP404/410 → expire source + EXPIRED
 ├─ HTTP khác2xx → REJECTED
 └─ HTML2xx → parse → kiểm deadline → lọc location → import+item
~~~

Final URL canonicalize sau fetch. HTTP404/410 expire requested URL và final URL nếu khác; không dùng timeout/500 làm bằng chứng hết hạn.

Parser ưu tiên JSON-LD JobPosting, fallback HTML theo nguồn. Thiếu dữ liệu đủ nhận diện hoặc metadata không hợp lệ → ApiException, ghi REJECTED.

parsed_jobs tăng sau parse, trước expiry/region nên parsed không đồng nghĩa imported.

- validThrough đã qua: expire alias, ghi EXPIRED, rejected++.
- location có yêu cầu: normalize/fold và kiểm substring; không match ghi WRONG_REGION, tăng wrongRegion và rejected.
- Location là text/alias comparison, không geocoding và không tự coi Remote phù hợp mọi thành phố.

**4. Import và item chung một transaction**

~~~java
Map<String, Object> saved =
    scheduled ? jobs.importJobAs(posting.job(), actor) : jobs.importJob(posting.job());
boolean reused = existing || Boolean.TRUE.equals(saved.get("deduplicated"));
~~~

Repository thử tìm raw hash ACTIVE trước đó; cờ deduplicated của JobService bao phủ cả reuse khác nguồn.

| Kết quả | Thao tác/counter |
| --- | --- |
| Job mới | Update quality json_ld/html và parsed_at, item IMPORTED, imported++, cộng số skills parser |
| Reuse | Refresh alias, item DUPLICATE, duplicates++; không tăng imported_skills |
| Import lỗi | Transaction import/item rollback; sau đó recordItem lỗi trong transaction khác |

**5. Phân loại exception**

| Exception | Item status |
| --- | --- |
| IOException/FetchFailure | FETCH_FAILED |
| ApiException | REJECTED |
| RuntimeException khác | IMPORT_FAILED |

Runtime còn lại chỉ ghi tên loại lỗi vào message. FetchFailure giữ HTTPstatus đã biết để counter/monitoring phản ánh response nhận trước lỗi.

**6. Tính trạng thái và trả kết quả**

~~~java
String outcome = rejected == 0
    ? "COMPLETED"
    : imported + duplicates == 0 ? "FAILED" : "PARTIAL";
~~~

Không rejected →COMPLETED, kể cả không tìm được URL. Có rejected nhưng không import/reuse được job →FAILED. Có cả thành công và rejected →PARTIAL. HTTP200 chỉ cho biết API trả được kết quả run.

Counter: fetched_urls, parsed_jobs, imported_jobs, skipped_duplicates, detail_pages_checked, wrong_region_pages, rejected_pages, search_pages_checked, candidate_urls_discovered, imported_skills. detail_pages_checked là số URL đã lên danh sách kiểm, không phải số fetch nhận response. Explicit mode không tăng search/discovered counters.

Update finished_at/run/audit trong transaction cuối; trả run+items.

### fetch(source, url): request HTTP an toàn, hữu hạn

Protected method trả `HttpPage(url,status,html)`, ném IOException; test override để dùng fixture.

1. validateSourceUrl kiểm whitelist HTTPS.
2. Jsoup GET: user-agent EduNexus, timeout5000ms, body cap2MiB+1.
3. followRedirects(false); tự xử lý301/302/303/307/308.
4. Tối đa3 redirect, phải có Location và validate lại target sau resolve.
5. Body vượt2MiB →FetchFailure.
6. HTTP ngoài2xx →HttpPage với HTML rỗng để collect xử lý.
7. HTTP2xx chỉ nhận text/html hoặc application/xhtml+xml.
8. Trả final URL/status/body.

ignoreHttpErrors(true) giúp đọc404/410; ignoreContentType(true) không bỏ kiểm content type của code. Timeout theo mỗi connection, không phải tổng run.

Cho exact host itviec.com/www.itviec.com/topcv.vn/www.topcv.vn, không userinfo/port/custom host. Redirect ra ngoài bị từ chối.

### Các helper, record và nested class

| Thành phần | Giải thích |
| --- | --- |
| `expireSource(site,url)` | Transaction riêng + advisory URL lock; khóa job trước source, expire alias/tính deadline; chỉ expire canonical/invalidate vector nếu hết nguồn hợp lệ |
| `runs(limit,offset)` | ADMIN; kiểm pageSize/offset rồi đọc run list |
| `run(id)` | ADMIN; đọc run+items; thiếu run404 |
| `recordItem(...)` | Bọc insertItem trong REQUIRES_NEW, giữ log lỗi sau rollback import |
| `insertItem(...)` | Tạo fields map, cắt message1000 ký tự, lưu created_at và item |
| `parseJobPosting(html,source,url,roleCategory)` | Wrapper static tương thích test cũ, delegate parser rồi tạo ParsedJob; main collect dùng parser trực tiếp |
| `contentHash(job)` | Wrapper static JobNormalizer.contentHash |
| `HttpPage` | Record final URL/HTTPstatus/HTML |
| `ParsedJob` | Record ImportJobRequest/postedAt/validThrough |
| `FetchFailure` | IOException con; constructor gọi super(message), giữ thêm HTTPstatus trước lỗi |

### Utility cần đọc cùng service

| Utility | Logic liên quan |
| --- | --- |
| JobUrlDiscovery | Search URL keyword/page, numeric detail paths, resolve links, loại tracking đã biết/giữ query khác |
| JobPostingParser | JSON-LD array/@graph/@type; HTML selectors/sections; dictionary skills required/optional; date-only expiry đến hết ngày nguồn; tránh section việc làm liên quan |
| JobNormalizer | Aliases location/employment/skill; giữ original HTML, hash text; hash dates theo Instant; fingerprint không dùng URL |
| JobSourceValidator | Exact HTTPS host whitelist, URI normalize, reject mismatch/redirect ngoài nguồn |

Không invent company/deadline/salary khi thiếu. Dictionary không hiểu mọi công nghệ hoặc ý nghĩa phủ định; trang JavaScript/challenge có thể chỉ tạo item lỗi.

### Cách sử dụng

`POST /api/v1/admin/jobs/crawl` với Bearer ADMIN:

~~~json
{
  "sourceSite": "TOPCV",
  "keyword": "Java",
  "roleCategory": "Backend",
  "location": "Ho Chi Minh",
  "limit": 10,
  "maxPages": 2
}
~~~

Bỏ urls/null/[] để discovery. Có list URLs là explicit mode, maxPages không dùng. Đọc `GET /api/v1/admin/jobs/crawl?limit=20&offset=0` hoặc `GET /api/v1/admin/jobs/crawl/{id}`.

<a id="svc-crawlsettingsservice"></a>

## CrawlSettingsService

Nguồn: [CrawlSettingsService.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/admin/jobs/CrawlSettingsService.java). Controller: [CrawlSettingsController.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/controller/admin/jobs/CrawlSettingsController.java). Util: [CrawlSettingsSupport.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/util/admin/jobs/CrawlSettingsSupport.java).

Quản lý snapshot cấu hình collection, tách khỏi lịch expiration. Constructor nhận CrawlSettingsRepository, JobCollectionProperties, JobCollectionRepository và CurrentUser. Repository lưu một row `job_crawl_settings` (id=1), tăng version khi cập nhật và ghi audit CRAWL_SETTINGS_UPDATED.

| Method/API | Luồng |
| --- | --- |
| `get()`; GET `/api/v1/admin/crawl-settings` | Kiểm ADMIN rồi trả effective. Controller cũng hỗ trợ prefix /api/admin. |
| `update(CrawlSettingsRequest)`; PUT cùng route | Transaction; kiểm ADMIN, enabled/sources bắt buộc, tối đa 20 nguồn; parse fixedDelay; actor mặc định user gọi và phải là admin active. Chuẩn hóa nguồn, từ chối trùng identity hoặc enabled với list rỗng; save và audit rồi trả snapshot DB. |
| `effective()` | Đọc mới row DB, có thì fromDatabase; chưa có thì fromEnvironment. Entry nền không đòi SecurityContext, được scheduler dùng mỗi lượt. |

`com.example.intelligent_cv_matching_ai_agent.util.admin.jobs.CrawlSettingsSupport` có `fromEnvironment(JobCollectionProperties)`, `fromDatabase(Map)`, `fixedDelay(String)` và `normalize(CrawlSourceSettingsRequest)`. fixedDelay phải ISO-8601 từ PT1M đến P30D, chuẩn hóa tới millisecond. Nguồn chỉ ITVIEC/TOPCV; keyword/roleCategory bắt buộc, location hợp lệ tối đa 120 ký tự, limit 1..20 và maxPages 1..5. bounded/instant là helper private của util.

Snapshot cho biết source ENVIRONMENT/DATABASE, version, actor và thông tin cập nhật. Việc lưu DB ghi đè nguồn fallback cho các lượt đọc sau; thay đổi môi trường không tự thay row DB đã lưu. Scheduler đọc lại enabled/version giữa các nguồn để dừng lượt theo cấu hình mới. GET /api/v1/admin/crawl-history thuộc controller này nhưng ủy quyền JobCrawlService.runs.

<a id="svc-jobcollectionscheduler"></a>

## JobCollectionScheduler

**Code:** [JobCollectionScheduler.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/admin/jobs/JobCollectionScheduler.java).

Util đã tách: [JobCollectionSupport.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/util/admin/jobs/JobCollectionSupport.java). Xem [ranh giới service/util](#doc-utils).

Hai entry theo lịch: collection tìm/nhập job và expiration kiểm deadline đã lưu. PostgreSQL lease phối hợp nhiều replica theo tên lịch; không lưu token HTTP trong cấu hình.

### Constructor, dependency và cấu hình

- [CrawlSettingsService](#svc-crawlsettingsservice): snapshot cấu hình DB; fallback JobCollectionProperties từ môi trường khi chưa có bản ghi.
- JobCollectionRepository: kiểm admin, acquire/complete lease, transaction expiration.
- [JobCrawlService](#svc-jobcrawlservice): pipeline nhập tin.
- expirationEnabled mặc địnhtrue; expirationDelay15 phút phải dương.
- expirationBatchSize500, clamp1–1000.
- COLLECTION_LEASE15 phút, EXPIRATION_LEASE5 phút; SLF4J logger.

Constructor từ chối expirationDelay null/0/âm. Properties validation kiểm actor/list khi bật collection, source/keyword/category và giới hạn limit/pages/delay.

### collect(): collection entry

`@Scheduled(fixedDelay=30000, initialDelay=30000)` kiểm cấu hình hiệu lực mỗi lượt. Khoảng cách collection thực tế của từng nguồn được guard bằng `configuration.fixedDelay` trong lease, không phải chu kỳ 30 giây của polling.

1. Disabled →return.
2. Kiểm actor ADMIN/ACTIVE/chưa delete; sai thì log warning và return.
3. Parse fixedDelay và tạo snapshot nguồn bằng List.copyOf.
4. Trước các nguồn tiếp theo, đọc lại enabled/version; dừng vòng nếu cấu hình tắt hoặc đổi.
5. Duyệt source qua collectSource(source, actor, interval).

Actor cấu hình là chủ audit/quota cho job import/embedding; không cần phiên đăng nhập HTTP.

### collectSource(source, actor, interval)

1. Tạo leaseName, UUID token.
2. acquireCollectionLease TTL15 phút + guard thời gian từ last_started_at theo fixedDelay.
3. Không claim được: return.
4. Tạo CrawlRequest urls=[]/limit/maxPages, gọi crawlAs.
5. Run FAILED/PARTIAL: last_error chứa run/status.
6. RuntimeException trong pipeline: log/store loại lỗi và message.
7. finally completeLease theo token, ghi last_completed_at; worker cũ không ghi đè lease mới.

Guard last_started_at tránh replica startup lệch giờ chạy lại ngay cùng source. Lỗi pipeline một nguồn được ghi trước khi chuyển sang nguồn khác; lỗi DB ngay tại acquire/complete nằm ngoài catch này có thể làm lượt scheduler kết thúc lỗi.

### expire(): expiration entry

`@Scheduled` đọc app.jobs.expiration.fixed-delay, defaultPT15M. Không fetch nguồn hoặc gọi AI.

Disabled thì return; claim lease job-expiration TTL5 phút; gọi expireSources(batch), log số source/job/vector thay đổi; catch lỗi và finally ghi completion/error.

Repository làm trong một transaction: khóa due jobs bằng SKIP LOCKED, expire aliases quá deadline, chỉ canonical EXPIRED khi không còn ACTIVE alias future/null deadline, tính lại deadline và invalidate current vectors. Không suy expiry từ số ngày kể từ crawl.

### `JobCollectionSupport.leaseName(CrawlSourceSettingsResponse source)`

Logic nằm ở `com.example.intelligent_cv_matching_ai_agent.util.admin.jobs.JobCollectionSupport`; service giữ delegate phạm vi package.

Ghép sourceSite viết hoa, keyword/roleCategory viết thường và location chuẩn hóa/fold bằng newline. Băm qua `TokenSupport.hash` rồi thêm prefix `job-collection:`.

Key không chứa limit/maxPages, nên cấu hình cùng source/keyword/category/location chia lease ngay cả khi khác limit.

### Cách sử dụng

Có thể cập nhật runtime qua `PUT /api/v1/admin/crawl-settings`. Cấu hình môi trường dưới đây là fallback khi chưa có row DB:

~~~dotenv
JOB_COLLECTION_ENABLED=true
JOB_COLLECTION_ACTOR_ID=<UUID admin ACTIVE>
JOB_COLLECTION_FIXED_DELAY=PT6H
JOB_COLLECTION_LIMIT=20
JOB_COLLECTION_MAX_PAGES=3
JOB_EXPIRATION_ENABLED=true
JOB_EXPIRATION_FIXED_DELAY=PT15M
~~~

Nguồn default ITVIEC/TOPCV nhận keyword/category/location qua application.yaml/env; có thể đổi list YAML. Xem [job-collection-setup.md](job-collection-setup.md).

Monitoring: crawl-runs API, `GET /api/v1/admin/monitoring/crawl-runs` và job_collection_leases. Collection kết thúc không có nghĩa task embedding đã hoàn tất; đọc indexing status riêng.

<a id="svc-ragdocumentservice"></a>

## RagDocumentService

**Code:** [RagDocumentService.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/admin/rag/RagDocumentService.java).

Util đã tách: [RagDocumentSupport.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/util/admin/rag/RagDocumentSupport.java). Xem [ranh giới service/util](#doc-utils).

Admin nhập tài liệu tham khảo thành chunks và tạo embeddings để [CareerAssistantService](#svc-careerassistantservice) tìm nội dung liên quan. Đây là tài liệu hệ thống do admin cung cấp; không phải toàn bộ CV của ứng viên được tự đưa vào RAG.

### Constructor, dependency và bảng

`RagDocumentRepository` quản lý rag_documents/rag_chunks/rag_chunk_embeddings/vector_indexing_jobs/vector_store_metrics/audit_logs. `CurrentUser` kiểm ADMIN; [AiService](#svc-aiservice) tạo embedding và ghi quota/usage.

`ResumeData.text` kiểm kiểu/độ dài input; `Map/List/LinkedHashMap` biểu diễn JSON/database; `Character.isHighSurrogate/isLowSurrogate` bảo vệ ranh giới UTF-16 khi split. Constructor chỉ inject ba dependency.

### list() và get(id)

| Hàm | Output/luồng |
| --- | --- |
| `list()` | ADMIN, list tối đa100 tài liệu, newest first; gồm cả archived để quản trị |
| `get(id)` | ADMIN, lấy row document, copy sang LinkedHashMap, thêm chunks và thông tin vector provider/model/indexed_at; thiếu document404 |

get dùng map copy để bổ sung key chunks; không sửa nội dung row gốc trong DB.

### create(input): nhập nội dung

Input map từ `CreateRagDocumentRequest.toInputMap`; có title/content/sourceType/metadata.

1. Kiểm ADMIN.
2. Title text tối đa255, content100000; blank →400.
3. Nếu có sourceType phải ADMIN.
4. Metadata phải object/map; copy key sang String.
5. Ghi đè metadata.disabled=false.
6. Gọi `RagDocumentSupport.split(content)`.
7. Insert rag_documents với uploaded_by, title, source_type=ADMIN, metadata JSON, chunks_count, embedding_status=PENDING.
8. Insert từng rag_chunks có chunk_index bắt đầu0 và content.
9. Audit IMPORT_RAG_DOCUMENT và trả get(id).

`@Transactional` bảo đảm document/chunks/audit đồng bộ. Create chưa gọi AI, chưa sinh vector. Metadata do client gửi không thể tạo document disabled ngay qua nhánh này.

### `RagDocumentService.split(String content)` → `RagDocumentSupport.split(String content)`

Service giữ delegate public static; thuật toán nằm ở `com.example.intelligent_cv_matching_ai_agent.util.admin.rag.RagDocumentSupport.split`.

Public static helper, trả List<String>. Đoạn tối đa4000 Java char, overlap300 char.

~~~java
int end = Math.min(content.length(), start + 4000);
if (end < content.length() && Character.isHighSurrogate(content.charAt(end - 1)))
    end--;
result.add(content.substring(start, end));
if (end == content.length())
    break;
start = Math.max(start + 1, end - 300);
if (start > 0 && Character.isLowSurrogate(content.charAt(start)))
    start--;
~~~

- Overlap giữ ngữ cảnh quanh ranh giới đoạn.
- Char count không phải token count.
- Surrogate là hai code unit biểu diễn một ký tự Unicode như nhiều emoji; code không cắt đôi cặp ở đầu/cuối đoạn.
- Math.max(start+1,...) bảo đảm vòng lặp tiến lên.
- Helper không tách theo câu/heading hoặc dùng AI; content="" cho list rỗng, create đã từ chối input blank.

### index(id): tạo vector cho từng chunk

ADMIN; archived metadata.disabled=true →409; embedding provider chưa bật →503.

Đầu tiên repository update có điều kiện document sang PROCESSING, chỉ nhận PENDING/FAILED/COMPLETED và chưa disabled. Update không được1 row →409 để tránh index chồng hoặc archive.

Sau đó:

1. Lấy chunks đúng thứ tự; không có →409.
2. Chunk đã có vector cùng provider/model: skip, không gọi provider lại.
3. Chunk thiếu vector: insert vector_indexing_jobs entity_type=RAG_CHUNK/operation=EMBED/status=PROCESSING/attempt_count1.
4. ai.embed(content) tạo vector, quota gắn admin đang gọi.
5. Đọc lại metadata.disabled sau network; đã archive →409, không chủ động hồi sinh document.
6. Upsert embedding chunk, ghi task COMPLETED.
7. Lỗi chunk →ghi task FAILED với safeError và ném lại lỗi.
8. Hết chunks: update document COMPLETED/chunks_count có điều kiện chưa archived.
9. Ghi vector_store_metrics theo tổng vector provider/model và audit INDEX_RAG_DOCUMENT, trả get.

Catch ngoài cùng chuyển document FAILED rồi ném lỗi. Chunks đã thành công được giữ, nên retry skip chúng và xử lý phần thiếu.

`index` không có một transaction dài bao trùm toàn bộ provider calls; các bước ghi được commit độc lập. Cơ chế này khác worker JobEmbeddingService: RAG index là synchronous qua API, không dùng queue lease/retry3 lần tự động. Check archive sau provider là check được thực hiện tại thời điểm đọc, không phải một khóa toàn bộ document xuyên suốt network.

### archive(id)

ADMIN, kiểm tồn tại, update metadata.disabled=true và archived_at, embedding_status=FAILED; audit ARCHIVE_RAG_DOCUMENT, trả get(id).

`@Transactional`; không xóa vật lý document/chunks/embeddings. Retrieval RAG phải đọc điều kiện disabled để bỏ tài liệu đã archive.

### `audit(action,id,detail)` trong service và `RagDocumentSupport.safeError(RuntimeException ex)`

| Helper | Tác dụng |
| --- | --- |
| `audit` | Insert audit_logs actor/admin, entity_type=RAG_DOCUMENT, entity_id/document và new_value JSON |
| `RagDocumentSupport.safeError` | Giữ ApiException message, lỗi khác dùng thông điệp chung; tối đa1000 ký tự, null có fallback |

Audit ở đây là helper riêng, không gọi [AuditService](#svc-auditservice), nên không tự tạo thêm admin_actions.

### Cách sử dụng

`POST /api/v1/admin/rag/documents`:

~~~json
{
  "title": "Hướng dẫn phỏng vấn Java",
  "content": "Nội dung tham khảo thực tế mà admin muốn đưa vào hệ thống tìm kiếm tài liệu.",
  "sourceType": "ADMIN",
  "metadata": {"topic": "java-interview"}
}
~~~

Lấy document UUID → POST `/api/v1/admin/rag/documents/{id}/index` → GET cùng document để xem chunks/embedding_status. Archive bằng POST `.../{id}/archive`. Tất cả cần Bearer ADMIN.

<a id="svc-ragcourseservice"></a>

## RagCourseService

Nguồn: [RagCourseService.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/admin/rag/RagCourseService.java). Controller: [RagCourseController.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/controller/admin/rag/RagCourseController.java). Util: [RagCourseSupport.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/util/admin/rag/RagCourseSupport.java).

Quản trị link khóa học qua review trước khi dùng trong gợi ý học và RAG. Constructor nhận RagCourseRepository, CurrentUser và RagDocumentService. Các entry đều kiểm ADMIN; route gốc `/api/v1/admin/rag/courses`. Không tự crawl nội dung khóa học.

| Method/API | Trạng thái và tác động |
| --- | --- |
| `list/get`; GET gốc hoặc /{id} | List tối đa 200 row mới nhất hoặc đọc một khóa học. |
| `create(CourseLinkRequest)`; POST gốc | Transaction; CourseLink.parse kiểm URL/provider; kiểm title/description/language/isFree và skills; ghi rag_course_links PENDING_REVIEW, URL trùng trả 409; audit CREATE_RAG_COURSE. |
| `approve(id)`; POST /{id}/approve | Transaction, lock row; APPROVED trả lại idempotent, trạng thái khác PENDING_REVIEW trả 409. Tạo learning_resources ADMIN_CURATED active cùng metadata reviewed/course/skills, tạo rag document từ mô tả quản trị, lưu liên kết/reviewer, chuyển APPROVED và audit. URL resource đã tồn tại trả 409. |
| `archive(id)`; POST /{id}/archive | Transaction, lock row; ARCHIVED trả lại. Chỉ pending/approved được archive; tắt learning resource liên kết, archive rag document nếu có, cập nhật trạng thái và audit. |
| `index(id)`; POST /{id}/index | Chỉ APPROVED có rag_document_id; gọi rag.index và trả course kèm rag_document. Không bao transaction dài quanh provider calls; giữ trạng thái retry của RagDocumentService. |

Approve chưa tạo embeddings. Sau approve, resource đã vào catalog; muốn dùng semantic retrieval cho tài liệu cần index thành công. Archive giữ dữ liệu để quản trị nhưng tắt resource và document khỏi các query phù hợp.

`com.example.intelligent_cv_matching_ai_agent.util.admin.rag.RagCourseSupport.content(Map,List<String>)` dựng nội dung tài liệu; `skills(Object)` kiểm/chuẩn hóa danh sách skill; `text(String,String,int)` kiểm text bắt buộc/giới hạn. URL/provider/skill slug dùng CourseLink. Quyền, transition, row lock, repository và audit vẫn thuộc service.

<a id="svc-adminservice"></a>

## AdminService

**Code:** [AdminService.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/admin/management/AdminService.java).

Một facade cho tài khoản, gói/quota, cấu hình AI, prompt, monitoring và xử lý payment report. Controller ánh xạ các route /api/v1/admin vào lớp này; từng hàm vẫn tự gọi admin().

### Constructor, dependency và dữ liệu

`AdminRepository` đọc/ghi SQL; `CurrentUser` kiểm ADMIN; [AuditService](#svc-auditservice) ghi before/after; [TokenService](#svc-tokenservice) revoke sessions; `PasswordEncoder` băm temporary password.

Bảng liên quan: users/candidate_profiles, subscription_tiers/quotas/user_subscriptions, payment_orders/reports, ai_configurations/ai_prompt_templates/ai_usage_logs, vector_store_metrics/vector_indexing_jobs, system_logs/audit_logs/admin_actions, market_crawl_runs, support_tickets.

Account projection chỉ chọn field quản trị được phép trả; không trả password_hash, google_sub hoặc token raw.

### admin(): guard dùng chung

Private void, gọi user.requireRole("ADMIN"). Không phải annotation tự suy role từ package. Helper này được gọi trước mỗi thao tác public trong service.

### dashboard()

Output Map.of gồm users, activeSubscriptions, revenue, aiUsage, openSupportTickets.

- users: tổng chưa DELETED.
- activeSubscriptions: ACTIVE và chưa quá expiry.
- revenue: cộng completed payment theo currency, không trộn các tiền tệ thành một số.
- aiUsage: tổng tokens/estimated_cost/count request trong tháng DB.
- tickets: OPEN/IN_PROGRESS.

Hàm tổng hợp read queries, không phải workflow ghi hoặc số liệu cache được refresh nền.

### users(search,page,size) và account(id)

users kiểm ADMIN; repository tìm email/fullName ILIKE, offset=(long)page*size. Annotation/controller giới hạn search255, page>=0, size1–100. Ép long trước nhân tránh overflow int.

account lấy user theo UUID, thiếu →404. Projection trả được cả account pending/suspended/deleted để admin quản trị.

### create(body): tạo tài khoản có thể đăng nhập

Body AccountCreateRequest: email/fullName/role/temporaryPassword.

1. ADMIN.
2. Email trim/lowercase.
3. Mật khẩu UTF-8 tối đa72 byte bên cạnh Size10–72 character của DTO.
4. Email đã tồn tại →409.
5. PasswordEncoder.encode, insert auth_provider=EMAIL/status=ACTIVE/email_verified_at=now.
6. Role CANDIDATE: tạo candidate_profiles.
7. Đọc account và audit CREATE_USER, trả account.

`@Transactional`. Hàm này coi admin đã xác nhận account nên không phát OTP, không tự gửi temporary password hoặc bắt buộc đổi password ở lần đầu.

### edit(id,body): đổi tên/role/status

1. ADMIN; advisory lock ACCOUNT_ADMIN_MEMBERSHIP.
2. Đọc before; DELETED không reactivation →409.
3. protectLastAdmin kiểm không mất admin ACTIVE cuối cùng.
4. Chuyển ACTIVE chỉ được nếu email_verified_at đã có →400 nếu chưa verify.
5. Update fullName/role/status.
6. Role đổi hoặc account trở inactive →tokens.revokeAll(id).
7. Đọc after, audit UPDATE_USER, trả after.

`@Transactional`; revoke và thay account cùng transaction. Hợp đồng PUT cần đủ scalar DTO muốn giữ, không phải patch map tùy ý.

### delete(id): soft delete

ADMIN, cùng advisory lock membership, before/protectLastAdmin; update DELETED/deleted_at theo repository, revokeAll, audit DELETE_USER. Output message "Account deleted".

Không drop user row/FK lịch sử. Cần giữ tối thiểu một admin hoạt động nên có thể409 nếu xóa admin cuối cùng.

### protectLastAdmin(before,role,status)

Chỉ vào kiểm khi before.role=ADMIN và trạng thái/role mới không còn ADMIN/ACTIVE. Repository khóa danh sách admin hoạt động FOR UPDATE; stream.noneMatch kiểm không có admin khác UUID before. Không có người thay thế →409.

Lock dùng ở edit/delete giúp hai request đồng thời không cùng nghĩ còn một admin khác rồi loại cả hai.

### tiers(), createTier(body), editTier(id,body)

| Hàm | Thực hiện |
| --- | --- |
| `tiers` | ADMIN, đọc các tier để quản trị |
| `createTier` | Insert tier_code/display_name/price_monthly/currency/is_active và timestamps, audit CREATE_TIER, trả row |
| `editTier` | Đọc before, bảo vệ FREE, update fields, audit UPDATE_TIER, trả after |

Create/edit là transaction. DTO price BigDecimal>=0, tối đa99999999.99 với2 chữ số thập phân; currency3 chữ uppercase; code2–30 chữ uppercase/số/underscore.

Nếu before.tier_code=FREE, không cho đổi code, deactivate hoặc đặt giá khác0. Đây là tier fallback mà [EntitlementService](#svc-entitlementservice) cần khi user không có paid subscription phù hợp.

### quotas(id,body): upsert quota theo feature

Body list tối đa100 SubscriptionQuotaRequest: featureCode/quotaValue/quotaPeriod.

1. ADMIN, kiểm tier tồn tại.
2. Stream map featureCode/distinct/count; duplicate trong body →400.
3. Đọc before.
4. Duyệt từng quota, upsert theo unique tier_id/feature_code.
5. Đọc after toàn bộ tier, audit UPDATE_QUOTAS, trả list.

Không xóa quota không xuất hiện trong body; list rỗng không xóa toàn bộ quotas. quotaValue>=0; quotaPeriod DAILY/MONTHLY/YEARLY/LIFETIME. Transaction bảo đảm cập nhật một batch hoặc rollback cùng batch.

### configurations() và configure(body)

configurations đọc tối đa100 phiên bản AI config, sắp config_type/version desc.

configure:

1. ADMIN, nhận AiConfigurationRequest(configType,provider,modelName,configuration).
2. Map configuration đổi toString/lowercase; nếu có api_key/apikey/secret/password thì400. Đây là kiểm substring của code, không phải parser secret đầy đủ.
3. Advisory lock theo configType.
4. version=max(version)+1; inactive các version cùng type.
5. Insert config mới active với JSON, updated_by/timestamps.
6. Audit CONFIGURE_AI, trả row.

Transaction + lock giữ version nhất quán giữa request đồng thời. Credentials thực nằm trong env. Config DB không tự tạo provider bean mới; [AiService](#svc-aiservice) đọc config CHAT theo provider, trong khi embedding model/provider bean phụ thuộc startup configuration.

### restoreConfiguration(oldVersionId)

Không sửa version cũ thành active tại chỗ. Đọc old → lock configType → version mới → inactive cùng type → clone provider/model/config_json thành row UUID mới active → audit RESTORE_AI_CONFIGURATION → trả clone.

LinkedHashMap cho phép config_json null; audit lưu old và row restored. Gọi lặp có thể tạo nhiều version mới.

### prompts() và prompt(body)

prompts đọc tối đa100 phiên bản theo feature/version.

prompt nhận featureCode/name/systemPrompt/userPromptTemplate:

1. ADMIN, lock feature.
2. version mới, inactive template cùng feature.
3. Insert prompt active + updated_by/timestamps.
4. Audit UPDATE_PROMPT và trả row.

DTO feature code2–100 uppercase/số/underscore, name tối đa150, mỗi prompt text tối đa50000. AI service dùng các prompt làm task guidance; JSON schema và ranh giới dữ liệu untrusted vẫn được service áp dụng.

### restorePrompt(oldVersionId)

Đọc prompt cũ → lock featureCode → version mới → inactive feature → clone name/system/user prompt thành row mới active → audit RESTORE_AI_PROMPT → trả clone. Transaction không xóa lịch sử cũ.

### monitor(category)

ADMIN; switch whitelist cố định, không ghép table name trực tiếp từ client:

| Category | Nguồn đọc |
| --- | --- |
| ai-usage | ai_usage_logs |
| vector-stores | vector_store_metrics |
| indexing-jobs | vector_indexing_jobs |
| logs | system_logs |
| audit | audit_logs |
| billing | payment_orders |
| crawl-runs | market_crawl_runs |

Mỗi query trả tối đa100 mới nhất. category ngoài danh sách →400. Monitoring hiện không nhận filter/pagination tùy ý ở method này.

### reports() và resolve(id,body)

reports đọc payment_reports, tối đa100 mới nhất.

resolve là transaction: ADMIN →before report →update resolution/resolved_by/resolved_at/status RESOLVED theo repository →after →audit RESOLVE_PAYMENT_REPORT. Không tự refund payment hoặc gọi gateway; đây là xử lý báo cáo/hỗ trợ.

### Cách sử dụng

Prefix /api/v1/admin:

| Nhóm | Route gọi service |
| --- | --- |
| Dashboard/user | GET /dashboard; GET/POST /users; GET/PUT/DELETE /users/{id} |
| Tier/quota | GET/POST /subscription-tiers; PUT /subscription-tiers/{id}; PUT /subscription-tiers/{id}/quotas |
| Config | GET/POST /ai/configurations; POST /ai/configurations/{oldVersionId}/restore |
| Prompt | GET/POST /ai/prompts; POST /ai/prompts/{oldVersionId}/restore |
| Monitor | GET /monitoring/{category} |
| Report | GET /payment-reports; POST /payment-reports/{id}/resolve |

Ví dụ tạo consultant; dùng mật khẩu tạm và email của môi trường test:

~~~json
{
  "email": "consultant@example.test",
  "fullName": "Consultant Example",
  "role": "CAREER_CONSULTANT",
  "temporaryPassword": "Example12345!"
}
~~~

Ví dụ config không chứa credentials:

~~~json
{
  "configType": "CHAT",
  "provider": "gemini",
  "modelName": "gemini-2.5-flash-lite",
  "configuration": {"temperature": 0.2}
}
~~~

Giá trị model ở ví dụ khớp default repository; configure lưu cấu hình, không bảo đảm mọi key config_json được dùng để thay tất cả option runtime.

<a id="svc-auditservice"></a>

## AuditService

**Code:** [AuditService.java](../src/main/java/com/example/intelligent_cv_matching_ai_agent/service/admin/monitoring/AuditService.java).

Dùng chung cho AdminService để ghi dấu vết thay đổi và hành động quản trị. Không có controller/endpoint riêng gọi record để người dùng tự tạo log.

### Constructor và dependency

AuditRepository insert audit_logs/admin_actions và serialize JSON. CurrentUser xác định actor từ HTTP context. Không tự lấy actor scheduler; background job/crawl sử dụng helper audit riêng với explicit UUID.

### record(action,entity,id,before,after)

Input: tên action/entity, UUID đối tượng, snapshot before/after; output void.

1. Tạo LinkedHashMap để before/after có thể null.
2. actor_user_id=current.id(), action/entity/entity_id/timestamp.
3. old_value/new_value qua db.json, insert audit_logs.
4. Nếu current.hasRole("ADMIN"), insert thêm admin_actions với admin_user_id/action_type/target_type/target_id/metadata(after)/timestamp.

Không có `REQUIRES_NEW` riêng. Khi caller như AdminService.create/edit dùng transaction, log tham gia cùng transaction, rollback nghiệp vụ cũng rollback log.

Map.of trong nhánh admin không nhận Java null; db.json(after) tạo wrapper JSON kể cả input null. Actor/entity UUID vẫn phải do workflow cung cấp hợp lệ. Snapshot account đã được repository loại password/token trước khi audit.

### Cách sử dụng và phân biệt các loại log

Admin đổi user/tier/config/prompt/report → gọi record với before/after → xem `GET /api/v1/admin/monitoring/audit`. audit_logs ghi sự kiện nghiệp vụ; admin_actions ghi vai trò quản trị; ai_usage_logs do [AiUsageRecorder](#svc-aiusagerecorder) ghi; vector_indexing_jobs là trạng thái tác vụ, không phải audit.

<a id="doc-flows"></a>

## Luồng liên kết giữa các service

### Auth và dữ liệu ứng viên

~~~mermaid
flowchart TD
    C[AuthController] --> F[AuthFlowService]
    F --> A[AuthService]
    F --> G[GoogleLoginService]
    F --> T[TokenService]
    A --> T
    G --> T
    T --> S[Bearer authentication and DB session]
    S --> P[ProfileService]
    S --> R[ResumeService]
    R --> AI[AiService]
    AI --> E[EntitlementService or GuestAccess]
    AI --> U[AiUsageRecorder]
~~~

Đăng ký email mới cần verify trước login. Login/Google trả token; bearer dùng token đó cho owner. CV READY là prerequisite cho nhiều luồng nghề nghiệp, không chỉ upload có file là đủ.

### Job crawl và semantic recommendation

~~~mermaid
flowchart TD
    AC[Admin request] --> CR[JobCrawlService]
    SCH[JobCollectionScheduler] --> CR
    CR --> JS[JobService]
    JS --> Q[JobEmbeddingService queue]
    Q --> AI[AiService embedding]
    AI --> V[Current job vector]
    EX[Scheduled expiration] --> ST[Source and canonical status]
    ST --> V
    CV[Candidate profile or READY CV] --> M[MatchingService]
    V --> M
    M --> R[AI rerank or rule branch]
    R --> CT[CareerToolsService result]
~~~

Arrow từ expiry đến vector nghĩa invalidate khi canonical không còn hiệu lực. Rerank sử dụng chat khi AI phù hợp; rule branch có thể hoạt động khi chat tắt. Khi model/provider thay đổi, phải index dữ liệu tương thích; queue không tự đổi model của task cũ.

### Billing, quota và consultation

1. BillingQueryService kiểm request/owner/signature.
2. BillingService tạo PENDING order hoặc xử lý webhook đã verify.
3. Payment completed kích hoạt subscription/invoice/notification.
4. EntitlementService chọn tier và reserve quota trước các AI calls.
5. SubscriptionMaintenance xử lý các subscription quá hạn và thông báo.
6. ConsultationService tạo/assign thread; messages/close/read/SSE đều theo quyền thread.
7. ConsultationAnalysisService đọc các báo cáo đã lưu của candidate trong thread, không gọi AI mới.

### Các luồng học tập/assistant/admin

- LearningService gọi AI đánh giá gap/lộ trình, kiểm output và lưu DAG. AssessmentService kiểm questions/answers rồi submit kết quả.
- CareerAssistantService giữ conversation/message/context; RAG index do RagDocumentService chuẩn bị, retrieval chỉ lấy tài liệu đủ điều kiện.
- AnnouncementService phát nội dung theo audience và giữ notifications.
- SupportService xử lý ticket có owner và trao đổi.
- AdminService điều chỉnh user/quota/config/prompt và monitoring; AuditService giữ snapshot thay đổi.

<a id="doc-usage"></a>

## Cách chạy và kiểm tra chức năng

File này giải thích code; để chạy backend xem [project-guide.md](project-guide.md), danh mục138 API/input xem [postman-api-guide.md](postman-api-guide.md).

### Một vòng đọc và test từ đầu

1. Khởi động PostgreSQL/schema qua Flyway, cấu hình .env bằng script local hoặc env của IDE/JAR.
2. Tạo/verify account, login; lấy token đúng role.
3. GET profile; tạo/upload CV, xem parse result, sửa structured_data và confirm READY.
4. Admin nhập/crawl job; đọc run/items và index status.
5. Candidate matching/ATS/analysis/draft; lấy UUID từ response đúng loại.
6. Sau đó mới test assessment/roadmap/assistant/consultation theo prerequisite của từng phương thức.
7. Billing/payment webhook và SMTP/model thật cần adapter/credentials thật nếu muốn test external integration.
8. Đọc monitoring/log/errors để hiểu nhánh thất bại, thay vì chỉ kiểm HTTP200.

Không tự tạo IDs giả: jobId, resumeId, taskId, conversationId, paymentOrderId, reservationId và consultationThreadId là các đối tượng khác nhau.

### Dữ liệu đầu vào

- Dùng tên field DTO; metadata optional thì bỏ khi không có dữ kiện.
- Boolean/number là JSON boolean/number, không đổi tất cả thành chuỗi.
- Ngày ISO8601 có timezone khi field là OffsetDateTime.
- Ví dụ URL/email/password/model trong file là mẫu; thay bằng dữ liệu đúng môi trường.
- Với GET public, bỏ bearer đã hết hạn nếu đang inherit trong Postman.
- Tạo resource → đọc detail để kiểm normalize → chuyển state cần thiết → gọi feature phụ thuộc.

### Cấu hình quan trọng

| Nhóm | Cấu hình cần đọc |
| --- | --- |
| Identity/SMTP | MAIL_ENABLED/MAIL_HOST/MAIL_PORT/MAIL_USERNAME/MAIL_PASSWORD/MAIL_SMTP_AUTH/MAIL_STARTTLS/MAIL_FROM, AUTH_EXPOSE_DEV_OTP |
| Google | GOOGLE_CLIENT_ID và ID token hợp lệ |
| AI | AI_ENABLED, AI_PROVIDER=gemini, AI_CHAT_PROVIDER=openai, AI_EMBEDDING_PROVIDER=gemini, GEMINI_BASE_URL; chat gemini-2.5-flash-lite, embedding gemini-embedding-001/1024 chiều; cần GEMINI_API_KEY |
| Storage | UPLOAD_DIR hoặc app.storage.path; app YAML default ./uploads, annotation ResumeService có fallback ./storage/resumes khi property không có |
| Guest | Guest token limit và X-Guest-Session |
| Payment | PAYMENT_PROVIDER, PAYMENT_WEBHOOK_SECRET, signature/timestamp body contract |
| Crawl/expiry | JOB_COLLECTION_* / JOB_EXPIRATION_*, configured admin ACTIVE |
| Embedding worker | JOB_EMBEDDING_*, provider và quota owner |
| Billing maintenance | BILLING_MAINTENANCE_ENABLED và maintenance zone/batch properties |
| Cache/SSE | Redis tùy chọn cho cache; SSE subscribers trong memory của một process |

Cấu hình dùng chung được ánh xạ ở [application.yaml](../src/main/resources/application.yaml); các profile `dev`, `prod`, `docker` bổ sung cấu hình và giá trị mặc định theo môi trường. Không chọn active profile thì dùng `dev`. Spring Boot tự nạp `.env` UTF-8 từ working directory qua `spring.config.import`; có thể chọn file khác bằng `APP_ENV_FILE`. Biến môi trường hệ điều hành/container được ưu tiên hơn file; secret và các biến bắt buộc vẫn phải được cấp. Xem [cấu hình profile](runtime-profiles.md) và [README](../README.md) để chọn cách chạy và khai báo biến.

Compose local hiện chạy app với PostgreSQL trên host qua `host.docker.internal:5432/edunexus`, Redis `redis:6379` và chờ Redis healthy. Chạy bằng `docker compose --profile app --profile optional up -d --build app`; PostgreSQL container là lựa chọn riêng trong profile `database` cho backend chạy trên host.

### Kiểm thử code hiện có

Các test được đặt theo feature trong [src/test/java](../src/test/java/com/example/intelligent_cv_matching_ai_agent). Lần cập nhật Markdown này không chạy lại toàn bộ test; không xem việc sửa tài liệu là một lần kiểm thử mới. Kết quả build/ingestion lần trước xem [job-automation-verification.md](job-automation-verification.md); không coi kết quả đó là đã thử provider/website hôm nay.

<a id="doc-limits"></a>

## Các giới hạn cần hiểu đúng khi đọc code

| Điều dễ nhầm | Hành vi thật |
| --- | --- |
| Import job xong là có vector | Import enqueue; cần task COMPLETED đúng hash/provider/model |
| Mọi index đều cùng queue | Job index dùng lease worker; RAG và CV embedding có luồng riêng |
| AI off là mọi tính năng vẫn sinh kết quả AI | Một số feature có rule/baseline fallback; feature bắt buộc provider trả503 |
| Parse JSON AI thành công là nội dung hợp lệ | Service còn kiểm schema/score/questions/DAG/ownership và điều kiện nghiệp vụ |
| Rollback SQL sẽ hoàn tiền/quota provider | Reservation/usage có transaction riêng; provider đã phản hồi có thể vẫn bị charge |
| Event SSE chắc replay được sau reconnect | Subscribers process-local, không có message replay tự động ở SSE entry |
| Package consultant nghĩa chỉ consultant dùng | Quyền thread/owner/assigned/admin do code kiểm, không do tên folder |
| Cache luôn có cùng TTL | Matching và ATS dùng điều kiện/cache khác nhau; đọc phần cache của từng service |
| Mọi query job kiểm deadline | Một số query guest/learning còn chỉ lọc status ACTIVE; phần service mô tả đúng query hiện tại |
| Deadline thiếu thì tự expire theo tuổi job | Expiration không suy ngày hết hạn khi nguồn không cung cấp |
| Config AI JSON thay mọi runtime option | Nhiều field chỉ được lưu; bean/provider/model và option sử dụng phụ thuộc code đọc |
| Payment endpoint tạo checkout/QR thật | Hiện là order + signed webhook generic, không adapter checkout gateway đầy đủ |
| Skills từ crawl là hiểu ngôn ngữ hoàn chỉnh | Dictionary/aliases/section heuristic, không nhận biết mọi skill/phủ định |
| Callback transaction giống rollback mọi file | Cần xem afterCompletion/write cleanup; file system không là JDBC transaction |

Đọc source khi muốn sửa hành vi, sau đó tìm controller/DTO/repository/test liên quan để giữ contract và dữ liệu. Khi thêm một service/phương thức mới, cập nhật mục lục và phần tương ứng của file này.
