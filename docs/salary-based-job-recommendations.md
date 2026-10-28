# Một mức lương kỳ vọng và gợi ý việc làm theo hồ sơ hoặc CV

Candidate lưu **một mức lương kỳ vọng mỗi tháng**, ví dụ **7.500.000 VND**. Admin cấu hình phần trăm cho phép thấp hơn kỳ vọng;
mặc định 10%, tương ứng job có mức lương **từ 90% mức kỳ vọng trở lên**. Lương cao hơn không bị giới hạn.
Sau bước đối chiếu lương, matching tiếp tục xếp hạng theo hồ sơ nghề nghiệp hoặc CV và kỹ năng.
Chính sách hiện tại là **`candidate-configured-salary-monthly-floor-v3`**: quy đổi tiền tệ khi có tỷ giá,
và vẫn xét job chưa đối chiếu được lương với nhãn **`UNVERIFIED`**. Không coi lương chưa biết là 0 hoặc là đã đạt kỳ vọng.
Xem [cấu hình Admin, ví dụ và JSON Postman](admin-salary-policy.md).

Biên độ là phần trăm cho phép thấp hơn kỳ vọng, không phải khoảng đối xứng. Ví dụ biên độ 10%, kỳ vọng 7,5 triệu
thì ngưỡng là 6,75 triệu; nếu Admin đặt 20% thì ngưỡng là 6 triệu. Job 20 triệu vẫn được xét trong cả hai trường hợp.

## Quy tắc đối chiếu

Gọi `E` là mức lương kỳ vọng, `P` là phần trăm do Admin đặt (0–100, tối đa hai chữ số thập phân). Điều kiện lương là:

```text
minimum_acceptable = E × (1 − P / 100)
comparison_salary_max >= minimum_acceptable
```

`comparison_salary_max` là mức tối đa theo tháng sau khi quy đổi sang tiền tệ của Candidate nếu cần.
Job có mức tối đa xác định thấp hơn ngưỡng bị loại. Với một mức lương, hai đầu mút bằng nhau;
với khoảng lương, mức tối đa phải đạt ngưỡng. Điều này không bảo đảm nhà tuyển dụng sẽ trả mức tối đa cho mọi ứng viên.

Lương USD, EUR hoặc tiền tệ khác được quy đổi khi nguồn tỷ giá có đủ hai mã tiền tệ. Cùng tiền tệ dùng tỷ lệ 1,
không phụ thuộc kết nối đến nguồn tỷ giá. Không quy đổi gross/net, chi phí sinh hoạt hoặc thuế.
Tin không ghi kỳ trả lương được coi là lương tháng; tin ghi rõ năm/ngày/giờ/tuần không được tự chia thành lương tháng.

Job thỏa thuận, không công khai lương, văn bản không đọc được chắc chắn, kỳ lương khác tháng,
tiền tệ không rõ/mâu thuẫn, hoặc thiếu tỷ giá vẫn được xét theo kỹ năng và ghi `status = "UNVERIFIED"`, `verified = false`.
Lý do là `SALARY_UNDISCLOSED_OR_UNPARSABLE` hoặc `EXCHANGE_RATE_UNAVAILABLE`; không có các chỉ số chênh lệch giả.
`VERIFIED` nghĩa là đã đọc và đối chiếu được khoảng lương công bố, không bảo đảm mức offer thực tế.

Khoảng một phía giữ đầu mút chưa biết là `null`: `Từ 10 triệu VND` có min 10 triệu/max `null`;
`Up to 20 triệu VND` có min `null`/max 20 triệu. Các đầu mút là bao gồm. Không thay đầu mút thiếu bằng 0,
một mức trần tùy ý hoặc một con số do AI đoán. Khi max là `null`, không có bằng chứng rằng toàn bộ khoảng thấp hơn ngưỡng;
khi max xác định và dưới ngưỡng, job bị loại.

Ví dụ candidate đặt `expectedSalary = 7500000`, `salaryCurrency = "VND"`, Admin đang giữ biên độ **10%**:

