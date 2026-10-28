# Kiểm tra crawler TopCV ngày 25/10/2026

TopCV đang trả trang xác minh Cloudflare cho HTTP client của dự án. Chưa thể xác nhận crawler
thu thập được dữ liệu TopCV tự động trong môi trường hiện tại.

## Bằng chứng

- Lượt TopCV trong DB lúc 20:06 ngày 24/10/2026 thất bại ngay trang tìm kiếm React: HTTP 403,
  chưa tìm được URL chi tiết và chưa import job nào. Bản cũ chỉ lưu `http_status = null` dù message ghi 403.
- Kiểm tra trực tiếp cả `/tim-viec-lam?keyword=Java&page=1` và `/tim-viec-lam-java` đều trả 403,
  header `cf-mitigated: challenge` và HTML có tiêu đề `Just a moment...`.
- Đây là bước kiểm tra truy cập trước khi parser nhận được nội dung tin tuyển dụng. Trang có thể mở
  được trên một trình duyệt hoặc xuất hiện trong kết quả tìm kiếm mà HTTP client vẫn bị challenge.

Cloudflare mô tả chính thức header này tại
[Detect a Challenge Page response](https://developers.cloudflare.com/cloudflare-challenges/challenge-types/challenge-pages/detect-response/).

## Thay đổi chẩn đoán trong backend

`JobCrawlService` nhận diện trang challenge qua header và nhận diện HTML challenge HTTP 200.
Lượt bị chặn dừng tại nguồn; không import trang challenge thành job và không đánh dấu job cũ hết hạn.
HTTP 429 được ghi rõ là giới hạn tần suất và không tự lặp yêu cầu.

Với challenge tại bước discovery, phản hồi API dự kiến:

```json
{
  "status": "FAILED",
  "imported_jobs": 0,
  "search_pages_checked": 1,
  "candidate_urls_discovered": 0,
  "items": [
    {
      "item_status": "SEARCH_FETCH_FAILED",
      "http_status": 403,
      "message": "Source access blocked by Cloudflare verification (HTTP 403). Automated HTML collection is unavailable; use a source-authorized API/feed or manual job import."
    }
  ]
}
```

API crawl trả kết quả của lượt thu thập; HTTP 200 của API nội bộ không có nghĩa nguồn TopCV trả 200.
Thông tin lỗi cũng được đưa vào `market_crawl_runs.message`. Lịch sử cũ giữ nguyên; tạo lượt mới để thấy
message và mã HTTP mới.

## Để tiếp tục lấy dữ liệu TopCV

Cần API/feed hoặc quyền truy cập crawler do TopCV cung cấp, chẳng hạn nguồn cho phép IP của máy chủ.
Khi có hợp đồng API/feed, thêm adapter theo hợp đồng đó; hiện dự án chưa cấu hình adapter như vậy.
Đổi keyword, tăng số trang hoặc tăng thời gian chờ không xử lý được challenge đã đo ở trên.

Trong lúc chờ nguồn truy cập, Admin có thể nhập dữ liệu tin đã được cung cấp hợp lệ bằng
`POST /api/v1/admin/jobs`, với `sourceSite = TOPCV`, URL gốc và nội dung thật. Endpoint này không tự
fetch TopCV. Xem [payload nhập tin](crawling.md#2-parsing-và-manual-override).

Không có migration mới cho thay đổi chẩn đoán này. Bằng chứng cục bộ nằm trong `target/topcv-runtime-audit.json`,
`target/topcv-search-live-headers.txt`, `target/topcv-slug-live-headers.txt` và log test `target/topcv-tests.log`.

## Kết quả kiểm tra bản sửa

- 87 test crawler/discovery/parser/scheduler đạt, không failure/error hoặc skipped; trong đó có 7 test
  về challenge 403/503/200, HTTP 429 và bảo vệ job đang có.
- Đóng gói thành công, cập nhật container `edunexus-app-1`, health `UP`; hash JAR trong container
  khớp bản đã kiểm tra. Đây là kiểm tra theo phạm vi crawler, không phải một lượt `mvn verify` toàn dự án mới.
- Gọi API Docker với Admin tạm, giới hạn một trang React: API 200, run `FAILED`, nguồn 403,
  thông báo Cloudflare rõ ràng, 0 job nhập. Tài khoản và bản ghi test đã được xóa.
  Bằng chứng: `target/topcv-live-verification.json`.
- Việc truy cập tự động TopCV **chưa được khôi phục**; bản sửa làm rõ lỗi nguồn và tránh xử lý nhầm challenge.
