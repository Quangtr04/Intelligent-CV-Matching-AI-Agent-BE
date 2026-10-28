# Thanh toán gói đăng ký qua SePay Payment Gateway

Backend Java tạo đơn theo bảng giá trong database, ký form checkout và nhận IPN xác nhận thanh toán.
Merchant secret chỉ nằm ở backend. Frontend nhận URL và danh sách hidden fields đã ký, sau đó gửi form
POST sang SePay. Không cài `sepay-pg-node` hoặc đưa merchant secret vào frontend.

Tích hợp này dùng **Cổng thanh toán SePay** theo mẫu `SePayPgClient.checkout`, không dùng hợp đồng webhook
biến động số dư của sản phẩm Bank Transaction API.

## 1. Cấu hình backend

Thêm vào `.env` trong thư mục backend:

```dotenv
SEPAY_ENABLED=false
SEPAY_ENVIRONMENT=sandbox
SEPAY_MERCHANT_ID=
SEPAY_MERCHANT_SECRET=
SEPAY_IPN_SECRET=
SEPAY_RETURN_URL=https://your-public-frontend.example/candidate/subscription
SEPAY_PAYMENT_METHOD=BANK_TRANSFER
```

Điền Merchant ID và Merchant Secret của môi trường sandbox từ tài khoản merchant SePay. Đặt IPN secret
là chuỗi ngẫu nhiên ít nhất 32 byte UTF-8 (có thể dùng ít nhất 32 ký tự ASCII) và cấu hình đúng cùng giá trị
ở phần xác thực IPN của merchant.
Nếu `.env` đã có `SEPAY_IPN_SECRET`, giữ giá trị đó và sao chép sang cấu hình IPN SePay; không ghi đè bằng
giá trị rỗng trong ví dụ và không đưa secret vào frontend hoặc Git.
`SEPAY_RETURN_URL` là địa chỉ frontend công khai nhận người dùng sau checkout. Các giá trị trên đang để
trống hoặc là ví dụ; thay bằng cấu hình của nhóm rồi đặt `SEPAY_ENABLED=true`.

`PAYMENT_PROVIDER` của luồng thanh toán cũ có thể giữ `MANUAL_GATEWAY`. Các endpoint SePay tự tạo đơn với
provider riêng; không cần đổi toàn bộ thanh toán sang một provider duy nhất.

Nếu backend chạy bằng Docker, tạo lại container app sau khi đổi `.env`:

```powershell
docker compose --profile app --profile optional up -d --no-deps app
```

Lệnh trên nạp lại cấu hình cho image đã build. Khi đổi Java, build JAR và image backend trước theo quy trình
chạy dự án; không xóa volume database hoặc volume model để đổi cấu hình thanh toán.

Hai môi trường dùng các bộ credentials riêng:

| Môi trường | URL nhận form checkout | Provider lưu trong DB |
| --- | --- | --- |
| `sandbox` | `https://pay-sandbox.sepay.vn/v1/checkout/init` | `SEPAY_SANDBOX` |
| `production` | `https://pay.sepay.vn/v1/checkout/init` | `SEPAY_PRODUCTION` |

