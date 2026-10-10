package com.example.intelligent_cv_matching_ai_agent.service.shared.identity;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.identity.AuthRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class AuthService {

    private final AuthRepository db;

    private final PasswordEncoder encoder;

    private final TokenService tokens;

    private final ObjectProvider<JavaMailSender> mail;

    private final boolean exposeDevelopmentCode;

    private final String mailFrom;

    private final boolean mailEnabled;

    private final String dummyHash;

    public AuthService(
        AuthRepository db,
        PasswordEncoder encoder,
        TokenService tokens,
        ObjectProvider<JavaMailSender> mail,
        @Value("${app.auth.expose-development-code:false}")
        boolean devCode,
        @Value("${app.auth.mail-from:noreply@edunexus.local}")
        String mailFrom,
        @Value("${app.mail.enabled:false}")
        boolean mailEnabled) {
        this.db = db;
        this.encoder = encoder;
        this.tokens = tokens;
        this.mail = mail;
        this.exposeDevelopmentCode = devCode;
        this.mailFrom = mailFrom;
        this.mailEnabled = mailEnabled;
        this.dummyHash = encoder.encode(TokenService.random(""));
    }

    public static String email(
        String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private void passwordLength(
        String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw ApiException.badRequest("Password must fit within 72 UTF-8 bytes");
    }

    @Transactional
    public Map<String, Object> register(
        String email,
        String password,
        String fullName,
        String language) {
        passwordLength(password);

        String normalized = email(email);
        if (db.findUsersByEmail(normalized).isPresent())
            throw ApiException.conflict("Email already registered");

        OffsetDateTime now = OffsetDateTime.now();

        UUID user = db.insertUsers(
            Map.of(
                "email",
                normalized,
                "password_hash",
                encoder.encode(password),
                "full_name",
                fullName.trim(),
                "auth_provider",
                "EMAIL",
                "role",
                "CANDIDATE",
                "status",
                "PENDING",
                "preferred_language",
                language == null ? "vi" : language,
                "created_at",
                now,
                "updated_at",
                now
            )
        );

        return issueCode(user, normalized);
    }

    @Transactional
    public Map<String, Object> resend(
        String email) {
        var user = db.findUsersByEmail2(email(email));
        // Uniform response prevents probing existing accounts.
        if (user.isEmpty())
            return Map.of("message", "If this account is pending, a verification code will be sent");

        return issueCode((UUID) user.get().get("id"), user.get().get("email").toString());
    }

    private Map<String, Object> issueCode(
        UUID user,
        String email) {
        if (db.findEmailVerificationCodesByUserId(user).isPresent())
            throw new ApiException(429, "Wait 60 seconds before requesting a new code");

        db.updateEmailVerificationCodesByUserId(user);

        String code = String.format("%06d", new SecureRandom().nextInt(1000000));

        db.insertEmailVerificationCodes(
            Map.of(
                "user_id",
                user,
                "code_hash",
                encoder.encode(code),
                "expires_at",
                OffsetDateTime.now().plusMinutes(10),
                "created_at",
                OffsetDateTime.now()
            )
        );

        if (!exposeDevelopmentCode) {
            if (!mailEnabled)
                throw new ApiException(503, "Email delivery is disabled; configure MAIL_ENABLED and SMTP settings");

            JavaMailSender sender = mail.getIfAvailable();
            if (sender == null)
                throw new ApiException(503, "Email delivery is not configured");

            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(mailFrom);
            message.setTo(email);
            message.setSubject("EduNexus email verification");
            message.setText("Your verification code is " + code + ". It expires in 10 minutes.");

            try {
                sender.send(message);
            } catch (RuntimeException ex) {
                throw new ApiException(503, "Email could not be sent; retry later");
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message", "If this account is pending, a verification code will be sent");
        result.put("expiresIn", 600);

        if (exposeDevelopmentCode)
            result.put("developmentCode", code);

        return result;
    }

    @Transactional(noRollbackFor = ApiException.class)
    public Map<String, Object> verify(
        String email,
        String code) {
        Map<String, Object> row = db.findEmailVerificationCodesByEmailForUpdate(email(email))
            .orElseThrow(() -> ApiException.badRequest("Invalid or expired verification code"));
        int attempts = ((Number) row.get("attempt_count")).intValue();
        if (attempts >= 5)
            throw new ApiException(429, "Verification attempt limit reached; request a new code");

        db.updateEmailVerificationCodesById(row.get("id"));
        if (!encoder.matches(code, row.get("code_hash").toString()))
            throw ApiException.badRequest("Invalid or expired verification code");

        db.updateEmailVerificationCodesById2(row.get("id"));
        db.updateUsersById(row.get("user_id"));
        db.updateCandidateProfiles(row.get("user_id"));

        return Map.of("message", "Email verified; you can log in");
    }

    @Transactional
    public Map<String, Object> login(
        String email,
        String password) {
        passwordLength(password);

        var user = db.findUsersByEmailForUpdate(email(email));
        String hashed = user.map(u -> u.get("password_hash")).map(Object::toString).orElse(dummyHash);
        boolean valid = encoder.matches(password, hashed);
        if (user.isEmpty() || !valid)
            throw new ApiException(401, "Invalid email or password");
        if (!"ACTIVE".equals(user.get().get("status").toString()))
            throw new ApiException(403, "Verify your email or contact support about your account status");

        UUID id = (UUID) user.get().get("id");

        db.updateUsersById2(id);

        return tokens.issue(id);
    }
}
