package com.example.intelligent_cv_matching_ai_agent.util.candidate.jobs;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.JobSalaryParser;
import com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.MatchingSalaryPolicy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** A monthly preference with the snapshotted administrator floor and no upper ceiling. */
public record SalaryExpectation(
    BigDecimal amount,
    String currency,
    MatchingSalaryPolicy settings
) {

    public static final String POLICY_VERSION = MatchingSalaryPolicy.POLICY_VERSION;

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
        Object raw = profile.get("expected_salary");
        if (raw == null)
            return Optional.empty();
        try {
            BigDecimal amount = new BigDecimal(raw.toString());
            String currency =
                Optional.ofNullable(profile.get("salary_currency")).orElse("VND").toString().toUpperCase(Locale.ROOT);
            if (amount.signum() <= 0 || !Set.of("VND", "USD", "EUR").contains(currency))
                throw new IllegalArgumentException();
            return Optional.of(new SalaryExpectation(amount, currency, settings));
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("Set a positive monthly expectedSalary and a supported salaryCurrency");
        }
    }

    public BigDecimal minimum() {
        return amount.multiply(settings.minimumRatio());
    }

    public Map<String, Object> policy() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("active", true);
        result.put("policy_version", POLICY_VERSION);
        result.put("expected_salary", amount);
        result.put("currency", currency);
        result.put("period", "MONTH");
        result.put("minimum_acceptable", minimum());
        result.put("comparison", "AT_LEAST_CONFIGURED_MINIMUM");
        result.put("tolerance_percent", settings.tolerancePercent());
        result.put("minimum_ratio", settings.minimumRatio());
        result.put("settings_version", settings.version());
        result.put("reference", "CANDIDATE_EXPECTED_SALARY");
        result.put("unknown_salary_policy", "EXCLUDE");
        result.put("currency_policy", "SAME_CURRENCY");
        return result;
    }

    public Optional<Map<String, Object>> match(
        String salaryText) {
        return JobSalaryParser.parseMonthly(salaryText).filter(salary -> currency.equals(salary.currency()))
            .filter(salary -> salary.maximum().compareTo(minimum()) >= 0).map(salary -> {
                BigDecimal closest = amount.max(salary.minimum()).min(salary.maximum());
                BigDecimal difference = closest.subtract(amount).abs().multiply(new BigDecimal("100"))
                    .divide(amount, 6, RoundingMode.HALF_UP);
                Map<String, Object> result = new LinkedHashMap<>(policy());
                result.put("job_salary_min", salary.minimum());
                result.put("job_salary_max", salary.maximum());
                result.put("closest_offer", closest);
                result.put("difference_percent", difference);
                result.put(
                    "shortfall_percent",
                    amount.subtract(closest).max(BigDecimal.ZERO).multiply(new BigDecimal("100"))
                        .divide(amount, 6, RoundingMode.HALF_UP)
                );
                return result;
            });
    }
}
