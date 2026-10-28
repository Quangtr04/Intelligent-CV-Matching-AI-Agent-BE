# Tài khoản, dùng thử, thanh toán và quản trị

Các API dùng tiền tố `/api/v1`. API riêng tư nhận `Authorization: Bearer <accessToken>`; API dùng thử nhận `X-Guest-Session: <sessionKey>`. Swagger mô tả request tại `/swagger-ui/index.html`. Dữ liệu lưu bằng JDBC vào schema PostgreSQL trong [schema.md](schema.md).

## Đăng ký và đăng nhập

| Phương thức | Đường dẫn | Nội dung |
| --- | --- | --- |
| POST | `/auth/register` | `email`, `password`, `fullName`, `preferredLanguage` tùy chọn (`vi`, `en`) |
| POST | `/auth/verify-email` | `email`, `code` gồm 6 chữ số |
| POST | `/auth/resend-code` | Gửi lại mã cho tài khoản `PENDING` |
| POST | `/auth/login` | Đăng nhập bằng email và mật khẩu |
| POST | `/auth/google` | `idToken` Google dành cho `GOOGLE_CLIENT_ID` |
| POST | `/auth/refresh` | `refreshToken`; mã cũ bị thu hồi sau khi đổi thành công |
| POST | `/auth/logout` | Thu hồi toàn bộ phiên của tài khoản hiện tại |

Ví dụ đăng ký:

```json
{"email":"candidate@example.com","password":"a-strong-password","fullName":"Nguyễn Văn A","preferredLanguage":"vi"}
```

Tài khoản email bắt đầu ở trạng thái `PENDING`; xác minh email chuyển sang `ACTIVE` và tạo career profile. OTP hết hạn sau 10 phút, cho phép tối đa 5 lần kiểm tra, gửi lại cách nhau ít nhất 60 giây. Mật khẩu tối thiểu 10 ký tự và tối đa 72 byte UTF-8, lưu bằng BCrypt. Các request chứa mật khẩu, OTP và token có `toString()` che dữ liệu để hạn chế lộ thông tin khi MVC ghi debug.

Email OTP sử dụng tên người gửi `EduNexus`, nội dung tiếng Việt UTF-8 và hai phiên bản HTML/văn bản thuần trong cùng email. Mẫu HTML nằm ở `src/main/resources/templates/mail/email-verification.html`; mã xác minh được chèn vào vị trí `{{verificationCode}}` khi gửi. Cả đăng ký và gửi lại mã đều dùng mẫu này.

Đăng nhập trả `accessToken`, `refreshToken`, `tokenType=Bearer`, `expiresIn=900`. Access token sống 15 phút; refresh token sống 30 ngày. Token là chuỗi ngẫu nhiên, chỉ hash SHA-256 được lưu trong `refresh_tokens`; refresh token không dùng được làm access token. Refresh đổi refresh token một lần; access token trước đó còn hiệu lực đến khi hết hạn hoặc bị thu hồi. Các thao tác phát hành, đổi và thu hồi token khóa tài khoản trước, cùng thứ tự với thay đổi mật khẩu và đình chỉ tài khoản. Tài khoản bị đình chỉ hoặc xóa không được xác thực.

Google sign-in kiểm tra chữ ký, issuer, audience, thời hạn và email đã xác minh. Hệ thống tìm tài khoản theo Google `sub`; cùng email với một tài khoản đã có **không tự động liên kết**. Google Gmail hoặc Workspace được tạo tài khoản đã xác minh; email bên thứ ba phải xác minh thêm bằng OTP của hệ thống. Không cấu hình `GOOGLE_CLIENT_ID` thì API trả 503.

## Hồ sơ và phiên đăng nhập

| Phương thức | Đường dẫn | Nội dung |
| --- | --- | --- |
| GET / PUT | `/me` | Thông tin cá nhân; PUT nhận `fullName`, `avatarUrl`, `preferredLanguage`, `githubUrl` |
| GET / PUT | `/me/career-profile` | Nghề hiện tại/mục tiêu, kinh nghiệm, lương, vị trí, preferences và kỹ năng |
| PUT | `/me/password` | `currentPassword`, `newPassword`; thu hồi mọi phiên và yêu cầu đăng nhập lại |
| GET | `/me/sessions` | Phiên chưa bị thu hồi, chưa hết hạn; không trả hash hoặc token thô |
| DELETE | `/me/sessions/{id}` | Thu hồi token thuộc tài khoản hiện tại |
| DELETE | `/me` | Xóa mềm bằng `status=DELETED`, `deleted_at` và thu hồi phiên |