Backend kiểm tra provider của đơn để không xác nhận nhầm đơn giữa sandbox và production.
Không dùng host REST `pgapi-sandbox.sepay.vn` làm `action` của form.
Xem [hướng dẫn Sandbox chính thức](https://developer.sepay.vn/vi/cong-thanh-toan/sandbox).

## 2. Cấu hình IPN tại merchant SePay

Tại **Cổng thanh toán → Cấu hình → IPN**:

1. Đặt URL nhận thông báo là `https://your-public-backend.example/api/v1/payments/sepay/ipn`.
2. Chọn kiểu xác thực **`SECRET_KEY`**.
3. Cấu hình secret tương ứng với `SEPAY_IPN_SECRET` của backend.
4. Lưu cấu hình đúng môi trường đang sử dụng.

SePay gửi `X-Secret-Key` để xác thực IPN. Endpoint này không cần Bearer token của người dùng.
`localhost`, `127.0.0.1` và địa chỉ LAN không phải URL callback mà SePay có thể gọi từ Internet.
Khi phát triển local, nhóm có thể tự cấu hình một tunnel HTTPS cho backend và địa chỉ frontend công khai;
tài liệu này không tự mở tunnel hoặc công khai máy của bạn.

Khi FE chạy trên domain công khai, đặt `VITE_API_BASE_URL` về URL HTTPS của backend có đuôi `/api/v1`,
và bổ sung origin HTTPS của FE vào `CORS_ALLOWED_ORIGINS` của backend. Giữ các origin đang cần dùng;
không thay bằng wildcard chỉ để thử thanh toán.

Theo [hợp đồng IPN chính thức](https://developer.sepay.vn/vi/cong-thanh-toan/IPN), server nhận phải trả
HTTP 200 sau khi xử lý thành công. Backend xác thực secret, đối chiếu đơn/tiền tệ/số tiền và xử lý trùng
trong database trước khi xác nhận đã nhận.

## 3. Postman: thứ tự thử checkout

Tạo environment:

```text
baseUrl = http://localhost:8080/api/v1
accessToken = accessToken của tài khoản đăng nhập dùng để mua gói
tierId = UUID thật của gói BASIC hoặc PRO đang hoạt động
```

Các URL bên dưới đã dùng `baseUrl` chứa `/api/v1`, không thêm tiền tố này lần nữa.

### Bước 1: lấy gói và mức giá thực tế

```http
GET {{baseUrl}}/subscriptions/tiers
```

Chọn UUID của gói trả phí từ phản hồi. Giá được backend lấy từ `subscription_tiers.price_monthly`,
nhân số tháng và cố định vào đơn. Request checkout không nhận giá, tiền tệ hoặc trạng thái thanh toán
do frontend tự quyết định. Checkout chuyển khoản hiện dùng VND; gói FREE không cần tạo thanh toán.

### Bước 2: tạo đơn và lấy form checkout

```http
POST {{baseUrl}}/payments/sepay/checkout
Authorization: Bearer {{accessToken}}
Content-Type: application/json
```

```json
{
  "tierId": "{{tierId}}",
  "durationMonths": 1
}
```

Ví dụ cấu trúc phản hồi; lấy các giá trị thật từ backend, không gửi lại chữ ký ví dụ:

```json
{
  "order": {
    "id": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
    "status": "PENDING",
    "amount": 199000,
    "currency": "VND",
    "payment_provider": "SEPAY_SANDBOX"
  },
  "checkoutUrl": "https://pay-sandbox.sepay.vn/v1/checkout/init",
  "method": "POST",
  "formFields": [
    { "name": "order_amount", "value": "199000" },
    { "name": "merchant", "value": "YOUR_SANDBOX_MERCHANT_ID" },
    { "name": "currency", "value": "VND" },
    { "name": "operation", "value": "PURCHASE" },
    { "name": "order_description", "value": "Mo ta do backend tao" },
    { "name": "order_invoice_number", "value": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa" },
    { "name": "customer_id", "value": "USER_ID_DO_BACKEND_TAO" },
    { "name": "payment_method", "value": "BANK_TRANSFER" },
    { "name": "success_url", "value": "URL_SUCCESS_DO_BACKEND_TAO" },
    { "name": "error_url", "value": "URL_ERROR_DO_BACKEND_TAO" },
    { "name": "cancel_url", "value": "URL_CANCEL_DO_BACKEND_TAO" },
    { "name": "signature", "value": "CHU_KY_DO_BACKEND_TAO" }
  ],
  "environment": "sandbox"
}
```

`formFields` là **array có thứ tự**, không phải object map tùy ý. Giữ đúng thứ tự, tên và giá trị khi
dựng form; không tự thay số tiền, mã đơn, callback URL hoặc tính lại chữ ký trong FE.
`order_invoice_number` chính là UUID của đơn nội bộ trong bản tích hợp này, không có tiền tố `EN-`.
Lưu `order.id` để đọc trạng thái đơn sau checkout. Trong tab Scripts → Post-response có thể dùng:

```javascript
const checkout = pm.response.json();
const valueOf = name => checkout.formFields.find(field => field.name === name)?.value;
pm.environment.set('paymentOrderId', checkout.order.id);
pm.environment.set('sepayInvoiceNumber', valueOf('order_invoice_number'));
pm.environment.set('paymentAmount', valueOf('order_amount'));
```

### Bước 3: mở checkout trong trình duyệt

Postman tạo đơn được nhưng không thay thế luồng người dùng mở form thanh toán trong trình duyệt.
Ví dụ component React nhận nguyên phản hồi backend:

```jsx
function SePayPaymentForm({ checkout }) {
  return (
    <form action={checkout.checkoutUrl} method={checkout.method}>
      {checkout.formFields.map(field => (
        <input
          key={field.name}
          type="hidden"
          name={field.name}
          value={field.value}
        />
      ))}
      <button type="submit">Thanh toán qua SePay</button>
    </form>
  );
}
```

BE tạo chữ ký HMAC-SHA256, Base64, theo thứ tự field của
[tài liệu tạo checkout SePay](https://developer.sepay.vn/vi/cong-thanh-toan/API/don-hang/form-thanh-toan).
Browser thực hiện mã hóa form khi submit. FE không cần biết merchant secret hoặc IPN secret.

### Bước 4: đọc trạng thái đơn và gói

```http
GET {{baseUrl}}/payments/orders/{{paymentOrderId}}
Authorization: Bearer {{accessToken}}
```

Đơn giữ `PENDING` trong lúc chưa có IPN hợp lệ. Khi nhận xác nhận thanh toán đúng, backend chuyển đơn
sang `COMPLETED` và kích hoạt subscription tương ứng. Quay về `success_url` trên trình duyệt không tự
chuyển trạng thái đơn hay cấp PRO. FE đọc lại đơn và gói từ BE để hiển thị kết quả.

Các callback được tạo từ `SEPAY_RETURN_URL`, với `orderId=<UUID đơn>` và
`payment=success`, `payment=error` hoặc `payment=cancel`. Query này chỉ giúp FE biết cần đọc lại đơn nào;
không phải xác nhận từ SePay. Đọc lại gói bằng:

```http
GET {{baseUrl}}/subscriptions/me
Authorization: Bearer {{accessToken}}
```

Chỉ chủ đơn được đọc hoặc lấy lại checkout của đơn đó.

### Bước 5: lấy lại form cho đơn PENDING đã có

```http
POST {{baseUrl}}/payments/orders/{{paymentOrderId}}/sepay-checkout
Authorization: Bearer {{accessToken}}
```

Không cần body. Endpoint trả cùng cấu trúc `SePayCheckoutResponse`, dùng giá/tiền tệ/mã đơn đã lưu,
không tạo thêm một đơn mới. Đơn của tài khoản khác, đơn đã kết thúc hoặc đơn thuộc provider/môi trường
khác không được cấp form checkout.

## 4. Postman: kiểm tra IPN trong sandbox

Dùng tài khoản và đơn test. IPN hợp lệ sẽ thực sự chuyển đơn test thành `COMPLETED` và cấp gói trong
database đang kết nối. Để thử toàn bộ luồng SePay, thanh toán giả lập trên sandbox và đợi callback thật.
Việc tự gửi request dưới đây chỉ kiểm tra endpoint của ứng dụng, không chứng minh SePay đã gửi IPN.

Đặt `sepayIpnSecret` bằng secret IPN đã cấu hình ở BE. Tạo `sepayProviderOrderId`, `sepayTransactionId`
cho giao dịch test, giữ nguyên khi gửi lặp để kiểm tra chống xử lý trùng. Trước khi gửi:

```javascript
pm.environment.set('sepayIpnTimestamp', Math.floor(Date.now() / 1000));
if (!pm.environment.get('sepayProviderOrderId')) {
  pm.environment.set('sepayProviderOrderId', pm.variables.replaceIn('{{$guid}}'));
}
if (!pm.environment.get('sepayTransactionId')) {
  pm.environment.set('sepayTransactionId', pm.variables.replaceIn('{{$guid}}'));
}
```

```http
POST {{baseUrl}}/payments/sepay/ipn
X-Secret-Key: {{sepayIpnSecret}}
Content-Type: application/json
```

Chọn **No Auth** cho request IPN để không gửi Bearer token cũ từ collection. Body:

```json
{
  "timestamp": {{sepayIpnTimestamp}},
  "notification_type": "ORDER_PAID",
  "order": {
    "id": "{{sepayProviderOrderId}}",
    "order_id": "{{sepayProviderOrderId}}",
    "order_status": "CAPTURED",
    "order_currency": "VND",
    "order_amount": "{{paymentAmount}}",
    "order_invoice_number": "{{sepayInvoiceNumber}}"
  },
  "transaction": {
    "id": "{{sepayTransactionId}}",
    "transaction_id": "{{sepayTransactionId}}",
    "transaction_type": "PAYMENT",
    "transaction_status": "APPROVED",
    "transaction_amount": "{{paymentAmount}}",
    "transaction_currency": "VND"
  }
}
```

Đây là dữ liệu mô phỏng. Mã `order_invoice_number` và số tiền phải lấy từ form thật của đơn vừa tạo.
Với callback do SePay gửi, dùng nguyên ID và dữ liệu do SePay cung cấp.

IPN được xử lý hợp lệ trả HTTP 200, ví dụ:

```json
{
  "accepted": true,
  "duplicate": false,
  "orderId": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
  "success": true
}
```

IPN trùng có `duplicate = true`. Thông báo void được acknowledge với `ignored = true`;
không tự đảo thanh toán hoặc thu hồi quyền lợi.

Các trường hợp cần kiểm tra:

| Tình huống | Kết quả cần xác nhận |
| --- | --- |
| Thiếu/sai `X-Secret-Key` | Bị từ chối, đơn và gói không đổi |
| Số tiền hoặc tiền tệ sai | Không cấp gói |
| Mã đơn không đúng | Không xác nhận một đơn khác |
| `ORDER_PAID` hợp lệ, `CAPTURED`, giao dịch `PAYMENT`/`APPROVED` | Đơn `COMPLETED`, có subscription/invoice tương ứng |
| Gửi lại cùng giao dịch đã nhận | HTTP 200 xác nhận đã nhận; không tạo thêm gói hoặc invoice |
| `TRANSACTION_VOID` | Được ghi nhận/acknowledge, không tự thu hồi gói đã kích hoạt |
| Mở trực tiếp callback success hoặc sửa query của callback | Không được cấp gói |
| Gửi định dạng webhook cũ vào endpoint thanh toán generic | Không dùng để hoàn tất đơn SePay |

Không dùng webhook HMAC/timestamp của `MANUAL_GATEWAY` để giả lập IPN SePay. Hai hợp đồng xác thực
khác nhau và đơn SePay được ràng buộc với đúng provider/môi trường.

## 5. Chẩn đoán khi chưa thanh toán được

- Endpoint checkout báo chưa cấu hình: kiểm tra `SEPAY_ENABLED`, merchant ID/secret, IPN secret và return URL;
  sau khi sửa `.env`, nạp lại cấu hình vào app.
- SePay báo chữ ký sai: lấy lại form từ BE; giữ nguyên array fields và giá trị, dùng đúng credentials/môi trường.
- Đã về frontend nhưng đơn vẫn `PENDING`: kiểm tra IPN URL HTTPS công khai, kiểu auth `SECRET_KEY`, secret
  trùng với backend và log IPN tại SePay. Redirect thành công không thay IPN.
- Đơn sandbox không được hoàn tất ở app cấu hình production: dùng đúng môi trường đã tạo đơn.
- Người dùng vẫn thấy gói cũ: đọc lại order và subscription từ backend sau khi IPN được xác nhận.

Tài liệu này mô tả hợp đồng tích hợp và các bước kiểm tra. Merchant ID/secret trong ví dụ là placeholder;
không suy ra đã thanh toán thật, đã kết nối tài khoản ngân hàng hoặc đã nhận callback SePay qua Internet
chỉ từ việc tạo được JSON checkout hoặc tự gửi IPN bằng Postman. Kết quả test/build và thử nghiệm sandbox
thực tế phải được ghi nhận riêng theo cấu hình merchant của nhóm.
