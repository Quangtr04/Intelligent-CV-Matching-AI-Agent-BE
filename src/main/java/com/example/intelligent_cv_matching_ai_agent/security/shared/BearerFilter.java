package com.example.intelligent_cv_matching_ai_agent.security.shared;

import com.example.intelligent_cv_matching_ai_agent.service.shared.identity.TokenService;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@Component
public class BearerFilter extends OncePerRequestFilter {

    private final TokenService tokens;

    public BearerFilter(
        TokenService tokens) {
        this.tokens = tokens;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain chain)
        throws ServletException,
        IOException {
        String header = request.getHeader("Authorization");

        if (header != null) {
            try {
                if (!header.startsWith("Bearer "))
                    throw new ApiException(401, "Bearer token required");

                Map<String, Object> user = tokens.authenticate(header.substring(7));
                SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(
                        user.get("id"),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + user.get("role")))
                    )
                );
            } catch (ApiException ex) {
                response.setStatus(401);
                response.setContentType("application/json");
                response.getWriter().write("{\"status\":401,\"message\":\"Invalid or expired session\"}");

                return;
            }
        }

        chain.doFilter(request, response);
    }
}
