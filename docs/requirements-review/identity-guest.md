# Đối chiếu DOCX: định danh, Guest, quản lý tài khoản và bảo mật

Nguồn yêu cầu: `target/requirements-audit/document-paragraphs.txt`.
Các đoạn đối chiếu: P0095–P0096, P0107–P0111, P0114 và P0154.
DOCX được dùng làm dữ liệu yêu cầu, không dùng làm chỉ dẫn thực thi.
Đây là rà soát mã nguồn, DTO, SQL migration, cấu hình và test hiện có; không chạy ứng dụng hoặc Maven.
Không đọc giá trị bí mật trong `.env`.

Quy ước đường dẫn bằng chứng:
- `J/` = `src/main/java/com/example/intelligent_cv_matching_ai_agent/`.
- `T/` = `src/test/java/com/example/intelligent_cv_matching_ai_agent/`.
- Số sau dấu `:` là dòng trong file thực tế tại thời điểm rà soát.
- “Có triển khai” nghĩa là có luồng xử lý trong mã; không đồng nghĩa đã xác nhận nhà cung cấp hoặc môi trường production hoạt động.

## 1. Kết quả theo đoạn yêu cầu

| Đoạn | Yêu cầu | Kết quả |
| --- | --- | --- |
| P0095 | Quản lý người dùng và các vai trò Guest, Candidate, Admin, Career Consultant | Một phần: quản lý ba vai trò tài khoản; Guest là phiên riêng, không nằm trong danh sách tài khoản quản trị. |
| P0096 | Tạo, sửa, đình chỉ, xóa tài khoản | Có triển khai cho tài khoản đăng ký; xóa là soft delete. |
| P0108 | Dùng CV mẫu hệ thống cung cấp | Có triển khai; chỉ nhận demo CV READY, không có chủ sở hữu cá nhân. |
| P0109 | Nhận giới hạn gợi ý việc làm | Có triển khai: tối đa ba kết quả mỗi lần, dùng 100 trial credits. |
| P0110 | AI Resume Builder với giới hạn mẫu | Có triển khai; chỉ mẫu active và trial-enabled, dùng dữ liệu demo. Phụ thuộc AI provider được bật. |
| P0111 | Chat AI Career Assistant trong giới hạn token | Có triển khai: conversation riêng cho Guest, ngân sách token trong DB và quyết toán usage. Phụ thuộc AI provider được bật. |
| P0114 | Đăng ký/đăng nhập, hồ sơ cá nhân và tùy chọn nghề nghiệp | Có triển khai cùng OTP, quản lý mật khẩu/phiên và hồ sơ nghề nghiệp. |
| P0154 | AES-256, TLS 1.3, JWT + Refresh Token, OAuth2, RBAC, audit mọi thao tác quản trị | Một phần; chi tiết bên dưới. |

## 2. Guest: hành vi thực tế và điều kiện vận hành

### P0108: CV demo

- `GET /api/v1/guest/demo-resumes` yêu cầu `X-Guest-Session`; phiên phải hợp lệ, còn hạn.
  Bằng chứng: `J/controller/guest/identity/GuestController.java:33`;
  `J/service/guest/identity/GuestSessionService.java:69`.
- Danh sách chỉ gồm demo READY, tối đa 20.
  Khi chạy builder/recommendation, CV được kiểm tra thêm `user_id IS NULL`.
  Bằng chứng: `J/repository/guest/identity/GuestSessionRepository.java:32`;
  `J/repository/guest/ai/GuestTrialRepository.java:59`.
- Có test không cho CV cá nhân đi qua luồng Guest hoặc làm tiêu hao quota:
  `T/ai/GuestTrialTests.java:104`.

### P0109: giới hạn gợi ý việc làm

- `POST /api/v1/guest/trials/recommendations` nhận `demo_resume_id`.
  Hệ thống xếp hạng bằng skill overlap, lưu run/results và trả tối đa ba việc làm.
  Đây là preview rule-based; không phải luồng semantic/LLM đầy đủ của Candidate.
  Bằng chứng: `J/controller/guest/ai/GuestTrialController.java:31`;
  `J/service/guest/ai/GuestTrialService.java:99`, `:109`.
- Mỗi lần reserve 100 credits, success quyết toán 100; kết quả có lời mời đăng ký.
  Giới hạn này là số do server quy định, không nhận chi phí từ client.
  Bằng chứng: `J/service/guest/ai/GuestTrialService.java:77`, `:135`;
  `T/ai/GuestTrialTests.java:73`.
