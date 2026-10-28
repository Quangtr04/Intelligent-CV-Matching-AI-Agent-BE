package com.example.intelligent_cv_matching_ai_agent.jobs;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.util.candidate.jobs.SalaryExpectation;
import com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.MatchingSalaryPolicy;
import com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.SalaryExchangeRates;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SalaryExpectationTests {

    @ParameterizedTest
    @ValueSource(
        strings = {
            "6750000 VND",
            "7tr5",
            "8250000 VND",
            "8250001 VND",
            "20 triệu",
            "100 triệu",
            "7 - 10 triệu",
            "8 - 12 triệu",
            "8.25 - 9 triệu",
            "8.250001 - 9 triệu",
            "5 - 6.75 triệu",
            "1 - 100 triệu",
            "6750000 - 6750000 VND"}
    )
    void acceptsOffersAtOrAboveNinetyPercentWithoutAnUpperCap(
        String salaryText) {
        assertTrue(expectation().match(salaryText).isPresent(), salaryText);
    }

    @ParameterizedTest
    @ValueSource(strings = {"6749999 VND", "6 - 6.749999 triệu", "1 - 6 triệu", "5000000 VND"})
    void excludesOnlyKnownOffersBelowTheFloor(
        String salaryText) {
        assertTrue(expectation().match(salaryText).isEmpty(), salaryText);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(
        strings = {
            "$1000-$2000",
            "1000 EUR",
            "7500000",
            "Thỏa thuận",
            "Negotiable",
            "7500000 VND / YEAR",
            "7500000 VND / DAY",
            "7500000 VND / HOUR"}
    )
    void keepsSalariesThatCannotBeComparedExplicitlyUnverified(
        String salaryText) {
        assertUnverified(expectation().match(salaryText).orElseThrow());
    }

    @ParameterizedTest
    @CsvSource(
        value = {
            "6750000 VND|6750000|10",
            "8250000 VND|8250000|10",
            "7 - 10 triệu|7500000|0",
            "6.75 - 7 triệu|7000000|6.666667",
            "7.7 - 8 triệu|7700000|2.666667",
            "20 - 30 triệu|20000000|166.666667",
            "100 triệu|100000000|1233.333333"},
        delimiter = '|'
    )
    void explainsTheClosestAdvertisedOfferRelativeToCandidateExpectation(
        String salaryText,
        String closestOffer,
        String differencePercent) {
        var result = expectation().match(salaryText).orElseThrow();
        assertDecimal(closestOffer, result.get("closest_offer"));
        assertDecimal(differencePercent, result.get("difference_percent"));
        assertDecimal("7500000", result.get("expected_salary"));
    }

    @Test
    void evaluatesTheFloorWithoutFloatingPointOrDisplayRounding() {
        var expected = SalaryExpectation.fromProfile(Map.of("expected_salary", "7500000.000001")).orElseThrow();
        assertDecimal("6750000.00000090", expected.minimum());
        assertTrue(expected.match("6750000.00000090 VND").isPresent());
        assertTrue(expected.match("6750000.00000089 VND").isEmpty());
        assertTrue(expected.match("100000000.12345678 VND").isPresent());
    }

    @Test
    void higherOffersHaveNoShortfallEvenWhenTheirAbsoluteDifferenceExceedsTenPercent() {
        var higher = expectation().match("20 triệu").orElseThrow();
        assertDecimal("166.666667", higher.get("difference_percent"));
        assertDecimal("0", higher.get("shortfall_percent"));
        var floor = expectation().match("6750000 VND").orElseThrow();
        assertDecimal("10", floor.get("shortfall_percent"));
    }

    @Test
    void policyStatesTheCandidateReferenceAndDoesNotAdvertiseAnUpperLimit() {
        var policy = expectation().policy();
        assertEquals(true, policy.get("active"));
        assertEquals(SalaryExpectation.POLICY_VERSION, policy.get("policy_version"));
        assertEquals("VND", policy.get("currency"));
        assertEquals("MONTH", policy.get("period"));
        assertEquals("CANDIDATE_EXPECTED_SALARY", policy.get("reference"));
        assertEquals("AT_LEAST_CONFIGURED_MINIMUM", policy.get("comparison"));
        assertEquals("INCLUDE_UNVERIFIED", policy.get("unknown_salary_policy"));
        assertEquals("CONVERT_WHEN_AVAILABLE", policy.get("currency_policy"));
        assertDecimal("6750000", policy.get("minimum_acceptable"));
        assertFalse(policy.containsKey("maximum_acceptable"));
    }

    @ParameterizedTest
    @CsvSource({"0,7500000,7499999", "20,6000000,5999999", "12.34,6574500,6574499"})
    void appliesConfiguredExactFloorsWithoutAnUpperCap(
        String tolerance,
        String accepted,
        String rejected) {
        var settings = new MatchingSalaryPolicy(new BigDecimal(tolerance), 7, null, null);
        var expected = SalaryExpectation.fromProfile(Map.of("expected_salary", 7500000), settings).orElseThrow();
        assertTrue(expected.match(accepted + " VND/month").isPresent());
        assertTrue(expected.match(rejected + " VND/month").isEmpty());
        assertTrue(expected.match("1000000000 VND/month").isPresent());
        assertEquals(7L, expected.policy().get("settings_version"));
        assertDecimal(tolerance, expected.policy().get("tolerance_percent"));
    }

    @Test
    void oneHundredPercentAllowsKnownPositiveSalaryAndPreservesUnverifiedSalaryLabels() {
        var expected = new SalaryExpectation(
            new BigDecimal("7500000"),
            "VND",
            new MatchingSalaryPolicy(new BigDecimal("100"), 2, null, null)
        );
        assertDecimal("0", expected.minimum());
        assertTrue(expected.match("1 VND/month").isPresent());
        assertUnverified(expected.match("Negotiable").orElseThrow());
        assertUnverified(expected.match("1 USD/month").orElseThrow());
        assertUnverified(expected.match("1 VND/year").orElseThrow());
    }

    @Test
    void omitsTheFilterWhenNoExpectationHasBeenSaved() {
        assertTrue(SalaryExpectation.fromProfile(Map.of()).isEmpty());
        Map<String, Object> profile = new HashMap<>();
        profile.put("expected_salary", null);
        assertTrue(SalaryExpectation.fromProfile(profile).isEmpty());
    }

    @Test
    void defaultsCurrencyToVndAndPreservesTheSingleExpectedAmount() {
        var expected = SalaryExpectation.fromProfile(Map.of("expected_salary", 7500000)).orElseThrow();
        assertDecimal("7500000", expected.amount());
        assertEquals("VND", expected.currency());
    }

    @Test
    void comparesSupportedForeignCurrencyWithoutConvertingIt() {
        var expected =
            SalaryExpectation.fromProfile(Map.of("expected_salary", 1000, "salary_currency", "usd")).orElseThrow();
        assertEquals("USD", expected.currency());
        assertTrue(expected.match("$900 / month").isPresent());
        assertTrue(expected.match("USD 100000 monthly").isPresent());
        assertTrue(expected.match("USD 899.99 monthly").isEmpty());
        assertUnverified(expected.match("EUR 1000 monthly").orElseThrow());
        assertUnverified(expected.match("25000000 VND monthly").orElseThrow());
    }

    @Test
    void comparesTheSameCurrencyWithoutDependingOnExchangeRates() {
        var match = expectation().match("7 - 10 triệu").orElseThrow();
        assertEquals("VERIFIED", match.get("status"));
        assertEquals(true, match.get("verified"));
        assertEquals(false, match.get("conversion_applied"));
        assertDecimal("1", match.get("conversion_rate"));
        assertDecimal("7000000", match.get("comparison_salary_min"));
        assertDecimal("10000000", match.get("comparison_salary_max"));
    }

    @Test
    void convertsDollarOffersToVndAndExplainsTheOriginalAndComparedAmounts() {
        var expected = withRates("7500000", "VND");
        var match = expected.match("USD 300 - 500").orElseThrow();
        assertEquals("VERIFIED", match.get("status"));
        assertEquals("VND", match.get("currency"));
        assertEquals("USD", match.get("job_currency"));
        assertEquals(true, match.get("conversion_applied"));
        assertDecimal("300", match.get("job_salary_min"));
        assertDecimal("500", match.get("job_salary_max"));
        assertDecimal("7500000", match.get("comparison_salary_min"));
        assertDecimal("12500000", match.get("comparison_salary_max"));
        assertDecimal("25000", match.get("conversion_rate"));
        assertDecimal("7500000", match.get("closest_offer"));
        assertDecimal("0", match.get("difference_percent"));
        assertEquals(rates().source(), match.get("conversion_source"));
        assertEquals(rates().asOf(), match.get("conversion_as_of"));
    }

    @Test
    void convertedDollarAndEuroOffersHonorTheInclusiveFloorWithoutRounding() {
        var expected = withRates("7500000", "VND");
        assertDecimal("6750000", expected.match("270 USD").orElseThrow().get("comparison_salary_max"));
        assertTrue(expected.match("269.99996 USD").isEmpty());
        assertDecimal("6750000", expected.match("216 EUR").orElseThrow().get("comparison_salary_max"));
        assertTrue(expected.match("215.999968 EUR").isEmpty());
        assertTrue(expected.match("USD 10000").isPresent());
    }

    @Test
    void nonTerminatingCrossRatesStillAcceptTheExactFloorAndRejectAnArbitrarilyCloseLowerOffer() {
        var exchange = new SalaryExchangeRates(
            Map.of("USD", BigDecimal.ONE, "EUR", new BigDecimal("0.9")),
            Instant.parse("2026-10-24T00:00:00Z"),
            "https://fx.example.test"
        );
        var expected =
            new SalaryExpectation(new BigDecimal("100"), "USD", MatchingSalaryPolicy.legacyDefault(), exchange);
        var exactFloor = expected.match("81 EUR").orElseThrow();
        assertEquals("VERIFIED", exactFloor.get("status"));
        assertDecimal("90", exactFloor.get("comparison_salary_max"));
        assertDecimal("10", exactFloor.get("shortfall_percent"));
        assertTrue(expected.match("80.999999999999999999999999999999999 EUR").isEmpty());
    }

    @Test
    void supportsDollarCandidateComparingVndOffersAndEuroCandidateComparingDollars() {
        var dollars = withRates("1000", "USD");
        var match = dollars.match("22500000 VND").orElseThrow();
        assertDecimal("900", match.get("comparison_salary_max"));
        assertDecimal("0.00004", match.get("conversion_rate"));
        assertTrue(dollars.match("22499999 VND").isEmpty());
        assertTrue(withRates("1000", "EUR").match("1125 USD").isPresent());
        assertTrue(withRates("1000", "EUR").match("1124.99 USD").isEmpty());
    }

    @Test
    void preservesUndisclosedSidesOfConvertedAndSameCurrencyOffers() {
        var expected = withRates("7500000", "VND");
        var upperOnly = expected.match("Up to 270 USD").orElseThrow();
        assertNull(upperOnly.get("job_salary_min"));
        assertNull(upperOnly.get("comparison_salary_min"));
        assertDecimal("6750000", upperOnly.get("comparison_salary_max"));
        assertDecimal("6750000", upperOnly.get("closest_offer"));
        assertTrue(expected.match("Up to 269 USD").isEmpty());
        var lowerOnly = expected.match("From 200 USD").orElseThrow();
        assertNull(lowerOnly.get("job_salary_max"));
        assertNull(lowerOnly.get("comparison_salary_max"));
        assertDecimal("5000000", lowerOnly.get("comparison_salary_min"));
        assertDecimal("7500000", lowerOnly.get("closest_offer"));
        assertNull(expected.match("Từ 7 triệu").orElseThrow().get("comparison_salary_max"));
        assertNull(expected.match("Tối đa 7 triệu").orElseThrow().get("comparison_salary_min"));
    }

    @Test
    void unavailableRatesKeepOriginalSalaryButNeverClaimItPassedTheSalaryFloor() {
        var match = expectation().match("USD 300 - 500").orElseThrow();
        assertUnverified(match);
        assertEquals("EXCHANGE_RATE_UNAVAILABLE", match.get("reason"));
        assertEquals("USD", match.get("job_currency"));
        assertDecimal("300", match.get("job_salary_min"));
        assertDecimal("500", match.get("job_salary_max"));
        assertFalse(match.containsKey("comparison_salary_min"));
        assertFalse(match.containsKey("conversion_rate"));
        var unsupported = withRates("7500000", "VND").match("CNY 1000").orElseThrow();
        assertUnverified(unsupported);
        assertEquals("EXCHANGE_RATE_UNAVAILABLE", unsupported.get("reason"));
    }

    @Test
    void malformedAndUndisclosedSalariesDoNotInventAnOfferOrAConversion() {
        for (String text : new String[]{
            "Thỏa thuận",
            "Up to 35tr (Thương lượng) USD / MONTH",
            "You'll love it USD / MONTH",
            "20 - 30 million"}) {
            var match = withRates("7500000", "VND").match(text).orElseThrow();
            assertUnverified(match);
            assertEquals("SALARY_UNDISCLOSED_OR_UNPARSABLE", match.get("reason"));
            assertFalse(match.containsKey("job_salary_min"));
            assertFalse(match.containsKey("job_salary_max"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "-7500000", "NaN", "Infinity", "", "7tr5", "true"})
    void rejectsInvalidSavedExpectationsInsteadOfSilentlyDisablingTheFilter(
        String amount) {
        var error =
            assertThrows(ApiException.class, () -> SalaryExpectation.fromProfile(Map.of("expected_salary", amount)));
        assertEquals(400, error.status());
    }

    @ParameterizedTest
    @ValueSource(strings = {"JPY", "", "UNKNOWN"})
    void rejectsUnsupportedProfileCurrencies(
        String currency) {
        var error = assertThrows(
            ApiException.class,
            () -> SalaryExpectation.fromProfile(Map.of("expected_salary", 7500000, "salary_currency", currency))
        );
        assertEquals(400, error.status());
    }

    private static SalaryExpectation expectation() {
        return SalaryExpectation.fromProfile(Map.of("expected_salary", 7500000)).orElseThrow();
    }

    private static SalaryExpectation withRates(
        String amount,
        String currency) {
        return new SalaryExpectation(new BigDecimal(amount), currency, MatchingSalaryPolicy.legacyDefault(), rates());
    }

    private static SalaryExchangeRates rates() {
        return new SalaryExchangeRates(
            Map.of("USD", BigDecimal.ONE, "VND", new BigDecimal("25000"), "EUR", new BigDecimal("0.8")),
            Instant.parse("2026-10-24T00:00:00Z"),
            "https://fx.example.test"
        );
    }

    private static void assertUnverified(
        Map<String, Object> match) {
        assertEquals("UNVERIFIED", match.get("status"));
        assertEquals(false, match.get("verified"));
        assertEquals(false, match.get("conversion_applied"));
        assertFalse(match.containsKey("closest_offer"));
        assertFalse(match.containsKey("difference_percent"));
        assertFalse(match.containsKey("shortfall_percent"));
    }

    private static void assertDecimal(
        String expected,
        Object actual) {
        assertEquals(0, new BigDecimal(expected).compareTo((BigDecimal) actual));
    }
}
