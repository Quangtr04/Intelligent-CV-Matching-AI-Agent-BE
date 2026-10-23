package com.example.intelligent_cv_matching_ai_agent.util.shared.identity;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class AuthValidation {

    private AuthValidation() {
    }

    public static String email(
        String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    public static void passwordLength(
        String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw ApiException.badRequest("Password must fit within 72 UTF-8 bytes");
    }
}