- P0109 không yêu cầu Guest dùng semantic matching, nên khác biệt thuật toán nêu trên
  là mô tả phạm vi triển khai, không tự động là thiếu chức năng.

### P0110: builder giới hạn template

- `POST /api/v1/guest/trials/build` chỉ chấp nhận template đang active và bật trial.
  Payload gửi AI lấy facts từ CV demo và template hợp lệ; không dùng CV cá nhân.
  Bằng chứng: `J/repository/guest/ai/GuestTrialRepository.java:26`;
  `J/service/guest/ai/GuestTrialService.java:44`, `:55`.
- Trả `structured_data` và registration prompt; không tạo CV cá nhân đã lưu cho Guest.
  Bằng chứng: `J/service/guest/ai/GuestTrialService.java:57`;
  `T/ai/GuestTrialTests.java:50`.

### P0111: chat và token limit

- Có tạo conversation, đọc lịch sử và gửi message tại `/api/v1/guest/assistant`.
  Conversation được gắn `guest_session_id`; đọc conversation của phiên khác trả 404.
  Bằng chứng: `J/controller/guest/ai/GuestAssistantController.java:21`, `:28`, `:37`;
  `J/service/candidate/ai/CareerAssistantService.java:65`, `:277`.
- Guest context chỉ là trial; không lấy career profile/CV riêng của Candidate.
  Nhánh retrieval chỉ chạy cho người dùng đăng ký.
  Bằng chứng: `J/service/candidate/ai/CareerAssistantService.java:145`, `:299`.
- Mặc định 5.000 token/credits mỗi phiên, phiên tồn tại 24 giờ.
  Reserve ghi PROCESSING trong transaction riêng; SQL tăng quota nguyên tử chỉ khi còn đủ
  và phiên còn hạn/chưa chuyển đổi. Settle ghi usage thực tế và hoàn phần reserve thừa.
  Hết ngân sách trả 429 kèm yêu cầu đăng ký.
  Bằng chứng: `J/service/guest/identity/GuestSessionService.java:29`, `:41`;
  `J/service/guest/identity/GuestAccess.java:48`, `:68`, `:84`;
  `J/repository/guest/identity/GuestAccessRepository.java:35`.
- Có test reserve, hoàn quota, giữ usage đã tính phí khi output AI không hợp lệ,
  chống refund lặp và không charge vượt reserve:
  `T/identity/GuestAccessTest.java:19`, `:35`, `:46`, `:57`, `:73`.

### Điều kiện ảnh hưởng các luồng Guest

- Builder và gửi chat trả 503 nếu AI bị tắt hoặc không có chat model.
  Cấu hình mặc định là `AI_ENABLED=false`; recommendation rule-based vẫn có đường xử lý độc lập.
  Bằng chứng: `src/main/resources/application.yaml:105`;
  `J/service/shared/ai/AiService.java:89`, `:120`.
- Giới hạn tạo session là ba phiên/IP/ngày nhưng throttle lưu trong bộ nhớ từng instance.
  Ngân sách của một phiên nằm trong DB, còn giới hạn tạo phiên không chia sẻ giữa các instance
  và mất khi process khởi động lại. Đây là hạn chế chống lạm dụng, không phải thiếu quota của phiên.
  Bằng chứng: `J/service/guest/identity/GuestSessionService.java:39`;
  `J/security/shared/AuthThrottle.java:21`.

## 3. Candidate: đăng ký, đăng nhập và profile (P0114)

- Có register, verify-email, resend-code, login, refresh, logout và Google sign-in.
  Email được chuẩn hóa; password được BCrypt cost 12; đăng ký tạo Candidate PENDING,
  OTP hết hạn sau 10 phút, tối đa năm lần sai và resend cách nhau tối thiểu 60 giây.
  Bằng chứng: `J/controller/shared/identity/AuthController.java:28`–`:78`;
  `J/config/shared/SecurityConfig.java:28`;
  `J/service/shared/identity/AuthService.java:99`, `:127`, `:140`, `:184`.
- Mặc định SMTP delivery và development OTP đều tắt.
  Register/resend không thể hoàn tất nếu không bật/cấu hình SMTP hoặc chế độ development OTP;
  service trả 503, không âm thầm kích hoạt account.
  Bằng chứng: `src/main/resources/application.yaml:110`, `:113`;
  `J/service/shared/identity/AuthService.java:146`.
