package com.example.intelligent_cv_matching_ai_agent.ai;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.model.ollama.autoconfigure.OllamaApiAutoConfiguration;
import org.springframework.ai.model.ollama.autoconfigure.OllamaChatAutoConfiguration;
import org.springframework.ai.model.ollama.autoconfigure.OllamaEmbeddingAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the real native adapters against a loopback HTTP fixture; no model is downloaded or invoked. */
class OllamaAdapterIntegrationTests {

    private static final String CHAT_MODEL = "qwen2.5:7b";

    private static final String EMBEDDING_MODEL = "qwen3-embedding:0.6b";

    private static final int EMBEDDING_DIMENSIONS = 1024;

    private final JsonMapper mapper = JsonMapper.builder().build();

    private final ConcurrentLinkedQueue<Request> requests = new ConcurrentLinkedQueue<>();

    private HttpServer server;

    @BeforeEach
    void startLoopbackEndpoint() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::respond);
        server.start();
    }

    @AfterEach
    void stopLoopbackEndpoint() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void configuredNativeAdaptersUseChatAndEmbedEndpointsAndPreserveModelResponses() {
        new ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    ToolCallingAutoConfiguration.class,
                    OllamaApiAutoConfiguration.class,
                    OllamaChatAutoConfiguration.class,
                    OllamaEmbeddingAutoConfiguration.class
                )
            )
            .withPropertyValues(
                "spring.ai.model.chat=ollama",
                "spring.ai.model.embedding=ollama",
                "spring.ai.ollama.base-url=http://127.0.0.1:" + server.getAddress().getPort(),
                "spring.ai.ollama.init.pull-model-strategy=never",
                "spring.ai.ollama.chat.model=" + CHAT_MODEL,
                "spring.ai.ollama.chat.temperature=0.2",
                "spring.ai.ollama.embedding.model=" + EMBEDDING_MODEL,
                "spring.ai.ollama.embedding.truncate=false"
            ).run(context -> {
                assertNull(context.getStartupFailure());
                OllamaChatModel chat = context.getBean(OllamaChatModel.class);
                assertEquals(CHAT_MODEL, chat.getOptions().getModel(), "Flat model configuration must bind");
                assertEquals(0.2, chat.getOptions().getTemperature());
                EmbeddingModel embeddings = context.getBean(EmbeddingModel.class);
                var response = chat.call(
                    new Prompt(
                        List.of(
                            new SystemMessage("Return a JSON object containing answer."),
                            new UserMessage("Explain Java backend skills.")
                        ),
                        // OllamaChatOptions defaults a missing runtime model to Mistral; AiService always sets it.
                        OllamaChatOptions.builder().model(CHAT_MODEL).format("json").numPredict(48).build()
                    )
                );
                assertEquals("{\"answer\":\"Java backend skills\"}", response.getResult().getOutput().getText());
                assertEquals(11, response.getMetadata().getUsage().getPromptTokens());
                assertEquals(7, response.getMetadata().getUsage().getCompletionTokens());

                float[] vector = embeddings.embed("Java backend CV");
                assertEquals(EMBEDDING_DIMENSIONS, vector.length);
                assertEquals(0.125f, vector[0]);
                assertEquals(-0.25f, vector[1]);
                assertEquals(0.0f, vector[EMBEDDING_DIMENSIONS - 1]);

                List<Request> captured = new ArrayList<>(requests);
                assertEquals(2, captured.size(), "Model initialization must not download or probe models");
                Request chatRequest = captured.get(0);
                assertEquals("POST", chatRequest.method());
                assertEquals("/api/chat", chatRequest.path());
                assertEquals(CHAT_MODEL, chatRequest.body().path("model").asString());
                assertFalse(chatRequest.body().path("stream").asBoolean());
                assertEquals("json", chatRequest.body().path("format").asString());
                assertEquals(48, chatRequest.body().path("options").path("num_predict").asInt());
                assertEquals("system", chatRequest.body().path("messages").get(0).path("role").asString());
                assertEquals("user", chatRequest.body().path("messages").get(1).path("role").asString());
                assertEquals(
                    "Explain Java backend skills.",
                    chatRequest.body().path("messages").get(1).path("content").asString()
                );

                Request embeddingRequest = captured.get(1);
                assertEquals("POST", embeddingRequest.method());
                assertEquals("/api/embed", embeddingRequest.path());
                assertEquals(EMBEDDING_MODEL, embeddingRequest.body().path("model").asString());
                assertEquals("Java backend CV", embeddingRequest.body().path("input").get(0).asString());
                assertFalse(embeddingRequest.body().path("truncate").asBoolean());
            });
    }

    private void respond(
        HttpExchange exchange)
        throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            JsonNode body = mapper.readTree(exchange.getRequestBody().readAllBytes());
            requests.add(new Request(exchange.getRequestMethod(), path, body));
            Object payload;
            int status = 200;
            if ("/api/chat".equals(path)) {
                payload = Map.of(
                    "model",
                    CHAT_MODEL,
                    "created_at",
                    "2026-01-01T00:00:00Z",
                    "message",
                    Map.of("role", "assistant", "content", "{\"answer\":\"Java backend skills\"}"),
                    "done",
                    true,
                    "done_reason",
                    "stop",
                    "prompt_eval_count",
                    11,
                    "eval_count",
                    7
                );
            } else if ("/api/embed".equals(path)) {
                float[] vector = new float[EMBEDDING_DIMENSIONS];
                vector[0] = 0.125f;
                vector[1] = -0.25f;
                payload = Map.of("model", EMBEDDING_MODEL, "embeddings", List.of(vector), "prompt_eval_count", 5);
            } else {
                status = 404;
                payload = Map.of("error", "Unexpected test endpoint " + path);
            }
            byte[] bytes = mapper.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    private record Request(
        String method,
        String path,
        JsonNode body
    ) {
    }
}
