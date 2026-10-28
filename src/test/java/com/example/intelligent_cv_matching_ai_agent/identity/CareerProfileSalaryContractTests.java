package com.example.intelligent_cv_matching_ai_agent.identity;

import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.profile.CareerProfileRequest;
import com.example.intelligent_cv_matching_ai_agent.entity.candidate.profile.CandidateProfile;
import jakarta.validation.Validation;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class CareerProfileSalaryContractTests {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void aSingleMonthlyAmountIsValidatedAndLegacyRangeFieldsAreNotExposed() {
        var request =
            mapper.readValue("{\"expectedSalary\":7500000,\"salaryCurrency\":\"VND\"}", CareerProfileRequest.class);
        assertEquals(0, new BigDecimal("7500000").compareTo(request.expectedSalary()));
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertTrue(factory.getValidator().validate(request).isEmpty());
            assertTrue(factory.getValidator().validate(mapper.readValue("{}", CareerProfileRequest.class)).isEmpty());
        }
        CandidateProfile profile = new CandidateProfile();
        profile.setExpectedSalary(new BigDecimal("7500000"));
        assertThrows(
            NoSuchMethodException.class,
            () -> CandidateProfile.class.getMethod("setExpectedSalaryMin", BigDecimal.class)
        );
        assertThrows(
            NoSuchMethodException.class,
            () -> CandidateProfile.class.getMethod("setExpectedSalaryMax", BigDecimal.class)
        );
        String json = mapper.writeValueAsString(profile);
        assertTrue(json.contains("\"expected_salary\":7500000"));
        assertFalse(json.contains("expected_salary_min"));
        assertFalse(json.contains("expected_salary_max"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"expectedSalaryMin", "expectedSalaryMax", "expected_salary_min", "expected_salary_max"})
    void theRemovedRangeContractFailsDeserializationEvenForExplicitNull(
        String name) {
        assertThrows(
            RuntimeException.class,
            () -> mapper.readValue("{\"" + name + "\":null}", CareerProfileRequest.class)
        );
    }
}