Các API `/me` lấy chủ sở hữu từ phiên đăng nhập. Thay đổi career profile kiểm tra khoảng lương và kỹ năng trùng; vô hiệu embedding profile hiện tại và đánh dấu roadmap cũ. Không cho xóa quản trị viên hoạt động cuối cùng. Lịch sử thanh toán và khóa ngoại được giữ lại khi tài khoản bị xóa mềm.

## Guest trial và hạn mức AI

`POST /guest/sessions` tạo phiên dùng thử 24 giờ, trả `sessionKey` một lần. `GET /guest/sessions/me` xem hạn mức; `GET /guest/demo-resumes` và `GET /guest/templates` cung cấp CV/mẫu cho phép dùng thử. Guest không tải CV riêng và không truy cập dữ liệu ứng viên khác. Giới hạn tạo guest session hiện tại là 3 lần/IP/ngày trên mỗi tiến trình.

Các lời gọi AI đặt chỗ ngân sách trước khi gọi provider. Guest dùng `guest_sessions.token_limit`; ứng viên dùng `AI_TOTAL_TOKENS` cùng hạn mức theo `feature_code` trong tier hiệu lực. Một quota feature bằng 0 chặn feature đó. Các reservation đang xử lý cũng tính vào ngân sách, ngăn nhiều request đồng thời vượt quota.

Guest reservation tạo `trial_usage_events.status=PROCESSING`; hoàn tất chuyển `COMPLETED` hoặc `FAILED`, ghi token thực tế và hoàn lại phần chưa dùng trong một transaction. Retry cùng kết quả không hoàn tiền token lần thứ hai. Nếu provider trả kết quả nhưng thiếu metadata token, ngân sách đã đặt chỗ được tính là sử dụng; lỗi không nhận được phản hồi provider hoàn lại ngân sách. `ai_usage_logs` lưu telemetry riêng. Quota theo `DAILY`, `MONTHLY`, `YEARLY`, `LIFETIME`; cập nhật quota từ admin áp dụng ở request tiếp theo. Quota âm không được cấu hình qua API admin.

Hạn mức xác thực hiện dùng bộ đếm trong tiến trình; khi chạy nhiều replica cần cấu hình limiter dùng chung tại gateway. Các feature AI có thể trả 429 trước khi gọi provider nếu ngân sách dự kiến không còn đủ.

## Subscription và thanh toán

| Phương thức | Đường dẫn | Nội dung |
| --- | --- | --- |
| GET | `/subscriptions/tiers` | Gói đang hoạt động và quota; công khai |
| GET | `/subscriptions/me` | Gói hiệu lực, quota và token dùng trong tháng |
| GET | `/subscriptions/history` | Lịch sử gói của tài khoản |
| POST | `/subscriptions/{id}/cancel` | Hủy gói thuộc tài khoản; quyền của gói dừng ngay |
| POST | `/payments/orders` | `tierId`, `durationMonths` từ 1 đến 12 |
| GET | `/payments/orders`, `/payments/orders/{id}` | Đơn của tài khoản hiện tại |
| POST | `/payments/orders/{id}/reports` | `subject`, `description`; báo lỗi trên đơn thuộc tài khoản |
| GET | `/payments/reports` | Báo lỗi của tài khoản |
| GET | `/invoices`, `/invoices/{id}` | Hóa đơn thuộc tài khoản |
| POST | `/payments/webhook` | Webhook có chữ ký từ adapter thanh toán |

Backend tính tiền từ giá của tier và số tháng, không nhận số tiền do trình duyệt quyết định. Đơn mới luôn `PENDING`; gói FREE không cần tạo đơn. Bản triển khai hiện có hợp đồng webhook HMAC cho `MANUAL_GATEWAY`, chưa có adapter cụ thể tạo QR, checkout thẻ, hoặc truy vấn giao dịch ngân hàng. Frontend cần tích hợp checkout của provider qua adapter trước khi thu tiền thật.

