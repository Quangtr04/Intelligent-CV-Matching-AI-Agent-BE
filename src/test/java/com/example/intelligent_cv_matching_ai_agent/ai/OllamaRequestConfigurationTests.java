package com.example.intelligent_cv_matching_ai_agent.ai;

import com.example.intelligent_cv_matching_ai_agent.config.shared.ai.OllamaRequestConfiguration;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.ollama.autoconfigure.OllamaApiAutoConfiguration;
import org.springframework.ai.model.ollama.autoconfigure.OllamaChatAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.junit.jupiter.api.Assertions.*;

class OllamaRequestConfigurationTests {

    @Test
    void providerServerErrorDoesNotSilentlyGenerateAgain() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] body = "{\"error\":\"synthetic unavailable model\"}".getBytes(StandardCharsets.UTF_8);
            try (exchange) {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(503, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        try {
            context(server.getAddress().getPort(), "PT5S").run(application -> {
                assertNull(application.getStartupFailure());
                OllamaChatModel chat = application.getBean(OllamaChatModel.class);
                assertThrows(RuntimeException.class, () -> chat.call(new Prompt("Synthetic question")));
                assertEquals(1, calls.get(), "An ambiguous failure must not rerun inference");
            });
        } finally {
            server.stop(0);
        }
    }

    @Test
    void readTimeoutBoundsSlowInferenceWithoutRetrying() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            try (exchange) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        server.start();
        try {
            context(server.getAddress().getPort(), "PT0.1S").run(application -> {
                assertNull(application.getStartupFailure());
                OllamaChatModel chat = application.getBean(OllamaChatModel.class);
                long started = System.nanoTime();
                RuntimeException failure =
                    assertThrows(RuntimeException.class, () -> chat.call(new Prompt("Synthetic slow question")));
                assertNotNull(failure.getCause());
                assertTrue((System.nanoTime() - started) / 1_000_000 < 5000);
                assertEquals(1, calls.get(), "The client must time out once instead of starting another request");
            });
        } finally {
            server.stop(0);
        }
    }

    @Test
    void unboundedAndNonpositiveModelTimeoutsAreRejected() {
        OllamaRequestConfiguration configuration = new OllamaRequestConfiguration();
        for (String timeout : new String[]{"PT0S", "PT-1S", "PT21M"}) {
            assertThrows(
                IllegalArgumentException.class,
                () -> configuration.ollamaApi("http://localhost:11434", timeout)
            );
        }
    }

    private ApplicationContextRunner context(
        int port,
        String timeout) {
        return new ApplicationContextRunner().withConfiguration(
            AutoConfigurations.of(
                ToolCallingAutoConfiguration.class,
                OllamaApiAutoConfiguration.class,
                OllamaChatAutoConfiguration.class
            )
        ).withUserConfiguration(OllamaRequestConfiguration.class).withPropertyValues(
            "spring.ai.model.chat=ollama",
            "spring.ai.ollama.base-url=http://127.0.0.1:" + port,
            "spring.ai.ollama.chat.model=synthetic-fixture",
            "spring.ai.ollama.init.pull-model-strategy=never",
            "app.ai.request-timeout=" + timeout
        );
    }
}
