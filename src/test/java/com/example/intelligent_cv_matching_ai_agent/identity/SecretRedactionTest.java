package com.example.intelligent_cv_matching_ai_agent.identity;

import com.example.intelligent_cv_matching_ai_agent.dto.request.shared.identity.GoogleLoginRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.shared.identity.LoginRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.shared.identity.PasswordChangeRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.shared.identity.RefreshTokenRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.shared.identity.RegistrationRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.shared.identity.VerifyEmailRequest;

import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.management.AccountCreateRequest;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SecretRedactionTest {

    @Test
    void requestDtoDebugDescriptionsDoNotExposeAuthenticationSecrets() {
        String secret = "distinct-secret-for-redaction";
        var requests = List.of(
            new RegistrationRequest("person@example.org", secret, "Person", "vi"),
            new LoginRequest("person@example.org", secret),
            new VerifyEmailRequest("person@example.org", secret),
            new RefreshTokenRequest(secret),
            new GoogleLoginRequest(secret),
            new PasswordChangeRequest(secret, secret),
            new AccountCreateRequest("person@example.org", "Person", "CANDIDATE", secret)
        );
        for (Object request : requests)
            assertFalse(request.toString().contains(secret), request.getClass().getSimpleName());
    }
}
