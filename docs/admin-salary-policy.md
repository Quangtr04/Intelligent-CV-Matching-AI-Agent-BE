# Admin cấu hình biên độ lương khi gợi ý việc làm

Admin có thể thay tỷ lệ lương thấp hơn mức kỳ vọng mà hệ thống chấp nhận khi gợi ý job.
Candidate vẫn nhập **một mức lương kỳ vọng mỗi tháng** trong hồ sơ; Admin không sửa con số này của từng người.

Gọi `E` là lương kỳ vọng của Candidate và `P` là phần trăm Admin cấu hình:

```text
minimum_acceptable = E × (1 − P / 100)
comparison_salary_max >= minimum_acceptable
```

Mặc định **P = 10%** để giữ quy tắc trước đây. Job trả lương cao hơn kỳ vọng vẫn được xét, không có trần.
Đây là phần trăm cho phép thấp hơn kỳ vọng, không áp dụng khoảng đối xứng hai phía.
`comparison_salary_max` dùng tiền tệ của Candidate sau khi quy đổi nếu cần.

Ví dụ Candidate kỳ vọng **7.500.000 VND/tháng**:

| Biên độ Admin đặt | Lương job tối thiểu được xét |
| --- | --- |
| 0% | 7.500.000 VND |
| 10% | 6.750.000 VND |
| 12,5% | 6.562.500 VND |
| 20% | 6.000.000 VND |
| 100% | Ngưỡng bằng 0; lương chưa đối chiếu được vẫn được đánh dấu `UNVERIFIED` |

Với khoảng lương, mức tối đa phải đạt ngưỡng. Ví dụ biên độ 20%, job 5–6 triệu qua điều kiện lương;
điều này không bảo đảm nhà tuyển dụng trả 6 triệu cho mọi ứng viên. Khoảng một phía giữ đầu mút chưa biết là `null`:
`Từ 10 triệu VND` có max `null`, `Up to 6 triệu VND` có min `null` và max 6 triệu.
Chỉ loại vì lương khi mức tối đa xác định, đã đối chiếu được, thấp hơn ngưỡng; lương cao hơn không có trần.

## Chính sách v3: tiền tệ và lương chưa công khai

Chính sách hiện tại là **`candidate-configured-salary-monthly-floor-v3`** và áp dụng cho cả gợi ý theo hồ sơ lẫn theo CV
khi Candidate đã đặt kỳ vọng lương:

- `currency_policy = "CONVERT_WHEN_AVAILABLE"`: đọc lương tháng gốc, quy đổi khi có tỷ giá rồi áp dụng ngưỡng.
  Cùng tiền tệ dùng tỷ lệ 1, không cần kết nối nguồn tỷ giá.
- `unknown_salary_policy = "INCLUDE_UNVERIFIED"`: job không công khai/thỏa thuận, không đọc được chắc chắn,
  ghi kỳ lương khác tháng hoặc thiếu tỷ giá vẫn được xét theo kỹ năng, không bị loại chỉ vì thiếu dữ liệu lương.
- `salary_match.status = "UNVERIFIED"`, `verified = false` đi cùng `reason` là
  `SALARY_UNDISCLOSED_OR_UNPARSABLE` hoặc `EXCHANGE_RATE_UNAVAILABLE`.
  Không tạo `closest_offer`, `difference_percent` hoặc `shortfall_percent` giả cho các job này.
- Job `VERIFIED` giữ `job_currency` và khoảng gốc `job_salary_min`/`job_salary_max`, đồng thời ghi
  `comparison_salary_min`/`comparison_salary_max`, `conversion_applied`, tỷ lệ và thời điểm quy đổi.
  Mỗi đầu mút chưa biết là `null`; không biến thành 0 hoặc một số tiền tự đoán.

