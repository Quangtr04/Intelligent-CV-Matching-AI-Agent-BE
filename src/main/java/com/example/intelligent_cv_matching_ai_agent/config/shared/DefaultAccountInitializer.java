package com.example.intelligent_cv_matching_ai_agent.config.shared;

import com.example.intelligent_cv_matching_ai_agent.repository.admin.management.AdminRepository;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the default admin and consultant accounts at startup when they are configured in the
 * environment and do not exist yet. Existing accounts are never changed.
 */
@Component
public class DefaultAccountInitializer implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultAccountInitializer.class);

    private final AdminRepository db;

    private final PasswordEncoder encoder;

    private final Environment env;

    public DefaultAccountInitializer(
        AdminRepository db,
        PasswordEncoder encoder,
        Environment env) {
        this.db = db;
        this.encoder = encoder;
        this.env = env;
    }

    @Override
    @Transactional
    public void run(
        ApplicationArguments args) {
        create("ADMIN", "app.bootstrap.admin", "Administrator");
        create("CAREER_CONSULTANT", "app.bootstrap.consultant", "Career Consultant");
    }

    private void create(
        String role,
        String prefix,
        String defaultName) {
        String email = env.getProperty(prefix + ".email", "").trim().toLowerCase(Locale.ROOT);
        String password = env.getProperty(prefix + ".password", "");
        if (email.isEmpty() || password.isEmpty())
            return;
        if (db.findUsersByEmail(email).isPresent())
            return;
        if (password.length() < 10 || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            LOG.warn("Skipped default {} account {}: password must be 10 characters to 72 UTF-8 bytes", role, email);
            return;
        }

        OffsetDateTime now = OffsetDateTime.now();
        db.insertUsers(
            Map.of(
                "email",
                email,
                "full_name",
                env.getProperty(prefix + ".full-name", defaultName),
                "password_hash",
                encoder.encode(password),
                "auth_provider",
                "EMAIL",
                "role",
                role,
                "status",
                "ACTIVE",
                "email_verified_at",
                now,
                "created_at",
                now,
                "updated_at",
                now
            )
        );
        LOG.info("Created default {} account {}", role, email);
    }
}
