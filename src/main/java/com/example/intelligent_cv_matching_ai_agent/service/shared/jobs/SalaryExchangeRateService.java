package com.example.intelligent_cv_matching_ai_agent.service.shared.jobs;

import com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.SalaryExchangeRates;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.*;

/** A shared daily public feed, with bounded latency and a retry cooldown on failure. */
@Service
public class SalaryExchangeRateService {

    private static final String SOURCE = "https://www.exchangerate-api.com";

    private final boolean enabled;

    private final URI endpoint;

    private final ObjectMapper mapper;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    private SalaryExchangeRates cached = SalaryExchangeRates.unavailable();

    private Instant nextAttempt = Instant.EPOCH;

    private Instant validUntil = Instant.EPOCH;

    public SalaryExchangeRateService(
        @Value("${app.jobs.salary-exchange.enabled:true}")
        boolean enabled,
        @Value("${app.jobs.salary-exchange.url:https://open.er-api.com/v6/latest/USD}")
        String url,
        ObjectMapper mapper) {
        this.enabled = enabled;
        this.endpoint = URI.create(url);
        this.mapper = mapper;
    }

    public synchronized SalaryExchangeRates snapshot() {
        Instant now = Instant.now();
        if (!enabled) {
            return SalaryExchangeRates.unavailable();
        }
        if (now.isBefore(nextAttempt)) {
            return now.isBefore(validUntil) ? cached : SalaryExchangeRates.unavailable();
        }
        nextAttempt = now.plus(Duration.ofMinutes(15));
        try {
            var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(3))
                .header("Accept", "application/json").GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || response.body().length() > 100000) {
                return now.isBefore(validUntil) ? cached : SalaryExchangeRates.unavailable();
            }
            Map<String, Object> data = object(mapper.readValue(response.body(), Map.class));
            long updated = ((Number) data.get("time_last_update_unix")).longValue();
            long next = ((Number) data.get("time_next_update_unix")).longValue();
            Instant asOf = Instant.ofEpochSecond(updated);
            if (!"success".equals(data.get("result")) || !"USD".equals(data.get("base_code"))
                || asOf.isAfter(now.plusSeconds(300)) || asOf.isBefore(now.minus(Duration.ofDays(3)))
                || next <= updated) {
                return now.isBefore(validUntil) ? cached : SalaryExchangeRates.unavailable();
            }
            Map<String, BigDecimal> rates = new LinkedHashMap<>();
            for (var entry : object(data.get("rates")).entrySet()) {
                BigDecimal value = new BigDecimal(entry.getValue().toString());
                if (entry.getKey().matches("[A-Z]{3}") && value.signum() > 0) {
                    rates.put(entry.getKey(), value);
                }
            }
            if (BigDecimal.ONE.compareTo(rates.getOrDefault("USD", BigDecimal.ZERO)) != 0) {
                return now.isBefore(validUntil) ? cached : SalaryExchangeRates.unavailable();
            }
            cached = new SalaryExchangeRates(rates, asOf, SOURCE);
            validUntil = asOf.plus(Duration.ofDays(3));
            Instant advertisedNext = Instant.ofEpochSecond(next);
            nextAttempt = advertisedNext.isAfter(now) ? advertisedNext : now.plus(Duration.ofHours(1));
            if (nextAttempt.isAfter(now.plus(Duration.ofDays(1)))) {
                nextAttempt = now.plus(Duration.ofDays(1));
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (java.io.IOException | RuntimeException ex) {
            // Missing rates leave salary explicitly unverified; recommendations remain available.
        }
        return now.isBefore(validUntil) ? cached : SalaryExchangeRates.unavailable();
    }
}
