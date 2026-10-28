package com.example.intelligent_cv_matching_ai_agent.util.shared.jobs;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** Immutable rates from one dated feed; never substitutes invented or stale rates. */
public record SalaryExchangeRates(
    Map<String, BigDecimal> rates,
    Instant asOf,
    String source
) {

    public SalaryExchangeRates {
        rates = java.util.Collections.unmodifiableMap(new TreeMap<>(rates));
    }

    public static SalaryExchangeRates unavailable() {
        return new SalaryExchangeRates(Map.of(), null, null);
    }

    public Optional<BigDecimal> rate(
        String from,
        String to) {
        if (from.equals(to)) {
            return Optional.of(BigDecimal.ONE);
        }
        BigDecimal origin = rates.get(from);
        BigDecimal target = rates.get(to);
        if (origin == null || target == null || origin.signum() <= 0 || target.signum() <= 0) {
            return Optional.empty();
        }
        return Optional.of(target.divide(origin, MathContext.DECIMAL128));
    }

    public BigDecimal convert(
        BigDecimal amount,
        String from,
        String to) {
        if (from.equals(to)) {
            return amount;
        }
        return amount.multiply(rates.get(to)).divide(rates.get(from), MathContext.DECIMAL128);
    }

    /** Cross multiplication keeps exact inclusive floor decisions independent of display rounding. */
    public int compare(
        BigDecimal amount,
        String from,
        BigDecimal threshold,
        String to) {
        if (from.equals(to)) {
            return amount.compareTo(threshold);
        }
        return amount.multiply(rates.get(to)).compareTo(threshold.multiply(rates.get(from)));
    }
}
