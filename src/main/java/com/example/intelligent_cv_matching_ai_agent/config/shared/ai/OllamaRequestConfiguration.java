package com.example.intelligent_cv_matching_ai_agent.config.shared.ai;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Bounds local inference calls without changing unrelated application HTTP clients. */
@Configuration(proxyBeanMethods = false)
public class OllamaRequestConfiguration {

    @Bean
    public OllamaApi ollamaApi(
        @Value("${spring.ai.ollama.base-url:http://localhost:11434}")
        String baseUrl,
        @Value("${app.ai.request-timeout:PT15M}")
        String requestTimeout) {
        Duration timeout = Duration.parse(requestTimeout);
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(20)) > 0) {
            throw new IllegalArgumentException("Ollama request timeout must be positive and at most PT20M");
        }
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(timeout);
        return OllamaApi.builder().baseUrl(baseUrl).restClientBuilder(RestClient.builder().requestFactory(factory))
            .responseErrorHandler(RetryUtils.DEFAULT_RESPONSE_ERROR_HANDLER).build();
    }

    @Bean
    public RetryTemplate ollamaRetryTemplate() {
        // A timed-out generation may still have run. Do not silently generate and charge again.
        return new RetryTemplate(RetryPolicy.withMaxRetries(0));
    }
}
