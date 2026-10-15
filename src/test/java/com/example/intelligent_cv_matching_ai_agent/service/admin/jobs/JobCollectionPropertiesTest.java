package com.example.intelligent_cv_matching_ai_agent.service.admin.jobs;

import com.example.intelligent_cv_matching_ai_agent.config.shared.jobs.JobCollectionProperties;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class JobCollectionPropertiesTest {

    @Test
    void disabledDefaultsAreValidWithoutAnAdministratorOrSources() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            JobCollectionProperties properties = new JobCollectionProperties();
            assertTrue(factory.getValidator().validate(properties).isEmpty());
            assertFalse(properties.isEnabled());
            assertEquals(Duration.ofHours(6), properties.getFixedDelay());
        }
    }

    @Test
    void enabledCollectionRequiresActorAndRejectsUnboundedSourceConfiguration() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            JobCollectionProperties properties = new JobCollectionProperties();
            properties.setEnabled(true);
            assertFalse(validator.validate(properties).isEmpty());

            JobCollectionProperties.Source source = new JobCollectionProperties.Source();
            source.setSourceSite("ITVIEC");
            source.setKeyword("Java");
            source.setRoleCategory("Backend");
            properties.setActorId(UUID.randomUUID());
            properties.setSources(List.of(source));
            assertTrue(validator.validate(properties).isEmpty());

            source.setMaxPages(6);
            source.setLimit(21);
            assertEquals(2, validator.validate(properties).size());
            properties.setFixedDelay(Duration.ZERO);
            assertFalse(properties.isDelayValid());
        }
    }
}
