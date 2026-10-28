package com.example.intelligent_cv_matching_ai_agent.util.shared.jobs;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses explicit job salary metadata, without inventing amounts or converting currencies.
 * Job-board salaries with no stated period are treated as monthly; explicit non-monthly periods are rejected.
 */
public final class JobSalaryParser {

    private static final Pattern OTHER_PERIOD = Pattern.compile(
        "\\b(?:year|yearly|annual|annually|annum|nam|day|daily|ngay|hour|hourly|gio|week|weekly|tuan|quarter|quy)\\b"
    );

    private static final Pattern UNKNOWN_SALARY =
        Pattern.compile("\\b(?:negotiable|competitive|thoa thuan|thuong luong|canh tranh|over|under|tren|duoi)\\b");

    private static final Pattern CURRENCY =
        Pattern.compile("\\b(vnd|usd|eur|gbp|jpy|sgd|aud|cad|hkd|nzd|chf|cny|krw|inr)\\b");

    private static final Pattern PREFIXED_DOLLAR = Pattern.compile("\\b(us|sg|s|au|a|ca|c|hk|nz)\\s*\\$");

    private static final Map<String, String> DOLLAR_PREFIXES = Map.ofEntries(
        Map.entry("us", "USD"),
        Map.entry("sg", "SGD"),
        Map.entry("s", "SGD"),
        Map.entry("au", "AUD"),
        Map.entry("a", "AUD"),
        Map.entry("ca", "CAD"),
        Map.entry("c", "CAD"),
        Map.entry("hk", "HKD"),
        Map.entry("nz", "NZD")
    );

    private static final Set<String> DOLLAR_CURRENCIES = Set.of("USD", "SGD", "AUD", "CAD", "HKD", "NZD");

    private static final Pattern VIETNAMESE_UNIT = Pattern.compile("\\b(?:trieu|tr|ty|nghin|ngan)\\b");

    private static final Pattern RANGE_PREFIX = Pattern.compile("^\\s*(?:tu|from)\\s+");

    private static final Pattern LOWER_BOUND_PREFIX = Pattern.compile("^\\s*(?:at least|toi thieu)\\s+");

    private static final Pattern UPPER_BOUND_PREFIX = Pattern.compile("^\\s*(?:up to|toi da)\\s+");

    private static final Pattern LOWER_BOUND_SUFFIX = Pattern.compile("\\s*(?:tro len|or more|\\+)\\s*$");

    private static final Pattern QUANTITY =
        Pattern.compile("^(\\d+(?:[.,]\\d+)*)\\s*(trieu|tr|million|m|ty|billion|nghin|ngan|thousand|k)?\\s*(\\d+)?$");

    private JobSalaryParser() {
    }

