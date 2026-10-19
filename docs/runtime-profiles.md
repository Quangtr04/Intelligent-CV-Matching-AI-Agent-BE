# Cấu hình runtime với dev, prod và docker

Backend dùng cùng một JAR với ba Spring profile. `application.yaml` chứa cấu hình chung, import `.env` UTF-8 và đặt profile mặc định `dev`. Chọn môi trường chạy bằng `SPRING_PROFILES_ACTIVE`, cấu hình Maven/IDE hoặc `--spring.profiles.active` khi khởi động JAR.

## Các file cấu hình

| File | Nội dung |
| --- | --- |
| `src/main/resources/application.yaml` | Cấu hình chung Spring/app, import `.env`, mặc định `dev` khi chưa chọn active profile |
| `src/main/resources/application-dev.yaml` | PostgreSQL/Redis local, upload `./uploads`, logging và thiết lập phát triển qua biến môi trường |
| `src/main/resources/application-docker.yaml` | Fallback PostgreSQL `postgres:5432`, Redis `redis:6379`, upload `/app/uploads`; Compose hiện ghi đè URL database sang host; Spring không tự khởi động Compose |
| `src/main/resources/application-prod.yaml` | Bắt buộc `DATABASE_URL`, `REDIS_HOST`, `REDIS_PORT`; tắt tự động Compose, devtools, development OTP, chi tiết lỗi và health |

`prod` vẫn cần username/password database, `APP_JWT_SECRET` và các giá trị bắt buộc trong cấu hình chung. Redis là cache tùy chọn trong nghiệp vụ: yêu cầu cấu hình địa chỉ Redis trong `prod` không biến Redis thành kho dữ liệu bắt buộc để lưu CV/matching/ATS. Backend vẫn có các đường fallback database khi cache không kết nối được.

Profile `docker` dành cho mạng nội bộ Compose local. Chạy bằng container ở production dùng `prod` với endpoint của các dịch vụ ngoài được cấp lúc triển khai. Không bật cả ba profile cùng lúc; chọn một profile phù hợp.

## Nguồn giá trị và thứ tự ưu tiên

Spring import file bằng cấu hình chung:

```text
optional:file:${APP_ENV_FILE:.env}[extension=.properties][encoding=utf-8]
```

Working directory mặc định là thư mục dự án có `pom.xml` và `.env`. `APP_ENV_FILE` có thể chọn file khác. Import optional cho phép chạy bằng các biến môi trường đã được cấp mà không cần file `.env`; những biến bắt buộc vẫn phải đủ.

Các nguồn thường dùng trong dự án có thứ tự ưu tiên:

1. Tham số ứng dụng, ví dụ `--spring.profiles.active=prod` khi chạy JAR.
2. System property của JVM, ví dụ `-Dspring.profiles.active=prod` trước `-jar`.
3. Biến môi trường của tiến trình/container, như `SPRING_PROFILES_ACTIVE`, `DATABASE_URL`.
4. Giá trị đọc từ file `.env` được import.
5. Giá trị mặc định được khai báo trong cấu hình profile.

File `.env` dùng UTF-8, các dòng `KEY=value` không bọc nháy và không có `export` hoặc comment cuối dòng. Đặt comment trên dòng riêng. Đường dẫn Windows dùng `/`. Khi thêm key, giữ nguyên credential/giá trị đang dùng; không cần chép đè `.env` hiện có bằng file mẫu.

`.env.example` không đặt sẵn `SPRING_PROFILES_ACTIVE=dev`. Không có active profile thì cấu hình chung tự dùng `dev`; container vẫn chọn profile rõ qua Compose. Nếu chủ động thêm active profile vào `.env`, giá trị đó áp dụng cho IDE/Maven/JAR khi không bị môi trường hoặc tham số cao hơn ghi đè.

Riêng Docker Compose, hai bước đọc cấu hình khác nhau:

