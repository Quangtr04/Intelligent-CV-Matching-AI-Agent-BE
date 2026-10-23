package com.example.intelligent_cv_matching_ai_agent.util.shared.jobs;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
            "20000000 - 30 million VND|20000000|30000000|VND"},
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
            "Up to 30 triệu",
            "Tối đa 30 triệu",
            "Trên 20 triệu",
            "20 triệu trở lên",
            "Từ 7 triệu",
            "From 700 USD",
            "Salary: from 700 USD monthly",
            "From 700\nUSD",
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
    void rejectsUnknownUnboundedAndNonMonthlySalaries(
        String text) {
        assertTrue(JobSalaryParser.parseMonthly(text).isEmpty(), text);
    }

    @Test
    void rejectsUnreasonablyLongMetadata() {
        assertTrue(JobSalaryParser.parseMonthly("7".repeat(1001) + " VND").isEmpty());
    }
}
