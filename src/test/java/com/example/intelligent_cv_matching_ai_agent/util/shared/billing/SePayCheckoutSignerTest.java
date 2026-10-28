package com.example.intelligent_cv_matching_ai_agent.util.shared.billing;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class SePayCheckoutSignerTest {

    private static final String SECRET = "test_sepay_secret_123";

    private static final String GOLDEN = "MzMeYibRFsgEBbfzgFlMuwB6A3v9iKdCt+v2YdcvMIU=";

    @Test
    void matchesAnIndependentHmacSha256Base64FixtureInTheDocumentedFieldOrder() {
        assertEquals(GOLDEN, SePayCheckoutSigner.sign(goldenFields(), SECRET));
    }

    @Test
    void insertionOrderAndAlphabeticMapOrderDoNotChangeTheDocumentedSigningOrder() {
        Map<String, String> fields = goldenFields();
        Map<String, String> reverse = new LinkedHashMap<>();
        fields.keySet().stream().toList().reversed().forEach(key -> reverse.put(key, fields.get(key)));
        assertEquals(GOLDEN, SePayCheckoutSigner.sign(reverse, SECRET));
        assertEquals(GOLDEN, SePayCheckoutSigner.sign(new TreeMap<>(fields), SECRET));
    }

    @Test
    void unicodeAmpersandsPlusSignsAndUrlsAreSignedAsRawUtf8WithoutUrlEncoding() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("order_amount", "10000");
        fields.put("merchant", "MERCHANT_TEST");
        fields.put("currency", "VND");
        fields.put("operation", "PURCHASE");
        fields.put("order_description", "Thanh toán gói PRO & hỗ trợ");
        fields.put("order_invoice_number", "DH123");
        fields.put("success_url", "https://example.test/ok?a=1&b=đ");
        fields.put("error_url", "https://example.test/fail?cause=a+b");
        assertEquals("a19NZx+aP8ltURont01DeW7C+bssL2CFN5R/qLF5uVg=", SePayCheckoutSigner.sign(fields, SECRET));
    }

    @Test
    void unsupportedFieldsAndAnExistingSignatureAreNotPartOfTheSignature() {
        Map<String, String> fields = goldenFields();
        fields.put("signature", "an-old-signature");
        fields.put("custom_data", "not-signed");
        fields.put("unrelated", "not-signed-either");
        assertEquals(GOLDEN, SePayCheckoutSigner.sign(fields, SECRET));
    }

    @Test
    void explicitNullAndEmptyOptionalFieldsMatchWhileAnAbsentFieldIsOmitted() {
        Map<String, String> fields = goldenFields();
        fields.remove("customer_id");
        String omitted = SePayCheckoutSigner.sign(fields, SECRET);
        fields.put("customer_id", "");
        String empty = SePayCheckoutSigner.sign(fields, SECRET);
        fields.put("customer_id", null);
        assertEquals(empty, SePayCheckoutSigner.sign(fields, SECRET));
        assertNotEquals(omitted, empty);
    }

    @Test
    void signingDoesNotMutateTheInputOrTrimAConfiguredSecret() {
        Map<String, String> fields = goldenFields();
        Map<String, String> original = new LinkedHashMap<>(fields);
        assertEquals(GOLDEN, SePayCheckoutSigner.sign(Collections.unmodifiableMap(fields), SECRET));
        assertEquals(original, fields);
        assertNotEquals(GOLDEN, SePayCheckoutSigner.sign(fields, " " + SECRET + " "));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void rejectsAnUnconfiguredSecret(
        String secret) {
        assertThrows(IllegalArgumentException.class, () -> SePayCheckoutSigner.sign(goldenFields(), secret));
    }

    @Test
    void rejectsANullFieldsMap() {
        assertThrows(IllegalArgumentException.class, () -> SePayCheckoutSigner.sign(null, SECRET));
    }

    private static Map<String, String> goldenFields() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("order_amount", "10000");
        fields.put("merchant", "MERCHANT_TEST");
        fields.put("currency", "VND");
        fields.put("operation", "PURCHASE");
        fields.put("order_description", "Thanh toan goi PRO");
        fields.put("order_invoice_number", "EN-123");
        fields.put("customer_id", "user-123");
        fields.put("payment_method", "BANK_TRANSFER");
        fields.put("success_url", "https://example.test/payment?state=success");
        fields.put("error_url", "https://example.test/payment?state=error");
        fields.put("cancel_url", "https://example.test/payment?state=cancel");
        return fields;
    }
}
