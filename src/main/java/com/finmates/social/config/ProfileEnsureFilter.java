package com.finmates.social.config;

import com.finmates.social.profile.ProfileInitializationService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Thin filter that fires after Spring Security has validated the JWT and populated
 * the {@link SecurityContextHolder}. Delegates to {@link ProfileInitializationService}
 * to ensure a {@code social.profiles} row exists for the authenticated user.
 *
 * <p>Skips unauthenticated requests and requests without a {@code user_id} JWT claim.
 * The service-layer cache means this is essentially free after the first check (60s TTL).
 *
 * <p>Exceptions are swallowed — profile creation failures must never break a request.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProfileEnsureFilter extends OncePerRequestFilter {

    private final ProfileInitializationService profileInitializationService;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return path.startsWith("/actuator")
                || path.startsWith("/v3/api-docs")
                || path.startsWith("/swagger-ui");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {
        try {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth instanceof JwtAuthenticationToken jwtAuth) {
                var jwt = jwtAuth.getToken();
                Object userIdClaim = jwt.getClaim("user_id");
                if (userIdClaim != null) {
                    Long userId = Long.valueOf(userIdClaim.toString());

                    // Prefer "name" claim for display; fall back to preferred_username
                    String name = jwt.getClaimAsString("name");
                    if (name == null || name.isBlank()) {
                        name = jwt.getClaimAsString("preferred_username");
                    }

                    profileInitializationService.ensureProfileExists(userId, name);
                }
            }
        } catch (Exception e) {
            log.warn("ProfileEnsureFilter: non-fatal error for {}: {}", request.getRequestURI(), e.getMessage());
        }

        chain.doFilter(request, response);
    }
}
