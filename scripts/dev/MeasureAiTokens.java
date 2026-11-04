import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.ai.AiRepository;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.service.guest.identity.GuestAccess;
import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiUsageRecorder;
import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.GeminiTokenCounter;
import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.GeminiEmbeddingModel;
import com.example.intelligent_cv_matching_ai_agent.service.shared.billing.EntitlementService;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import tools.jackson.databind.json.JsonMapper;

/** Measures application prompts without booting the server or modifying users, quotas or the database. */
public class MeasureAiTokens {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final UUID ACTOR = UUID.fromString("00000000-0000-4000-8000-000000000001");

    private static final Encoding CHAT_ENCODING =
        Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.O200K_BASE);

    private static final Encoding EMBEDDING_ENCODING =
        Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);

    private static final class CaptureFinished extends RuntimeException {
    }

    @SuppressWarnings("unchecked")
    public static void main(
        String[] args)
        throws Exception {
        List<String> arguments = Arrays.asList(args);
        boolean live = arguments.contains("--live");
        boolean countOnly = arguments.contains("--count-only");
        boolean remote = live || countOnly;
        boolean expanded = arguments.contains("--all-variants") || !live;
        Path samples = Path.of(argument(arguments, "--samples", "scripts/ai-token-samples.json"));
        Path output = Path.of(argument(arguments, "--output", "target/ai-token-measurements-gemini"));
        Properties settings = new Properties();
        if (Files.exists(Path.of(".env"))) {
            try (var reader = Files.newBufferedReader(Path.of(".env"), StandardCharsets.UTF_8)) {
                settings.load(reader);
            }
        }
        String key = setting(settings, "GEMINI_API_KEY", "");
        if (remote && key.isBlank()) {
            throw new IllegalStateException(
                "Set GEMINI_API_KEY in .env or the process environment before API measurement."
            );
        }
        String model = setting(settings, "AI_CHAT_MODEL", "gemini-2.5-flash-lite");
        String assessmentModel = setting(settings, "AI_ASSESSMENT_MODEL", model);
        String embeddingModel = setting(settings, "AI_EMBEDDING_MODEL", "gemini-embedding-001");
        String baseUrl =
            setting(settings, "GEMINI_BASE_URL", "https://generativelanguage.googleapis.com/v1beta/openai");
        String nativeBaseUrl =
            setting(settings, "GEMINI_NATIVE_BASE_URL", "https://generativelanguage.googleapis.com/v1beta");
        if (live && "api.openai.com".equalsIgnoreCase(java.net.URI.create(baseUrl).getHost())
            && key.startsWith("AIza")) {
            throw new IllegalArgumentException(
                "The configured key looks like a Google/Gemini key, but the endpoint "
                    + "is OpenAI. Configure a matching provider key before --live."
            );
        }
        Duration timeout = Duration.parse(setting(settings, "AI_REQUEST_TIMEOUT", "PT2M"));
        ChatModel realChat = live
            ? OpenAiChatModel.builder()
                .options(
                    OpenAiChatOptions.builder().apiKey(key).baseUrl(baseUrl).model(model).timeout(timeout).maxRetries(0)
                        .build()
                ).build()
            : null;
        if (LoggerFactory.getLogger(AiService.class) instanceof ch.qos.logback.classic.Logger logger) {
            logger.setLevel(ch.qos.logback.classic.Level.OFF);
        }

        Map<String, Object> fixture = JSON.readValue(Files.readString(samples), Map.class);
        List<Map<String, Object>> scenarios = (List<Map<String, Object>>) fixture.get("scenarios");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> scenario : scenarios) {
            if (!expanded && "expanded".equals(scenario.get("variant"))) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            for (String field : List.of("id", "feature", "label", "variant", "notes")) {
                if (scenario.containsKey(field)) {
                    row.put(field, scenario.get(field));
                }
            }
            Map<String, Object> input = (Map<String, Object>) scenario.get("input");
            String feature = scenario.get("feature").toString();
            GeminiTokenCounter counter = remote ? new GeminiTokenCounter(nativeBaseUrl, key, timeout) {

                @Override
                public int countText(
                    String selectedModel,
                    String text) {
                    try {
                        int tokens = super.countText(selectedModel, text);
                        row.put("gemini_counted_input_tokens", tokens);
                        return tokens;
                    } catch (org.springframework.web.client.RestClientResponseException failure) {
                        row.put("counter_http_status", failure.getStatusCode().value());
                        row.put("counter_error", failure.getResponseBodyAsString().replace(key, "[REDACTED]"));
                        throw failure;
                    } catch (RuntimeException failure) {
                        row.put("counter_exception_type", failure.getClass().getSimpleName());
                        row.put("counter_error", String.valueOf(failure.getMessage()).replace(key, "[REDACTED]"));
                        throw failure;
                    }
                }
            } : null;
            ChatModel recorder = prompt -> {
                capturePrompt(row, prompt);
                if (countOnly && !live) {
                    row.put("gemini_counted_input_tokens", counter.countPrompt(model, prompt));
                    throw new CaptureFinished();
                }
                if (!live) {
                    throw new CaptureFinished();
                }
                ChatResponse response;
                try {
                    response = realChat.call(prompt);
                } catch (com.openai.errors.OpenAIServiceException failure) {
                    row.put("provider_http_status", failure.statusCode());
                    throw failure;
                }
                if (response != null && response.getMetadata() != null && response.getMetadata().getUsage() != null) {
                    var usage = response.getMetadata().getUsage();
                    row.put("actual_input_tokens", usage.getPromptTokens());
                    int inputTokens = usage.getPromptTokens();
                    int outputTokens = usage.getCompletionTokens();
                    int totalTokens = Math.max(usage.getTotalTokens(), inputTokens + outputTokens);
                    row.put("actual_output_tokens", totalTokens - inputTokens);
                    row.put("actual_total_tokens", totalTokens);
                    row.put("usage_source", "gemini_chat_completion_usage");
                    if (usage.getNativeUsage() instanceof com.openai.models.completions.CompletionUsage nativeUsage) {
                        nativeUsage.completionTokensDetails().flatMap(details -> details.reasoningTokens())
                            .ifPresent(tokens -> row.put("reasoning_tokens", tokens));
                    }
                }
                return response;
            };
            EmbeddingModel embeddingRecorder = new EmbeddingModel() {

                @Override
                public EmbeddingResponse call(
                    EmbeddingRequest request) {
                    String text = request.getInstructions().getFirst();
                    row.put("input_content_tokens", EMBEDDING_ENCODING.countTokensOrdinary(text));
                    row.put("tokenizer", "cl100k_base proxy, not Gemini tokenizer");
                    row.put("model", embeddingModel);
                    row.put("max_output_tokens", 0);
                    row.put("total_upper_content_tokens", row.get("input_content_tokens"));
                    if (!live) {
                        throw new CaptureFinished();
                    }
                    EmbeddingResponse response;
                    try {
                        response = new GeminiEmbeddingModel(nativeBaseUrl, key, timeout, embeddingModel, 1024, counter)
                            .call(request);
                    } catch (RuntimeException failure) {
                        row.put("embedding_exception_type", failure.getClass().getSimpleName());
                        row.put("embedding_error", String.valueOf(failure.getMessage()).replace(key, "[REDACTED]"));
                        throw failure;
                    }
                    if (response != null) {
                        int actual = response.getMetadata() != null && response.getMetadata().getUsage() != null
                            ? response.getMetadata().getUsage().getTotalTokens()
                            : 0;
                        row.put(
                            "usage_source",
                            row.containsKey("gemini_counted_input_tokens")
                                ? "gemini_countTokens"
                                : "gemini_native_embedding_usage"
                        );
                        if (actual == 0) {
                            actual = ((Number) row.get("gemini_counted_input_tokens")).intValue();
                        }
                        row.put("actual_input_tokens", actual);
                        row.put("actual_output_tokens", 0);
                        row.put("actual_total_tokens", actual);
                    }
                    return response;
                }

                @Override
                public float[] embed(
                    Document document) {
                    throw new UnsupportedOperationException("This benchmark only embeds explicit sample text.");
                }
            };
            AiService service =
                service(row, recorder, embeddingRecorder, settings, model, assessmentModel, embeddingModel);
            service.configureRequestTimeout(timeout);
            service.configureReasoningEffort(setting(settings, "AI_REASONING_EFFORT", "none"));
            if (counter != null) {
                service.configureGeminiTokenCounter(counter);
            }
            long started = System.nanoTime();
            try {
                if ("EMBEDDING".equals(feature)) {
                    float[] vector = service.embed(input.get("text").toString());
                    double norm = 0;
                    for (float value : vector) {
                        norm += (double) value * value;
                    }
                    row.put("embedding_dimensions", vector.length);
                    row.put("normalized_l2_norm", Math.sqrt(norm));
                } else {
                    service.generate(feature, input);
                }
                row.put("status", "API_RESPONSE_PASSED_SHARED_VALIDATION");
            } catch (ApiException failure) {
                row.put("application_error", failure.getMessage());
                if (countOnly && !live && row.containsKey("gemini_counted_input_tokens")) {
                    row.put("status", "GEMINI_COUNT_TOKENS_ONLY");
                } else if (!remote && row.containsKey("input_content_tokens")) {
                    row.put("status", "OFFLINE_OPENAI_TOKENIZER_PROXY_ONLY");
                } else {
                    row.put(
                        "status",
                        row.containsKey("actual_total_tokens") ? "API_RESPONSE_FAILED_VALIDATION" : "REQUEST_FAILED"
                    );
                    row.put("application_status", failure.status());
                }
            }
            row.put("elapsed_ms", (System.nanoTime() - started) / 1_000_000);
            rows.add(row);
            System.out.println(row.get("id") + ": " + row.get("status"));
            Files.createDirectories(output);
            writeReport(output, fixture, rows, live, countOnly);
        }
        System.out.println("Report: " + output.resolve("measurements.md").toAbsolutePath());
    }

    private static AiService service(
        Map<String, Object> row,
        ChatModel chat,
        EmbeddingModel embedding,
        Properties settings,
        String model,
        String assessmentModel,
        String embeddingModel) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("chat", chat);
        beans.addBean("embedding", embedding);
        AiRepository repository = new AiRepository(null) {

            @Override
            public Optional<Map<String, Object>> findAiConfigurationsByConfigType(
                Object... args) {
                return Optional.empty();
            }

            @Override
            public Optional<Map<String, Object>> findAiAssessmentConfiguration(
                Object... args) {
                return Optional.empty();
            }

            @Override
            public Optional<Map<String, Object>> findAiPromptTemplatesByFeatureCode(
                Object... args) {
                return Optional.empty();
            }
        };
        CurrentUser actor = new CurrentUser() {

            @Override
            public UUID id() {
                return ACTOR;
            }
        };
        EntitlementService quota = new EntitlementService(null) {

            @Override
            public UUID reserve(
                UUID actor,
                String feature,
                int budget) {
                row.put("application_reservation_units", budget);
                return ACTOR;
            }

            @Override
            public void complete(
                UUID reservation,
                int actual) {
                row.put("application_charged_units", actual);
            }

            @Override
            public void cancel(
                UUID reservation) {
                // Benchmark reservations exist only in memory.
            }
        };
        AiUsageRecorder usage = new AiUsageRecorder(null) {

            @Override
            public void record(
                Map<String, Object> fields) {
                // Keep database telemetry untouched; provider counts are captured above before validation.
            }
        };
        return new AiService(
            beans.getBeanProvider(ChatModel.class),
            beans.getBeanProvider(EmbeddingModel.class),
            JSON,
            repository,
            actor,
            quota,
            new GuestAccess(null),
            usage,
            true,
            "gemini",
            model,
            embeddingModel,
            1024,
            integer(settings, "AI_MAX_OUTPUT_TOKENS", 2000),
            integer(settings, "AI_ASSESSMENT_MAX_OUTPUT_TOKENS", 12000),
            integer(settings, "AI_LEARNING_MAX_OUTPUT_TOKENS", 4000),
            assessmentModel,
            Double.parseDouble(setting(settings, "AI_CHAT_TEMPERATURE", "0.2"))
        );
    }

    private static void capturePrompt(
        Map<String, Object> row,
        Prompt prompt) {
        int system = CHAT_ENCODING.countTokensOrdinary(prompt.getInstructions().getFirst().getText());
        int data = CHAT_ENCODING.countTokensOrdinary(prompt.getInstructions().getLast().getText());
        OpenAiChatOptions options = (OpenAiChatOptions) prompt.getOptions();
        row.put("model", options.getModel());
        row.put("tokenizer", "o200k_base proxy, not Gemini tokenizer");
        row.put("system_content_tokens", system);
        row.put("user_data_content_tokens", data);
        row.put("input_content_tokens", system + data);
        row.put("max_output_tokens", options.getMaxTokens());
        row.put("reasoning_effort", options.getReasoningEffort());
        row.put("total_upper_content_tokens", system + data + options.getMaxTokens());
    }

    private static void writeReport(
        Path output,
        Map<String, Object> fixture,
        List<Map<String, Object>> rows,
        boolean live,
        boolean countOnly)
        throws Exception {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("generated_at_utc", Instant.now().toString());
        report.put(
            "mode",
            live
                ? "LIVE_GEMINI_SYNTHETIC_API"
                : countOnly ? "GEMINI_COUNT_TOKENS_API" : "OFFLINE_OPENAI_TOKENIZER_PROXY_FOR_GEMINI"
        );
        report.put("scope", "Actual AiService prompts; synthetic sample data; environment defaults; no DB overrides.");
        report.put(
            "input_count_caveat",
            "Offline input_content_tokens use an OpenAI tokenizer proxy, NOT Gemini's tokenizer. "
                + "Use actual_* for Gemini generation usage or gemini_counted_input_tokens for native countTokens. "
                + "Embeddings lack usage in the compatible response, so input is measured by native countTokens. "
                + "Reasoning tokens, when reported, are already included in actual_output_tokens."
        );
        report.put(
            "validation_scope",
            "Live validation covers AiService's shared JSON/language/support checks, "
                + "not downstream feature persistence, assessment or matching validation."
        );
        report.put("sample_description", fixture.get("description"));
        report.put("measurements", rows);
        Files.writeString(
            output.resolve("measurements.json"),
            JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n",
            StandardCharsets.UTF_8
        );
        StringBuilder markdown = new StringBuilder("# Token measurements\n\n");
        markdown.append("Mode: **").append(report.get("mode")).append("**.\n\n");
        markdown.append(report.get("scope")).append("\n\n").append(report.get("input_count_caveat")).append("\n\n");
        markdown
            .append("| Sample | Feature | Gemini input | Gemini output | Gemini total | Output ceiling | Status |\n");
        markdown.append("|---|---|---:|---:|---:|---:|---|\n");
        for (Map<String, Object> row : rows) {
            markdown.append("| ").append(row.get("id")).append(" | ").append(row.get("feature")).append(" | ")
                .append(row.getOrDefault("actual_input_tokens", row.getOrDefault("gemini_counted_input_tokens", "—")))
                .append(" | ").append(row.getOrDefault("actual_output_tokens", "—")).append(" | ")
                .append(row.getOrDefault("actual_total_tokens", "—")).append(" | ")
                .append(row.getOrDefault("max_output_tokens", "—")).append(" | ").append(row.get("status"))
                .append(" |\n");
        }
        markdown.append("\nOnly actual_* / native countTokens are Gemini counts; output ceilings are not usage.\n\n");
        markdown
            .append("- Matching FULL: one candidate embedding plus one rerank request per batch of up to 3 jobs.\n");
        markdown.append("- Chat retrieval: one extra embedding; cached operations may make no API calls.\n");
        markdown
            .append("- Assessment: one request generates 10 questions; email and cover letter share one request.\n");
        markdown.append("- Upload parsing currently uses rules; CV_PARSE is not an active model call.\n");
        Files.writeString(output.resolve("measurements.md"), markdown, StandardCharsets.UTF_8);
    }

    private static String argument(
        List<String> args,
        String name,
        String fallback) {
        int index = args.indexOf(name);
        return index >= 0 && index + 1 < args.size() ? args.get(index + 1) : fallback;
    }

    private static String setting(
        Properties settings,
        String name,
        String fallback) {
        String value = System.getenv(name);
        return value == null ? settings.getProperty(name, fallback).trim() : value;
    }

    private static int integer(
        Properties settings,
        String name,
        int fallback) {
        return Integer.parseInt(setting(settings, name, Integer.toString(fallback)));
    }
}