- `GET/PUT /api/v1/me` quản lý profile; `GET/PUT /api/v1/me/career-profile`
  quản lý nghề hiện tại/mục tiêu, salary range, địa điểm/workplace, preferences và skills.
  Có kiểm tra salary min <= max, skill trùng và skill tồn tại.
  Response cá nhân dùng projection, không trả password hash/Google subject.
  Bằng chứng: `J/controller/shared/identity/ProfileController.java:27`, `:40`;
  `J/dto/request/candidate/profile/CareerProfileRequest.java:11`;
  `J/service/shared/identity/ProfileService.java:72`, `:95`;
  `J/dto/response/shared/identity/PersonalProfileResponse.java:13`.
- Có đổi mật khẩu cho EMAIL account, thu hồi token sau khi đổi, xem/thu hồi session,
  xóa tài khoản và bảo vệ Admin active cuối cùng.
  Bằng chứng: `J/service/shared/identity/ProfileService.java:119`, `:138`, `:151`, `:156`.
- Bằng chứng test: `T/CoreFlowIntegrationTests.java:198` cho đăng ký/OTP/refresh;
  `T/identity/ProfileAdminGuardTest.java:25` cho bảo vệ Admin cuối cùng.
  Không có yêu cầu password-reset riêng trong P0114 nên không kết luận thiếu chỉ vì không thấy luồng đó.

## 4. Admin quản lý người dùng (P0095–P0096)

- Admin có list/search/page, đọc chi tiết, tạo, sửa và xóa user tại `/api/v1/admin/users`.
  Service kiểm tra ADMIN ngoài kiểm tra route; tạo password tạm được hash,
  sửa status ACTIVE/SUSPENDED và thu hồi token khi đình chỉ hoặc đổi role.
  Bằng chứng: `J/controller/admin/management/AdminController.java:36`, `:58`, `:66`;
  `J/service/admin/management/AdminService.java:53`, `:110`, `:157`.
- Delete là cập nhật `status='DELETED'` và `deleted_at`, không xóa vật lý dữ liệu liên quan.
  Code không cho khôi phục account đã deleted; không cho bỏ Admin active cuối cùng;
  không cho chuyển account chưa verify email sang ACTIVE.
  Bằng chứng: `J/repository/admin/management/AdminRepository.java:115`;
  `J/service/admin/management/AdminService.java:144`, `:147`, `:174`;
  `T/admin/AdminAccountTest.java:25`.
  P0096 không quy định hard delete, nên soft delete là lựa chọn triển khai, không tự động là thiếu chức năng.
- **Lệch P0095 nếu “quản lý Guest role” được hiểu là quản trị cùng danh sách user**:
  DTO và DB chỉ chấp nhận CANDIDATE/CAREER_CONSULTANT/ADMIN;
  Guest nằm trong `guest_sessions`, không có route quản trị list/suspend/delete Guest session.
  Bằng chứng: `J/dto/request/admin/management/AccountCreateRequest.java:15`;
  `J/dto/request/admin/management/AccountUpdateRequest.java:11`;
  `src/main/resources/db/migration/V1__edunexus_complete_schema.sql:13`, `:62`.
  Nếu Guest được chấp thuận là tác nhân không có tài khoản, cần sửa mô tả yêu cầu thay vì thêm role giả.
- Bootstrap Admin chỉ nâng role của tài khoản ACTIVE đã verify và khi chưa có Admin active;
  có log BOOTSTRAP_ADMIN. Không thấy account/password Admin mặc định được tạo sẵn.
  Bằng chứng: `scripts/bootstrap-admin.sql:6`, `:11`.

## 5. Bảo mật P0154: các phần đã có và các khoảng trống

### JWT + Refresh Token: refresh có, JWT nền tảng chưa có

- Access token là chuỗi ngẫu nhiên `a_`, refresh là `r_`, dài 45 ký tự.
  Access hết hạn sau 15 phút, refresh sau 30 ngày; DB chỉ lưu SHA-256 hash.
  Bearer filter tra DB theo hash, trạng thái/expiry/revocation; đây là opaque token, không phải JWT.
  Bằng chứng: `J/service/shared/identity/TokenService.java:45`, `:56`, `:59`, `:85`;
  `J/repository/shared/identity/TokenRepository.java:39`.
- Refresh có lock và rotation một lần, revoke refresh cũ trước khi cấp cặp mới.
  Logout/password change/suspend/delete thu hồi token. Rotate không thu hồi access token cũ;
  access cũ còn hiệu lực đến hạn hoặc đến khi bị revoke.
  Bằng chứng: `J/service/shared/identity/TokenService.java:93`, `:107`;
  `J/service/shared/identity/AuthFlowService.java:84`.
