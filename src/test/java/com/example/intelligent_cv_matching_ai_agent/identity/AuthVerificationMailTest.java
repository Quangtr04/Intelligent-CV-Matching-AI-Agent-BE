package com.example.intelligent_cv_matching_ai_agent.identity;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.identity.AuthRepository;
import com.example.intelligent_cv_matching_ai_agent.service.shared.identity.AuthService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.identity.TokenService;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuthVerificationMailTest {

    private static final String NORMALIZED_EMAIL = "candidate@example.invalid";

    private static final String FROM_EMAIL = "noreply@example.invalid";

    private static final Pattern CODE = Pattern.compile("(?<!\\d)\\d{6}(?!\\d)");

    private final AuthRepository repository = mock(AuthRepository.class);

    private final PasswordEncoder encoder = mock(PasswordEncoder.class);

    private final TokenService tokens = mock(TokenService.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<JavaMailSender> mail = mock(ObjectProvider.class);

    private final JavaMailSender sender = mock(JavaMailSender.class);

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void fixtures() {
        when(encoder.encode(any(CharSequence.class))).thenAnswer(invocation -> "encoded:" + invocation.getArgument(0));
        when(repository.findUsersByEmail(anyString())).thenReturn(Optional.empty());
        when(repository.insertUsers(anyMap())).thenReturn(userId);
        when(repository.findUsersByEmail2(anyString()))
            .thenReturn(Optional.of(Map.of("id", userId, "email", NORMALIZED_EMAIL)));
        when(repository.findEmailVerificationCodesByUserId(any(UUID.class))).thenReturn(Optional.empty());
        when(mail.getIfAvailable()).thenReturn(sender);
        when(sender.createMimeMessage())
            .thenAnswer(invocation -> new MimeMessage(Session.getInstance(new Properties())));
    }

    @Test
    void registrationSendsUtf8AlternativeBodiesWithTheStoredVerificationCode() throws Exception {
        Map<String, Object> response =
            service(false, true).register(" Candidate@Example.Invalid \r\n", "fixture-password", "Candidate", "vi");
        String code = storedCode();
        assertDelivery(code);
        assertSafeResponse(response, code);
        verify(repository).findUsersByEmail(NORMALIZED_EMAIL);
        verify(repository).insertUsers(argThat(values -> NORMALIZED_EMAIL.equals(values.get("email"))));
        verifyNoInteractions(tokens);
    }

    @Test
    void resendUsesTheSameMailContractForThePendingAccount() throws Exception {
        Map<String, Object> response = service(false, true).resend(" CANDIDATE@EXAMPLE.INVALID ");
        String code = storedCode();
        assertDelivery(code);
        assertSafeResponse(response, code);
        verify(repository).findUsersByEmail2(NORMALIZED_EMAIL);
        verify(repository, never()).insertUsers(anyMap());
        verifyNoInteractions(tokens);
    }

    @Test
    void developmentCodeModeReturnsTheCodeWithoutCreatingOrSendingMail() {
        Map<String, Object> response = service(true, false).resend(NORMALIZED_EMAIL);
        assertEquals(storedCode(), response.get("developmentCode"));
        assertEquals(600, response.get("expiresIn"));
        verifyNoInteractions(mail, sender, tokens);
    }

    @Test
    void smtpFailureReturnsUnavailableWithoutExposingTheCodeOrProviderDetails() {
        doThrow(new MailSendException("PRIVATE_SMTP_DIAGNOSTIC")).when(sender).send(any(MimeMessage.class));
        ApiException error = assertThrows(ApiException.class, () -> service(false, true).resend(NORMALIZED_EMAIL));
        assertEquals(503, error.status());
        assertFalse(error.getMessage().contains(storedCode()));
        assertFalse(error.getMessage().contains("PRIVATE_SMTP_DIAGNOSTIC"));
        verify(sender).createMimeMessage();
        verify(sender).send(any(MimeMessage.class));
        verifyNoInteractions(tokens);
    }

    @Test
    void disabledMailReturnsUnavailableWithoutUsingTheSenderOrExposingTheCode() {
        ApiException error = assertThrows(ApiException.class, () -> service(false, false).resend(NORMALIZED_EMAIL));
        assertEquals(503, error.status());
        assertFalse(error.getMessage().contains(storedCode()));
        verifyNoInteractions(mail, sender, tokens);
    }

    @Test
    void missingMailSenderReturnsUnavailableWithoutExposingTheCode() {
        when(mail.getIfAvailable()).thenReturn(null);
        ApiException error = assertThrows(ApiException.class, () -> service(false, true).resend(NORMALIZED_EMAIL));
        assertEquals(503, error.status());
        assertFalse(error.getMessage().contains(storedCode()));
        verifyNoInteractions(sender, tokens);
    }

    private AuthService service(
        boolean developmentCode,
        boolean mailEnabled) {
        return new AuthService(repository, encoder, tokens, mail, developmentCode, FROM_EMAIL, mailEnabled);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private String storedCode() {
        ArgumentCaptor<Map<String, Object>> persisted = ArgumentCaptor.forClass((Class) Map.class);
        verify(repository).insertEmailVerificationCodes(persisted.capture());
        Map<String, Object> values = persisted.getValue();
        assertEquals(userId, values.get("user_id"));
        ArgumentCaptor<CharSequence> encoded = ArgumentCaptor.forClass(CharSequence.class);
        verify(encoder, atLeastOnce()).encode(encoded.capture());
        List<String> codes = encoded.getAllValues().stream().map(CharSequence::toString)
            .filter(value -> value.matches("\\d{6}")).toList();
        assertEquals(1, codes.size());
        String code = codes.getFirst();
        assertEquals("encoded:" + code, values.get("code_hash"));
        Duration lifetime =
            Duration.between((OffsetDateTime) values.get("created_at"), (OffsetDateTime) values.get("expires_at"));
        assertTrue(lifetime.compareTo(Duration.ofMinutes(9)) > 0);
        assertTrue(lifetime.compareTo(Duration.ofMinutes(11)) < 0);
        return code;
    }

    private void assertSafeResponse(
        Map<String, Object> response,
        String code) {
        assertEquals(600, response.get("expiresIn"));
        assertTrue(response.containsKey("message"));
        assertFalse(response.containsKey("developmentCode"));
        assertFalse(response.toString().contains(code));
    }

    private void assertDelivery(
        String code)
        throws Exception {
        ArgumentCaptor<MimeMessage> delivery = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(delivery.capture());
        MimeMessage message = delivery.getValue();
        message.saveChanges();
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        message.writeTo(wire);
        message = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(wire.toByteArray()));

        assertEquals(1, message.getAllRecipients().length);
        assertEquals(1, message.getRecipients(Message.RecipientType.TO).length);
        assertEquals(
            NORMALIZED_EMAIL,
            ((InternetAddress) message.getRecipients(Message.RecipientType.TO)[0]).getAddress()
        );
        assertNull(message.getRecipients(Message.RecipientType.CC));
        assertNull(message.getRecipients(Message.RecipientType.BCC));
        assertNull(message.getHeader("Reply-To"));
        assertEquals(1, message.getFrom().length);
        InternetAddress from = (InternetAddress) message.getFrom()[0];
        assertEquals(FROM_EMAIL, from.getAddress());
        assertEquals("EduNexus", from.getPersonal());
        assertFalse(message.getSubject().contains(code));
        assertTrue(message.getSubject().codePoints().anyMatch(character -> character > 127));
        assertTrue(message.getHeader("Subject", null).toLowerCase(Locale.ROOT).contains("utf-8"));

        List<Part> leaves = new ArrayList<>();
        List<String> multipartTypes = new ArrayList<>();
        collectParts(message, leaves, multipartTypes);
        assertTrue(multipartTypes.contains("multipart/alternative"));
        assertEquals(2, leaves.size());
        assertEquals(1, leaves.stream().filter(part -> mimeType(part).equals("text/plain")).count());
        assertEquals(1, leaves.stream().filter(part -> mimeType(part).equals("text/html")).count());
        for (Part part : leaves) {
            ContentType type = new ContentType(part.getContentType());
            assertEquals("UTF-8", type.getParameter("charset").toUpperCase(Locale.ROOT));
            String body = (String) part.getContent();
            assertFalse(body.contains("{{verificationCode}}"));
            String visible = part.isMimeType("text/html") ? Jsoup.parse(body).text() : body;
            assertTrue(visible.contains(code));
            assertEquals(
                List.of(code),
                CODE.matcher(visible).results().map(result -> result.group()).distinct().toList()
            );
            assertTrue(Pattern.compile("10\\s*(?:phút|minutes?)", Pattern.CASE_INSENSITIVE).matcher(visible).find());
            assertTrue(visible.codePoints().anyMatch(character -> character > 127));
        }
    }

    private void collectParts(
        Part part,
        List<Part> leaves,
        List<String> multipartTypes)
        throws Exception {
        if (part.isMimeType("multipart/*")) {
            multipartTypes.add(mimeType(part));
            Multipart multipart = (Multipart) part.getContent();
            for (int index = 0; index < multipart.getCount(); index++) {
                collectParts(multipart.getBodyPart(index), leaves, multipartTypes);
            }
        } else {
            leaves.add(part);
        }
    }

    private String mimeType(
        Part part) {
        try {
            return new ContentType(part.getContentType()).getBaseType().toLowerCase(Locale.ROOT);
        } catch (Exception error) {
            throw new AssertionError("Verification email contains an invalid MIME content type", error);
        }
    }
}
