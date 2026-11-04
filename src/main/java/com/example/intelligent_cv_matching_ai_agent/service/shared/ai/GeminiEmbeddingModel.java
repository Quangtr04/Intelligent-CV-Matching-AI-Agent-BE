package com.example.intelligent_cv_matching_ai_agent.service.shared.ai;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Native Gemini embeddings preserve dimensions and usage omitted by the compatible endpoint. */
@Component
@ConditionalOnProperty(name = "app.ai.embedding-provider", havingValue = "gemini", matchIfMissing = true)
public class GeminiEmbeddingModel implements EmbeddingModel {

    private final RestClient client;

    private final String defaultModel;

    private final int dimensions;

    private final GeminiTokenCounter counter;

    public GeminiEmbeddingModel(
        @Value("${app.ai.gemini-native-base-url:https://generativelanguage.googleapis.com/v1beta}")
        String nativeBaseUrl,
        @Value("${spring.ai.openai.api-key:}")
        String apiKey,
        @Value("${app.ai.request-timeout:PT2M}")
        Duration timeout,
        @Value("${app.ai.embedding-model:gemini-embedding-001}")
        String defaultModel,
        @Value("${app.ai.embedding-dimensions:1024}")
        int dimensions,
        GeminiTokenCounter counter) {
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofMinutes(20)) > 0) {
            throw new IllegalArgumentException("Gemini request timeout must be positive and at most PT20M");
        }
        if (dimensions != 1024) {
            throw new IllegalArgumentException("Gemini embeddings must match vector(1024)");
        }
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(timeout);
        this.client = RestClient.builder().baseUrl(nativeBaseUrl).defaultHeader("x-goog-api-key", apiKey)
            .requestFactory(factory).build();
        this.defaultModel = defaultModel;
        this.dimensions = dimensions;
        this.counter = counter;
    }

    @Override
    public EmbeddingResponse call(
        EmbeddingRequest request) {
        String selected = request.getOptions() == null || request.getOptions().getModel() == null
            ? defaultModel
            : request.getOptions().getModel();
        String model = selected.startsWith("models/") ? selected.substring(7) : selected;
        if (!model.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,149}")) {
            throw new IllegalArgumentException("Invalid Gemini model name");
        }
        if (request.getOptions() != null && request.getOptions().getDimensions() != null
            && request.getOptions().getDimensions() != dimensions) {
            throw new IllegalArgumentException("Requested embedding dimensions must match vector(1024)");
        }
        List<Embedding> embeddings = new ArrayList<>();
        int tokens = 0;
        for (String text : request.getInstructions()) {
            Map<?, ?> response = client.post().uri("/models/{model}:embedContent", model)
                .body(
                    Map.of(
                        "model",
                        "models/" + model,
                        "content",
                        Map.of("parts", List.of(Map.of("text", text))),
                        "outputDimensionality",
                        dimensions
                    )
                ).retrieve().body(Map.class);
            if (response == null || !(response.get("embedding") instanceof Map<?, ?> embedding)
                || !(embedding.get("values") instanceof List<?> values) || values.size() != dimensions) {
                throw new IllegalStateException("Gemini returned incompatible embedding dimensions");
            }
            float[] vector = new float[dimensions];
            for (int index = 0; index < values.size(); index++) {
                if (!(values.get(index) instanceof Number value) || !Float.isFinite(value.floatValue())) {
                    throw new IllegalStateException("Gemini returned an invalid embedding value");
                }
                vector[index] = value.floatValue();
            }
            Object rawUsage = response.get("usageMetadata");
            Object rawCount = rawUsage instanceof Map<?, ?> usage ? usage.get("promptTokenCount") : null;
            int count = rawCount instanceof Number number ? number.intValue() : 0;
            if (count < 1) {
                count = counter.countText(model, text);
            }
            tokens = Math.addExact(tokens, count);
            embeddings.add(new Embedding(vector, embeddings.size()));
        }
        return new EmbeddingResponse(
            embeddings,
            new EmbeddingResponseMetadata(model, new DefaultUsage(tokens, 0, tokens))
        );
    }

    @Override
    public float[] embed(
        Document document) {
        return embed(document.getText());
    }

    @Override
    public int dimensions() {
        return dimensions;
    }
}