- Google ID token được verify là JWT của Google, không chứng minh access token nền tảng là JWT.
  Có test ngăn refresh dùng làm Bearer, bảo đảm hash-only, trạng thái account và lock rotation:
  `T/identity/TokenServiceTest.java:20`, `:33`, `:49`, `:62`, `:82`.

### OAuth2: có Google/OIDC sign-in với điều kiện cấu hình

- `POST /api/v1/auth/google` nhận ID token; verifier dùng Google JWK,
  kiểm tra issuer, signature, audience/client ID, expiry, subject, verified email và azp khi cần.
  Account Google được tạo/đăng nhập theo subject; email trùng account cũ không bị tự động link.
  Email ngoài Gmail/Google hosted domain phải qua OTP của hệ thống trước khi ACTIVE.
  Bằng chứng: `J/controller/shared/identity/AuthController.java:78`;
  `J/security/shared/GoogleTokenVerifier.java:40`, `:62`, `:68`;
  `J/service/shared/identity/GoogleLoginService.java:49`, `:64`, `:74`.
- Thiếu `GOOGLE_CLIENT_ID` trả 503. Không thấy backend authorization-code/redirect flow;
  đây là Google ID-token exchange từ client. P0154 không nêu provider/grant cụ thể,
  nên không kết luận “OAuth2 hoàn toàn chưa làm”; cần ghi rõ phạm vi Google/OIDC hiện có.
  Bằng chứng: `J/security/shared/GoogleTokenVerifier.java:51`;
  `J/config/shared/SecurityConfig.java:32`.
- Có test wrong audience, unverified email, third-party email verification và không tự link account:
  `T/security/shared/GoogleTokenVerifierTest.java:26`, `:37`, `:48`, `:58`;
  `T/identity/GoogleLoginServiceTest.java:27`.

### RBAC: có triển khai

- `/api/v1/admin/**` yêu cầu ROLE_ADMIN; route còn lại mặc định phải authenticate
  trừ public allowlist. Bearer tạo authority từ role của account trong DB.
  Service có requireRole và kiểm tra chủ sở hữu theo tài nguyên.
  Guest sử dụng xác thực session riêng ở controller/service, không phải authenticated GUEST role.
  Bằng chứng: `J/config/shared/SecurityConfig.java:40`, `:62`;
  `J/security/shared/BearerFilter.java:44`;
  `J/security/shared/CurrentUser.java:33`, `:40`.
- Cơ chế này không tự chứng minh mọi route nghiệp vụ đã phân quyền hoàn chỉnh;
  kiểm tra sâu quyền Consultant/analysis thuộc phạm vi audit module đó.

### AES-256 at rest: chưa có bằng chứng đáp ứng

- CV raw/structured data được ghi trực tiếp text/JSONB; file upload được ghi bytes bằng Files.write.
  Compose production gắn volume upload thông thường, không cấu hình encryption/key management.
  Không thấy AES/Cipher/encrypt/decrypt trong application/migration/config đã rà soát.
  Bằng chứng: `J/service/candidate/resume/ResumeService.java:126`, `:550`;
  `src/main/resources/db/migration/V1__edunexus_complete_schema.sql:145`;
  `compose.production.yaml:12`.
- Hash password/token, DTO toString redaction, từ chối lưu API key vào AI configuration,
  và projection bỏ credential là biện pháp khác; không phải mã hóa AES-256.
  Bằng chứng: `J/config/shared/SecurityConfig.java:28`;
  `J/service/shared/identity/TokenService.java:45`;
  `J/service/admin/management/AdminService.java:292`.
- Có thể host/database/storage bên ngoài đã mã hóa ổ đĩa; repository không cung cấp bằng chứng đó.
  Kết luận ở đây: chưa triển khai/chưa chứng minh NFR AES-256, không khẳng định hạ tầng thực tế chắc chắn không mã hóa.

### TLS 1.3 in transit: cấu hình triển khai chưa bảo đảm

- Production compose xuất cổng ứng dụng 8080, không kèm reverse proxy/certificate/TLS policy.
  application.yaml không cấu hình server SSL hoặc giới hạn giao thức TLS 1.3.
  Redis SSL mặc định false; datasource mặc định cũng không ép sslmode.
  Bằng chứng: `compose.production.yaml:5`; `Dockerfile:9`;
  `src/main/resources/application.yaml:5`, `:31`, `:93`.
