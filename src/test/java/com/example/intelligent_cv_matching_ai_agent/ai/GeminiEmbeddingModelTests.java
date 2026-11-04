package com.example.intelligent_cv_matching_ai_agent.ai;

import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.GeminiEmbeddingModel;
import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.GeminiTokenCounter;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GeminiEmbeddingModelTests {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String MODEL = "gemini-embedding-001";

    @Test
    void nativeRequestUsesTheModelDimensionsAndKeyHeaderAndPreservesUsage() throws Exception {
        AtomicReference<CapturedRequest> captured = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        GeminiTokenCounter counter = mock(GeminiTokenCounter.class);
        HttpServer server = server(200, responseBody(7), captured, calls);
        server.start();
        try {
            var response = model(server, Duration.ofSeconds(3), counter).call(request("Java backend"));

            assertEquals(1024, response.getResult().getOutput().length);
            assertEquals(7, response.getMetadata().getUsage().getTotalTokens());
            assertEquals(1, calls.get());
            CapturedRequest request = captured.get();
            assertEquals("POST", request.method());
            assertEquals("/v1beta/models/gemini-embedding-001:embedContent", request.path());
            assertNull(request.query());
            assertEquals("embedding-test-key", request.keyHeader());
            assertEquals("models/gemini-embedding-001", request.body().path("model").asText());
            assertEquals(1024, request.body().path("outputDimensionality").asInt());
            assertEquals("Java backend", request.body().path("content").path("parts").get(0).path("text").asText());
            verifyNoInteractions(counter);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void missingNativeUsageIsCountedOnlyAfterTheEmbeddingResponse() throws Exception {
        AtomicReference<CapturedRequest> captured = new AtomicReference<>();
        GeminiTokenCounter counter = mock(GeminiTokenCounter.class);
        when(counter.countText(MODEL, "Java backend")).thenAnswer(call -> {
            assertNotNull(captured.get(), "The embedding request must run before fallback token counting");
            return 9;
        });
        HttpServer server = server(200, responseBody(null), captured, new AtomicInteger());
        server.start();
        try {
            var response = model(server, Duration.ofSeconds(3), counter).call(request("Java backend"));

            assertEquals(1024, response.getResult().getOutput().length);
            assertEquals(9, response.getMetadata().getUsage().getTotalTokens());
            verify(counter).countText(MODEL, "Java backend");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void emptyNativeUsageAlsoUsesTheCounter() throws Exception {
        GeminiTokenCounter counter = mock(GeminiTokenCounter.class);
        when(counter.countText(MODEL, "Java backend")).thenReturn(9);
        HttpServer server = server(200, responseBody(0), new AtomicReference<>(), new AtomicInteger());
        server.start();
        try {
            var response = model(server, Duration.ofSeconds(3), counter).call(request("Java backend"));

            assertEquals(9, response.getMetadata().getUsage().getTotalTokens());
            verify(counter).countText(MODEL, "Java backend");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void providerFailureIsNotAutomaticallyRetriedOrCounted() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        GeminiTokenCounter counter = mock(GeminiTokenCounter.class);
        HttpServer server =
            server(503, "{\"error\":{\"message\":\"Synthetic outage\"}}", new AtomicReference<>(), calls);
        server.start();
        try {
            GeminiEmbeddingModel embedding = model(server, Duration.ofSeconds(3), counter);

            assertThrows(RuntimeException.class, () -> embedding.call(request("Java backend")));

            assertEquals(1, calls.get());
            verifyNoInteractions(counter);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void timeoutBoundsTheNativeEmbeddingRequestWithoutRetrying() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        GeminiTokenCounter counter = mock(GeminiTokenCounter.class);
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
            GeminiEmbeddingModel embedding = model(server, Duration.ofMillis(200), counter);
            long started = System.nanoTime();

            assertThrows(RuntimeException.class, () -> embedding.call(request("Java backend")));

            assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(4)) < 0);
            assertEquals(1, calls.get());
            verifyNoInteractions(counter);
        } finally {
            release.countDown();
            server.stop(0);
            executor.shutdownNow();
        }
    }

    private GeminiEmbeddingModel model(
        HttpServer server,
        Duration timeout,
        GeminiTokenCounter counter) {
        return new GeminiEmbeddingModel(
            "http://127.0.0.1:" + server.getAddress().getPort() + "/v1beta",
            "embedding-test-key",
            timeout,
            MODEL,
            1024,
            counter
        );
    }

    private EmbeddingRequest request(
        String text) {
        return new EmbeddingRequest(
            List.of(text),
            OpenAiEmbeddingOptions.builder().model(MODEL).dimensions(1024).build()
        );
    }

    private String responseBody(
        Integer tokenCount) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("embedding", Map.of("values", Collections.nCopies(1024, 0.1)));
        if (tokenCount != null) {
            body.put("usageMetadata", Map.of("promptTokenCount", tokenCount));
        }
        return JSON.writeValueAsString(body);
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
