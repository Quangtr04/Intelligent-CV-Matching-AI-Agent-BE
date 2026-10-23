package com.example.intelligent_cv_matching_ai_agent.jobs;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.util.candidate.jobs.SalaryExpectation;
import com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.MatchingSalaryPolicy;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    @NullAndEmptySource
    @ValueSource(
        strings = {
            "6749999 VND",
            "6 - 6.749999 triệu",
            "1 - 6 triệu",
            "5000000 VND",
            "$1000-$2000",
            "1000 EUR",
            "7500000",
            "Thỏa thuận",
            "Negotiable",
            "7500000 VND / YEAR",
            "7500000 VND / DAY",
            "7500000 VND / HOUR"}
    )
    void excludesOffersBelowTheFloorAndSalariesThatCannotBeCompared(
        String salaryText) {
        assertTrue(expectation().match(salaryText).isEmpty(), salaryText);
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
        assertEquals("EXCLUDE", policy.get("unknown_salary_policy"));
        assertEquals("SAME_CURRENCY", policy.get("currency_policy"));
        assertDecimal("6750000", policy.get("minimum_acceptable"));
        assertFalse(policy.containsKey("maximum_acceptable"));
    }

    @ParameterizedTest
    @CsvSource({"0,7500000,7499999", "20,6000000,5999999", "100,1,0", "12.34,6574500,6574499"})
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
    void oneHundredPercentAllowsKnownPositiveSalaryAndStillExcludesUnknownAndForeignCurrency() {
        var expected = new SalaryExpectation(
            new BigDecimal("7500000"),
            "VND",
            new MatchingSalaryPolicy(new BigDecimal("100"), 2, null, null)
        );
        assertDecimal("0", expected.minimum());
        assertTrue(expected.match("1 VND/month").isPresent());
        assertTrue(expected.match("Negotiable").isEmpty());
        assertTrue(expected.match("1 USD/month").isEmpty());
        assertTrue(expected.match("1 VND/year").isEmpty());
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
        assertTrue(expected.match("EUR 1000 monthly").isEmpty());
        assertTrue(expected.match("25000000 VND monthly").isEmpty());
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

    private static void assertDecimal(
        String expected,
        Object actual) {
        assertEquals(0, new BigDecimal(expected).compareTo((BigDecimal) actual));
    }
}
