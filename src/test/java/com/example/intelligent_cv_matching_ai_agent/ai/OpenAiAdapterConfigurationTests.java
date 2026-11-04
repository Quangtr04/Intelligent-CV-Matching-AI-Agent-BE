package com.example.intelligent_cv_matching_ai_agent.ai;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiCommonProperties;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class OpenAiAdapterConfigurationTests {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void applicationConfigurationSendsAuthenticatedJsonChat() throws Exception {
        AtomicReference<CapturedRequest> captured = new AtomicReference<>();
        HttpServer server = server();
        server.createContext("/v1/chat/completions", exchange -> {
            captured.set(capture(exchange));
            respond(
                exchange,
                200,
                "{\"id\":\"chatcmpl-fixture\",\"object\":\"chat.completion\",\"created\":0,"
                    + "\"model\":\"fixture-chat\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"{\\\"ok\\\":true}\"},\"finish_reason\":\"stop\"}],"
                    + "\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":4,\"total_tokens\":16}}"
            );
        });
        server.start();
        try {
            context(server, "PT5S").withPropertyValues("spring.ai.openai.chat.response-format.type=json_object")
                .run(application -> {
                    assertNull(application.getStartupFailure());
                    OpenAiCommonProperties properties = application.getBean(OpenAiCommonProperties.class);
                    assertEquals(Duration.ofSeconds(5), properties.getTimeout());
                    assertEquals(0, properties.getMaxRetries());
                    var response = application.getBean(OpenAiChatModel.class).call(new Prompt("Return JSON"));
                    assertEquals("{\"ok\":true}", response.getResult().getOutput().getText());
                    CapturedRequest request = captured.get();
                    assertNotNull(request);
                    assertEquals("POST", request.method());
                    assertEquals("Bearer adapter-test-key", request.authorization());
                    assertEquals("fixture-chat", request.body().path("model").asText());
                    assertEquals(2000, request.body().path("max_tokens").asInt());
                    assertEquals(0.2, request.body().path("temperature").asDouble());
                    assertEquals("json_object", request.body().path("response_format").path("type").asText());
                });
        } finally {
            server.stop(0);
        }
    }

    @Test
    void compatibleTransportPreservesTheProviderTotalIncludingThinkingUsage() throws Exception {
        AtomicReference<CapturedRequest> captured = new AtomicReference<>();
        HttpServer server = server();
        server.createContext("/v1/chat/completions", exchange -> {
            captured.set(capture(exchange));
            respond(
                exchange,
                200,
                "{\"id\":\"chatcmpl-thinking-fixture\",\"object\":\"chat.completion\",\"created\":0,"
                    + "\"model\":\"gemini-2.5-flash-lite\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"{\\\"answer\\\":\\\"Hello\\\"}\"},\"finish_reason\":\"stop\"}],"
                    + "\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":10,\"total_tokens\":160,"
                    + "\"completion_tokens_details\":{\"reasoning_tokens\":50}}}"
            );
        });
        server.start();
        try {
            context(server, "PT5S").run(application -> {
                assertNull(application.getStartupFailure());
                var options = OpenAiChatOptions.builder().model("gemini-2.5-flash-lite").reasoningEffort("none")
                    .maxRetries(0).build();

                var response = application.getBean(OpenAiChatModel.class).call(new Prompt("Return JSON.", options));

                assertEquals(100, response.getMetadata().getUsage().getPromptTokens());
                assertEquals(10, response.getMetadata().getUsage().getCompletionTokens());
                assertEquals(160, response.getMetadata().getUsage().getTotalTokens());
                assertEquals("none", captured.get().body().path("reasoning_effort").asText());
            });
        } finally {
            server.stop(0);
        }
    }

    @Test
    void providerFailuresDoNotSilentlyRepeatChatRequests() throws Exception {
        AtomicInteger chatCalls = new AtomicInteger();
        HttpServer server = server();
        server.createContext("/v1/chat/completions", exchange -> {
            chatCalls.incrementAndGet();
            capture(exchange);
            respond(exchange, 503, "{\"error\":{\"message\":\"Synthetic outage\",\"type\":\"server_error\"}}");
        });
        server.start();
        try {
            context(server, "PT5S").run(application -> {
                assertNull(application.getStartupFailure());
                assertThrows(
                    RuntimeException.class,
                    () -> application.getBean(OpenAiChatModel.class).call(new Prompt("Synthetic question"))
                );
                assertEquals(1, chatCalls.get(), "A provider failure must not generate and charge again");
            });
        } finally {
            server.stop(0);
        }
    }

    @Test
    void configuredRuntimeTimeoutBoundsSlowChatWithoutRetrying() throws Exception {
        AtomicInteger chatCalls = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        HttpServer server = server();
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/v1/chat/completions", exchange -> slowResponse(exchange, chatCalls, release));
        server.start();
        try {
            context(server, "PT0.5S").run(application -> {
                assertNull(application.getStartupFailure());
                Duration timeout = application.getBean(OpenAiCommonProperties.class).getTimeout();
                OpenAiChatModel chat = application.getBean(OpenAiChatModel.class);
                // AiService explicitly carries the configured timeout in each request's options.
                var chatOptions =
                    ((OpenAiChatOptions) chat.getDefaultOptions()).mutate().timeout(timeout).maxRetries(0).build();
                assertBoundedFailure(() -> chat.call(new Prompt("Synthetic slow question", chatOptions)));
                assertEquals(1, chatCalls.get(), "A timeout must not start another chat request");
            });
        } finally {
            release.countDown();
            server.stop(0);
            executor.shutdownNow();
        }
    }

    private void assertBoundedFailure(
        Runnable request) {
        long started = System.nanoTime();
        assertThrows(RuntimeException.class, request::run);
        assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(4)) < 0);
    }

    private void slowResponse(
        HttpExchange exchange,
        AtomicInteger calls,
        CountDownLatch release)
        throws IOException {
        calls.incrementAndGet();
        capture(exchange);
        try (exchange) {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private ApplicationContextRunner context(
        HttpServer server,
        String timeout) {
        return new ApplicationContextRunner().withInitializer(application -> {
            try {
                var sources =
                    new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"));
                for (var source : sources) {
                    application.getEnvironment().getPropertySources().addLast(source);
                }
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
        })
            .withConfiguration(
                AutoConfigurations.of(ToolCallingAutoConfiguration.class, OpenAiChatAutoConfiguration.class)
            ).withPropertyValues(
                "GEMINI_BASE_URL=http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "GEMINI_API_KEY=adapter-test-key",
                "AI_CHAT_MODEL=fixture-chat",
                "AI_EMBEDDING_MODEL=fixture-embedding",
                "AI_EMBEDDING_DIMENSIONS=1024",
                "AI_MAX_OUTPUT_TOKENS=2000",
                "AI_CHAT_TEMPERATURE=0.2",
                "AI_REQUEST_TIMEOUT=" + timeout
            );
    }

    private HttpServer server() throws IOException {
        return HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    }

    private CapturedRequest capture(
        HttpExchange exchange)
        throws IOException {
        return new CapturedRequest(
            exchange.getRequestMethod(),
            exchange.getRequestHeaders().getFirst("Authorization"),
            JSON.readTree(exchange.getRequestBody().readAllBytes())
        );
    }

    private void respond(
        HttpExchange exchange,
        int status,
        String json)
        throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        try (exchange) {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
        }
    }

    private record CapturedRequest(
        String method,
        String authorization,
        JsonNode body
    ) {
    }
}
