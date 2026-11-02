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
import java.util.regex.Pattern;

@Component
public class BearerFilter extends OncePerRequestFilter {

    private static final Pattern EVENT_STREAM = Pattern.compile("^/api/v1/consultations/[0-9a-fA-F-]{36}/events$");

    private final TokenService tokens;

    public BearerFilter(
        TokenService tokens) {
        this.tokens = tokens;
    }

    @Override
    protected boolean shouldNotFilter(
        HttpServletRequest request) {
        if (!"POST".equals(request.getMethod())) {
            return false;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return "/api/v1/auth/forgot-password".equals(path) || "/api/v1/auth/reset-password".equals(path);
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain chain)
        throws ServletException,
        IOException {
        String header = request.getHeader("Authorization");
        String queryToken = request.getParameter("access_token");

        // EventSource cannot send headers, so the consultation event stream may pass the token in the query.
        if (header == null && queryToken != null && "GET".equals(request.getMethod())
            && EVENT_STREAM.matcher(request.getRequestURI().substring(request.getContextPath().length())).matches())
            header = "Bearer " + queryToken;

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
