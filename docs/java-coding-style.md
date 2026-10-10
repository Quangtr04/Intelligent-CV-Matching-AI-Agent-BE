# Quy ước trình bày Java

Áp dụng cho mã ứng dụng, test và công cụ Java trong `scripts/dev`. Quy ước cho các lần sửa tiếp theo nằm trong [AGENTS.md](../AGENTS.md); IDE dùng [.editorconfig](../.editorconfig), còn Maven dùng profile chung [java-formatter.xml](../config/java-formatter.xml).

- Thụt lề bằng 4 dấu cách; dùng UTF-8, xuống dòng LF và có newline cuối file.
- Annotation của khai báo nằm trên dòng riêng, annotation của tham số nằm phía trên tham số. Khi một kiểu phần tử có nhiều annotation, tách từng annotation thành dòng riêng và giữ nguyên đối tượng được kiểm tra.
- Record, constructor và method có nhiều tham số đặt mỗi tham số trên một dòng riêng.
- Dấu `{` mở nằm trên dòng kết thúc khai báo. Thân method, câu lệnh và nhánh điều kiện được viết thành các dòng rõ ràng.
- Giữ một dòng trống giữa các method, thành viên quan trọng và nhóm xử lý có cùng mục đích.
- Giới hạn 120 ký tự mỗi dòng. Ngoại lệ là import không thể tách và dữ liệu text block có sẵn cần giữ nguyên nội dung.
- Giữ cấu trúc lớp/vai trò/chức năng, tách request/response DTO và bảo toàn nghiệp vụ, hợp đồng API, validation, transaction cùng thao tác database khi định dạng.

Ví dụ khai báo nhiều tham số:

```java
public record LoginRequest(
    @NotBlank
    @Email
    String email,
    @NotBlank
    String password
) {
}
```

## Định dạng và kiểm tra

Chạy từ thư mục chứa `pom.xml`, với JDK của dự án:

```powershell
mvn formatter:format
mvn formatter:validate
mvn verify
```

Trước khi commit, chạy format và verify rồi commit cả các file Java đã được formatter sửa. Lỗi CI `has not been previously formatted` cho biết nội dung file vẫn khác profile của dự án. CI kiểm tra định dạng trước khi chạy test và đóng gói JAR.

Để dùng cùng phiên bản Maven với CI, trên Windows chạy `./mvnw.cmd --batch-mode formatter:format verify`; trên Linux/macOS chạy `./mvnw --batch-mode formatter:format verify`. Quy tắc `*.java text eol=lf` trong `.gitattributes` giữ thống nhất xuống dòng Java khi Git checkout trên Windows và Linux.

Formatter xử lý toàn bộ Java trong `src/main/java`, `src/test/java` và `scripts/dev`. Kiểm tra định dạng được gắn vào phase `validate`, nên `mvn verify` cũng kiểm tra trước khi biên dịch và chạy test. Sau khi sinh lại entity/JPA repository, chạy formatter trước khi build.

Formatter hiện tự gộp annotation bên trong kiểu generic. Với nhiều annotation kiểm tra phần tử, dùng đoạn `@formatter:off` / `@formatter:on` hẹp như trong `CrawlRequest`, giữ từng annotation trên dòng riêng. Chỉ bảo vệ phần kiểu cần giữ bố cục; giữ nguyên vị trí annotation để phân biệt ràng buộc của collection và của từng phần tử.

Nếu chuỗi literal thông thường quá dài, công cụ sau chia nó thành phép nối các literal tại thời điểm biên dịch, giữ nguyên giá trị chuỗi và không sửa text block:

```powershell
java scripts/dev/WrapJavaStrings.java check
java scripts/dev/WrapJavaStrings.java apply
mvn formatter:format
```

Với thay đổi chỉ để trình bày, có thể so sánh cây cú pháp trước và sau để kiểm tra logic và giá trị chuỗi:

```powershell
java scripts/dev/CheckJavaSemantics.java snapshot target/java-style-snapshot
# Thực hiện thay đổi định dạng hoặc chia chuỗi tại đây.
java scripts/dev/CheckJavaSemantics.java check target/java-style-snapshot
mvn verify
```

So sánh này bỏ qua khoảng trắng/comment và chuẩn hóa các phép nối literal theo giá trị chuỗi. Kiểm tra biên dịch và test vẫn được thực hiện bằng `mvn verify`.