Webhook nhận JSON dạng:

```json
{"orderId":"<UUID>","providerOrderId":"<provider_order_id>","eventId":"unique-provider-event-id","status":"COMPLETED","amount":99000,"currency":"VND"}
```

`status` chấp nhận `COMPLETED`, `FAILED`, `CANCELLED`. Header `X-Payment-Timestamp` là Unix timestamp theo giây, sai lệch không quá 300 giây. `X-Payment-Signature` là HMAC-SHA256 dạng hex của **chuỗi timestamp + dấu chấm + nguyên văn body**, mã hóa UTF-8, dùng `PAYMENT_WEBHOOK_SECRET` có ít nhất 32 byte. Không chuẩn hóa JSON sau khi ký. Hệ thống kiểm tra provider, provider order ID, số tiền và tiền tệ; khóa đơn trước khi xử lý; chống trùng bằng `(provider,event_id)`. Một event ID không được dùng cho đơn khác.

Chỉ webhook `COMPLETED` đã kiểm chứng mới kích hoạt subscription, hoàn tất đơn, tạo hóa đơn PAID và thông báo trong cùng transaction. Gói ACTIVE trước đó được hủy, gói mới bắt đầu tại thời điểm thanh toán; thời gian còn lại của gói cũ không được cộng dồn. Mỗi đơn có một hóa đơn; subtotal bằng tiền đơn, tax hiện bằng 0, total bằng subtotal + tax. Lặp webhook không tạo gói/hóa đơn/thông báo lần hai. Đơn đã kết thúc không chuyển sang trạng thái kết thúc khác.

Khi subscription quá hạn, truy vấn entitlement chuyển về FREE ngay. Job bảo trì cập nhật `EXPIRED` và tạo thông báo trong ứng dụng trước 7 ngày, trước 1 ngày, khi hết hạn; có unique receipt để tránh thông báo trùng. Job không tự thu tiền hoặc gọi SMTP; `auto_renew` hiện không có adapter thu tiền tự động. Mỗi nhóm xử lý tối đa `app.billing.maintenance-batch-size` trong một lần chạy.

## Quản trị và hỗ trợ

`/admin/**` yêu cầu role `ADMIN`. Không có mật khẩu hoặc tài khoản admin được seed; tài khoản quản trị đầu tiên cần được cấp quyền trong database bởi người vận hành. Sau đó admin có thể tạo tài khoản ứng viên, tư vấn viên, admin qua API. Đình chỉ, đổi role, xóa tài khoản đều thu hồi phiên; email chưa xác minh không được kích hoạt bằng API quản trị. Mọi thay đổi quản trị ghi `audit_logs` và `admin_actions`, không ghi password hash trong dữ liệu account trả về/audit.

| Đường dẫn admin | Thao tác |
| --- | --- |
| `/admin/dashboard` | GET thống kê người dùng, subscription, doanh thu theo tiền tệ, AI và ticket |
| `/admin/users` | GET tìm kiếm/phân trang, POST tạo tài khoản với `temporaryPassword` |
| `/admin/users/{id}` | GET, PUT tên/role/status, DELETE mềm |
| `/admin/subscription-tiers` | GET, POST; PUT `/admin/subscription-tiers/{id}` sửa gói |
| `/admin/subscription-tiers/{id}/quotas` | PUT danh sách quota; upsert các feature gửi lên |
| `/admin/ai/configurations` | GET, POST cấu hình có version |
| `/admin/ai/prompts` | GET, POST prompt có version theo feature |
| `/admin/monitoring/{category}` | GET `ai-usage`, `vector-stores`, `indexing-jobs`, `logs`, `audit`, `billing`, `crawl-runs` |
| `/admin/payment-reports` | GET; POST `/{id}/resolve` xử lý báo lỗi |
| `/admin/announcements` | GET, POST; PUT `/{id}` sửa bản nháp; POST `/{id}/publish`, `/{id}/archive` |