| Lương job / tháng | Được xét gợi ý? | Giải thích |
| --- | --- | --- |
| 6.749.999 VND | Không | Thấp hơn 6.750.000 VND |
| 6.750.000 VND | Có | Đúng ngưỡng 90% |
| 7.500.000 VND | Có | Bằng kỳ vọng |
| 8.250.001 VND | Có | Không có mức trần |
| 20.000.000 VND | Có | Lương cao hơn vẫn được xét |
| 5–7 triệu VND | Có | Mức tối đa 7 triệu đạt ngưỡng |
| 5–6 triệu VND | Không | Toàn bộ khoảng dưới ngưỡng |
| 1.000 USD | Tùy tỷ giá | Quy đổi sang VND rồi so với ngưỡng; thiếu tỷ giá thì giữ với nhãn `UNVERIFIED` |
| Từ 10 triệu VND | Có | `VERIFIED`, min 10 triệu và max `null` |
| Up to 6 triệu VND | Không | Max 6 triệu dưới ngưỡng |
| Thỏa thuận hoặc trống | Có | `UNVERIFIED`, chưa kết luận phù hợp lương |
| 90.000.000 VND / năm | Có | `UNVERIFIED`, không tự quy đổi kỳ lương |

“Có” chỉ nghĩa là được đưa vào tập xét gợi ý; `UNVERIFIED` không có nghĩa đã đạt kỳ vọng lương.
Thứ hạng và số kết quả trả về còn phụ thuộc kỹ năng, phương pháp matching, trạng thái/index của job và `limit`.

## Tỷ giá và cấu hình vận hành

