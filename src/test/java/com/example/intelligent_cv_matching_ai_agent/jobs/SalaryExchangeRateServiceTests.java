package com.example.intelligent_cv_matching_ai_agent.jobs;

import com.example.intelligent_cv_matching_ai_agent.service.shared.jobs.SalaryExchangeRateService;
import com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.SalaryExchangeRates;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Uses a loopback public-feed fixture; never contacts a currency provider or an AI API. */
class SalaryExchangeRateServiceTests {

    private final JsonMapper mapper = JsonMapper.builder().build();

    private final AtomicInteger requests = new AtomicInteger();

    private final AtomicInteger status = new AtomicInteger(200);

    private final AtomicReference<String> response = new AtomicReference<>();

    private HttpServer server;

    @BeforeEach
    void startLocalFeed() throws IOException {
        response.set(mapper.writeValueAsString(validFeed()));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rates", exchange -> {
            requests.incrementAndGet();
            byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    @AfterEach
    void stopLocalFeed() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void cachesOneDatedSnapshotAndConvertsCrossRatesWithoutAnotherRequest() {
        var service = service(true);
        var first = service.snapshot();
        assertDecimal("25000", first.rate("USD", "VND").orElseThrow());
        assertDecimal("31250", first.rate("EUR", "VND").orElseThrow());
        assertDecimal("0.00004", first.rate("VND", "USD").orElseThrow());
        assertDecimal("1", first.rate("USD", "USD").orElseThrow());
        assertEquals("https://www.exchangerate-api.com", first.source());
        assertNotNull(first.asOf());
        assertSame(first, service.snapshot());
        assertEquals(1, requests.get());
        assertThrows(UnsupportedOperationException.class, () -> first.rates().put("VND", BigDecimal.ONE));
    }

    @Test
    void failedFirstFetchReturnsUnavailableRatesAndRetriesOnlyAfterTheCooldown() {
        status.set(503);
        var service = service(true);
        assertUnavailable(service.snapshot());
        assertUnavailable(service.snapshot());
        assertEquals(1, requests.get());
        ReflectionTestUtils.setField(service, "nextAttempt", Instant.EPOCH);
        status.set(200);
        assertDecimal("25000", service.snapshot().rate("USD", "VND").orElseThrow());
        assertEquals(2, requests.get());
    }

    @Test
    void transientRefreshFailurePreservesTheLastStillValidSnapshot() {
        var service = service(true);
        var cached = service.snapshot();
        ReflectionTestUtils.setField(service, "nextAttempt", Instant.EPOCH);
        status.set(503);
        assertSame(cached, service.snapshot());
        assertSame(cached, service.snapshot());
        assertEquals(2, requests.get());
    }

    @Test
    void invalidRefreshedBaseRateAlsoPreservesTheLastStillValidSnapshot() {
        var service = service(true);
        var cached = service.snapshot();
        var invalid = validFeed();
        invalid.put("rates", Map.of("USD", 2, "VND", 25000));
        response.set(mapper.writeValueAsString(invalid));
        ReflectionTestUtils.setField(service, "nextAttempt", Instant.EPOCH);
        assertSame(cached, service.snapshot());
        assertEquals(2, requests.get());
    }

    @Test
    void expiredCachedRatesAreNotReusedWhenTheFeedIsUnavailable() {
        var service = service(true);
        service.snapshot();
        ReflectionTestUtils.setField(service, "validUntil", Instant.EPOCH);
        status.set(503);
        assertUnavailable(service.snapshot());
        assertEquals(1, requests.get());
    }

    @Test
    void disabledServiceDoesNotContactTheFeed() {
        assertUnavailable(service(false).snapshot());
        assertEquals(0, requests.get());
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
            "STALE",
            "FUTURE",
            "WRONG_BASE",
            "WRONG_RESULT",
            "BACKWARDS_UPDATE",
            "ZERO_BASE",
            "NEGATIVE_BASE",
            "NON_NUMERIC",
            "MISSING_TIME",
            "MALFORMED_JSON",
            "OVERSIZED_BODY"}
    )
    void rejectsStaleAndInvalidFeedsWithoutInventingReplacementRates(
        String scenario) {
        var feed = validFeed();
        switch (scenario) {
            case "STALE" -> feed.put("time_last_update_unix", Instant.now().minus(Duration.ofDays(4)).getEpochSecond());
            case "FUTURE" ->
                feed.put("time_last_update_unix", Instant.now().plus(Duration.ofHours(1)).getEpochSecond());
            case "WRONG_BASE" -> feed.put("base_code", "EUR");
            case "WRONG_RESULT" -> feed.put("result", "error");
            case "BACKWARDS_UPDATE" -> feed.put("time_next_update_unix", feed.get("time_last_update_unix"));
            case "ZERO_BASE" -> feed.put("rates", Map.of("USD", 0, "VND", 25000));
            case "NEGATIVE_BASE" -> feed.put("rates", Map.of("USD", -1, "VND", 25000));
            case "NON_NUMERIC" -> feed.put("rates", Map.of("USD", 1, "VND", "invalid"));
            case "MISSING_TIME" -> feed.remove("time_last_update_unix");
            default -> {
                // Raw malformed/oversized payloads are selected below.
            }
        }
        response.set(switch (scenario) {
            case "MALFORMED_JSON" -> "{broken-json";
            case "OVERSIZED_BODY" -> " ".repeat(100001);
            default -> mapper.writeValueAsString(feed);
        });
        var service = service(true);
        assertUnavailable(service.snapshot());
        assertUnavailable(service.snapshot());
        assertEquals(1, requests.get());
    }

    @Test
    void missingOrNonPositiveTargetRatesDoNotProduceAConversion() {
        var feed = validFeed();
        feed.put("rates", Map.of("USD", 1, "VND", -25000, "EUR", 0));
        response.set(mapper.writeValueAsString(feed));
        var rates = service(true).snapshot();
        assertTrue(rates.rate("USD", "VND").isEmpty());
        assertTrue(rates.rate("USD", "EUR").isEmpty());
        assertTrue(rates.rate("USD", "SGD").isEmpty());
    }

    private SalaryExchangeRateService service(
        boolean enabled) {
        return new SalaryExchangeRateService(
            enabled,
            "http://127.0.0.1:" + server.getAddress().getPort() + "/rates",
            mapper
        );
    }

    private static Map<String, Object> validFeed() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("result", "success");
        data.put("base_code", "USD");
        data.put("time_last_update_unix", Instant.now().minusSeconds(60).getEpochSecond());
        data.put("time_next_update_unix", Instant.now().plus(Duration.ofDays(1)).getEpochSecond());
        data.put("rates", Map.of("USD", 1, "VND", 25000, "EUR", new BigDecimal("0.8")));
        return data;
    }

    private static void assertUnavailable(
        SalaryExchangeRates rates) {
        assertTrue(rates.rate("USD", "VND").isEmpty());
        assertTrue(rates.rate("EUR", "VND").isEmpty());
    }

    private static void assertDecimal(
        String expected,
        BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
}
