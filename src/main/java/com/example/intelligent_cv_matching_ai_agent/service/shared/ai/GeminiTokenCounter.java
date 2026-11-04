package com.example.intelligent_cv_matching_ai_agent.service.shared.ai;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Uses Google's tokenizer when the compatible embeddings response omits usage. */
@Component
@ConditionalOnProperty(name = "app.ai.provider", havingValue = "gemini", matchIfMissing = true)
public class GeminiTokenCounter {

    private final RestClient client;

    public GeminiTokenCounter(
        @Value("${app.ai.gemini-native-base-url:https://generativelanguage.googleapis.com/v1beta}")
        String nativeBaseUrl,
        @Value("${spring.ai.openai.api-key:}")
        String apiKey,
        @Value("${app.ai.request-timeout:PT2M}")
        Duration timeout) {
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofMinutes(20)) > 0) {
            throw new IllegalArgumentException("Gemini request timeout must be positive and at most PT20M");
        }
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(timeout);
        this.client = RestClient.builder().baseUrl(nativeBaseUrl).defaultHeader("x-goog-api-key", apiKey)
            .requestFactory(factory).build();
    }

    public int countText(
        String model,
        String text) {
        return count(model, Map.of("contents", List.of(Map.of("parts", List.of(Map.of("text", text))))));
    }

    public int countPrompt(
        String model,
        Prompt prompt) {
        List<Map<String, Object>> contents = new ArrayList<>();
        List<Map<String, String>> system = new ArrayList<>();
        for (var message : prompt.getInstructions()) {
            if (message.getMessageType() == MessageType.SYSTEM) {
                system.add(Map.of("text", message.getText()));
            } else {
                contents.add(
                    Map.of(
                        "role",
                        message.getMessageType() == MessageType.ASSISTANT ? "model" : "user",
                        "parts",
                        List.of(Map.of("text", message.getText()))
                    )
                );
            }
        }
        Map<String, Object> request = new java.util.LinkedHashMap<>();
        request.put("model", "models/" + modelName(model));
        request.put("contents", contents);
        if (!system.isEmpty()) {
            request.put("systemInstruction", Map.of("parts", system));
        }
        return count(model, Map.of("generateContentRequest", request));
    }

    private int count(
        String model,
        Map<String, Object> body) {
        Map<?, ?> response =
            client.post().uri("/models/{model}:countTokens", modelName(model)).body(body).retrieve().body(Map.class);
        if (response == null || !(response.get("totalTokens") instanceof Number tokens)) {
            throw new IllegalStateException("Gemini did not return a valid token count");
        }
        try {
            int count = new java.math.BigDecimal(tokens.toString()).intValueExact();
            if (count < 1) {
                throw new ArithmeticException();
            }
            return count;
        } catch (ArithmeticException | NumberFormatException failure) {
            throw new IllegalStateException("Gemini did not return a valid token count");
        }
    }

    private static String modelName(
        String model) {
        String name = model.startsWith("models/") ? model.substring(7) : model;
        if (!name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,149}")) {
            throw new IllegalArgumentException("Invalid Gemini model name");
        }
        return name;
    }
}