    public static Optional<MonthlySalary> parseMonthly(
        String salaryText) {
        if (salaryText == null || salaryText.isBlank() || salaryText.length() > 1000) {
            return Optional.empty();
        }
        String text =
            Normalizer.normalize(salaryText, Normalizer.Form.NFD).replaceAll("\\p{M}+", "").replace('\u0111', 'd')
                .replace('\u0110', 'D').toLowerCase(Locale.ROOT).replace('\u2013', '-').replace('\u2014', '-')
                .replace('\u00a0', ' ').replaceAll("(?<=\\d)(?=[a-z])|(?<=[a-z])(?=\\d)", " ").strip();
        if (OTHER_PERIOD.matcher(text).find()) {
            return Optional.empty();
        }
        text = text.replaceAll("\\(\\s*(?:negotiable|thoa thuan|thuong luong)\\s*\\)", " ");
        if (UNKNOWN_SALARY.matcher(text).find()) {
            return Optional.empty();
        }

        Set<String> currencies = new HashSet<>();
        Matcher currencyMatcher = CURRENCY.matcher(text);
        while (currencyMatcher.find()) {
            currencies.add(currencyMatcher.group(1).toUpperCase(Locale.ROOT));
        }
        Matcher dollarMatcher = PREFIXED_DOLLAR.matcher(text);
        while (dollarMatcher.find()) {
            currencies.add(DOLLAR_PREFIXES.get(dollarMatcher.group(1)));
        }
        String bareSymbols = PREFIXED_DOLLAR.matcher(text).replaceAll(" ");
        if (bareSymbols.contains("$") && (currencies.isEmpty() || !DOLLAR_CURRENCIES.containsAll(currencies))) {
            currencies.add("USD");
        }
        if (text.contains("\u20ac")) {
            currencies.add("EUR");
        }
        if (text.contains("\u00a3")) {
            currencies.add("GBP");
        }
        if (text.contains("\u20a9")) {
            currencies.add("KRW");
        }
        if (text.contains("\u20b9")) {
            currencies.add("INR");
        }
        if ((text.contains("\u00a5") || text.contains("\uffe5")) && !currencies.contains("CNY")) {
            currencies.add("JPY");
        }
        if (text.contains("\u20ab") || text.matches(".*\\d\\s*d\\b.*")
            || VIETNAMESE_UNIT.matcher(text.replaceAll("(?<=\\d)(?=[a-z])", " ")).find()) {
            currencies.add("VND");
        }
        if (currencies.size() != 1) {
            return Optional.empty();
        }

        text = PREFIXED_DOLLAR.matcher(text).replaceAll(" ");
        text = CURRENCY.matcher(text).replaceAll(" ").replaceAll("[$\u20ac\u00a3\u20a9\u20b9\u00a5\uffe5\u20ab]", " ")
            .replaceAll("(?<=\\d)\\s*d\\b", " ")
            .replaceAll("(?:/\\s*|\\bper\\s+|\\ba\\s+)?\\b(?:month|monthly|thang)\\b", " ")
            .replaceAll("\\b(?:muc luong|luong|salary|gross|net)\\b", " ").replaceAll("[():]", " ").strip();
        boolean rangePrefix = RANGE_PREFIX.matcher(text).find();
        boolean lowerPrefix = LOWER_BOUND_PREFIX.matcher(text).find();
        boolean upperPrefix = UPPER_BOUND_PREFIX.matcher(text).find();
        boolean lowerSuffix = LOWER_BOUND_SUFFIX.matcher(text).find();
        if (rangePrefix) {
            text = RANGE_PREFIX.matcher(text).replaceFirst("");
        } else if (lowerPrefix) {
            text = LOWER_BOUND_PREFIX.matcher(text).replaceFirst("");
        } else if (upperPrefix) {
            text = UPPER_BOUND_PREFIX.matcher(text).replaceFirst("");
        }
        text = LOWER_BOUND_SUFFIX.matcher(text).replaceFirst("").replaceAll("\\b(?:to|den)\\b", "-").strip();
        String[] bounds = text.split("\\s*-\\s*", -1);
        if (bounds.length < 1 || bounds.length > 2 || (upperPrefix && lowerSuffix)
            || (bounds.length == 2 && (lowerPrefix || upperPrefix || lowerSuffix))) {
            return Optional.empty();
        }
        try {
            Quantity first = quantity(bounds[0]);
            Quantity last = bounds.length == 1 ? first : quantity(bounds[1]);
            if (first == null || last == null) {
                return Optional.empty();
            }
            String firstUnit = first.unit();
            String lastUnit = last.unit();
            if (firstUnit.isEmpty() && !lastUnit.isEmpty() && first.number().compareTo(new BigDecimal("1000")) < 0) {
                firstUnit = lastUnit;
            }
            if (lastUnit.isEmpty() && !firstUnit.isEmpty() && last.number().compareTo(new BigDecimal("1000")) < 0) {
                lastUnit = firstUnit;
            }
            BigDecimal minimum = first.number().multiply(multiplier(firstUnit));
            BigDecimal maximum = last.number().multiply(multiplier(lastUnit));
            if (minimum.signum() <= 0 || minimum.compareTo(maximum) > 0) {
                return Optional.empty();
            }
            boolean lowerOnly = bounds.length == 1 && (rangePrefix || lowerPrefix || lowerSuffix);
            return Optional.of(
                new MonthlySalary(
                    upperPrefix ? null : minimum,
                    lowerOnly ? null : maximum,
                    currencies.iterator().next()
                )
            );
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }

    private static Quantity quantity(
        String text) {
        Matcher matcher = QUANTITY.matcher(text.strip());
        if (!matcher.matches()) {
            return null;
        }
        String number = matcher.group(1);
        if (number.matches("\\d{1,3}(?:,\\d{3})+(?:\\.\\d{1,2})?")) {
            number = number.replace(",", "");
        } else if (number.matches("\\d{1,3}(?:\\.\\d{3})+(?:,\\d{1,2})?")) {
            number = number.replace(".", "").replace(',', '.');
        } else if (number.matches("\\d+(?:[.,]\\d+)?")) {
            number = number.replace(',', '.');
        } else {
            return null;
        }
        BigDecimal amount = new BigDecimal(number);
        String unit = matcher.group(2) == null ? "" : matcher.group(2);
        String fraction = matcher.group(3);
        if (fraction != null) {
            if (!(unit.equals("tr") || unit.equals("trieu")) || number.contains(".")) {
                return null;
            }
            amount = amount.add(new BigDecimal("0." + fraction));
        }
        return new Quantity(amount, unit);
    }

    private static BigDecimal multiplier(
        String unit) {
        return switch (unit) {
            case "trieu", "tr", "million", "m" -> new BigDecimal("1000000");
            case "ty", "billion" -> new BigDecimal("1000000000");
            case "nghin", "ngan", "thousand", "k" -> new BigDecimal("1000");
            default -> BigDecimal.ONE;
        };
    }

    /** Advertised monthly range; a null bound means that side was not disclosed. */
    public record MonthlySalary(
        BigDecimal minimum,
        BigDecimal maximum,
        String currency
    ) {
    }

    private record Quantity(
        BigDecimal number,
        String unit
    ) {
    }
}
