# Admin cấu hình biên độ lương khi gợi ý việc làm

Admin có thể thay tỷ lệ lương thấp hơn mức kỳ vọng mà hệ thống chấp nhận khi gợi ý job.
Candidate vẫn nhập **một mức lương kỳ vọng mỗi tháng** trong hồ sơ; Admin không sửa con số này của từng người.

Gọi `E` là lương kỳ vọng của Candidate và `P` là phần trăm Admin cấu hình:

```text
minimum_acceptable = E × (1 − P / 100)
job.salary_max >= minimum_acceptable
```

Mặc định **P = 10%** để giữ quy tắc trước đây. Job trả lương cao hơn kỳ vọng vẫn được xét, không có trần.
Đây là phần trăm cho phép thấp hơn kỳ vọng, không áp dụng khoảng đối xứng hai phía.

Ví dụ Candidate kỳ vọng **7.500.000 VND/tháng**:

| Biên độ Admin đặt | Lương job tối thiểu được xét |
| --- | --- |
| 0% | 7.500.000 VND |
| 10% | 6.750.000 VND |
| 12,5% | 6.562.500 VND |
| 20% | 6.000.000 VND |
| 100% | Ngưỡng bằng 0; vẫn phải có mức lương tháng xác định, cùng tiền tệ |

Với khoảng lương, mức tối đa phải đạt ngưỡng. Ví dụ biên độ 20%, job 5–6 triệu qua điều kiện lương;
điều này không bảo đảm nhà tuyển dụng trả 6 triệu cho mọi ứng viên. Các quy tắc tiền tệ, lương tháng,
loại lương không xác định và xếp hạng theo kỹ năng/hồ sơ giữ nguyên.

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
`settings_version`, `policy_version`. Các run lịch sử trước thay đổi này có thể vẫn mang
`comparison = "AT_LEAST_90_PERCENT"`; đó là snapshot cũ.

Biên độ được đọc một lần cho mỗi lượt matching và dùng thống nhất cho lọc job, cache, lời giải thích và snapshot.
Một lượt đang chạy có thể hoàn tất theo cấu hình đã đọc trước khi Admin chỉnh; lượt bắt đầu sau cập nhật dùng cấu hình mới.

## Lưu trữ

Migration **V15** thêm bảng singleton `matching_salary_settings`, khởi tạo tỷ lệ 10% và version 1.
Database lưu `tolerance_percent`, `version`, `updated_by`, `updated_at`; chỉ có dòng `id = 1`.
Thay đổi tỷ lệ có audit, tồn tại qua restart và áp dụng chung cho các Candidate.
Lương kỳ vọng riêng của từng Candidate tiếp tục nằm ở `candidate_profiles.expected_salary`.

Xem thêm [lương kỳ vọng và gợi ý theo hồ sơ](salary-based-job-recommendations.md).

## Kết quả kiểm tra ngày 23/10/2026

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
