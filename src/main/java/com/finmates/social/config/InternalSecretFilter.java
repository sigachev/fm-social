package com.finmates.social.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Validates the {@code X-Internal-Secret} header on all {@code /api/internal/**} paths.
 * Rejects with 401 if the header is absent or does not match {@code finmates.internal.shared-secret}.
 * Uses timing-safe comparison to prevent timing attacks.
 *
 * <p>Logs a WARN with request path + remote address on each rejection. Never logs
 * the header value or the expected secret. Configuration validation lives in
 * {@link InternalSecretValidator} (fail-fast on startup in non-dev profiles).
 */
@Slf4j
@Component
public class InternalSecretFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-Internal-Secret";

    @Value("${finmates.internal.shared-secret:}")
    private String internalSharedSecret;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!request.getRequestURI().startsWith("/api/internal/")) {
            filterChain.doFilter(request, response);
            return;
        }
        String provided = request.getHeader(HEADER);
        if (provided == null || internalSharedSecret == null || internalSharedSecret.isEmpty()
                || !timingSafeEquals(provided, internalSharedSecret)) {
            log.warn("[InternalSecretFilter] 401 on {} from {} — X-Internal-Secret {}",
                    request.getRequestURI(),
                    request.getRemoteAddr(),
                    provided == null ? "missing" : "mismatched");
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write(
                    "{\"error\":\"Unauthorized\","
                            + "\"hint\":\"X-Internal-Secret missing or mismatched\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean timingSafeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }
}