- `.env` hỗ trợ thay `${VAR}` trong Compose; `env_file: .env` cấp các biến cho container.
- Các key trong `environment` của service ghi đè key trùng tên từ `env_file`. Compose local gán profile `docker`, URL PostgreSQL trên host, Redis nội bộ và upload `/app/uploads`; Compose production gán profile `prod` và upload `/app/uploads`.

Trong container app, `localhost` chỉ container app. Compose local hiện kết nối PostgreSQL trên host qua `host.docker.internal:5432/edunexus`, thay cho `localhost` dùng khi backend chạy trực tiếp trên host.

## IDE và Maven trên máy phát triển

Chuẩn bị `.env` theo [README](../README.md), đặt credential thật cho môi trường của mình và khởi động PostgreSQL có pgvector. IDE chạy main class với working directory là thư mục chứa `pom.xml`; không chọn profile thì dùng `dev`.

Chạy bằng script local:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/run-local.ps1 -WithInfrastructure
```

`-WithInfrastructure` khởi động và chờ PostgreSQL; Redis được khởi động riêng khi cần cache. Chọn rõ profile cho script bằng:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/run-local.ps1 -RuntimeProfile dev -WithInfrastructure
```

`-RuntimeProfile` nhận `dev`, `prod` hoặc `docker`. Khi truyền, script đặt `SPRING_PROFILES_ACTIVE` cho tiến trình chạy backend, ghi đè lựa chọn từ môi trường/file. Khi bỏ, script giữ biến môi trường hiện có, hoặc đọc lựa chọn từ `.env`; cuối cùng Spring mặc định `dev` nếu chưa có lựa chọn. Script không sửa file `.env`. `-EnvFile` tiếp tục chọn file cấu hình local khác.

Chạy trực tiếp bằng Maven:

```powershell
mvn spring-boot:run
mvn spring-boot:run "-Dspring-boot.run.profiles=dev"
```

Hai dòng trên là hai cách chạy thay thế. Có thể đặt `$env:SPRING_PROFILES_ACTIVE='dev'` trước khi chạy Maven; trong IDE, dùng biến môi trường tương tự hoặc phần Active profiles của cấu hình chạy. Muốn thử `prod` trên host, cấp đầy đủ endpoint/credential phù hợp rồi dùng `"-Dspring-boot.run.profiles=prod"`.

## Chạy JAR với prod

Tạo JAR trước bằng `mvn verify`. Chạy artifact theo version hiện tại trong `pom.xml` với profile `prod`; endpoint/credential/secret được cấp trước qua môi trường triển khai hoặc file cấu hình riêng:

```powershell
java -jar target/INTELLIGENT_CV_MATCHING_AI_AGENT-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod
```

Đổi đường dẫn JAR nếu artifact/version build khác. Có thể dùng `SPRING_PROFILES_ACTIVE=prod` thay tham số command line. Profile `prod` không chọn sẵn database local, không chạy Compose và không trả mã OTP development. Cấu hình SMTP thật khi cần đăng ký/xác minh qua email.

Các giá trị bắt buộc cho kết nối production gồm `DATABASE_URL`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, `REDIS_HOST`, `REDIS_PORT`. `APP_JWT_SECRET` phải đáp ứng kiểm tra bảo mật của ứng dụng; sinh và quản lý theo [README](../README.md). Không lưu credential thật vào file YAML hoặc Git.

## Backend và hạ tầng trong Compose local

Dockerfile chỉ đóng gói JAR có sẵn tại `target/*.jar`; không tự chạy Maven và không cố định Spring profile. Chuẩn bị PostgreSQL có pgvector trên host tại port 5432/database `edunexus`, rồi từ thư mục dự án:

```powershell
mvn verify
docker compose --profile app --profile optional up -d --build app
```

