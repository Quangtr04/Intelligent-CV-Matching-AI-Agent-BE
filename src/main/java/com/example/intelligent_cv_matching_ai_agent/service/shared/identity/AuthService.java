package com.example.intelligent_cv_matching_ai_agent.service.shared.identity;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.identity.AuthRepository;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.identity.ResetPasswordTokenRepository;
import com.example.intelligent_cv_matching_ai_agent.util.shared.identity.AuthValidation;
import jakarta.mail.MessagingException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class AuthService {

    private final AuthRepository db;

    private final ResetPasswordTokenRepository resetPasswordTokenRepository;

    private final PasswordEncoder encoder;

    private final TokenService tokens;

    private final ObjectProvider<JavaMailSender> mail;

    private final boolean exposeDevelopmentCode;

    private final String mailFrom;

    private final boolean mailEnabled;

    private final String dummyHash;

    private final String frontendResetUrl;

    public AuthService(
        AuthRepository db,
        ResetPasswordTokenRepository resetPasswordTokenRepository,
        PasswordEncoder encoder,
        TokenService tokens,
        ObjectProvider<JavaMailSender> mail,
        @Value("${app.auth.expose-development-code:false}")
        boolean devCode,
        @Value("${app.auth.mail-from:noreply@edunexus.local}")
        String mailFrom,
        @Value("${app.frontend-reset-url}")
        String frontendResetUrl,
        @Value("${app.mail.enabled:false}")
        boolean mailEnabled) {
        this.db = db;
        this.resetPasswordTokenRepository = resetPasswordTokenRepository;
        this.encoder = encoder;
        this.tokens = tokens;
        this.mail = mail;
        this.exposeDevelopmentCode = devCode;
        this.mailFrom = mailFrom;
        this.mailEnabled = mailEnabled;
        this.frontendResetUrl = frontendResetUrl;
        this.dummyHash = encoder.encode(TokenService.random(""));
    }

    public static String email(
        String email) {
        return AuthValidation.email(email);
    }

    @Transactional
    public Map<String, Object> register(
        String email,
        String password,
        String fullName,
        String language) {
        AuthValidation.passwordLength(password);

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

        return issueCodeEmail(user, normalized);
    }

    @Transactional
    public Map<String, Object> resend(
        String email) {
        var user = db.findUsersByEmail2(email(email));
        // Uniform response prevents probing existing accounts.
        if (user.isEmpty())
            return Map.of("message", "If this account is pending, a verification code will be sent");

        return issueCodeEmail((UUID) user.get().get("id"), user.get().get("email").toString());
    }

    private Map<String, Object> issueCodeEmail(
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

            sendVerificationEmail(sender, email, code);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message", "If this account is pending, a verification code will be sent");
        result.put("expiresIn", 600);

        if (exposeDevelopmentCode)
            result.put("developmentCode", code);

        return result;
    }

    private void sendVerificationEmail(
        JavaMailSender sender,
        String recipient,
        String code) {
        try {
            MimeMessageHelper message =
                new MimeMessageHelper(sender.createMimeMessage(), true, StandardCharsets.UTF_8.name());
            message.setFrom(mailFrom, "EduNexus");
            message.setTo(recipient);
            message.setSubject("EduNexus - Xác minh email của bạn");

            String plainText = """
                Xin chào,

                Mã xác minh email của bạn tại EduNexus: %s

                Nhập mã này trên màn hình xác minh để hoàn tất đăng ký.
                Mã có hiệu lực trong 10 phút. Nếu mã đã hết hạn, hãy yêu cầu gửi lại trên ứng dụng.

                Không chia sẻ mã này với bất kỳ ai.
                Nếu bạn không yêu cầu đăng ký tài khoản, bạn có thể bỏ qua email này.

                Đội ngũ EduNexus
                """.formatted(code);
            String html = new ClassPathResource("templates/mail/email-verification.html")
                .getContentAsString(StandardCharsets.UTF_8).replace("{{verificationCode}}", code);
            message.setText(plainText, html);
            sender.send(message.getMimeMessage());
        } catch (MessagingException | IOException | RuntimeException ex) {
            throw new ApiException(503, "Email could not be sent; retry later");
        }
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
        AuthValidation.passwordLength(password);

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

    @Transactional 
    public Map<String, Object> forgotPassword(String email){
        AuthValidation.email(email);

        var user = db.findUsersByEmailForUpdate(email(email));
        if (user.isEmpty())
            throw ApiException.badRequest("Email not registered");

        if(user.get().get("status").toString().equals("PENDING"))
            throw ApiException.badRequest("Email not verified; check your inbox or request a new verification code");

        if(!user.get().get("auth_provider").toString().equals("EMAIL"))
            throw ApiException.badRequest("Password reset is not available for this account; contact support");

        if(!user.get().get("status").toString().equals("ACTIVE"))
            throw ApiException.badRequest("Account is not active; contact support");
        
        return issueCodePassword((UUID) user.get().get("id"), user.get().get("email").toString());
    }

    private Map<String, Object> issueCodePassword(
        UUID user,
        String email) {
        if (db.findResetPasswordTokensByUserId(user).isPresent())
            throw new ApiException(429, "Wait 60 seconds before requesting a new code");

        db.updateResetPasswordTokensByUserId(user);

        String code = String.format("%06d", new SecureRandom().nextInt(1000000));

        db.insertResetPasswordTokens(
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

            sendResetPasswordEmail(sender, email, code);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message", "If this account is pending, a verification code will be sent");
        result.put("expiresIn", 600);

        if (exposeDevelopmentCode)
            result.put("developmentCode", code);

        return result;
    }

    private void sendResetPasswordEmail(
        JavaMailSender sender,
        String recipient,
        String token) {
    try {
        // Tạo đường link dẫn tới trang reset password của Frontend
        String resetLink = frontendResetUrl
                + "?token="
                + URLEncoder.encode(token, StandardCharsets.UTF_8);

        MimeMessageHelper message = new MimeMessageHelper(
                sender.createMimeMessage(),
                true,
                StandardCharsets.UTF_8.name()
        );

        message.setFrom(mailFrom, "EduNexus");
        message.setTo(recipient);
        message.setSubject("EduNexus - Đặt lại mật khẩu");

        String plainText = """
            Xin chào,

            Chúng tôi nhận được yêu cầu đặt lại mật khẩu tài khoản EduNexus của bạn.

            Nhấn vào đường link sau để đặt lại mật khẩu:

            %s

            Link có hiệu lực trong 10 phút.

            Nếu bạn không yêu cầu đặt lại mật khẩu,
            vui lòng bỏ qua email này.

            Không chia sẻ đường link này với bất kỳ ai.

            Đội ngũ EduNexus
            """.formatted(resetLink);

        String html = new ClassPathResource(
                "templates/mail/password-reset.html"
        )
        .getContentAsString(StandardCharsets.UTF_8)
        .replace("{{resetLink}}", resetLink);

        message.setText(plainText, html);

        sender.send(message.getMimeMessage());

    } catch (MessagingException | IOException | RuntimeException ex) {
            throw new ApiException(
                503,
                "Email could not be sent; retry later"
            );
        }
    }

    @Transactional 
    public Map<String, Object> resetPassword(String token, String newPassword, String newPasswordConfirmation){
        AuthValidation.passwordLength(newPassword);

        var tokenRow = resetPasswordTokenRepository.findResetPasswordTokensByTokenHashForUpdate(token);
        if (tokenRow.isEmpty())
            throw ApiException.badRequest("Invalid or expired verification code");

        UUID tokenId = UUID.fromString(
            tokenRow.get().get("id").toString()
        );

        UUID userId = UUID.fromString(
            tokenRow.get().get("user_id").toString()
        );


        int updated = resetPasswordTokenRepository
        .updateResetPasswordTokensById(tokenId, userId);

        if (updated != 1) {
            throw ApiException.badRequest(
            "Invalid or expired reset token"
            );
        }

        if(!newPassword.equals(newPasswordConfirmation)){
            throw ApiException.badRequest(
                "New password and confirmation do not match"
            );
        }
        
        db.updateUsersPasswordById(
            userId,
            encoder.encode(newPassword)
        );
    
        return Map.of("message", "Password reset successfully; you can log in");

    }
}
