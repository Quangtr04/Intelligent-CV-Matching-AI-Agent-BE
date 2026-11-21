package com.example.intelligent_cv_matching_ai_agent.security.shared;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;

import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class CurrentUser {

    public UUID id() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated())
            throw new ApiException(401, "Authentication required");

        try {
            return UUID.fromString(auth.getName());
        } catch (IllegalArgumentException ex) {
            throw new ApiException(401, "Authentication required");
        }
    }

    public boolean hasRole(
        String role) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return false;
        }
        return auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_" + role));
    }

    public boolean isStrictAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    public boolean hasOperationsRole() {
        return hasRole("ADMIN") || hasRole("MANAGER");
    }

    public boolean hasStaffOrOperationsRole() {
        return hasOperationsRole() || hasRole("STAFF");
    }

    public void requireOperationsRole() {
        if (hasRole("MANAGER")) {
            id();
        } else {
            requireRole("ADMIN");
        }
    }

    public void requireStaffOrOperationsRole() {
        if (hasRole("MANAGER") || hasRole("STAFF")) {
            id();
        } else {
            requireRole("ADMIN");
        }
    }

    public void requireRole(
        String role) {
        id();
        if (!hasRole(role))
            throw ApiException.forbidden("Role required: " + role);
    }

    public void owns(
        Map<String, Object> row,
        String ownerField) {
        if (!id().equals(row.get(ownerField)))
            throw ApiException.notFound("Resource not found");
    }
}