Backend dùng [endpoint Open Access miễn phí của ExchangeRate-API](https://www.exchangerate-api.com/docs/free),
cập nhật hằng ngày, không cần API key. Các trang dùng số quy đổi phải hiển thị link nguồn
[ExchangeRate-API](https://www.exchangerate-api.com); FE của hai luồng matching đã có attribution này.

```dotenv
SALARY_EXCHANGE_ENABLED=true
SALARY_EXCHANGE_URL=https://open.er-api.com/v6/latest/USD
JOB_EMBEDDING_ENABLED=true
```

Nguồn hiện tại phải trả hợp đồng Open Access với `base_code = "USD"`, các mốc cập nhật và bảng `rates`.
Cache tỷ giá dùng chung trong bộ nhớ backend, làm mới theo thời điểm cập nhật kế tiếp của nguồn và không chấp nhận
dữ liệu quá **3 ngày**. Khi tải lỗi, tiếp tục dùng snapshot còn hạn; hết hạn hoặc chưa có dữ liệu thì chỉ đánh dấu
các phép so khác tiền tệ là chưa xác minh. Sau lỗi chờ **15 phút** trước lần thử tiếp theo; HTTP timeout **3 giây**
(connect timeout 2 giây). Không gửi request tỷ giá riêng cho từng job. `SALARY_EXCHANGE_ENABLED=false` tắt tải tỷ giá;
job cùng tiền tệ vẫn đối chiếu được và job khác tiền tệ vẫn được xét với nhãn chưa xác minh.

Thay biến môi trường cần khởi động lại backend/container. Tỷ giá chỉ là tham khảo theo thời điểm ghi ở
`exchange_rate_as_of`/`conversion_as_of`, không phải cam kết mức lương sau thuế hoặc tỷ giá nhận tiền.

## Trên giao diện candidate

Đăng nhập bằng **tài khoản hệ thống**:

1. Vào **Hồ sơ nghề nghiệp** (`/candidate/profile`) → chỉnh sửa.
2. Điền vị trí mong muốn, kinh nghiệm, kỹ năng, định hướng và **một mức lương kỳ vọng / tháng**.
3. Có thể nhập `7500000`, `7.500.000` hoặc `7tr5` khi chọn VND. Frontend chuyển thành JSON number `7500000` trước khi gửi; không gửi chuỗi `"7tr5"` đến backend.
4. Lưu hồ sơ. Dữ liệu được đọc/ghi qua `GET`/`PUT /api/v1/me/career-profile`, không dùng profile mock của phiên demo.
5. Vào **Việc làm** (`/candidate/jobs`) → bấm **Gợi ý việc làm**. Trang hiển thị kỳ vọng, ngưỡng theo cấu hình Admin,
   kết quả thực tế, lý do matching và đường dẫn tin gốc nếu có. Kết quả đã tạo dùng snapshot chính sách của chính lượt đó.

Trang việc làm không tự gọi model chỉ vì mở Dashboard/trang. Nút gợi ý gửi `POST /api/v1/matching` với `basis = CAREER_PROFILE`. Quá trình inference self-hosted có thể cần vài phút; lần gọi ở frontend có timeout 300 giây. Matching có thể trả cache hợp lệ thay vì chạy inference lại. Trang không tạo kết quả giả nếu API lỗi hoặc không có job đủ điều kiện.

Sau khi lưu thay đổi hồ sơ, frontend loại kết quả matching cũ khỏi cache của candidate; cần bấm gợi ý lại để đối chiếu thông tin mới. Phiên demo vẫn là dữ liệu minh họa trên trình duyệt; không dùng phiên demo để nghiệm thu API thật.

## Postman: dữ liệu và thứ tự kiểm tra

Đặt biến:

```text
baseUrl = http://localhost:8080/api/v1
candidateAccessToken = accessToken của tài khoản CANDIDATE đã đăng nhập
```

Các URL dưới đây đã có `/api/v1` trong `baseUrl`; không thêm tiền tố này lần nữa. Authorization chọn **Bearer Token** với `{{candidateAccessToken}}`.

### 1. Đọc hồ sơ hiện tại

```http
GET {{baseUrl}}/me/career-profile
```

Khi chưa tạo hồ sơ, API trả `200` với `user_id`, `skills: []`; các thuộc tính chưa lưu có thể vắng mặt. Hồ sơ đã lưu dùng tên trường `snake_case`, trong đó có `expected_salary` và `salary_currency`.

### 2. Lưu một mức lương

```http
PUT {{baseUrl}}/me/career-profile
Content-Type: application/json
```

Ví dụ cho **hồ sơ test mới**:

```json
{
  "currentJobTitle": "Junior Java Developer",
  "currentCompany": "Test Company",
  "targetJobTitle": "Backend Developer",
  "careerGoal": "Phát triển backend Java và hệ thống API.",
  "yearsExperience": 1.5,
  "expectedSalary": 7500000,
  "salaryCurrency": "VND",
  "preferredLocation": "Hồ Chí Minh",
  "workplacePreference": "HYBRID",
  "employmentType": "FULL_TIME",
  "profileSummary": "Có kinh nghiệm Java, Spring Boot, REST API và PostgreSQL.",
  "preferences": {}
}
```

Đây là `PUT` thay thế các trường hồ sơ; khi sửa một tài khoản đang dùng, đọc hồ sơ trước và gửi lại các thông tin cần giữ. `skills` chỉ được thay thế khi gửi khác `null`; ví dụ trên không gửi nên giữ kỹ năng hiện tại. Để chọn kỹ năng, lấy UUID thật từ `GET {{baseUrl}}/skills`, rồi gửi `skills` theo hợp đồng [Postman API](postman-api-guide.md). Frontend đã tự giữ preferences và metadata của kỹ năng khi lưu.

Kỳ vọng: `200`, `expected_salary = 7500000`, `salary_currency = "VND"`; phản hồi không còn `expected_salary_min`/`expected_salary_max`.

Gửi `expectedSalary` dưới dạng JSON number dương, tối đa 12 chữ số phần nguyên và 2 chữ số thập phân. Các giá trị `0`, âm, vượt giới hạn, chuỗi `"7tr5"` hoặc boolean trả `400`. Tiền tệ hỗ trợ `VND`, `USD`, `EUR`; bỏ qua tiền tệ thì mặc định VND. API cho phép `null` để xóa kỳ vọng, nhưng sau đó matching theo career profile cần lưu lại một mức dương.

Các trường min/max cũ, cả dạng camelCase và snake_case, bị từ chối bằng `400`; không gửi cùng request mới.

### 3. Kiểm tra đọc lại

```http
GET {{baseUrl}}/me/career-profile
```

Xác nhận mức lương, tiền tệ, kỹ năng và các thông tin khác vẫn đúng sau khi lưu.

### 4. Gợi ý theo hồ sơ

```http
POST {{baseUrl}}/matching
Content-Type: application/json
```

```json
{
  "basis": "CAREER_PROFILE",
  "resumeIds": [],
  "limit": 20
}
```

Kỳ vọng: `200`; phản hồi có `results`, `salary_filter` và `profile_snapshot.salary_filter`. Một phần metadata ví dụ:

```json
{
  "active": true,
  "expected_salary": 7500000,
  "currency": "VND",
  "period": "MONTH",
  "minimum_acceptable": 6750000,
  "comparison": "AT_LEAST_CONFIGURED_MINIMUM",
  "tolerance_percent": 10,
  "settings_version": 1,
  "reference": "CANDIDATE_EXPECTED_SALARY",
  "policy_version": "candidate-configured-salary-monthly-floor-v3",
  "unknown_salary_policy": "INCLUDE_UNVERIFIED",
  "currency_policy": "CONVERT_WHEN_AVAILABLE",
  "exchange_rate_source": null,
  "exchange_rate_as_of": null,
  "verified_eligible_jobs": 0,
  "unverified_eligible_jobs": 0
}
```

Đây là minh họa cấu trúc, không phải kết quả đo trên dữ liệu thật. Khi có snapshot tỷ giá hợp lệ,
`exchange_rate_source` là `https://www.exchangerate-api.com`, `exchange_rate_as_of` là ISO instant;
hai trường này là `null` khi không có snapshot. Metadata thực tế còn có `examined_jobs`, `eligible_jobs`;
`eligible_jobs` gồm cả verified và unverified trước bước retrieval/ranking, không phải số dòng bắt buộc trong `results`.
Biên độ và `settings_version` được đọc một lần trước khi matching, cùng tham gia cache key. Lịch sử trước V15 có thể
giữ `AT_LEAST_90_PERCENT` và không có `settings_version`; không viết lại các run cũ theo chính sách mới.

Mỗi kết quả lưu lời giải thích ở `match_reason_json.salary_match`:

- `status`, `verified` cho biết lương đã đối chiếu được hay chưa.
- `job_currency`, `job_salary_min`, `job_salary_max` là tiền tệ và khoảng lương gốc khi đọc được.
- `comparison_salary_min`, `comparison_salary_max` là khoảng theo tiền tệ Candidate; một đầu mút có thể `null`.
- `conversion_applied`, `conversion_rate`, `conversion_source`, `conversion_as_of` mô tả phép quy đổi khi có.
- Chỉ kết quả verified có `closest_offer`, `difference_percent`, `shortfall_percent`.
  `difference_percent` đo khoảng cách từ kỳ vọng tới mức gần nhất trong khoảng lương job.
  Job cao hơn nhiều có thể có chỉ số này lớn hơn 10% và vẫn hợp lệ; điều kiện loại chỉ nằm ở ngưỡng thấp hơn.
- Kết quả unverified có `reason` như trên, không tạo `closest_offer` hay `% chênh lệch` bằng 0 để giả định đạt kỳ vọng.

Có catalog job nhưng tất cả đều có lương đã đối chiếu được và max dưới ngưỡng: trả `200` với `results: []`,
`eligible_jobs: 0`; không tạo job giả hoặc nới ngưỡng. Nếu không có job active nào được nhập, API trả `409`.
Khi bật AI mà không có embedding hợp lệ cho tập job đủ điều kiện, matching chuyển sang
`SKILL_OVERLAP_UNINDEXED_JOBS`, provider `RULE_BASED`, `llm_rerank_score = null` và không gọi rerank AI.
Đây là fallback do chưa có vector phù hợp; không phải bằng chứng rằng Ollama đã đánh giá các job này.

Khi chỉ một phần job đã có vector, backend bổ sung tối đa 20 job còn lại có kỹ năng trùng với hồ sơ/CV,
rồi đưa cùng tập truy xuất vào các nhóm rerank AI tối đa 3 JD. Tổng tập rerank tối đa 100 job.
Mỗi JD gửi tối đa 1.500 ký tự mô tả và 20 kỹ năng; không gửi URL hoặc điểm xếp hạng nội bộ.
Schema ràng buộc số lượng kết quả và danh sách ID của từng nhóm; backend kiểm tra thiếu/trùng/ID lạ.
Các nhóm dùng cùng thang điểm tuyệt đối 0–1, không chuẩn hóa trong từng nhóm. Không so trực tiếp điểm
keyword với điểm LLM; từng kết quả lưu `match_reason_json.retrieval = SKILL` hoặc `VECTOR`.
Khi toàn bộ index rỗng, fallback không gọi embedding/rerank và không tiêu quota model của candidate.

### 5. Gợi ý theo CV đã xác nhận

```http
POST {{baseUrl}}/matching
Content-Type: application/json
```

```json
{
  "basis": "SELECTED_CV",
  "resumeIds": ["{{confirmedResumeId}}"],
  "limit": 20
}
```

`confirmedResumeId` phải thuộc Candidate và có trạng thái `READY`. Với tất cả CV đã xác nhận, dùng
`basis = "ALL_CVS"`, `resumeIds = []`. Nếu hồ sơ có kỳ vọng lương, hai luồng dùng cùng chính sách v3;
nếu chưa đặt kỳ vọng thì không áp dụng ngưỡng lương.

Kiểm tra cả job VND, job USD/tiền tệ khác, job có lương dưới ngưỡng và job không công khai lương.
Job unverified phải có nhãn/trường lý do và không có phần trăm chênh lệch giả; nếu có quy đổi thì kiểm tra nguồn,
thời điểm và khoảng quy đổi thay vì hardcode tỷ giá trong test thủ công.

### 6. Đọc lại kết quả và kiểm tra đổi kỳ vọng

Lưu `id` của run thành biến `matchingRunId`, rồi gọi:

```http
GET {{baseUrl}}/matching/{{matchingRunId}}
```

Run chỉ đọc được bởi chủ sở hữu. Chính sách và lời giải thích lương được lưu cùng run để đối chiếu lịch sử. Kết quả lịch sử giữ chính sách tại thời điểm chạy, không tự chuyển sang kỳ vọng mới.

Nếu Admin đang đặt 10%, thử cập nhật kỳ vọng thành `10000000` rồi matching lại: ngưỡng mới là `9000000`.
Đổi biên độ sang 20% mà không đổi hồ sơ thì ngưỡng mới là `8000000` và phải dùng chính sách/cache mới.
Cache key tính cả hồ sơ/mức lương, biên độ/phiên bản cấu hình, phiên bản chính sách, catalog và cấu hình matching.
Phiên bản v3 và snapshot tỷ giá làm mất hiệu lực cache kết quả rỗng của chính sách cũ cho **POST mới**.
`GET /matching/{id}` vẫn trả nguyên run lịch sử; để kiểm tra bản sửa phải gửi `POST /matching` mới,
không chỉ mở lại ID của run từng trả 0 job.

## Phạm vi matching và model

`CAREER_PROFILE` cần hồ sơ đã lưu và `expected_salary > 0`. Hai basis `ALL_CVS`/`SELECTED_CV` vẫn hoạt động theo CV; nếu candidate đã đặt lương kỳ vọng thì áp dụng cùng bộ lọc, còn chưa đặt thì giữ hành vi matching CV trước đây. CV phải đáp ứng điều kiện READY/ownership hiện có.

Bộ đối chiếu lương chạy bằng quy tắc xác định trước bước chọn job theo vector và AI rerank.
Model không được quyền giữ lại job có max đã quy đổi dưới ngưỡng. Job chưa xác minh lương được giữ theo chính sách v3,
không phải do model bỏ qua điều kiện. Khi tắt AI, matching dùng skill overlap; khi bật AI và có vector hợp lệ,
backend dùng Ollama self-hosted. Nếu tập job chưa có vector hợp lệ thì dùng fallback đã mô tả ở trên.
`JOB_EMBEDDING_ENABLED=true` cho phép worker lập chỉ mục job theo cấu hình model hiện tại;
việc bật worker không chứng minh tất cả job đã index xong. Thay đổi này không thêm model thương mại hoặc fallback AI trả phí.

## Migration và kiểm chứng

[V12](../src/main/resources/db/migration/V12__single_expected_salary.sql) thêm cột `candidate_profiles.expected_salary` cùng CHECK số dương. Dữ liệu min/max cũ được giữ ở cột legacy để đối chiếu, nhưng API không đọc/ghi chúng cho giao diện mới. Backfill dùng trung điểm nếu cả hai đầu mút dương, đầu mút dương duy nhất nếu chỉ có một, còn không thì `null`; candidate có thể chỉnh lại mức chính xác. Embedding profile cũ được đánh dấu không còn current để tạo lại theo biểu diễn mới.

V15 bổ sung `matching_salary_settings` để Admin lưu biên độ, phiên bản, người và thời gian cập nhật;
khởi tạo 10% để giữ hành vi cũ. Không đổi mức lương kỳ vọng riêng của Candidate.
Sửa chính sách v3, quy đổi tỷ giá và fallback chưa có index **không cần migration database mới**;
metadata được lưu trong các JSON snapshot/lời giải thích hiện có.

Kiểm tra frontend adapter/parser và render có thể chạy từ thư mục frontend:

```powershell
node scripts/test-career-salary.mjs
node scripts/test-candidate-job-integration.mjs
npm.cmd run build
```

Script đầu bao phủ chuyển `7tr5`, từ chối khoảng lương/giá trị không hợp lệ, giữ dữ liệu hồ sơ khi PUT, request thật qua adapter,
liên kết job an toàn và hiển thị ngưỡng từ chính sách backend/snapshot, không có trần. Đây là kiểm tra adapter/render bằng dữ liệu
tổng hợp; không phải đo chất lượng model, nghiệm thu thao tác người dùng trong trình duyệt hay benchmark inference.
Các kiểm tra hồi quy hiện tại còn bao phủ trạng thái chưa xác minh, tỷ giá/quy đổi, khoảng một phía,
nguồn attribution và việc giữ nguyên mô tả snapshot lịch sử. Không dùng số test lịch sử bên dưới làm kết quả cho bản v3.

### Kiểm chứng ban đầu ngày 22/10/2026 — phiên bản cố định 10%

Các số liệu và quy tắc loại lương bên dưới là lịch sử của bản V12, **không phải hành vi v3**.
Kiểm chứng cấu hình Admin được ghi riêng trong
[hướng dẫn Admin](admin-salary-policy.md).

| Kiểm tra | Kết quả | Bằng chứng |
| --- | --- | --- |
| Backend `mvn -B verify` | **BUILD SUCCESS**, 632 test được ghi nhận: **579 đạt**, 0 failure, 0 error, **53 bỏ qua**; 2 phút 17 giây, hoàn tất lúc 21:21 giờ Việt Nam | [Log verify](../target/salary-policy-verify.log) |
| Các test mới về lương | **144 đạt**, gồm profile contract/API/migration, parser và matching; các suite integration mới chạy với PostgreSQL/pgvector trong Docker | [JUnit XML](../target/surefire-reports) |
| Frontend adapter/render | **16 kiểm tra đạt**, gồm một mức lương, quy tắc 90%, giữ dữ liệu hồ sơ, xử lý lỗi API và kết quả backend | [Log](../../../FE/FA26SE119-FE/salary-policy-contract-tests.log) |
| Frontend build và lint phần mới | TypeScript/Vite build thành công; ESLint đạt cho 5 file profile/recommendation mới. Build còn cảnh báo bundle lớn | [Build](../../../FE/FA26SE119-FE/salary-policy-build.log), [lint](../../../FE/FA26SE119-FE/salary-policy-lint.log) |
| Kiểm tra hồi quy luồng CV | **11 kiểm tra adapter/render đạt** | [Log CV](../../../FE/FA26SE119-FE/salary-policy-cv-regression.log) |
| Backend Docker local | Đã build và khởi động lại `edunexus-app-1`; health `UP`, SHA-256 của JAR trong container khớp JAR đã verify | [Log cập nhật](../target/salary-policy-docker-update.log), [runtime](../target/salary-policy-app-runtime.json) |
| DB ứng dụng local | Flyway **V12**, 0 migration lỗi; cột mới tồn tại, giữ cột legacy; 1/1 profile hiện có đã backfill đúng | [Metadata runtime](../target/salary-policy-app-runtime.json) |

Các test matching mới xác nhận ngưỡng bằng 90% được chấp nhận, dưới ngưỡng bị loại, lương cao hơn vẫn hợp lệ, khoảng lương dùng mức tối đa, khác tiền tệ/lương không xác định bị loại, lọc lương trước khi chọn top job theo vector, và đổi kỳ vọng/lương job làm mất hiệu lực cache. Lời gọi AI trong các test này dùng mock; kết quả không đo độ chính xác model.

Trước khi cập nhật DB, đã tạo [backup PostgreSQL](../target/pre-single-salary-20261022-212105.dump) và kiểm tra đọc được danh mục archive; [metadata backup](../target/salary-policy-app-backup.json) lưu kích thước/hash và trạng thái DB V11. Chưa thử phục hồi backup lên một DB riêng.

Kiểm tra HTTP trên container sau cập nhật: `GET /api/v1/jobs` và `GET /api/v1/resumes/templates` trả `200`; truy cập career profile không có token trả `401`. Chưa thực hiện thao tác trong trình duyệt hoặc gọi matching bằng tài khoản thật trên container; API có xác thực đã được kiểm tra qua MockMvc với DB integration. 53 test bỏ qua trong toàn bộ bộ kiểm tra vẫn được ghi riêng; không báo cáo 632 test đều đạt hoặc toàn bộ frontend lint đã sạch.
