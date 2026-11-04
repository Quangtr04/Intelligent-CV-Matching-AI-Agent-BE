package com.example.intelligent_cv_matching_ai_agent.ai;

import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.GeminiTokenCounter;
import com.sun.net.httpserver.HttpServer;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class GeminiTokenCounterTests {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void textUsesTheNativeEndpointAndApiKeyHeaderWithoutAQuerySecret() throws Exception {
        AtomicReference<CapturedRequest> captured = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = server(200, "{\"totalTokens\":7}", captured, calls);
        server.start();
        try {
            int count = counter(server, Duration.ofSeconds(3)).countText("models/gemini-embedding-001", "Java backend");

            assertEquals(7, count);
            assertEquals(1, calls.get());
            CapturedRequest request = captured.get();
            assertEquals("POST", request.method());
            assertEquals("/v1beta/models/gemini-embedding-001:countTokens", request.path());
            assertNull(request.query());
            assertEquals("counter-test-key", request.keyHeader());
            assertEquals(
                "Java backend",
                request.body().path("contents").get(0).path("parts").get(0).path("text").asText()
            );
            assertFalse(request.body().has("generateContentRequest"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void promptSeparatesSystemInstructionsAndPreservesConversationRoles() throws Exception {
        AtomicReference<CapturedRequest> captured = new AtomicReference<>();
        HttpServer server = server(200, "{\"totalTokens\":19}", captured, new AtomicInteger());
        server.start();
        try {
            Prompt prompt = new Prompt(
                List.of(
                    new SystemMessage("Return factual JSON."),
                    new UserMessage("First question"),
                    new AssistantMessage("First answer"),
                    new UserMessage("Follow-up question")
                )
            );

            assertEquals(19, counter(server, Duration.ofSeconds(3)).countPrompt("gemini-2.5-flash-lite", prompt));

            CapturedRequest request = captured.get();
            assertEquals("/v1beta/models/gemini-2.5-flash-lite:countTokens", request.path());
            assertNull(request.query());
            assertEquals("counter-test-key", request.keyHeader());
            JsonNode generation = request.body().path("generateContentRequest");
            assertEquals("models/gemini-2.5-flash-lite", generation.path("model").asText());
            assertEquals(
                "Return factual JSON.",
                generation.path("systemInstruction").path("parts").get(0).path("text").asText()
            );
            JsonNode contents = generation.path("contents");
            assertEquals(3, contents.size());
            assertEquals("user", contents.get(0).path("role").asText());
            assertEquals("model", contents.get(1).path("role").asText());
            assertEquals("user", contents.get(2).path("role").asText());
            assertEquals("Follow-up question", contents.get(2).path("parts").get(0).path("text").asText());
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
            "{}",
            "{\"totalTokens\":null}",
            "{\"totalTokens\":\"7\"}",
            "{\"totalTokens\":0}",
            "{\"totalTokens\":-1}",
            "{\"totalTokens\":2147483648}",
            "{\"totalTokens\":1.5}"}
    )
    void invalidProviderCountsAreRejected(
        String body)
        throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = server(200, body, new AtomicReference<>(), calls);
        server.start();
        try {
            GeminiTokenCounter counter = counter(server, Duration.ofSeconds(3));

            assertThrows(IllegalStateException.class, () -> counter.countText("gemini-embedding-001", "Java"));

            assertEquals(1, calls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void providerFailureDoesNotAutomaticallyRepeatTheCountingRequest() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server =
            server(503, "{\"error\":{\"message\":\"Synthetic outage\"}}", new AtomicReference<>(), calls);
        server.start();
        try {
            GeminiTokenCounter counter = counter(server, Duration.ofSeconds(3));

            assertThrows(RuntimeException.class, () -> counter.countText("gemini-embedding-001", "Java"));

            assertEquals(1, calls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void timeoutBoundsTheCountingRequestWithoutRetrying() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        var executor = Executors.newCachedThreadPool();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            try (exchange) {
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        server.start();
        try {
            GeminiTokenCounter counter = counter(server, Duration.ofMillis(200));
            long started = System.nanoTime();

            assertThrows(RuntimeException.class, () -> counter.countText("gemini-embedding-001", "Java"));

            assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(4)) < 0);
            assertEquals(1, calls.get());
        } finally {
            release.countDown();
            server.stop(0);
            executor.shutdownNow();
        }
    }

    private GeminiTokenCounter counter(
        HttpServer server,
        Duration timeout) {
        return new GeminiTokenCounter(
            "http://127.0.0.1:" + server.getAddress().getPort() + "/v1beta",
            "counter-test-key",
            timeout
        );
    }

    private HttpServer server(
        int status,
        String body,
        AtomicReference<CapturedRequest> captured,
        AtomicInteger calls)
        throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            captured.set(
                new CapturedRequest(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    exchange.getRequestURI().getRawQuery(),
                    exchange.getRequestHeaders().getFirst("x-goog-api-key"),
                    JSON.readTree(exchange.getRequestBody().readAllBytes())
                )
            );
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            try (exchange) {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, response.length);
                exchange.getResponseBody().write(response);
            }
        });
        return server;
    }

    private record CapturedRequest(
        String method,
        String path,
        String query,
        String keyHeader,
        JsonNode body
    ) {
    }
}