Thông báo hỗ trợ audience `ALL`, `ROLE`, `USER`. Chỉ bản nháp được sửa/xuất bản; bản đã quá hạn phải sửa expiry trước khi xuất bản. Xuất bản tạo notification cho tài khoản ACTIVE phù hợp audience trong cùng transaction. `/announcements` chỉ trả bản còn hiệu lực đúng audience; `/notifications` chỉ trả dữ liệu của người gọi, có `unreadOnly=true`. `PATCH /notifications/{id}/read` và `POST /notifications/read-all` đánh dấu đã đọc.

`GET/POST /support/tickets` liệt kê/tạo ticket; `GET /support/tickets/{id}` trả tin nhắn. Chủ ticket hoặc admin được đọc/gửi qua `POST /support/tickets/{id}/messages`; `PATCH /support/tickets/{id}` dành cho admin sửa trạng thái/độ ưu tiên/người phụ trách. Ticket CLOSED không nhận tin nhắn; khóa ticket khiến thao tác đóng và gửi đồng thời được xử lý theo thứ tự. Phản hồi từ admin tạo notification cho chủ ticket.

## Cấu hình vận hành

`.env.example` là mẫu cho Compose và biến môi trường. Spring Boot không tự nạp `.env`; cần export biến vào tiến trình chạy ứng dụng.

| Biến/property | Tác dụng |
| --- | --- |
| `AUTH_EXPOSE_DEV_OTP` | Mặc định false; true trả `developmentCode` và bỏ gửi SMTP, chỉ dùng khi phát triển |
| `MAIL_ENABLED` | Bật gửi email xác minh và các API gửi email được triển khai |
| `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_SMTP_AUTH`, `MAIL_STARTTLS`, `MAIL_FROM` | SMTP và người gửi |
| `GOOGLE_CLIENT_ID` | Audience được chấp nhận cho Google ID token |
| `GUEST_TOKEN_LIMIT` | Ngân sách token của guest session mới, mặc định 5000 |
| `PAYMENT_PROVIDER`, `PAYMENT_WEBHOOK_SECRET` | Provider của đơn mới và secret xác minh webhook |
| `CORS_ALLOWED_ORIGINS` | Danh sách origin frontend cách nhau bằng dấu phẩy |
| `AI_ENABLED`, `AI_CHAT_PROVIDER`, `AI_EMBEDDING_PROVIDER`, `OPENAI_API_KEY` | Bật AI và khởi tạo provider tại startup |
| `AI_CHAT_MODEL`, `AI_EMBEDDING_MODEL`, `AI_MAX_OUTPUT_TOKENS` | Model khởi tạo và giới hạn output; embedding phải có 1536 chiều |
| `BILLING_MAINTENANCE_ENABLED` / `app.billing.maintenance-enabled` | Bật/tắt job bảo trì, mặc định true |
| `app.billing.maintenance-cron` | Mặc định `0 0 8 * * *` |
| `app.billing.maintenance-zone` | Mặc định `Asia/Ho_Chi_Minh` |
| `app.billing.maintenance-batch-size` | Mặc định 500, giới hạn 1–1000 |

Đổi provider, API key, bật AI, embedding model/dimension hoặc cấu hình Spring qua môi trường cần khởi động lại ứng dụng. POST cấu hình AI lưu DB có version; request chat dùng `model_name` của bản `config_type=CHAT` đang active **và có provider trùng provider đã khởi tạo**. Đổi model chat cho provider này áp dụng từ request tiếp theo. `config_json` được lưu để quản trị, chưa áp dụng tùy ý vào options của provider. Ghi cấu hình cho provider khác hoặc loại EMBEDDING không chuyển Spring bean đang chạy; cần đổi cấu hình startup, khởi động lại và tạo lại vector khi đổi embedding model.

Prompt active mới nhất theo `feature_code` áp dụng ở request tiếp theo. `system_prompt` và `user_prompt_template` bổ sung hướng dẫn nhiệm vụ; template hiện được nối như nội dung hướng dẫn, không nội suy placeholder. Các quy tắc JSON, dữ liệu đầu vào không đáng tin và không bịa thông tin vẫn được giữ. Không lưu API key, secret hoặc password trong cấu hình admin; dùng biến môi trường.