Lệnh chỉ định app và khởi động Redis phụ thuộc; app chờ Redis healthy. Redis nằm trong Compose profile `optional`, nên lệnh bật cả `app` và `optional` nhưng chỉ target `app`, không khởi động Qdrant/RabbitMQ. PostgreSQL container thuộc profile `database`, không tự khởi động cùng app.

Service `app` có Compose profile `app`; bên trong container được cấp Spring profile `docker`. Đây là hai loại profile khác nhau: một loại chọn service của Compose, loại còn lại chọn cấu hình Spring.

| Thiết lập app trong Compose local | Giá trị mặc định |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | `docker` |
| `DATABASE_URL` | `jdbc:postgresql://host.docker.internal:5432/edunexus` |
| `REDIS_HOST` / `REDIS_PORT` | `redis` / `6379` |
| `REDIS_USERNAME` / `REDIS_PASSWORD` | Trống, Redis local không bật AUTH |
| `UPLOAD_DIR` | `/app/uploads`, lưu trong volume `resume_uploads` |
| Port truy cập từ host | `APP_PORT`, mặc định `8080`; port bên trong lấy `SERVER_PORT`, mặc định `8080` |

Các giá trị này được khai báo trực tiếp trong Compose `environment` và ghi đè các key cùng tên ở `.env`. Đổi `DATABASE_URL`, `POSTGRES_DB` hay `POSTGRES_PORT` trong `.env` không đổi URL database của app Compose hiện tại; Redis của app vẫn dùng port nội bộ 6379 dù đổi port publish trên host. Credential PostgreSQL tiếp tục lấy từ `POSTGRES_USER`/`POSTGRES_PASSWORD` trong `.env` và phải khớp database trên host. Topology Compose local không có các biến override riêng cho URL database/host Redis; deployment với dịch vụ ngoài dùng file Compose production riêng.

Muốn chỉ chạy hạ tầng để backend chạy trên host như trước:

```powershell
docker compose --profile database up -d --wait postgres
docker compose up -d --wait redis
```

Lệnh PostgreSQL không khởi động app vì app thuộc profile `app`. Dùng `dev` cho backend chạy trên host. Nếu PostgreSQL trên host đã dùng port 5432, có thể dùng luôn database đó và bỏ bước khởi động PostgreSQL container, hoặc chọn port publish khác cho container và cập nhật URL của backend chạy trên host. Volume `resume_uploads` được dùng khi chạy app trong Compose; CV đã có tại `./uploads` trên host không tự chuyển vào volume này.

## Compose production

`compose.production.yaml` chỉ chạy app từ image đã build/publish, chọn Spring profile `prod`, lấy biến runtime từ `.env` và kết nối PostgreSQL/Redis bên ngoài. Container cố định `UPLOAD_DIR=/app/uploads`, gắn volume `resume_uploads` để CV tồn tại qua lần tạo lại container.

Sau khi chuẩn bị `.env` production và cấp `IMAGE` phù hợp, khởi động bằng:

```powershell
docker compose -f compose.production.yaml up -d
```

Không ghép file này với `compose.yaml` để dùng topology production đã mô tả. `APP_PORT` chọn port public trên host, `SERVER_PORT` là port ứng dụng trong container. Các endpoint dịch vụ phải truy cập được từ container production; không dùng hostname `postgres`/`redis` của Compose local khi những service đó không tồn tại trong mạng deployment.

## Kiểm tra sau khi đổi cấu hình

Khởi động lại backend và xem log Spring để xác nhận active/default profile. Kiểm tra app kết nối đúng database và upload được lưu vào đường dẫn/volume mong muốn. `/actuator/health` không xác minh Redis khi Redis health indicator bị tắt; nếu muốn kiểm tra Redis local, dùng `docker compose exec redis redis-cli PING` và các bước kiểm tra cache trong [README](../README.md).

Các lệnh ở đây là hướng dẫn vận hành; tài liệu không khẳng định đã chạy deployment production hoặc gọi dịch vụ ngoài để xác minh cấu hình của bạn.
