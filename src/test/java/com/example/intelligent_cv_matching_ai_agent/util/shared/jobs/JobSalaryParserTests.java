package com.example.intelligent_cv_matching_ai_agent.util.shared.jobs;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobSalaryParserTests {

    @ParameterizedTest
    @CsvSource(
        value = {
            "20 - 30 million VND|20000000|30000000|VND",
            "20 triệu - 30 triệu / tháng|20000000|30000000|VND",
            "7.5 triệu|7500000|7500000|VND",
            "7,5 triệu|7500000|7500000|VND",
            "7tr5|7500000|7500000|VND",
            "7tr50 - 8tr|7500000|8000000|VND",
            "Từ 7,5 đến 8 triệu|7500000|8000000|VND",
            "Từ 7 đến 9 triệu|7000000|9000000|VND",
            "From 700 to 900 USD|700|900|USD",
            "7.500.000 - 8.000.000 VND|7500000|8000000|VND",
            "7,500,000 - 8,000,000 VND|7500000|8000000|VND",
            "7.500.000đ|7500000|7500000|VND",
            "7500000 ₫ / tháng|7500000|7500000|VND",
            "20000000 - 35000000 VND / MONTH|20000000|35000000|VND",
            "20 — 30 triệu|20000000|30000000|VND",
            "20 – 30 triệu|20000000|30000000|VND",
            "$1000-$2000|1000|2000|USD",
            "Salary USD 1,000 - 2,000 per month (gross)|1000|2000|USD",
            "USD 1.5 - 2 thousand monthly|1500|2000|USD",
            "1500 EUR / month|1500|1500|EUR",
            "Lương: 7.5 triệu (net)|7500000|7500000|VND",
            "7500000VND|7500000|7500000|VND",
            "20000000 - 30 million VND|20000000|30000000|VND",
            "€1500 - €2500 / month|1500|2500|EUR",
            "£1500 - 2500|1500|2500|GBP",
            "US$1,000 - US$2,000|1000|2000|USD",
            "S$1,000 - S$2,000|1000|2000|SGD",
            "SGD $1,000 - $2,000|1000|2000|SGD",
            "AUD $1,000 - $2,000|1000|2000|AUD",
            "A$1,000 - A$2,000|1000|2000|AUD",
            "C$1,000 - C$2,000|1000|2000|CAD",
            "HK$10,000 - HK$20,000|10000|20000|HKD",
            "NZ$1,000 - NZ$2,000|1000|2000|NZD",
            "JPY ¥100,000 - ¥200,000|100000|200000|JPY",
            "CNY ¥10,000 - ¥20,000|10000|20000|CNY",
            "₩1,000,000 - ₩2,000,000|1000000|2000000|KRW",
            "₹100,000 - ₹200,000|100000|200000|INR",
            "USD 1,000.50 - 2,000.75|1000.50|2000.75|USD",
            "EUR 1.000,50 - 2.000,75|1000.50|2000.75|EUR",
            "20 - 30 triệu (thỏa thuận)|20000000|30000000|VND",
            "USD 1000 - 2000 (negotiable)|1000|2000|USD",
            "800 - 2,000 USD USD / MONTH|800|2000|USD",
            "1,500 - 3,000 USD USD / MONTH|1500|3000|USD",
            "20 - 30 triệu (Thương lượng)|20000000|30000000|VND",
            "6750000.00000090 VND|6750000.00000090|6750000.00000090|VND",
            "6.749999 triệu|6749999|6749999|VND"},
        delimiter = '|'
    )
    void parsesExplicitBoundedMonthlyAmounts(
        String text,
        String minimum,
        String maximum,
        String currency) {
        var parsed = JobSalaryParser.parseMonthly(text).orElseThrow();
        assertEquals(0, new BigDecimal(minimum).compareTo(parsed.minimum()), text);
        assertEquals(0, new BigDecimal(maximum).compareTo(parsed.maximum()), text);
        assertEquals(currency, parsed.currency(), text);
    }

    @ParameterizedTest
    @CsvSource(
        value = {
            "Up to 30 triệu|30000000|VND",
            "Tối đa 30 triệu|30000000|VND",
            "Up to USD 2,000 per month|2000|USD",
            "Salary: up to €2,000 monthly|2000|EUR"},
        delimiter = '|'
    )
    void parsesUpperBoundsWithoutInventingAMinimum(
        String text,
        String maximum,
        String currency) {
        var parsed = JobSalaryParser.parseMonthly(text).orElseThrow();
        assertNull(parsed.minimum(), text);
        assertEquals(0, new BigDecimal(maximum).compareTo(parsed.maximum()), text);
        assertEquals(currency, parsed.currency(), text);
    }

    @ParameterizedTest
    @CsvSource(
        value = {
            "Từ 7 triệu|7000000|VND",
            "From 700 USD|700|USD",
            "Salary: from 700 USD monthly|700|USD",
            "At least 700 USD|700|USD",
            "Tối thiểu 7,5 triệu|7500000|VND",
            "20 triệu trở lên|20000000|VND",
            "USD 1000 or more|1000|USD",
            "USD 1000+|1000|USD"},
        delimiter = '|'
    )
    void parsesLowerBoundsWithoutInventingAMaximum(
        String text,
        String minimum,
        String currency) {
        var parsed = JobSalaryParser.parseMonthly(text).orElseThrow();
        assertEquals(0, new BigDecimal(minimum).compareTo(parsed.minimum()), text);
        assertNull(parsed.maximum(), text);
        assertEquals(currency, parsed.currency(), text);
    }

    @Test
    void parsesLowerBoundWithLineBreakBetweenAmountAndCurrency() {
        parsesLowerBoundsWithoutInventingAMaximum("From 700\nUSD", "700", "USD");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(
        strings = {
            " ",
            "Thỏa thuận",
            "Negotiable",
            "Competitive",
            "Không công khai",
            "20 - 30 million",
            "7500000",
            "7.5",
            "7 5 triệu",
            "20 - 30",
            "20 - 30 million VND / YEAR",
            "USD 100000 annually",
            "VND 7500000 / năm",
            "500 nghìn / ngày",
            "$20 / HOUR",
            "10 triệu / tuần",
            "60 triệu / quý",
            "20 triệu VND / day",
            "20 - 30 triệu USD",
            "USD 1000 - VND 20000000",
            "Trên 20 triệu",
            "Dưới 20 triệu",
            "Over 700 USD",
            "Under 700 USD",
            "Up to 20 - 30 triệu",
            "At least 20 - 30 triệu",
            "20 - 30 triệu trở lên",
            "Up to 30 triệu trở lên",
            "From up to 700 USD",
            "Up to from 700 USD",
            "From at least 700 USD",
            "From 0 USD",
            "Up to 0 USD",
            "USD 1000 €",
            "S$1000 USD",
            "SGD 1000 US$",
            "EUR $1000",
            "Up to 35tr (Thương lượng) USD / MONTH",
            "You'll love it USD / MONTH",
            "USD 1.000,000.00",
            "20 triệu + thưởng 5 triệu",
            "20 triệu / 13 tháng",
            "15% bonus",
            "2026-10-22",
            "30 - 20 triệu",
            "0 - 20 triệu",
            "-20 triệu",
            "20 - triệu",
            "10-20-30 triệu",
            "USD 1,00,000",
            "VND 7.5tr5",
            "7tr5tr",
            "10 triệu gross or 8 triệu net"}
    )
    void rejectsUnknownAmbiguousAndNonMonthlySalaries(
        String text) {
        assertTrue(JobSalaryParser.parseMonthly(text).isEmpty(), text);
    }

    @Test
    void rejectsUnreasonablyLongMetadata() {
        assertTrue(JobSalaryParser.parseMonthly("7".repeat(1001) + " VND").isEmpty());
    }
}