Nguồn là [Open Access ExchangeRate-API](https://www.exchangerate-api.com/docs/free), miễn phí, cập nhật hằng ngày,
không cần API key. Những trang dùng tỷ giá phải có attribution link đến [ExchangeRate-API](https://www.exchangerate-api.com).
Đây là API dữ liệu tỷ giá, không phải API model AI; chat/embedding vẫn dùng Gemini API.

Backend dùng `SALARY_EXCHANGE_ENABLED=true` và
`SALARY_EXCHANGE_URL=https://open.er-api.com/v6/latest/USD` theo `.env.example`.
Nguồn phải trả đúng hợp đồng Open Access, gốc USD. Cache trong bộ nhớ được dùng tối đa 3 ngày tính từ thời điểm
cập nhật nguồn; khi tải lỗi thì dùng snapshot còn hạn, hết hạn thì job khác tiền tệ chuyển sang chưa xác minh.
HTTP timeout 3 giây, connect timeout 2 giây; sau lỗi chờ 15 phút trước khi thử lại.
Tắt `SALARY_EXCHANGE_ENABLED` không làm mất job khác tiền tệ: chúng được gắn nhãn chưa xác minh.
Thay biến môi trường cần restart backend; thay tỷ lệ Admin qua API không cần restart.

Snapshot run có `exchange_rate_source`, `exchange_rate_as_of`, `verified_eligible_jobs`, `unverified_eligible_jobs`.
Các số job là trước retrieval/ranking và `limit`, không phải cam kết số kết quả cuối cùng.
Không quy đổi lương năm thành tháng, gross thành net hoặc thuế/chi phí sinh hoạt.

## Thao tác trên giao diện

1. Đăng nhập bằng tài khoản **Admin hệ thống**.
2. Mở mục cấu hình biên độ lương tại `/admin/matching/salary-policy`.
3. Nhập tỷ lệ từ **0 đến 100**, tối đa **hai chữ số thập phân**. Xem ngưỡng minh họa rồi lưu.
4. Candidate mở lại hồ sơ/việc làm để đọc chính sách hiện tại; bấm gợi ý lại để tạo kết quả theo chính sách mới.

Thao tác lưu biên độ không tự gọi AI. Không cần build lại image hoặc restart backend sau mỗi lần chỉnh tỷ lệ.
Các trang tài khoản hệ thống lấy tỷ lệ từ backend; phiên demo không thay thế quyền quản trị thật.

## Postman: đọc và thay đổi chính sách

Đặt `baseUrl = http://localhost:8080/api/v1`. Request Admin dùng Bearer `{{adminAccessToken}}`.

```http
GET {{baseUrl}}/admin/matching/salary-policy
```

Response camelCase chứa `tolerancePercent`, `minimumRatio`, `version`, `policyVersion`,
`updatedAt`; có thêm `updatedBy` sau khi Admin thay đổi giá trị. `minimumRatio` là tỷ lệ lương tối thiểu,
ví dụ 20% tương ứng `0.8`.

```http
PUT {{baseUrl}}/admin/matching/salary-policy
Content-Type: application/json
```

Body raw JSON:

```json
{
  "tolerancePercent": 20
}
```

Expected: `200`, `tolerancePercent = 20`, `minimumRatio = 0.8`. Đọc GET lần nữa để xác nhận giá trị đã lưu.
`version` tăng khi giá trị thực sự thay đổi; `updatedBy`/`updatedAt` ghi nhận Admin và thời điểm cập nhật.
Lưu cùng tỷ lệ không tạo một phiên bản chính sách mới.

Candidate dùng access token của mình để đọc chính sách:

```http
GET {{baseUrl}}/matching/salary-policy
```

Endpoint Candidate không cho sửa cấu hình và không trả thông tin người cập nhật. Thiếu đăng nhập trả `401`;
tài khoản không có quyền Admin gọi API quản trị trả `403`. Giá trị ngoài 0–100 hoặc quá hai chữ số thập phân trả `400`.

## Kiểm tra kết quả matching sau khi Admin đổi tỷ lệ

1. Dùng một Candidate test có `expectedSalary = 7500000`, `salaryCurrency = "VND"`.
2. Admin đặt 10%; Candidate gọi `POST /matching` với body bên dưới, lưu ID của run.
3. Admin đổi sang 20%; Candidate gọi lại đúng request đó.
4. Run mới phải có `salary_filter.tolerance_percent = 20`, `minimum_acceptable = 6000000`,
   `settings_version` mới; không lấy lại cache của phiên bản 10%.
5. `GET /matching/{id}` của run cũ vẫn giữ 10% và ngưỡng 6.750.000, không viết lại lịch sử.

```json
{
  "basis": "CAREER_PROFILE",
  "resumeIds": [],
  "limit": 20
}
```

Metadata mới dùng `comparison = "AT_LEAST_CONFIGURED_MINIMUM"` cùng `tolerance_percent`,
`settings_version`, `policy_version = "candidate-configured-salary-monthly-floor-v3"`.
Các run lịch sử có thể mang `comparison = "AT_LEAST_90_PERCENT"` hoặc chính sách v2 `SAME_CURRENCY`/`EXCLUDE`;
đó là snapshot cũ, không viết lại theo quy tắc v3.

Biên độ được đọc một lần cho mỗi lượt matching và dùng thống nhất cho lọc job, cache, lời giải thích và snapshot.
Một lượt đang chạy có thể hoàn tất theo cấu hình đã đọc trước khi Admin chỉnh; lượt bắt đầu sau cập nhật dùng cấu hình mới.

Sau khi cập nhật backend, gửi **POST matching mới** để kiểm tra. Cache key v3/snapshot tỷ giá không dùng lại kết quả rỗng
của chính sách cũ, nhưng `GET /matching/{id}` luôn giữ kết quả lịch sử của ID đó.
Nên thử thêm job USD/tiền tệ khác và job không công khai lương; kiểm tra trạng thái `UNVERIFIED` và nguồn/thời điểm
quy đổi thay vì mặc định một tỷ giá cố định cho mọi ngày.

Nếu bật AI nhưng tập job đủ điều kiện chưa có embedding hợp lệ theo provider/model hiện tại, backend dùng mode
`SKILL_OVERLAP_UNINDEXED_JOBS`, provider `RULE_BASED` và `llm_rerank_score = null`.
Không gán kết quả này thành điểm do model Gemini đánh giá. Cần bật `JOB_EMBEDDING_ENABLED=true` để worker lập chỉ mục;
vẫn phải kiểm tra tiến độ index, vì bật biến không đồng nghĩa vector đã có ngay.

## Lưu trữ

Migration **V15** thêm bảng singleton `matching_salary_settings`, khởi tạo tỷ lệ 10% và version 1.
Database lưu `tolerance_percent`, `version`, `updated_by`, `updated_at`; chỉ có dòng `id = 1`.
Thay đổi tỷ lệ có audit, tồn tại qua restart và áp dụng chung cho các Candidate.
Lương kỳ vọng riêng của từng Candidate tiếp tục nằm ở `candidate_profiles.expected_salary`.
Các thay đổi v3 dùng JSON snapshot/lời giải thích hiện có, **không cần migration mới**.

Xem thêm [lương kỳ vọng và gợi ý theo hồ sơ](salary-based-job-recommendations.md).

## Kết quả kiểm tra ngày 23/10/2026

Đây là bằng chứng lịch sử của phiên bản trước v3; không dùng các số này để khẳng định đã kiểm chứng tỷ giá,
lương chưa xác minh hoặc fallback mới.

- Backend formatter và `verify`: **BUILD SUCCESS**, 814 test được ghi nhận: **758 đạt, 0 failure/error, 56 bỏ qua**.
  Có chạy integration với PostgreSQL/pgvector trong Docker. Các test chính sách kiểm tra quyền, dữ liệu không hợp lệ,
  lưu/audit, các mốc 0–100%, thay đổi cache và giữ snapshot khi đang matching.
- Frontend: build thành công, ESLint các file thay đổi đạt; **25 kiểm tra logic lương** đạt.
  Vite vẫn cảnh báo bundle lớn.
- Log backend: [verify](../target/admin-salary-policy-verify.log), [formatter](../target/admin-salary-policy-format.log).

Lượt kiểm tra toàn bộ cũng phát hiện hợp đồng chấm nội dung CV hiện có thiếu schema đầu ra AI và migration cho cột lưu.
Đã đồng bộ schema/prompt với rubric hiện tại và thêm **V16** (`content_quality_json` nullable), giữ nguyên kiểm tra bằng chứng.
V16 giữ cột JSONB nullable cùng dữ liệu nếu đã có sẵn, tạo cột khi thiếu và báo lỗi nếu kiểu cột không tương thích;
bốn test migration riêng đã qua. Các báo cáo CV cũ không được tự gán điểm mới.

Backend Docker đã cập nhật và kiểm tra lúc **18:48 ngày 23/10/2026**: health `UP`, JAR khớp SHA-256 với bản vừa verify,
Flyway lên **V16**, OpenAPI có đủ ba thao tác mới; request không đăng nhập trả `401`, API danh sách job public trả `200`.
Xem [bằng chứng runtime](../target/admin-salary-policy-app-runtime.json).
Lượt runtime không đăng nhập bằng tài khoản người dùng và không thay đổi biên độ thật; thao tác Admin có xác thực,
lưu/audit và lọc job đã kiểm thử trên PostgreSQL/pgvector riêng. Database ứng dụng đã xác nhận có tỷ lệ mặc định 10%, version 1.
