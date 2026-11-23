package com.example.intelligent_cv_matching_ai_agent.util.candidate.jobs;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.JobSalaryParser;
import com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.MatchingSalaryPolicy;
import com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.SalaryExchangeRates;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** A monthly salary preference with the snapshotted administrator tolerance. */
public record SalaryExpectation(
    BigDecimal amount,
    String currency,
    MatchingSalaryPolicy settings,
    SalaryExchangeRates exchangeRates
) {

    public static final String POLICY_VERSION = MatchingSalaryPolicy.POLICY_VERSION;

    public SalaryExpectation(
        BigDecimal amount,
        String currency,
        MatchingSalaryPolicy settings) {
        this(amount, currency, settings, SalaryExchangeRates.unavailable());
    }

    public SalaryExpectation(
        BigDecimal amount,
        String currency) {
        this(amount, currency, MatchingSalaryPolicy.legacyDefault());
    }

    public static Optional<SalaryExpectation> fromProfile(
        Map<String, Object> profile) {
        return fromProfile(profile, MatchingSalaryPolicy.legacyDefault());
    }

    public static Optional<SalaryExpectation> fromProfile(
        Map<String, Object> profile,
        MatchingSalaryPolicy settings) {
        return fromProfile(profile, settings, SalaryExchangeRates.unavailable());
    }

    public static Optional<SalaryExpectation> fromProfile(
        Map<String, Object> profile,
        MatchingSalaryPolicy settings,
        SalaryExchangeRates exchangeRates) {
        Object raw = profile.get("expected_salary");
        if (raw == null)
            return Optional.empty();
        try {
            BigDecimal amount = new BigDecimal(raw.toString());
            String currency =
                Optional.ofNullable(profile.get("salary_currency")).orElse("VND").toString().toUpperCase(Locale.ROOT);
            if (amount.signum() <= 0 || !Set.of("VND", "USD", "EUR").contains(currency))
                throw new IllegalArgumentException();
            return Optional.of(new SalaryExpectation(amount, currency, settings, exchangeRates));
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("Set a positive monthly expectedSalary and a supported salaryCurrency");
        }
    }

    public BigDecimal minimum() {
        return amount.multiply(settings.minimumRatio());
    }

    public Map<String, Object> policy() {
        return policy(false);
    }

    public Map<String, Object> rangePolicy() {
        return policy(true);
    }

    private BigDecimal maximum() {
        return amount.add(amount.subtract(minimum()));
    }

    private Map<String, Object> policy(
        boolean bounded) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("active", true);
        result.put("policy_version", POLICY_VERSION);
        result.put("expected_salary", amount);
        result.put("currency", currency);
        result.put("period", "MONTH");
        result.put("minimum_acceptable", minimum());
        if (bounded)
            result.put("maximum_acceptable", maximum());
        result.put("comparison", bounded ? "OVERLAPS_CONFIGURED_RANGE" : "AT_LEAST_CONFIGURED_MINIMUM");
        result.put("tolerance_percent", settings.tolerancePercent());
        result.put("minimum_ratio", settings.minimumRatio());
        result.put("settings_version", settings.version());
        result.put("reference", "CANDIDATE_EXPECTED_SALARY");
        result.put("unknown_salary_policy", "INCLUDE_UNVERIFIED");
        result.put("currency_policy", "CONVERT_WHEN_AVAILABLE");
        result.put("exchange_rate_source", exchangeRates.source());
        result.put("exchange_rate_as_of", exchangeRates.asOf());
        return result;
    }

    public Optional<Map<String, Object>> match(
        String salaryText) {
        return match(salaryText, false);
    }

    public Optional<Map<String, Object>> matchRange(
        String salaryText) {
        return match(salaryText, true);
    }

    private Optional<Map<String, Object>> match(
        String salaryText,
        boolean bounded) {
        var parsed = JobSalaryParser.parseMonthly(salaryText);
        if (parsed.isEmpty()) {
            return Optional.of(unverified("SALARY_UNDISCLOSED_OR_UNPARSABLE", bounded));
        }
        var salary = parsed.get();
        Optional<BigDecimal> conversion = exchangeRates.rate(salary.currency(), currency);
        if (conversion.isEmpty()) {
            var result = unverified("EXCHANGE_RATE_UNAVAILABLE", bounded);
            result.put("job_currency", salary.currency());
            result.put("job_salary_min", salary.minimum());
            result.put("job_salary_max", salary.maximum());
            return Optional.of(result);
        }
        BigDecimal rate = conversion.get();
        BigDecimal lower =
            salary.minimum() == null ? null : exchangeRates.convert(salary.minimum(), salary.currency(), currency);
        BigDecimal upper =
            salary.maximum() == null ? null : exchangeRates.convert(salary.maximum(), salary.currency(), currency);
        if (salary.maximum() != null
            && exchangeRates.compare(salary.maximum(), salary.currency(), minimum(), currency) < 0) {
            return Optional.empty();
        }
        if (bounded && salary.minimum() != null
            && exchangeRates.compare(salary.minimum(), salary.currency(), maximum(), currency) > 0) {
            return Optional.empty();
        }
        BigDecimal closest = lower == null ? amount : amount.max(lower);
        closest = upper == null ? closest : closest.min(upper);
        BigDecimal difference =
            closest.subtract(amount).abs().multiply(new BigDecimal("100")).divide(amount, 6, RoundingMode.HALF_UP);
        Map<String, Object> result = new LinkedHashMap<>(policy(bounded));
        result.put("status", "VERIFIED");
        result.put("verified", true);
        result.put("conversion_applied", !currency.equals(salary.currency()));
        result.put("job_currency", salary.currency());
        result.put("job_salary_min", salary.minimum());
        result.put("job_salary_max", salary.maximum());
        result.put("comparison_salary_min", lower);
        result.put("comparison_salary_max", upper);
        result.put("conversion_rate", rate);
        if (!currency.equals(salary.currency())) {
            result.put("conversion_source", exchangeRates.source());
            result.put("conversion_as_of", exchangeRates.asOf());
        }
        result.put("closest_offer", closest);
        result.put("difference_percent", difference);
        result.put(
            "shortfall_percent",
            amount.subtract(closest).max(BigDecimal.ZERO).multiply(new BigDecimal("100"))
                .divide(amount, 6, RoundingMode.HALF_UP)
        );
        return Optional.of(result);
    }

    private Map<String, Object> unverified(
        String reason,
        boolean bounded) {
        Map<String, Object> result = new LinkedHashMap<>(policy(bounded));
        result.put("status", "UNVERIFIED");
        result.put("verified", false);
        result.put("reason", reason);
        result.put("conversion_applied", false);
        return result;
    }
}
