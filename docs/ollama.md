# Chat và embedding cục bộ bằng Ollama

Backend dùng adapter Ollama native của Spring AI cho cả chat và embedding. Model server chạy trong Docker,
không cần OpenAI API key. Các Compose manifest đặt `OLLAMA_NO_CLOUD=1`, dùng model tải về máy và không
publish cổng Ollama mặc định. Theo [FAQ Ollama](https://docs.ollama.com/faq#how-do-i-disable-ollamas-cloud-features),
biến này tắt cloud models và web search của Ollama. Lần tải image/weights đầu tiên vẫn cần Internet;
không có cam kết offline khi model chưa được tải đủ.

## Model và cấu hình

| Chức năng | Model mặc định | Đầu ra sử dụng |
| --- | --- | --- |
| Chat, phân tích CV, rerank, hướng dẫn học bù, assistant | `qwen2.5:7b` | Nội dung/JSON theo contract của từng nghiệp vụ |
| Sinh câu hỏi đánh giá theo JD (`ASSESSMENT_GENERATE`) | `qwen3.5:4b` | 10 câu trắc nghiệm JSON, dùng cấu hình riêng cho assessment |
| Embedding CV, job và RAG | `qwen3-embedding:0.6b` | Vector **1024 chiều** |

[Qwen2.5 7B](https://ollama.com/library/qwen2.5:7b) cung cấp model Q4_K_M khoảng 4,7 GB.
[Bản 1.5B](https://ollama.com/library/qwen2.5:1.5b) khoảng 986 MB là lựa chọn để thử trên máy ít tài nguyên;
khả năng tuân thủ JSON và chất lượng cần kiểm tra lại khi đổi model. Hai bản chat dùng giấy phép Apache 2.0
được ghi trên trang model. [Qwen3 Embedding 0.6B](https://ollama.com/library/qwen3-embedding:0.6b) có gói Ollama
khoảng 639 MB; xem [repository và model card Qwen](https://github.com/QwenLM/Qwen3-Embedding) khi lập hồ sơ
model/giấy phép. Kích thước weights không phải mức RAM/VRAM tối đa khi chạy; context và số request đồng thời
cũng dùng bộ nhớ. Các số trên là metadata model, không phải benchmark đo trên máy dự án.

Assessment dùng model riêng để có thể kiểm tra và điều chỉnh câu hỏi mà không đổi model của CV/chat.
Model [Qwen3.5 4B trên Ollama](https://ollama.com/library/qwen3.5:4b) và
[model card chính thức của Qwen](https://huggingface.co/Qwen/Qwen3.5-4B) công bố giấy phép Apache 2.0.
Model này vẫn chạy bằng weights trong container Ollama của nhóm; chọn model mới chưa chứng minh câu hỏi
hay đáp án đạt chất lượng. Không suy ra chất lượng assessment từ benchmark chung của nhà cung cấp.

Các key cho backend host trong `.env`:

```dotenv
AI_ENABLED=true
AI_PROVIDER=ollama
AI_CHAT_PROVIDER=ollama
AI_EMBEDDING_PROVIDER=ollama
AI_CHAT_MODEL=qwen2.5:7b
AI_ASSESSMENT_MODEL=qwen3.5:4b
AI_ASSESSMENT_THINK=true
AI_ASSESSMENT_MAX_OUTPUT_TOKENS=12000
AI_ASSESSMENT_CONTEXT_LENGTH=16384
AI_REQUEST_TIMEOUT=PT15M
AI_EMBEDDING_MODEL=qwen3-embedding:0.6b
AI_EMBEDDING_DIMENSIONS=1024
OLLAMA_BASE_URL=http://localhost:11434
OLLAMA_PORT=11434
OLLAMA_IMAGE=ollama/ollama:latest
OLLAMA_KEEP_ALIVE=5m
```

Không chép đè `.env` đang dùng hoặc thay credential database/JWT. `AI_ENABLED=false` tiếp tục tắt nghiệp vụ AI.
`AI_ASSESSMENT_MODEL` chỉ chọn model sinh câu hỏi theo JD; `AI_ASSESSMENT_THINK=true` bật thinking
cho lời gọi này qua tùy chọn native Ollama. Context riêng `AI_ASSESSMENT_CONTEXT_LENGTH=16384` và ngân sách
`AI_ASSESSMENT_MAX_OUTPUT_TOKENS=12000` dành cho
phần suy luận và JSON kết quả. Thinking cần thêm token và thời gian; timeout phải đủ cho model/máy thực tế,
chưa có cam kết latency. Các luồng chat/CV khác giữ `AI_CHAT_MODEL`, embedding giữ
model và dimension riêng. Sau khi đổi cấu hình, tải model tương ứng và khởi động lại backend.
`AI_REQUEST_TIMEOUT` giới hạn lời gọi HTTP đồng bộ đến Ollama (mặc định 15 phút, tối đa 20 phút),
không đổi timeout của các HTTP client khác. Lời gọi model thất bại hoặc timeout không tự chạy lại inference;
người dùng quyết định thử lại sau khi xem trạng thái. Timeout model được trả bằng lỗi 504 với thông báo đã lọc.
FE tạo bài đánh giá qua tác vụ nền và đọc trạng thái ngắn, nên không cần giữ một request mở trong suốt lúc sinh câu hỏi.
Server kiểm tra trích dẫn phải có trong JD, đủ 10 câu và cơ cấu 5 câu nền tảng + 5 câu tình huống trước khi lưu bài.
Model chat có thể thay bằng `qwen2.5:1.5b` trước khi khởi động, rồi chạy lại init và smoke test.
Model embedding phải khớp schema 1024 chiều; đổi sang model khác cần migration và index lại dữ liệu.
Không dùng tên model `:cloud`. Giá/quota/token trong ứng dụng là chính sách nội bộ, không phải chi phí
API inference bên ngoài.

## Backend chạy trên Windows/IDE

Từ thư mục backend chứa `pom.xml`, `.env` và Compose:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/start-ollama.ps1 -ExposeToHost -SmokeTest
```

Script khởi động `ollama`, chờ server healthy, chạy `ollama-init` để pull model chat/assessment/embedding còn thiếu, rồi kiểm tra
inference. Nó không build hoặc khởi động backend/PostgreSQL. Model được giữ trong volume `ollama_models`;
không cần tải lại toàn bộ weights khi tạo lại container. `-ExposeToHost` ghép `compose.ollama-host.yaml`,
chỉ bind `127.0.0.1:${OLLAMA_PORT}:11434`. Backend trên host dùng `localhost` và port đó.
Nếu đổi `OLLAMA_PORT`, cũng sửa `OLLAMA_BASE_URL` cho backend host.

Không cần publish cổng khi app chỉ chạy trong Docker:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/start-ollama.ps1
```

Init dùng lại model đã có trong volume, không pull lại khi `ollama show` thành công; có thể khởi động lại
khi offline nếu image và mọi model được cấu hình đã có trên máy. Muốn cập nhật weights, chủ động dùng
`docker compose exec ollama ollama pull <model>` rồi lưu lại digest và kiểm tra/reindex dữ liệu phù hợp.

Nếu volume cũ chỉ có chat và embedding, chuẩn bị thêm model assessment trước khi thử bài JD:

```powershell
docker compose exec ollama ollama pull qwen3.5:4b
docker compose exec ollama ollama show qwen3.5:4b
```

Nếu đặt `AI_ASSESSMENT_MODEL` khác mặc định, thay tên model trong hai lệnh trên. Init của Compose cũng
tải model assessment đã cấu hình; đặt tên model rỗng thì dùng lại model chat chung. Không cần tạo lại
embedding chỉ vì đổi model câu hỏi; model embedding vẫn giữ nguyên. `ollama show` chỉ xác nhận model
đã tải, chưa chứng minh inference hay độ đúng của đáp án.

Lệnh này chỉ kiểm tra server/model đã tải; chưa xác nhận inference. Chạy lại với `-ExposeToHost -SmokeTest`
để kiểm tra bằng HTTP trên host. Bỏ override host khi tạo lại service để quay về topology không publish cổng.

## Backend chạy trong Docker

Chuẩn bị database pgvector trên host theo README, tạo JAR, rồi chạy app:

```powershell
mvn verify
docker compose --profile app --profile optional up -d --build app
docker compose logs -f ollama-init
```

App chờ Redis healthy, Ollama healthy và `ollama-init` thành công. Compose cố định
`OLLAMA_BASE_URL=http://ollama:11434`, provider `ollama` và dimension 1024 trong container;
địa chỉ `localhost` của `.env` host không ghi đè chúng. Healthcheck `ollama list` xác nhận server trả lời,
còn init xác nhận model đã tải; hai bước này chưa chứng minh độ đúng của output nghiệp vụ.
Lần suy luận đầu tiên vẫn có thể tốn thời gian load model. Nếu model pull lỗi, app không qua dependency init.

## GPU NVIDIA tùy chọn

CPU là cấu hình Docker mặc định trong dự án. Dùng `-Gpu` để ghép `compose.ollama-gpu.yaml`, cấp GPU cho
service Ollama bằng `gpus: all`. Theo [Compose specification](https://docs.docker.com/reference/compose-file/services/#gpus),
cần Docker Compose **2.30 trở lên**. Máy Windows cần Docker Desktop dùng WSL 2 backend, WSL kernel và
NVIDIA driver hỗ trợ GPU passthrough; xem [hướng dẫn Docker Desktop](https://docs.docker.com/desktop/features/gpu/).
Trên Linux, chuẩn bị NVIDIA Container Toolkit theo [hướng dẫn Ollama](https://docs.ollama.com/docker).

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/start-ollama.ps1 -ExposeToHost -Gpu -SmokeTest
docker compose exec ollama ollama ps
```

`ollama ps` cho biết model đang ở CPU/GPU hoặc chia tải; việc máy có RTX 3050 Laptop 6 GB không bảo đảm
mọi model và context đều nằm hoàn toàn trong VRAM. Chat `qwen2.5:7b`, assessment `qwen3.5:4b` và embedding
`qwen3-embedding:0.6b` có thể được load theo từng lời gọi; kiểm tra bộ nhớ và latency thực tế.
Nếu Docker báo không có GPU/runtime phù hợp, chạy lại
lệnh trên **không có `-Gpu`** để tạo lại Ollama theo cấu hình CPU, giữ nguyên volume weights.
Script không tự đổi từ GPU sang CPU để kết quả đo không bị hiểu nhầm.

Muốn chạy cả app stack với GPU, ghép override tương tự:

```powershell
docker compose -f compose.yaml -f compose.ollama-gpu.yaml --profile app --profile optional up -d --build app
```

Với production có NVIDIA runtime, ghép `compose.production.yaml` cùng `compose.ollama-gpu.yaml` thay vì
dùng manifest local. Mặc định production cũng không yêu cầu GPU. Sau khi chuyển giữa CPU/GPU, chạy lại
smoke test và ghi rõ thiết bị; chưa được coi là chạy GPU chỉ vì Compose nhận cấu hình.

## Kiểm tra chất lượng bài đánh giá theo JD

Xem [luồng chuẩn bị, làm bài và học từng skill](jd-assessment-missing-skills.md) để thử từ frontend/Postman.
Kiểm tra cấu trúc gồm đúng 10 câu, phần 5 + 5, skill có trong JD, trích dẫn có thật, lựa chọn khác nhau
và câu hỏi không trùng chỉ xác nhận **cấu trúc và phạm vi dữ liệu**. Chúng không xác nhận `correct_option`
đúng về chuyên môn hoặc câu hỏi chỉ có một đáp án hợp lý.

Các lần thử thực tế với `qwen2.5:7b` đã cho thấy câu hỏi có đáp án sai/mơ hồ dù cấu trúc hợp lệ; một lượt
khác bị từ chối vì câu hỏi trùng. Vì vậy assessment được tách cấu hình để thử model/prompt riêng.
Việc đã tải `qwen3.5:4b` chưa phải kết quả nghiệm thu chất lượng model thay thế.

Đánh giá nội dung cần kiểm tra từng câu bằng kiến thức/tài liệu kỹ thuật tương ứng: câu hỏi đo kiến thức
cần cho JD, không chỉ hỏi người dùng nhớ JD viết gì; chỉ một lựa chọn đúng; đáp án và giải thích nhất quán;
không suy diễn năng lực từ câu hỏi sai. Dùng tập JD và đáp án được rà soát độc lập để đo chất lượng trước
khi xem điểm bài AI là bằng chứng đáng tin cậy về skill.

## Smoke test cold/warm và bằng chứng

Khi đã bật host override:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/test-ollama.ps1 -ColdStart
```

Smoke script này kiểm tra model **chat chung và embedding**, không đánh giá bộ câu hỏi assessment riêng.
`-ColdStart` yêu cầu unload hai model đó và xác nhận chúng không còn trong `/api/ps` trước lần inference đầu.
Chạy ngoài lúc có người dùng đang gọi AI. Script thực hiện hai lượt chat JSON và hai lượt embedding;
mỗi chức năng có lượt cold rồi warm liên tiếp. Embedding phải có đúng một vector, 1024 phần tử hữu hạn
và khác vector zero. Bỏ `-ColdStart` để kiểm tra readiness mà không unload; lượt đầu lúc đó ghi `first`,
không tự nhận là cold. Tham số `-TimeoutSeconds` mặc định 600 cho mỗi inference, có thể điều chỉnh cho CPU.

`target/ollama-smoke.json` ghi phiên bản Ollama, tên/digest/size model, thời gian UTC, số chiều,
wall time và `load_duration`/`total_duration` từ API. Không lưu vector hoặc dữ liệu CV thực.
API tham khảo: [chat](https://docs.ollama.com/api/chat), [embed](https://docs.ollama.com/api/embed),
[model đang load](https://docs.ollama.com/api/ps). Đây là **smoke test với dữ liệu tổng hợp**, chưa phải
benchmark p50/p95/throughput, đo RAM/VRAM, đánh giá chất lượng CV/matching hoặc kiểm thử backend/DB.
Chỉ có file kết quả sau lần chạy thành công; việc có script không chứng minh model đã được chạy trên máy.

## Smoke backend JAR → Ollama → database test

Sau khi có JAR đã build trong `target`, Docker đang chạy và model chat chung/embedding đã được tải vào Ollama
với cổng loopback host, chạy tại thư mục backend:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/test-ollama-backend.ps1
```

Script tự chọn JAR khi `target` chỉ có đúng một file `.jar`; nếu có nhiều file, truyền
`-JarPath target/INTELLIGENT_CV_MATCHING_AI_AGENT-0.0.1-SNAPSHOT.jar`. Địa chỉ mặc định là
`-OllamaBaseUrl http://127.0.0.1:11434`; chỉ nhận loopback HTTP/HTTPS. `-ChatModel` và
`-EmbeddingModel` mặc định lần lượt là `qwen2.5:7b` và `qwen3-embedding:0.6b`, phải có sẵn. Timeout mặc định:
`-StartupTimeoutSeconds 180`, `-RequestTimeoutSeconds 600`.

Script tạo PostgreSQL/pgvector container riêng, khởi động JAR bằng cấu hình test và áp dụng Flyway,
tạo guest session/conversation, gọi chat thật với `retrieve=false`, đọc lại hai message và đối chiếu
provider/model, usage và quota trong database. Nó không build JAR, không tải model và không dùng
database ứng dụng. Kết thúc sẽ dừng backend test, xóa container database do script tạo và file secret
tạm. Artifact mặc định là `target/ollama-backend-smoke.json`, đổi được bằng `-OutputFile`; kiểm tra
`passed`, `failed_phase` và `cleanup` để biết kết quả đầy đủ.

Artifact ghi nhận **00:24:47 ngày 22/10/2026 giờ Việt Nam** có `passed=true`: JAR gọi Ollama thật,
guest chat lưu/đọc lại hai message, usage 137 input + 11 output = **148 token**, Flyway version 10,
pgvector 0.8.7; thời gian một request chat là `110687.929 ms`. Backend test đã dừng và database/secret
tạm đã được dọn. Đây là kiểm chứng **chat, lưu hội thoại và tính token**; chưa kiểm chứng embedding
qua backend, RAG/retrieval, matching, chất lượng câu trả lời, throughput hoặc p95.

## Database và index lại vector

Chạy migration mới theo Flyway trên database pgvector đã sao lưu. Dữ liệu vector 1536 chiều trước đây
được giữ riêng để chuyển đổi, còn luồng Ollama dùng vector 1024 chiều. Không sửa migration cũ đã áp dụng,
không cắt/pad vector OpenAI để tái sử dụng và không trộn similarity giữa hai model.

Sau khi backend dùng provider/model mới:

1. Index lại CV đã xác nhận qua API embedding CV đang có.
2. Index/retry job qua `POST /api/v1/admin/jobs/{id}/index`, theo dõi `GET` cùng path.
3. Index lại document/course RAG qua API admin tương ứng; xác nhận status và nguồn retrieval.
4. Thử luồng CV → matching → assistant với nguồn của chính người dùng, kiểm tra provider/model lưu trong DB.

Script PostgreSQL test local dùng `real[]` không xác minh pgvector hoặc HNSW. Dùng database pgvector thật
để kiểm tra migration và semantic retrieval. Chưa thể đánh giá kết quả matching cũ như kết quả model mới
khi dữ liệu chưa được index lại.

## Production và tái lập phiên bản

`compose.production.yaml` chạy app, Ollama nội bộ và init models. PostgreSQL/Redis vẫn là endpoint bên ngoài
như cấu hình production đang có. Không ghép manifest production với `compose.yaml`. Cấp `IMAGE`,
`SERVER_PORT`, cấu hình DB/Redis/JWT và bật AI trong `.env` production trước deployment.

```powershell
docker compose -f compose.production.yaml up -d
docker compose -f compose.production.yaml logs -f ollama-init
```

Model pull ban đầu cần mạng; sau đó inference dùng weights trong volume, với cloud features tắt.
Fallback image là `latest` cho thiết lập ban đầu; pin `OLLAMA_IMAGE` bằng tag/digest đã kiểm tra để tái lập
deployment. Lưu cả model digest từ smoke artifact vì tên/tag model có thể được cập nhật.
Giữ volume `ollama_models` khi restart/deploy. Không dùng `docker compose down -v` nếu muốn giữ weights.

Nếu gặp connection refused, kiểm tra Docker Desktop, service health và URL host/container.
Nếu gặp model not found, xem log `ollama-init` và dung lượng ổ đĩa. Nếu thiếu bộ nhớ hoặc latency quá cao,
thử model chat nhỏ hơn, giảm context/concurrency và đo lại. Nếu báo sai chiều vector, dừng index và sửa
model/schema phù hợp; không bỏ validation. `/actuator/health` của backend không thay smoke inference.