- Google JWK gọi HTTPS không chứng minh TLS 1.3 cho API, DB, Redis và file/data traffic toàn hệ thống.
  Có thể TLS termination nằm ngoài repo; cần chứng cứ cấu hình deployment/handshake,
  không được đánh dấu NFR hoàn tất chỉ dựa trên URL HTTPS.

### Audit “all administrative actions”: có nhiều log nhưng chưa bao phủ tất cả

- AuditService ghi actor/action/entity/before/after/time vào audit_logs và admin_actions.
  User CRUD, tier/quota/config/prompt/restore/payment report có gọi audit;
  announcement create/update/publish/archive và RAG import/index/archive cũng có audit.
  Bằng chứng: `J/service/admin/monitoring/AuditService.java:31`, `:40`, `:43`;
  `J/service/admin/management/AdminService.java:132`, `:163`, `:179`, `:361`, `:441`;
  `J/service/shared/announcement/AnnouncementService.java:65`, `:140`;
  `J/service/admin/rag/RagDocumentService.java:96`, `:185`, `:206`.
- **Ví dụ mutation chưa có audit_logs event**: Admin trả lời support ticket qua
  `POST /api/v1/support/tickets/{id}/messages` lưu message và notification rồi return,
  không gọi AuditService. Tin nhắn có sender/time trong bảng nghiệp vụ,
  nhưng không phải event audit quản trị.
  Bằng chứng: `J/controller/shared/support/SupportController.java:45`;
  `J/service/shared/support/SupportService.java:101`, `:115`, `:135`.
  Update trạng thái/assignment của support ticket có audit, không nên kết luận cả support chưa có audit:
  `J/service/shared/support/SupportService.java:153`.
- **Ví dụ mutation thứ hai**: `POST /api/v1/admin/jobs/{id}/index` sửa embeddings,
  tạo vector_indexing_jobs và return; không ghi audit_logs hoặc admin actor vào indexing record.
  Có log job nghiệp vụ không tương đương log hành động quản trị.
  Bằng chứng: `J/controller/admin/jobs/AdminJobController.java:29`;
  `J/service/shared/jobs/JobService.java:157`, `:172`, `:195`.
- GET dashboard/users/config/monitoring không có audit đọc ở các service đã rà.
  Nếu “all actions” bao gồm đọc dữ liệu quản trị thì khoảng trống rộng hơn;
  ngay khi chỉ tính mutation, hai ví dụ trên đã đủ để chưa đánh dấu đáp ứng đầy đủ.
  Bằng chứng: `J/service/admin/management/AdminService.java:56`, `:72`, `:85`.
- AuditService serialize before/after trực tiếp sang JSON, không có lớp mã hóa AES hoặc
  redaction tổng quát bên trong record. Không nên mô tả audit là “đã mã hóa”;
  các caller/projection chịu trách nhiệm không đưa credential vào payload.
  Bằng chứng: `J/service/admin/monitoring/AuditService.java:36`, `:37`.

## 6. Các điểm cần ưu tiên xác nhận hoặc hoàn thiện

1. Chọn tuân thủ JWT theo P0154 hoặc sửa tài liệu để chấp thuận opaque access token + refresh rotation.
2. Cung cấp/triển khai bằng chứng AES-256 at rest và TLS 1.3 từ storage/database/reverse proxy deployment.
3. Bổ sung audit cho mutation còn thiếu và quy định rõ “mọi hành động” có bao gồm đọc, lỗi và hành động thất bại không.
4. Làm rõ Guest là phiên không tài khoản hay một role phải quản trị được trong P0095.
5. Khi nghiệm thu chạy AI/Google/OTP thật, cấu hình provider/client ID/mail trước;
   các test nguồn/mock không thay thế xác minh tích hợp các dịch vụ đó.

## 7. P0160: tuân thủ bảo vệ thông tin cá nhân

Trạng thái: **chưa xác minh**. Rà soát code không đủ chứng minh tuân thủ pháp lý hoặc
quy trình vận hành; không đưa ra kết luận “đạt” hay “vi phạm”. Cần hồ sơ và chứng cứ
triển khai/quản trị dữ liệu riêng. RBAC, soft delete, hash credential và audit một số thao tác
không tự chứng minh yêu cầu compliance đã hoàn tất. Không đánh giá nội dung pháp luật trong lượt audit này.
Không sửa Java, cấu hình hay dữ liệu hệ thống trong lượt rà soát này.
